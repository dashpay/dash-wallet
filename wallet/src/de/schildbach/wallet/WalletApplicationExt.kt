/*
 * Copyright (c) 2022.
 * Copyright 2022 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package de.schildbach.wallet

import androidx.work.WorkManager
import de.schildbach.wallet.service.platform.sdk.SdkSecretsWipeOutcome
import de.schildbach.wallet.service.platform.sdk.SeamOutputLockRegistry
import de.schildbach.wallet.service.platform.sdk.WalletSecretWriteFence
import de.schildbach.wallet.service.platform.sdk.fencedSdkSecretsWipe
import de.schildbach.wallet.service.platform.work.PublishTransactionMetadataOperation
import de.schildbach.wallet.service.platform.work.TransactionMetadataSaveQueue
import de.schildbach.wallet.util.WalletWipeSequence
import de.schildbach.wallet.util.WalletWipeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.dash.wallet.common.data.BaseConfig
import org.slf4j.LoggerFactory
import java.io.IOException

object WalletApplicationExt {
    private val log = LoggerFactory.getLogger(WalletApplicationExt::class.java)

    /**
     * Owns the wipe resumed at launch. Deliberately NOT a scope that anything
     * else can cancel: the wipe has already destroyed part of the wallet by
     * the time it runs, so it has to reach the end.
     */
    private val wipeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Phase 1-2 of Reset Wallet, on the caller's (main) thread: record the
     * wipe so a mid-wipe process death is repairable, hand the UI off to
     * onboarding, and only then let the destructive teardown start.
     *
     * The hand-off goes through the application context on purpose. The old
     * order ran it last, from the post-wipe callback on a background
     * dispatcher, against the fragment that had asked for the reset — by then
     * that fragment had been detached for two minutes and the process died
     * with "Fragment SecurityFragment not attached to Activity".
     */
    fun WalletApplication.beginWalletWipe() {
        // Stop transaction-metadata saves now, not at the destroy phase: the
        // service teardown in between can take minutes, and a save of this
        // wallet's settings must not write or enqueue a publish once the user
        // has confirmed the reset. The queue stays paused — refusing new saves
        // — until finishWalletWipe() is done; destroyWalletData() also waits
        // for the cancelled save to stop before anything is cleared.
        transactionMetadataSaveQueue.pause()
        // ...and publishes already handed to WorkManager: the cancelAllWork()
        // in the destroy phase comes after the same teardown.
        PublishTransactionMetadataOperation.cancelAll(this)
        WalletWipeSequence.begin(
            markPending = { WalletWipeState.begin(filesDir, noBackupFilesDir) },
            handOffUi = {
                setWipeInProgress(true)
                restartService.performRestart(this, true, false)
            }
        )
    }

    /**
     * Phase 3-5 of Reset Wallet: detach the in-memory wallet, destroy
     * everything behind it, clear the marker. Suspends rather than blocking —
     * it is called from the blockchain service's teardown coroutine, and the
     * SDK cleanup inside it ran for nearly two minutes on a live device.
     */
    suspend fun WalletApplication.finishWalletWipe() {
        var wipeFinished = false
        try {
            // A failure here must not become an uncaught exception in the
            // service-teardown coroutine that calls this — the process dying
            // in the middle of a wipe is the failure mode being fixed. The
            // marker stays behind instead, and the next launch re-runs it.
            wipeFinished = runCatching {
                WalletWipeSequence.finish(
                    pending = { WalletWipeState.isPending(filesDir, noBackupFilesDir) },
                    detachWallet = { withContext(Dispatchers.Main) { detachWalletForWipe() } },
                    destroy = { destroyWalletData() },
                    markComplete = { markWalletWipeComplete() }
                )
            }.onFailure {
                rethrowCancellation(it)
                log.error("Reset Wallet did not finish — the next launch will complete it", it)
            }.getOrDefault(false)
        } finally {
            // The UI is waiting on this flag whether the wipe finished or
            // threw; a launch that finds the marker still there re-runs the
            // wipe from the top.
            withContext(NonCancellable) {
                val wipeComplete = resumeMetadataSavesIfWipeComplete(transactionMetadataSaveQueue, wipeFinished) {
                    WalletWipeState.pendingOrNull(filesDir, noBackupFilesDir)
                }
                // Same proof as the metadata saves: only a completed wipe lets
                // the NEXT wallet persist SDK secrets again. An unfinished one
                // keeps the old wallet's producers fenced for the rest of this
                // (degraded) process.
                if (wipeComplete) {
                    walletSecretWriteFence.lower()
                    // Configs are cleared: the next wallet may migrate (the wiped one's steps stay refused).
                    runCatching { coinJoinFundsMigrationService.get().resumeAfterWalletWipe() }
                        .onFailure { log.warn("mixed-funds migration resume after wipe failed", it) }
                }
                // Before the flag drops: onboarding re-routes on it, and an
                // unfinished wipe must route to the degraded screen, not to
                // create/restore.
                recordWalletWipeStopped()
                withContext(Dispatchers.Main) { setWipeInProgress(false) }
            }
        }
    }

    /**
     * Called from `Application.onCreate` when the marker says the previous
     * process died mid-wipe. The wallet load is skipped for this launch, so
     * the only ordering left to honour is destroy-then-clear-marker.
     */
    fun WalletApplication.resumeInterruptedWipe() {
        log.warn("a previous Reset Wallet did not finish — completing it now; this launch does not load a wallet")
        setWipeInProgress(true)
        // Fence before the coroutine is even scheduled: nothing in this
        // process may persist a secret of the half-destroyed wallet.
        walletSecretWriteFence.raise()
        wipeScope.launch { finishWalletWipe() }
    }

    private suspend fun WalletApplication.destroyWalletData() {
        // FIRST: fence the old wallet's SDK secret producers (raise, cancel +
        // join the app-scoped creation/provisioning jobs, drain in-flight
        // writes). A username creation suspended in its preview or auto-resume
        // back-off would otherwise persist old-wallet key ciphertext AFTER the
        // erase below — the SDK's removal cascade cannot be relied on to have
        // tombstoned the wallet. If the fence cannot be established within its
        // budget the erase outcome counts as FAILED and the marker stays.
        val sdkSecrets = fencedSdkSecretsWipe(
            fence = walletSecretWriteFence,
            quiesceTimeoutMs = WalletSecretWriteFence.WIPE_QUIESCE_TIMEOUT_MS,
            teardown = { tearDownWalletData() },
            // AFTER the cascade, BEFORE the erase: the SDK's own persistence
            // deriver (a wallet the cascade failed to remove, loaded or not)
            // writes keys outside the fence's permits. Tombstones the wallet
            // ids, closes and drains a running runtime, and refuses every SDK
            // restart until the wipe completes; unproven quiescence makes the
            // outcome FAILED.
            quiesceSdk = {
                sdkWalletSecretsEraser.quiesceSdkForWalletWipe(WalletSecretWriteFence.WIPE_QUIESCE_TIMEOUT_MS)
            },
            // AFTER the SDK wallet-removal cascade inside the teardown: the
            // SDK's secrets DataStore (skipped by clearDatastorePrefFiles) is
            // kept only if that cascade verifiably left no wallet secret in it.
            erase = { eraseSdkWalletSecrets() }
        )
        // Last, so every other step still runs: a surviving primary or key
        // backup, or SDK wallet secrets that could not be erased, throw here,
        // keeping the wipe marker for the next launch.
        confirmWipeDestroyed(sdkSecrets) { confirmWalletSourcesDestroyed() }
    }

    private suspend fun WalletApplication.tearDownWalletData() {
        // A queued transaction-metadata save holds this wallet's consent and
        // field choices. Left to run after the clears below it would write
        // them into the next wallet and enqueue a publish, so stop it and wait.
        // Pausing again covers a wipe resumed at launch, which never went
        // through beginWalletWipe().
        runCatching { transactionMetadataSaveQueue.pauseAndJoin() }
            .onFailure { rethrowCancellation(it); log.warn("tx-metadata save queue pause failed during wipe", it) }
        // Before the configs are cleared, so nothing of this wallet is written after.
        resetInMemoryWalletState(
            seamOutputLockRegistry,
            { coinJoinFundsMigrationService.get().stopForWalletWipe() },
            { shieldedBalanceService.get().resetForWalletWipe() }
        )
        destroyWalletFiles()
        // Live DataStore-backed configs must be cleared through their API (one
        // atomic memory+disk edit) before the leftover files are deleted:
        // deleting a LIVE DataStore's file out-of-band leaves its in-memory
        // cache populated while disk is empty. Files with no live instance
        // this process have no cache, so raw deletion is safe for them.
        val apiCleared = runCatching { BaseConfig.clearAllLiveInstances() }
            .onFailure { rethrowCancellation(it); log.warn("live-config clear failed during wipe", it) }
            .getOrDefault(emptySet())
        clearDatastorePrefFiles(apiCleared)
        notifyWalletWipeListeners()
        destroyWalletSecrets()
        clearDatabasesInner(isWalletWipe = true)
    }

    /**
     * Process state with no wallet identity that would outlive Reset Wallet into the
     * next wallet: seam output locks, the mixed-funds migration, the shield resume
     * budget. Every step runs even when an earlier one fails.
     */
    internal suspend fun resetInMemoryWalletState(
        seamOutputLocks: SeamOutputLockRegistry,
        stopMigrationWatcher: suspend () -> Unit,
        resetShieldResumeBudget: () -> Unit
    ) {
        runCatching { seamOutputLocks.clearForWalletWipe() }
            .onFailure { rethrowCancellation(it); log.warn("seam output-lock clear failed during wipe", it) }
        runCatching { stopMigrationWatcher() }
            .onFailure { rethrowCancellation(it); log.warn("mixed-funds watcher stop failed during wipe", it) }
        runCatching { resetShieldResumeBudget() }
            .onFailure { rethrowCancellation(it); log.warn("shield resume-budget reset failed during wipe", it) }
    }

    private suspend fun WalletApplication.eraseSdkWalletSecrets(): SdkSecretsWipeOutcome {
        val outcome = runCatching {
            sdkWalletSecretsEraser.eraseForWalletWipe { deleteSdkSecretsDatastoreFile() }
        }.onFailure {
            rethrowCancellation(it)
            log.error("SDK secrets erase failed during wipe", it)
        }.getOrDefault(SdkSecretsWipeOutcome.FAILED)
        log.info("SDK secrets DataStore after wipe: {}", outcome)
        return outcome
    }

    /**
     * The wipe's completion gate: both persistent dashj wallet sources gone
     * ([confirmWalletSources] throws otherwise) AND the SDK's wallet secrets
     * erased. Either failure throws, so [WalletWipeSequence.finish] keeps the
     * marker and the next launch re-runs the wipe. [confirmWalletSources]
     * always runs first, so its own deletes are attempted regardless.
     */
    internal fun confirmWipeDestroyed(sdkSecrets: SdkSecretsWipeOutcome, confirmWalletSources: () -> Unit) {
        confirmWalletSources()
        if (!sdkSecrets.erased) {
            throw IOException("wipe could not confirm the SDK wallet secrets are erased ($sdkSecrets)")
        }
    }

    /**
     * The wipe listeners are `suspend () -> Unit` crossing a Java boundary,
     * where they are only expressible as their compiled `Function1<Continuation, Any?>`
     * form. Calling them used to mean `runBlocking` per listener on a
     * dispatcher thread; here they are simply awaited.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun WalletApplication.notifyWalletWipeListeners() {
        for (listener in wipeListeners) {
            runCatching { (listener as suspend () -> Unit).invoke() }
                .onFailure { rethrowCancellation(it); log.error("wallet-wipe listener failed", it) }
        }
    }

    /**
     * Clear the databases a blockchain RESCAN invalidates. Fire-and-forget on
     * purpose: it runs during service start, the process is not going away,
     * and every store it touches is re-syncable. The wipe path does NOT come
     * through here — it awaits [clearDatabasesInner] instead, because data
     * surviving a Reset Wallet resurrects the previous wallet's DashPay UI on
     * the next (fresh) wallet.
     */
    fun WalletApplication.clearDatabasesForRescan() {
        CoroutineScope(Dispatchers.IO).launch { clearDatabasesInner(isWalletWipe = false) }
    }

    /**
     * The awaited form of [clearDatabasesForRescan], for a backup-recovered
     * wallet's owed reset: its marker may only be removed once every store
     * is clear. Returns false when any step failed, so the marker stays and
     * the next launch resets again.
     */
    suspend fun WalletApplication.clearDatabasesForRecoveryReset(): Boolean =
        clearDatabasesInner(isWalletWipe = false)

    /**
     * Every step is failure-contained: one failing store (most notably
     * the platform metadata push inside [PlatformSyncService.clearDatabases])
     * must never abort the remaining clears — that partial-clear mode is
     * exactly the resurrected-DashPay-UI bug.
     */
    private suspend fun WalletApplication.clearDatabasesInner(isWalletWipe: Boolean): Boolean {
        var cleared = true
        // Stop the platform sync machinery BEFORE any clear: "Reset Wallet"
        // does not restart the process, and shutdown() gates its cancel on an
        // identity still being present — so an in-flight sync iteration
        // holding a pre-reset BlockchainIdentityData could keep running and
        // re-persist (resurrect) the previous wallet's identity right after
        // the clears below. Sync restarts naturally with the next blockchain
        // service start.
        runCatching { platformSyncService.stopSync() }
            .onFailure { rethrowCancellation(it); cleared = false; log.warn("platform-sync stop failed during reset", it) }
        runCatching { platformSyncService.clearDatabases() }
            .onFailure { rethrowCancellation(it); cleared = false; log.warn("platform-sync clear failed during reset", it) }
        if (isWalletWipe) {
            // SDK twin of the platform-sync resurrection guard above: destroy
            // this wallet's SDK state (bound wallet + binder latch) so the NEXT
            // wallet binds fresh and cannot inherit the previous wallet's
            // discovered identity — observed as the DashPay "Join" entry points
            // staying hidden after a "Reset this wallet". Wipe only: the restore
            // path re-binds to the restored seed instead of destroying it.
            runCatching { l1ShadowSyncService.clearForWalletWipe() }
                .onFailure { rethrowCancellation(it); cleared = false; log.warn("SDK wallet clear failed during wipe", it) }
            runCatching { transactionMetadataProvider.clear() }
                .onFailure { rethrowCancellation(it); cleared = false; log.warn("tx-metadata clear failed during wipe", it) }
            // Phase 5d PER-WALLET cutover reset: the cutover state is
            // per-install-persisted. Put it back to DUAL_RUNNING so the next
            // (restored/created) wallet re-runs its own immediate commit and
            // the one-time explainer logic starts from a clean transition.
            // The engine gate no longer reads this state (dashj never starts
            // on its own), so the reset is about the UI seams' ownership
            // reads, not about which engine runs.
            runCatching { cutoverCoordinator.resetForWalletWipe() }
                .onFailure { rethrowCancellation(it); cleared = false; log.warn("cutover state reset failed during wipe", it) }
            // The reset above only persists DUAL_RUNNING; the SDK tx pipeline
            // stops when that emission reaches its gate, and nothing waits for
            // it. Cancel AND join it here — even when the reset failed —
            // before the display caches are cleared below, or a walk holding
            // the wiped wallet's pages re-writes them into tx_display_cache /
            // tx_group_cache. It stays fenced until resumeAfterWalletWipe.
            runCatching { cutoverUiDataService.stopForWalletWipe() }
                .onFailure { rethrowCancellation(it); cleared = false; log.warn("SDK UI pipeline stop failed during wipe", it) }
        }
        try {
            runCatching { identityRepository.clearDatabase(isWalletWipe) }
                .onFailure { rethrowCancellation(it); cleared = false; log.warn("identity/DashPay clear failed during reset", it) }
            // A wipe starts a new wallet, so its history must go. A rescan keeps
            // the rows post-cutover — the SDK still holds every transaction
            // (MO-1054); see clearDatabaseForRescan.
            runCatching {
                if (isWalletWipe) {
                    txDisplayCacheService.clearDatabase()
                } else {
                    txDisplayCacheService.clearDatabaseForRescan()
                }
            }
                .onFailure { rethrowCancellation(it); cleared = false; log.warn("tx-display-cache clear failed during reset", it) }
        } finally {
            // Caches are clear: re-arm the SDK pipeline for the next wallet
            // explicitly — a failed cutover reset leaves CUT_OVER unchanged,
            // so the gate alone would never see a commit to restart on. If the
            // SDK wallet clear failed too, the pipeline keeps holding until a
            // bind is confirmed against the next app wallet (see
            // CutoverUiDataService.staleWipedWalletHold).
            if (isWalletWipe) cutoverUiDataService.resumeAfterWalletWipe()
        }
        WorkManager.getInstance(this).cancelAllWork()
        // The wipe just emptied the DashPay DataStore mid-process, and the
        // debug-flag seeding only runs in DashPayConfig's init — without
        // this re-seed every USE_KOTLIN_SDK_* flag silently reads OFF for
        // the rest of the process and the SDK paths go dark (observed
        // live: the duck-say overnight restore ran with no SDK engine).
        runCatching { dashPayConfig.seedDebugDefaultsIfUnset() }
            .onFailure { rethrowCancellation(it); log.warn("debug-flag re-seed failed after reset", it) }
        log.info("databases cleared (isWalletWipe = {}, all steps succeeded = {})", isWalletWipe, cleared)
        return cleared
    }

    /**
     * The queue was paused by beginWalletWipe() / destroyWalletData(). Only a
     * wipe that ran its destruction to the end AND whose marker is confirmed
     * cleared lets the next wallet's saves run. Fails closed: an absent marker
     * alone is not proof — if [WalletWipeState.begin] could not write it,
     * finish() skipped the destroy and the old wallet is still in place — and
     * a marker that cannot be read ([markerPending] null or throwing) counts
     * as still pending.
     *
     * @param wipeFinished what WalletWipeSequence.finish() returned; false if it threw.
     * @param markerPending true/false when the marker could be read, null when not.
     * @return true when the wipe is proven complete (the saves were resumed).
     */
    internal fun resumeMetadataSavesIfWipeComplete(
        queue: TransactionMetadataSaveQueue,
        wipeFinished: Boolean,
        markerPending: () -> Boolean?
    ): Boolean {
        val markerCleared = wipeFinished && runCatching { markerPending() }.getOrNull() == false
        if (markerCleared) {
            queue.resume()
        } else {
            log.warn("Reset Wallet did not finish — transaction metadata saves stay paused")
        }
        return markerCleared
    }

    private fun rethrowCancellation(t: Throwable) {
        if (t is CancellationException) throw t
    }

    /**
     * Owns fire-and-forget startup housekeeping. Separate from [wipeScope]
     * (whose never-cancel semantics are wipe-specific) but the same shape:
     * supervisor + IO, nothing else can cancel it.
     */
    private val startupHousekeepingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire-and-forget: the caller sits on the MAIN thread inside
     * Application.onCreate's integrations stage, and this used to
     * `runBlocking` two DataStore edits there — main-thread disk I/O on
     * every cold launch. Nothing in startup depends on the clear having
     * completed (it only prevents stale per-currency deposit addresses from
     * being REUSED later in the session), so it now runs on a background
     * scope. Never throws.
     */
    fun WalletApplication.clearCachedAddresses() {
        startupHousekeepingScope.launch {
            try {
                exchangeIntegrationProvider.clearCachedAddresses()
            } catch (t: Throwable) {
                rethrowCancellation(t)
                log.warn("failed to clear cached exchange deposit addresses", t)
            }
        }
    }

    /**
     * POST-RECOVERY guard (called from [WalletApplication.restoreWalletFromBackup]
     * after a successful key-backup restore): if the Tools "dashj sync
     * (diagnostic)" toggle is ON, force it OFF, breadcrumb it, and show a
     * one-line notice. Rationale: with the toggle on, the un-held dashj
     * peergroup dirties the wallet continuously and the 5s autosave rewrites
     * an ever-growing file — the growth engine that can balloon a wallet past
     * the 2GB parse wall. A freshly recovered wallet must not immediately
     * start down the same path.
     *
     * Fire-and-forget on a background thread (the caller is on the MAIN
     * thread inside Application.onCreate and must not block on DataStore
     * I/O); the Toast is posted back to the main looper. Never throws.
     */
    fun WalletApplication.disableDashjSyncDiagnosticAfterRecovery() {
        Thread({
            try {
                runBlocking {
                    if (dashPayConfig.getDashjSyncDiagnostic()) {
                        dashPayConfig.setDashjSyncDiagnostic(false)
                        log.warn(
                            "post-recovery: dashj sync (diagnostic) was ON — forced OFF so the " +
                                "recovered wallet's autosave does not immediately re-balloon the file"
                        )
                        de.schildbach.wallet.util.StartupBreadcrumbs.mark(
                            de.schildbach.wallet.util.StartupBreadcrumbs.STAGE_WALLET_RECOVERED_FROM_BACKUP,
                            "DASHJ_DIAGNOSTIC_FORCED_OFF"
                        )
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(
                                this@disableDashjSyncDiagnosticAfterRecovery,
                                "dashj sync (diagnostic) was turned off during wallet recovery",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } catch (t: Throwable) {
                rethrowCancellation(t)
                log.warn("failed to check/disable the dashj sync diagnostic after recovery", t)
            }
        }, "post-recovery-diagnostic-off").start()
    }
}
