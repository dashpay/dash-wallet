/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.service.platform.sdk

import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import kotlinx.coroutines.CancellationException
import org.bitcoinj.core.Utils
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashfoundation.dashsdk.wallet.TrackedAssetLock
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/** Wire-order (little-endian) txid bytes → the display-order hex logs/UI use. */
internal fun ByteArray.toTxidHex(): String = Utils.HEX.encode(reversedArray())

/**
 * Whether a failed resume proves the lock's credits ALREADY landed — a
 * terminal outcome that must never be retried (WorkManager would otherwise
 * back off forever on a lock nothing can advance).
 *
 * Two shapes, because Platform's own rejection does NOT arrive as the SDK's
 * typed error: the local tombstone check throws
 * [DashSdkError.PlatformWallet.AssetLockAlreadyConsumed], while a lock
 * consumed Platform-side but not yet marked locally comes back as a Generic
 * protocol error reading "…output N already completely used" (observed live
 * 2026-08-04 after a mid-top-up process death; the same wording the legacy
 * dashj path matched on). Message-matched until the SDK reconciles the
 * local row (platform ask on MO-998), and matched down the cause chain
 * because the JNI wraps it.
 */
internal fun isAlreadyConsumed(t: Throwable): Boolean {
    if (t is DashSdkError.PlatformWallet.AssetLockAlreadyConsumed) return true
    var cause: Throwable? = t
    var hops = 0
    while (cause != null && hops < 8) {
        if (cause.message?.contains("already completely used", ignoreCase = true) == true) return true
        cause = cause.cause
        hops++
    }
    return false
}

// ── Source seam ───────────────────────────────────────────────────────

/**
 * Seam over the SDK's tracked-lock recovery surface, so the drain
 * orchestration in [SdkTopUpRecoveryService] is host-JVM unit-testable —
 * the real calls need `libdash_sdk`.
 */
interface SdkTopUpRecoverySource {
    /** Same contract as [SdkDashPayWriteSource.boundWalletIdOrNull]. */
    suspend fun boundWalletIdOrNull(): String?

    /**
     * The Rust-authoritative tracked locks eligible for generic identity
     * recovery (`PlatformWalletManager.trackedIdentityRecoveryAssetLocks`):
     * funding types registration/top-up/top-up-not-bound, statuses
     * Built…ChainLocked. Consumed rows are never offered.
     */
    suspend fun trackedRecoveryLocks(walletIdHex: String): List<TrackedAssetLock>

    /**
     * Resume [lock] from its exact persisted outpoint
     * (`IdentityCredits.resumeTopUpWithExistingAssetLock`) — Rust owns
     * rebroadcast, proof acquisition, and consumption; no new funding
     * transaction is ever built. Returns the post-transition credit
     * balance; throws on failure (including the terminal
     * [DashSdkError.PlatformWallet.AssetLockAlreadyConsumed]).
     */
    suspend fun resumeTopUp(walletIdHex: String, identityId: ByteArray, lock: TrackedAssetLock): Long
}

/** Production [SdkTopUpRecoverySource]: boots the SDK on demand. */
internal class DashSdkTopUpRecoverySource(
    private val service: DashSdkService
) : SdkTopUpRecoverySource {

    private suspend fun manager(): org.dashfoundation.dashsdk.wallet.PlatformWalletManager {
        service.ensureStarted()
        return checkNotNull(service.walletManagerOrNull()) {
            "SDK wallet manager missing after ensureStarted()"
        }
    }

    override suspend fun boundWalletIdOrNull(): String? =
        manager().wallets.value.keys.singleOrNull()

    override suspend fun trackedRecoveryLocks(walletIdHex: String): List<TrackedAssetLock> =
        manager().trackedIdentityRecoveryAssetLocks(Utils.HEX.decode(walletIdHex))

    override suspend fun resumeTopUp(
        walletIdHex: String,
        identityId: ByteArray,
        lock: TrackedAssetLock
    ): Long {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        return manager.identityCredits.resumeTopUpWithExistingAssetLock(
            walletHandle = wallet.handle,
            identityId = identityId,
            lock = lock,
            coreSignerHandle = manager.mnemonicResolverHandle
        )
    }
}

// ── Drain report ──────────────────────────────────────────────────────

/**
 * One [SdkTopUpRecoveryService.drainPendingTopUps] pass over the SDK's
 * tracked top-up locks. [pending] is the count of top-up locks the
 * recovery surface offered; [resumed] completed their IdentityTopUp
 * transition this pass; [alreadyConsumed] were rejected as already
 * consumed Platform-side (terminal — retrying cannot help); [failed] hit
 * a retryable error; [surfaceUnavailable] means the locks could not even
 * be enumerated.
 */
data class TopUpDrainReport(
    val pending: Int,
    val resumed: Int,
    val alreadyConsumed: Int,
    val failed: Int,
    val surfaceUnavailable: Boolean = false
) {
    /** Another pass can plausibly make progress — the worker should retry. */
    val retryNeeded: Boolean get() = surfaceUnavailable || failed > 0

    companion object {
        val NOTHING_TO_DO = TopUpDrainReport(0, 0, 0, 0)
        val UNAVAILABLE = TopUpDrainReport(0, 0, 0, 0, surfaceUnavailable = true)
    }
}

// ── The recovery service ──────────────────────────────────────────────

/**
 * Restart-surviving completion of interrupted SDK identity top-ups
 * (#1520 item 3 / MO-998, Phase B). [SdkTransparentTopUp] owns the
 * user-facing top-up (its resume gate handles the in-process retry when
 * the user taps again); THIS service is the background half: a
 * [de.schildbach.wallet.service.platform.work.ResumeTopUpsWorker] drain
 * pass completes any tracked top-up lock left behind by a crash or an
 * ambiguous outcome, without the user having to re-enter the flow. The
 * SDK's tracked-lock table is the queue — the worker carries no payload.
 */
@Singleton
class SdkTopUpRecoveryService internal constructor(
    private val source: SdkTopUpRecoverySource,
    /**
     * The bound identity's 32-byte id, or null when the wallet has no
     * registered identity. Injected so the orchestration is testable
     * without the identity database.
     */
    private val identityIdBytes: suspend () -> ByteArray?,
    /**
     * Whether the SDK runtime is ALREADY up — the no-boot guard for
     * [hasPendingTopUpLocks]. Default false (never boot from a probe).
     */
    private val sdkIsStarted: () -> Boolean = { false }
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        blockchainIdentityConfig: BlockchainIdentityConfig
    ) : this(
        source = DashSdkTopUpRecoverySource(sdkService),
        identityIdBytes = {
            blockchainIdentityConfig.get(BlockchainIdentityConfig.IDENTITY_ID)
                ?.takeIf { it.isNotEmpty() }
                ?.let { Identifier.from(it).toBuffer() }
        },
        sdkIsStarted = { sdkService.isStarted }
    )

    /**
     * One drain pass over the SDK's tracked top-up locks. Enumerates
     * `trackedIdentityRecoveryAssetLocks`, filters to the resumable
     * top-up funding types (IDENTITY_TOP_UP / IDENTITY_TOP_UP_NOT_BOUND —
     * registration locks belong to the registration recovery flow), and
     * resumes each from its exact persisted outpoint. Rust owns
     * rebroadcast/proof/consumption, and consumed locks vanish from the
     * surface, so the pass is idempotent — a crash mid-drain or a double
     * enqueue is harmless.
     *
     * No flag gate and no L1 funding gate: tracked locks only exist
     * because an SDK top-up ran, and resume never builds a new funding
     * transaction — gating recovery would strand reserved funds. Never
     * throws (short of cancellation): every failure lands in the report
     * so the worker can decide success vs retry.
     */
    suspend fun drainPendingTopUps(): TopUpDrainReport {
        val walletIdHex = try {
            source.boundWalletIdOrNull()
                ?: return TopUpDrainReport.NOTHING_TO_DO.also {
                    log.info("drain: app wallet not bound to the SDK; no locks to resume")
                }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("drain: SDK bootstrap/bind lookup failed", t)
            return TopUpDrainReport.UNAVAILABLE
        }
        val locks = try {
            source.trackedRecoveryLocks(walletIdHex).filter { it.isResumableTopUp() }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("drain: could not enumerate tracked locks", t)
            return TopUpDrainReport.UNAVAILABLE
        }
        if (locks.isEmpty()) return TopUpDrainReport.NOTHING_TO_DO

        val identityId = try {
            identityIdBytes()?.takeIf { it.size == 32 }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
        if (identityId == null) {
            // Locks exist but there is no identity to credit — an odd state
            // (top-ups require an identity) worth retrying, not dropping.
            log.warn("drain: {} pending top-up lock(s) but no 32-byte identity id", locks.size)
            return TopUpDrainReport(pending = locks.size, resumed = 0, alreadyConsumed = 0, failed = locks.size)
        }

        var resumed = 0
        var alreadyConsumed = 0
        var failed = 0
        for (lock in locks) {
            try {
                val balance = source.resumeTopUp(walletIdHex, identityId, lock)
                resumed++
                log.info(
                    "drain: resumed top-up lock {}:{} — new credit balance {}",
                    lock.outpointTxid.toTxidHex(), lock.outpointVout, balance
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (isAlreadyConsumed(t)) {
                    // Terminal: the lock was burned by an earlier successful
                    // top-up; retrying can never help.
                    alreadyConsumed++
                    log.info("drain: lock {}:{} already consumed", lock.outpointTxid.toTxidHex(), lock.outpointVout)
                } else {
                    failed++
                    log.warn("drain: resume failed for lock {}:{}", lock.outpointTxid.toTxidHex(), lock.outpointVout, t)
                }
            }
        }
        return TopUpDrainReport(locks.size, resumed, alreadyConsumed, failed)
    }

    /**
     * Whether the SDK currently tracks any resumable top-up locks — the
     * `checkTopUps` trigger predicate. Deliberately NO-BOOT: when the SDK
     * is not already running this returns false WITHOUT starting it
     * ([sdkIsStarted]) — a periodic sync sweep must never boot the SDK
     * stack for users who never used it. The DURABLE recovery path is the
     * WorkManager job enqueued at failure time, which survives app
     * restarts and is allowed to boot the SDK. Contained: false when
     * unreadable.
     */
    suspend fun hasPendingTopUpLocks(): Boolean = try {
        if (!sdkIsStarted()) {
            false
        } else {
            val walletIdHex = source.boundWalletIdOrNull()
            walletIdHex != null && source.trackedRecoveryLocks(walletIdHex).any { it.isResumableTopUp() }
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("failed to check for pending top-up locks", t)
        false
    }

    /**
     * Whether the SDK top-up funded by the transaction with [txDisplayHex]
     * is still PENDING (its lock sits in the recovery surface awaiting the
     * credit transfer). False = no such pending lock — for a transaction
     * known to be an SDK top-up that means CREDITED. Null when unknowable
     * (SDK not running / surface unreadable). No-boot, read-only — safe
     * from UI screens.
     */
    suspend fun isTopUpPending(txDisplayHex: String): Boolean? = try {
        if (!sdkIsStarted()) {
            null
        } else {
            val walletIdHex = source.boundWalletIdOrNull() ?: return null
            val wanted = txDisplayHex.lowercase()
            source.trackedRecoveryLocks(walletIdHex).any {
                it.isResumableTopUp() && it.outpointTxid.toTxidHex() == wanted
            }
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("failed to check pending state for top-up {}", txDisplayHex, t)
        null
    }

    private fun TrackedAssetLock.isResumableTopUp(): Boolean =
        fundingType == TrackedAssetLock.FundingType.IDENTITY_TOP_UP ||
            fundingType == TrackedAssetLock.FundingType.IDENTITY_TOP_UP_NOT_BOUND

    companion object {
        private val log = LoggerFactory.getLogger(SdkTopUpRecoveryService::class.java)
    }
}
