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
        WalletWipeSequence.begin(
            markPending = { WalletWipeState.begin(filesDir) },
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
        try {
            // A failure here must not become an uncaught exception in the
            // service-teardown coroutine that calls this — the process dying
            // in the middle of a wipe is the failure mode being fixed. The
            // marker stays behind instead, and the next launch re-runs it.
            runCatching {
                WalletWipeSequence.finish(
                    pending = { WalletWipeState.isPending(filesDir) },
                    detachWallet = { withContext(Dispatchers.Main) { detachWalletForWipe() } },
                    destroy = { destroyWalletData() },
                    markComplete = { WalletWipeState.complete(filesDir) }
                )
            }.onFailure {
                rethrowCancellation(it)
                log.error("Reset Wallet did not finish — the next launch will complete it", it)
            }
        } finally {
            // The UI is waiting on this flag whether the wipe finished or
            // threw; a launch that finds the marker still there re-runs the
            // wipe from the top.
            withContext(NonCancellable) {
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
        wipeScope.launch { finishWalletWipe() }
    }

    private suspend fun WalletApplication.destroyWalletData() {
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
     * Every step is failure-contained: one failing store (most notably
     * the platform metadata push inside [PlatformSyncService.clearDatabases])
     * must never abort the remaining clears — that partial-clear mode is
     * exactly the resurrected-DashPay-UI bug.
     */
    private suspend fun WalletApplication.clearDatabasesInner(isWalletWipe: Boolean) {
        // Stop the platform sync machinery BEFORE any clear: "Reset Wallet"
        // does not restart the process, and shutdown() gates its cancel on an
        // identity still being present — so an in-flight sync iteration
        // holding a pre-reset BlockchainIdentityData could keep running and
        // re-persist (resurrect) the previous wallet's identity right after
        // the clears below. Sync restarts naturally with the next blockchain
        // service start.
        runCatching { platformSyncService.stopSync() }
            .onFailure { rethrowCancellation(it); log.warn("platform-sync stop failed during reset", it) }
        runCatching { platformSyncService.clearDatabases() }
            .onFailure { rethrowCancellation(it); log.warn("platform-sync clear failed during reset", it) }
        if (isWalletWipe) {
            // SDK twin of the platform-sync resurrection guard above: destroy
            // this wallet's SDK state (bound wallet + binder latch) so the NEXT
            // wallet binds fresh and cannot inherit the previous wallet's
            // discovered identity — observed as the DashPay "Join" entry points
            // staying hidden after a "Reset this wallet". Wipe only: the restore
            // path re-binds to the restored seed instead of destroying it.
            runCatching { l1ShadowSyncService.clearForWalletWipe() }
                .onFailure { rethrowCancellation(it); log.warn("SDK wallet clear failed during wipe", it) }
            runCatching { transactionMetadataProvider.clear() }
                .onFailure { rethrowCancellation(it); log.warn("tx-metadata clear failed during wipe", it) }
            // Phase 5d PER-WALLET cutover reset: the cutover state is
            // per-install-persisted, so a Reset-then-restore would otherwise
            // start already CUT_OVER and hold dashj while the SDK has not yet
            // synced the new wallet. Put it back to DUAL_RUNNING so the next
            // (restored/created) wallet re-runs the flow — immediate-commit for
            // a fresh restore, or dual-run → caught-up → auto-commit — and
            // re-arm the auto-commit observer so its in-memory streak/committed
            // latch does not carry over from the wiped wallet.
            runCatching { cutoverCoordinator.resetForWalletWipe() }
                .onFailure { rethrowCancellation(it); log.warn("cutover state reset failed during wipe", it) }
            runCatching { cutoverAutoCommitObserver.rearmForNewWallet() }
                .onFailure { rethrowCancellation(it); log.warn("cutover auto-commit re-arm failed during wipe", it) }
        }
        runCatching { identityRepository.clearDatabase(isWalletWipe) }
            .onFailure { rethrowCancellation(it); log.warn("identity/DashPay clear failed during reset", it) }
        runCatching { txDisplayCacheService.clearDatabase() }
            .onFailure { rethrowCancellation(it); log.warn("tx-display-cache clear failed during reset", it) }
        WorkManager.getInstance(this).cancelAllWork()
        // The wipe just emptied the DashPay DataStore mid-process, and the
        // debug-flag seeding only runs in DashPayConfig's init — without
        // this re-seed every USE_KOTLIN_SDK_* flag silently reads OFF for
        // the rest of the process and the SDK paths go dark (observed
        // live: the duck-say overnight restore ran with no SDK engine).
        runCatching { dashPayConfig.seedDebugDefaultsIfUnset() }
            .onFailure { rethrowCancellation(it); log.warn("debug-flag re-seed failed after reset", it) }
        log.info("databases cleared (isWalletWipe = {})", isWalletWipe)
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
