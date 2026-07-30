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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dash.wallet.common.money.Dash
import org.dashfoundation.dashsdk.Network
import org.dashfoundation.dashsdk.Sdk
import org.dashfoundation.dashsdk.funding.ShieldedProver
import org.dashfoundation.dashsdk.persistence.entities.ShieldedActivityEntity
import org.dashfoundation.dashsdk.persistence.entities.ShieldedNoteEntity
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

// ── Pure mapping helpers ──────────────────────────────────────────────
//
// No native, no Android, no I/O — the credits/activity/address mapping is
// unit-testable on the host JVM (the SDK's Room entity classes are plain
// data classes, constructible without Room).

/** Platform credits per duff: 1 DASH = 1e11 credits = 1e8 duffs. */
internal const val CREDITS_PER_DUFF = 1_000L

/** Floor-convert a non-negative credit amount to [Dash] (sub-duff credits are dropped). */
internal fun creditsToDash(credits: Long): Dash {
    require(credits >= 0) { "credits must be non-negative" }
    return Dash(credits / CREDITS_PER_DUFF)
}

/**
 * Exact-convert a [Dash] amount to Platform credits.
 * @throws ArithmeticException on overflow (amounts above ~92M DASH).
 */
internal fun dashToCredits(amount: Dash): Long =
    Math.multiplyExact(amount.duffs, CREDITS_PER_DUFF)

/**
 * Maximum UTF-8 byte length of a shielded text memo — the 32-byte payload
 * of the 36-byte on-chain `DashMemo` (`rs-dpp::shielded::MEMO_PAYLOAD_SIZE`).
 * Rust re-validates.
 */
internal const val MAX_SHIELDED_MEMO_BYTES = 32

/**
 * Decode the SDK's raw activity memo bytes to display text, or null when
 * there is nothing readable. The on-chain `DashMemo` is exactly 36 bytes:
 * a little-endian u32 `kind` tag (0 = empty, 1 = UTF-8 text zero-padded
 * to 32 bytes, anything else = opaque) followed by the 32-byte payload.
 * Unknown kinds and malformed UTF-8 decode to null rather than garbage.
 */
internal fun decodeShieldedMemo(memo: ByteArray): String? {
    if (memo.size != 36) return null
    val kind = (memo[0].toInt() and 0xff) or
        ((memo[1].toInt() and 0xff) shl 8) or
        ((memo[2].toInt() and 0xff) shl 16) or
        ((memo[3].toInt() and 0xff) shl 24)
    if (kind != 1) return null
    var end = memo.size
    while (end > 4 && memo[end - 1] == 0.toByte()) end--
    if (end == 4) return null
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(memo, 4, end - 4))
            .toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    }
}

/**
 * Map one SDK `shielded_activities` row to the app-side entry, or null
 * when the row should not be surfaced: failed entries (status 2) and
 * unrecognized direction tags (defensive — a future SDK direction must
 * not silently render as IN/OUT).
 */
internal fun toShieldedActivityEntry(entity: ShieldedActivityEntity): ShieldedActivityEntry? {
    if (entity.status == 2) return null
    val direction = when (entity.direction) {
        0 -> ShieldedActivityDirection.IN
        1 -> ShieldedActivityDirection.OUT
        2 -> ShieldedActivityDirection.INTERNAL
        else -> return null
    }
    if (entity.amount < 0) return null
    return ShieldedActivityEntry(
        id = entity.entryId.joinToString("") { "%02x".format(it) },
        direction = direction,
        amount = creditsToDash(entity.amount),
        timestampMs = entity.createdAtMs,
        memo = decodeShieldedMemo(entity.memo),
        pending = entity.status == 0
    )
}

/** Bech32m type byte marking an Orchard payload (DIP-0018). */
private const val ORCHARD_TYPE_BYTE = 0x10

/** DIP-0018 HRP: `dash` on mainnet, `tdash` everywhere else. */
internal fun shieldedHrp(network: Network): String =
    if (network == Network.MAINNET) "dash" else "tdash"

/**
 * Encode a raw 43-byte Orchard payment address (11-byte diversifier +
 * 32-byte pk_d) as its bech32m display string: prepend the 0x10 type
 * byte, encode under [hrp]. Null for a wrong-length input.
 */
internal fun encodeOrchardAddress(raw43: ByteArray, hrp: String): String? {
    if (raw43.size != 43) return null
    return Bech32m.encode(hrp, byteArrayOf(ORCHARD_TYPE_BYTE.toByte()) + raw43)
}

/**
 * Decode a bech32m Orchard address back to its raw 43 bytes, or null when
 * the input is not a well-formed Orchard address under [hrp] (wrong HRP =
 * wrong network, wrong payload length, or a non-Orchard type byte).
 * Detection only — Rust re-validates on send.
 */
internal fun decodeOrchardAddress(input: String, hrp: String): ByteArray? {
    val decoded = Bech32m.decode(input.trim()) ?: return null
    if (decoded.hrp != hrp) return null
    val data = decoded.data
    if (data.size != 44 || (data[0].toInt() and 0xff) != ORCHARD_TYPE_BYTE) return null
    return data.copyOfRange(1, 44)
}

/**
 * Parse an SDK `asset_locks` row key (`"<txid display hex>:<vout>"`, see
 * [org.dashfoundation.dashsdk.persistence.entities.AssetLockEntity]) into
 * the (raw txid, vout) pair the resume FFI wants. Display txid hex is
 * byte-REVERSED from wire order, and
 * `PlatformWalletManager.shieldedResumeFundFromAssetLock` documents its
 * `outPointTxid` as "32-byte raw txid (little-endian wire order)" — so the
 * decoded bytes are reversed here. Null for malformed input.
 */
internal fun parseOutPointHex(outPointHex: String): Pair<ByteArray, Int>? {
    val sep = outPointHex.lastIndexOf(':')
    if (sep != 64) return null
    val vout = outPointHex.substring(sep + 1).toIntOrNull() ?: return null
    if (vout < 0) return null
    val displayBytes = walletIdFromHex(outPointHex.substring(0, sep)) ?: return null
    return displayBytes.reversedArray() to vout
}

/** Ceil-convert a non-negative credit amount to duffs (1 duff = 1000 credits). */
internal fun ceilCreditsToDuffs(credits: Long): Long {
    require(credits >= 0) { "credits must be non-negative" }
    return (credits + CREDITS_PER_DUFF - 1) / CREDITS_PER_DUFF
}

/**
 * Decision of the L1 funding gate shared by
 * [ShieldedBalanceService.shieldFromWallet] and [SdkL1SendService].
 * [allowed] only when runtime evidence shows the SDK's OWN Core wallet has
 * a complete, current view of the chain to spend from.
 */
internal data class WalletFundingGate(val allowed: Boolean, val reason: String)

/**
 * Evaluate the L1 funding gate from the SDK engine's OWN live sync
 * progress — pure, host-testable.
 *
 * The SDK builds and broadcasts the L1 asset lock / send from its OWN SPV
 * wallet, so spending is only allowed once that SPV has a complete,
 * current view of the chain. The old design required SDK-vs-dashj balance
 * PARITY (estimated AND confirmed both matching dashj). That requirement
 * is RETIRED here for two independent reasons, both confirmed on device:
 *
 * 1. Parity is unsatisfiable post-cutover. The held dashj wallet is frozen
 *    at its migration state and can never learn of a new external receive,
 *    so the SDK (live) and dashj (frozen) balances diverge permanently —
 *    the persistent `MISMATCH-PRESYNC … sdk > dashj`. Requiring parity
 *    closed the gate FOREVER (the bricked wallet→shielded transfer).
 *
 * 2. The SYNCED flag is unreachable for a live shadow. See
 *    [ShadowSyncProgress.scanCaughtUpToTip]: the SDK's overall SYNCED state
 *    never latches while the shadow perpetually chases the moving tip, so
 *    `synced=false` reads forever even with headers at the tip.
 *
 * The replacement preconditions still FAIL CLOSED on every real problem,
 * using SDK-internal signals only ([progress] is [L1ShadowSyncService]'s
 * live 1 Hz feed, which resets to [ShadowSyncProgress.IDLE] the moment the
 * engine stops — so IDLE IS the "stale/not running" state, no clock
 * needed):
 * - engine running: phase is not [ShadowSyncPhase.IDLE];
 * - no sync error: phase is not [ShadowSyncPhase.ERROR];
 * - chain view current: [ShadowSyncProgress.scanCaughtUpToTip] — the header
 *   chain is at a known tip AND the wallet-relevant filter scan has caught
 *   up to it. Deliberately NOT phase==SYNCED (a live shadow may never
 *   report it) and NOT dashj parity (retired above); an all-zero snapshot
 *   or a filter scan short of the tip still keeps the gate closed.
 *
 * Wallet binding is NOT re-checked here: every caller already preflights
 * the bound wallet, and an unbound wallet cannot produce a non-IDLE
 * progress feed anyway.
 */
internal fun evaluateWalletFundingGate(
    progress: ShadowSyncProgress
): WalletFundingGate = when {
    progress.phase == ShadowSyncPhase.IDLE ->
        WalletFundingGate(false, "SDK L1 engine not running")
    progress.phase == ShadowSyncPhase.ERROR ->
        WalletFundingGate(false, "SDK L1 engine reported a sync error")
    !progress.scanCaughtUpToTip ->
        WalletFundingGate(false, "SDK L1 filter scan has not caught up to the chain tip yet")
    else -> WalletFundingGate(true, "SDK L1 filter scan caught up to the chain tip")
}

/**
 * Pure [ShieldedSyncStatus] decision — host-testable. [ready] is the bring-up
 * latch; [firstPassCompleted] latches once a full sync pass has finished since
 * bring-up; [passInFlight] is the live "a pass is scanning right now" signal.
 *
 * A pass in flight is always [ShieldedSyncStatus.SYNCING] (the pool is
 * re-scanning — the user-reported "funded wallet shows 0 for minutes" case),
 * and before the first pass finishes we are likewise SYNCING rather than
 * exposing the placeholder zero. Only a ready runtime with a finished pass and
 * nothing in flight is [ShieldedSyncStatus.READY].
 */
internal fun mapShieldedSyncStatus(
    ready: Boolean,
    firstPassCompleted: Boolean,
    passInFlight: Boolean
): ShieldedSyncStatus = when {
    !ready -> ShieldedSyncStatus.NOT_READY
    passInFlight -> ShieldedSyncStatus.SYNCING
    !firstPassCompleted -> ShieldedSyncStatus.SYNCING
    else -> ShieldedSyncStatus.READY
}

/**
 * App-neutral view of one tracked shielded-top-up asset lock
 * (`asset_locks` row with `fundingTypeRaw == 5`).
 * [statusRaw]: 0 Built, 1 Broadcast, 2 InstantSendLocked, 3 ChainLocked,
 * 4 Consumed.
 */
data class PendingWalletShieldLock(
    val outPointHex: String,
    val statusRaw: Int,
    val amountDuffs: Long
) {
    /**
     * Resumable = not yet consumed. Includes `Built` (0): a persisted
     * Built row only survives an AMBIGUOUS broadcast or a crash
     * mid-operation (a definitively rejected broadcast untracks the row
     * Rust-side), and resuming re-broadcasts the SAME persisted
     * transaction bytes — same txid, so it completes the user-authorized
     * spend rather than creating a new one.
     */
    val resumable: Boolean get() = statusRaw in 0..3
}

// ── Source seam ───────────────────────────────────────────────────────

/**
 * Seam over the Kotlin SDK's shielded surface
 * ([org.dashfoundation.dashsdk.wallet.PlatformWalletManager]'s shielded
 * section + the shielded Room DAOs + [ShieldedProver]), so the
 * flag/lifecycle/no-double-broadcast orchestration in
 * [ShieldedBalanceServiceImpl] is host-JVM unit-testable — the real calls
 * need `libdash_sdk`.
 */
interface ShieldedSource {
    /** Whether the native library was built with shielded (Orchard) support. */
    suspend fun hasShieldedSupport(): Boolean

    /** Same contract as [SdkDashPayWriteSource.boundWalletIdOrNull]. */
    suspend fun boundWalletIdOrNull(): String?

    /** Open (or create) the per-network commitment-tree SQLite file. Idempotent per path. */
    suspend fun configureShielded(dbPath: String)

    /**
     * Register the wallet's ZIP-32 [accounts] on the shielded coordinator.
     * The manager-owned mnemonic resolver serves the Orchard key
     * derivation from the SDK's Keystore-backed store — no prompt, no
     * seed hand-off. Idempotent (re-bind replaces the prior binding).
     */
    suspend fun bindShielded(walletId: ByteArray, accounts: List<Int>)

    /** Whether the background shielded sync LOOP is alive (not "a pass is in flight"). */
    suspend fun isShieldedSyncRunning(): Boolean

    /**
     * Whether a shielded sync PASS is currently in flight — distinct from
     * [isShieldedSyncRunning] (loop alive, which stays true between passes).
     * This is the signal the "syncing…" UI indicator polls.
     */
    suspend fun isShieldedSyncing(): Boolean

    suspend fun startShieldedSync()

    suspend fun stopShieldedSync()

    /**
     * Trigger an immediate shielded sync pass (instead of waiting for the
     * ~60s background loop tick) so a just-completed op's balance change
     * shows up right away. Best-effort — callers swallow failures.
     */
    suspend fun syncShieldedNow()

    /** Kick the ~30s Halo 2 proving-key build onto a background thread. Idempotent. */
    suspend fun warmUpProver()

    /** Live unspent notes for the wallet, from the SDK's Room store. */
    fun observeUnspentNotes(walletId: ByteArray): Flow<List<ShieldedNoteEntity>>

    /**
     * One-shot unspent, ANCHORED notes for the wallet (the SDK's
     * `shieldedDao().getUnspentAnchoredNotesByWallet`) — the funding-note
     * anchor gate reads these and keeps only `blockHeight > 0` rows.
     */
    suspend fun unspentAnchoredNotes(walletId: ByteArray): List<ShieldedNoteEntity>

    /**
     * The lowest anchored block height among the wallet's unspent notes
     * (`shieldedDao().minUnspentAnchoredBlockHeight`), or null when none is
     * anchored yet — a cheap "is anything anchored at all" probe.
     */
    suspend fun minUnspentAnchoredBlockHeight(walletId: ByteArray): Long?

    /** Live activity rows for the wallet, from the SDK's Room store. */
    fun observeActivity(walletId: ByteArray): Flow<List<ShieldedActivityEntity>>

    /** Raw 43-byte default Orchard address for account 0, or null when unbound. */
    suspend fun shieldedDefaultAddress(walletId: ByteArray): ByteArray?

    /**
     * The wallet's own DIP-17 Platform receive address (bech32m) — the
     * unshield-to-self target. Null when the SDK's address store has no
     * row for the wallet yet.
     */
    suspend fun ownPlatformAddressOrNull(walletId: ByteArray): String?

    /** Type 15: shield credits into the wallet's own pool. Blocks for the proof. */
    suspend fun shield(walletId: ByteArray, amountCredits: Long)

    /**
     * Type 18: build an L1 asset lock of [amountDuffs] from the SDK
     * wallet's OWN Core UTXOs, broadcast it over the SDK's SPV peers,
     * then shield the lock (minus the pool fee) to [recipientRaw43].
     * Blocks for the IS/CL proof AND the ~30s Halo 2 proof.
     */
    suspend fun fundFromAssetLock(walletId: ByteArray, recipientRaw43: ByteArray, amountDuffs: Long)

    /**
     * [fundFromAssetLock], but funded strictly from the ONE funds account
     * whose account-level derivation path equals [fundingPath] (the DIP-9
     * CoinJoin account) — SDK `PlatformWalletManager.shieldedFundFromAssetLock`'s
     * optional `fundingPath` argument (dashpay/platform#4184).
     *
     * Single-account selection: no union across accounts, so CoinJoin coins
     * are never co-spent with BIP44 coins. Change (only) lands on BIP44
     * account 0 — a non-Standard account cannot derive change Rust-side.
     * A path that matches no signable funds account fails pre-broadcast.
     *
     * Default throws: only the production source (and fakes that exercise
     * the mixed-funds migration) need it.
     */
    suspend fun fundFromAssetLockFromAccount(
        walletId: ByteArray,
        recipientRaw43: ByteArray,
        amountDuffs: Long,
        fundingPath: String
    ): Unit = throw UnsupportedOperationException(
        "single-account asset-lock funding not supported by this source"
    )

    /**
     * Resume a stuck Type 18 from an already-tracked lock outpoint.
     * [outPointTxid] is the 32-byte RAW txid (little-endian wire order).
     */
    suspend fun resumeFundFromAssetLock(
        walletId: ByteArray,
        outPointTxid: ByteArray,
        outPointVout: Int,
        recipientRaw43: ByteArray
    )

    /**
     * Every tracked shielded-top-up asset lock (`fundingTypeRaw == 5`)
     * of the wallet, ANY status, from the SDK's Room store.
     */
    suspend fun walletShieldLocks(walletId: ByteArray): List<PendingWalletShieldLock>

    /**
     * The flat shielded fee in credits for a 2-action Shield bundle
     * (consensus-pinned; the asset-lock base cost is NOT included).
     */
    suspend fun estimateShieldFeeCredits(): Long

    /** Type 16: shielded → shielded transfer. Blocks for the proof. */
    suspend fun transfer(walletId: ByteArray, recipientRaw43: ByteArray, amountCredits: Long, memo: String?)

    /** Type 17: shielded → Platform credits. Blocks for the proof. */
    suspend fun unshield(walletId: ByteArray, toPlatformAddress: String, amountCredits: Long)

    /** Type 19: shielded → Core L1. Blocks for the proof. */
    suspend fun withdraw(walletId: ByteArray, toCoreAddress: String, amountCredits: Long, coreFeePerByte: Int)
}

/** Production [ShieldedSource]: boots the SDK on demand. */
internal class DashSdkShieldedSource(
    private val service: DashSdkService
) : ShieldedSource {

    private suspend fun manager(): org.dashfoundation.dashsdk.wallet.PlatformWalletManager {
        service.ensureStarted()
        return checkNotNull(service.walletManagerOrNull()) {
            "SDK wallet manager missing after ensureStarted()"
        }
    }

    private suspend fun database(): org.dashfoundation.dashsdk.persistence.DashDatabase {
        service.ensureStarted()
        return checkNotNull(service.databaseOrNull()) {
            "SDK database missing after ensureStarted()"
        }
    }

    override suspend fun hasShieldedSupport(): Boolean {
        service.ensureStarted()
        return Sdk.hasShielded()
    }

    override suspend fun boundWalletIdOrNull(): String? =
        manager().wallets.value.keys.singleOrNull()

    override suspend fun configureShielded(dbPath: String) =
        manager().configureShielded(dbPath)

    override suspend fun bindShielded(walletId: ByteArray, accounts: List<Int>) =
        manager().bindShielded(walletId, accounts)

    override suspend fun isShieldedSyncRunning(): Boolean =
        manager().isShieldedSyncRunning()

    override suspend fun isShieldedSyncing(): Boolean =
        manager().isShieldedSyncing()

    override suspend fun startShieldedSync() = manager().startShieldedSync()

    override suspend fun stopShieldedSync() = manager().stopShieldedSync()

    override suspend fun syncShieldedNow() = manager().syncShieldedNow()

    override suspend fun warmUpProver() = ShieldedProver.warmUp()

    override fun observeUnspentNotes(walletId: ByteArray): Flow<List<ShieldedNoteEntity>> =
        flow { emitAll(database().shieldedDao().observeUnspentNotesByWallet(walletId)) }

    override suspend fun unspentAnchoredNotes(walletId: ByteArray): List<ShieldedNoteEntity> =
        database().shieldedDao().getUnspentAnchoredNotesByWallet(walletId)

    override suspend fun minUnspentAnchoredBlockHeight(walletId: ByteArray): Long? =
        database().shieldedDao().minUnspentAnchoredBlockHeight(walletId)

    override fun observeActivity(walletId: ByteArray): Flow<List<ShieldedActivityEntity>> =
        flow { emitAll(database().shieldedDao().observeActivityByWallet(walletId)) }

    override suspend fun shieldedDefaultAddress(walletId: ByteArray): ByteArray? =
        manager().shieldedDefaultAddress(walletId, account = 0)

    override suspend fun ownPlatformAddressOrNull(walletId: ByteArray): String? {
        // Mirror the example app's nextPlatformReceiveAddress: lowest
        // unused DIP-17 index; fall back to the lowest-index row of any
        // state (reusing our OWN used address is harmless for a
        // self-unshield, and better than failing).
        val rows = database().platformAddressDao().observeByWallet(walletId).first()
        val row = rows.filter { !it.isUsed }.minByOrNull { it.addressIndex }
            ?: rows.minByOrNull { it.addressIndex }
        return row?.address
    }

    override suspend fun shield(walletId: ByteArray, amountCredits: Long) =
        manager().shieldedShield(walletId = walletId, amount = amountCredits)

    override suspend fun fundFromAssetLock(
        walletId: ByteArray,
        recipientRaw43: ByteArray,
        amountDuffs: Long
    ) = manager().shieldedFundFromAssetLock(
        walletId = walletId,
        recipientRaw43 = recipientRaw43,
        amountDuffs = amountDuffs
    )

    override suspend fun fundFromAssetLockFromAccount(
        walletId: ByteArray,
        recipientRaw43: ByteArray,
        amountDuffs: Long,
        fundingPath: String
    ) = manager().shieldedFundFromAssetLock(
        walletId = walletId,
        recipientRaw43 = recipientRaw43,
        amountDuffs = amountDuffs,
        // fundingAccountIndex stays 0: with an explicit fundingPath it only
        // names the BIP44 account the CHANGE is routed to, and Rust errors
        // ("BIP44 account N not found for asset-lock change routing") on any
        // index that isn't provisioned. Account 0 always is.
        fundingAccountIndex = 0,
        surplusOutput = null,
        fundingPath = fundingPath
    )

    override suspend fun resumeFundFromAssetLock(
        walletId: ByteArray,
        outPointTxid: ByteArray,
        outPointVout: Int,
        recipientRaw43: ByteArray
    ) = manager().shieldedResumeFundFromAssetLock(
        walletId = walletId,
        outPointTxid = outPointTxid,
        outPointVout = outPointVout,
        recipientRaw43 = recipientRaw43
    )

    override suspend fun walletShieldLocks(walletId: ByteArray): List<PendingWalletShieldLock> =
        database().assetLockDao()
            .observeByWalletAndFundingType(walletId, SHIELDED_TOPUP_FUNDING_TYPE)
            .first()
            .map { PendingWalletShieldLock(it.outPointHex, it.statusRaw, it.amountDuffs) }

    override suspend fun estimateShieldFeeCredits(): Long =
        ShieldedProver.estimateFee(ShieldedProver.FeeKind.TransferOrShield, SHIELD_NUM_ACTIONS)

    private companion object {
        /** `AssetLockFundingType::AssetLockShieldedAddressTopUp` discriminant. */
        const val SHIELDED_TOPUP_FUNDING_TYPE = 5

        /** On-wire Orchard action count of a single-output Shield bundle. */
        const val SHIELD_NUM_ACTIONS = 2
    }

    override suspend fun transfer(
        walletId: ByteArray,
        recipientRaw43: ByteArray,
        amountCredits: Long,
        memo: String?
    ) = manager().shieldedTransfer(
        walletId = walletId,
        recipientRaw43 = recipientRaw43,
        amount = amountCredits,
        memo = memo
    )

    override suspend fun unshield(walletId: ByteArray, toPlatformAddress: String, amountCredits: Long) =
        manager().shieldedUnshield(
            walletId = walletId,
            toPlatformAddress = toPlatformAddress,
            amount = amountCredits
        )

    override suspend fun withdraw(
        walletId: ByteArray,
        toCoreAddress: String,
        amountCredits: Long,
        coreFeePerByte: Int
    ) = manager().shieldedWithdraw(
        walletId = walletId,
        toCoreAddress = toCoreAddress,
        amount = amountCredits,
        coreFeePerByte = coreFeePerByte
    )
}

/**
 * Default [ShieldedBalanceService] implementation. See the interface for
 * the full contract; this class adds the orchestration mechanics:
 *
 * - **Flag gate first**: [DashPayConfig.USE_KOTLIN_SDK_SHIELDED] is
 *   re-read at the top of every entry point; while OFF nothing touches
 *   [ShieldedSource] (verified by unit test — the inertness contract).
 * - **Single-flight + latch**: [ensureShieldedReady] serializes under a
 *   [Mutex] and latches the bound wallet id on success ([readyWalletIdHex]),
 *   so later calls (and the write preflights) are cheap no-ops. A failed
 *   pass leaves the latch empty — the next trigger retries the whole
 *   configure/bind/start sequence, all of which is idempotent SDK-side.
 * - **Never prompts**: the wallet must already be bound by
 *   [SdkWalletBinder]; if it isn't, [ensureShieldedReady] reports false
 *   and writes return [SdkWriteResult.NotBroadcast].
 * - **Write discipline**: one broadcast attempt per call, classified by
 *   [classifyBroadcastFailure] (shared with [SdkDashPayWrites]); local
 *   preflight failures (malformed address, over-long memo, missing
 *   platform address) are [SdkWriteResult.NotBroadcast] by construction.
 *
 * ## The [shieldFromWallet] architecture decision (from SDK sources)
 *
 * "Dash Wallet → Shielded" should ideally have dashj (which owns the
 * synced L1 today) build and broadcast the asset lock, with the SDK only
 * consuming it. The SDK does not support that: **there is no external
 * asset-lock intake** in the Kotlin SDK.
 *
 * - `PlatformWalletManager.shieldedFundFromAssetLock` takes only
 *   `(walletId, recipientRaw43, amountDuffs, fundingAccountIndex,
 *   surplusOutput)` — no transaction bytes, no proof. The FFI doc says
 *   the lock is "built from the wallet balance"
 *   (kotlin-sdk `ffi/FundingNative.kt`, `shieldedFundFromAssetLock`).
 * - Rust-side, `AssetLockFunding::FromWalletBalance` "builds an asset
 *   lock from wallet UTXOs" of the SDK's own key-wallet, and
 *   `FromExistingAssetLock` requires the lock to "already be tracked by
 *   the AssetLockManager" (rs-platform-wallet
 *   `wallet/asset_lock/orchestration.rs`, `AssetLockFunding`). The lock
 *   is broadcast via `SpvBroadcaster` over the SDK's OWN SPV peers
 *   (rs-platform-wallet `manager/wallet_lifecycle.rs` / `broadcaster.rs`).
 * - The only external-transaction entry (`asset_lock_manager_recover`,
 *   rs-platform-wallet-ffi `asset_lock/sync.rs`) is NOT exposed through
 *   the unified JNI the Kotlin SDK uses.
 *
 * So [shieldFromWallet] runs the SDK's own pipeline and is hard-gated on
 * the SDK engine's live sync progress ([L1ShadowSyncService.progress]):
 * the SDK SPV must have its filter scan caught up to the chain tip (see
 * [evaluateWalletFundingGate] — the gate runs on SDK-only preconditions;
 * the old dashj-parity requirement is retired because the frozen held
 * wallet makes parity permanently unsatisfiable and the SDK's SYNCED flag
 * is unreachable for a live shadow) before the SDK is allowed to spend the
 * (shared-seed) L1 funds. The lock pays to
 * the SDK's own `AssetLockShieldedAddressTopUp` DIP-9 family and is
 * claimed by the same Rust wallet that derived it, so no dashj↔SDK
 * lock-key derivation parity is required; the UTXO/balance parity that
 * IS required is exactly what the gate measures. Stage-(b) recovery
 * state lives in the SDK's own Room `asset_locks` table (written before
 * broadcast Rust-side) rather than a parallel app-side record — one
 * source of truth for [resumePendingWalletShields].
 */
@Singleton
class ShieldedBalanceServiceImpl internal constructor(
    private val source: ShieldedSource,
    private val dashPayConfig: DashPayConfig,
    private val shieldedDbPath: () -> String,
    private val displayHrp: () -> String,
    /**
     * The SDK engine's live sync-progress snapshot — the [shieldFromWallet]
     * funding-gate evidence (see [evaluateWalletFundingGate]: the gate runs
     * on SDK-only preconditions, not dashj parity). Prod wires
     * [L1ShadowSyncService.progress]; the default ([ShadowSyncProgress.IDLE])
     * keeps the gate CLOSED (funds-safe) for constructions that don't
     * provide it.
     */
    private val l1Progress: () -> ShadowSyncProgress = { ShadowSyncProgress.IDLE },
    /**
     * Live SDK sync-progress feed, for [observeWalletShieldingAvailable].
     * Prod wires [L1ShadowSyncService.progress]; the default keeps the
     * observed gate CLOSED (funds-safe) for constructions that don't
     * provide it. Distinct from [l1Progress] (the one-shot snapshot the
     * write preflight reads) — the UI re-derives the gate on each emission.
     */
    private val l1ProgressFlow: () -> Flow<ShadowSyncProgress> = { flowOf(ShadowSyncProgress.IDLE) },
    /**
     * Ensure the SDK's SPV is running before a [shieldFromWallet]
     * broadcast. In this interim architecture the SDK builds and
     * broadcasts the L1 asset lock through its OWN SPV peers, and that SPV
     * runs only as [L1ShadowSyncService]'s shadow sync — which lifecycle
     * teardown and the recovery paths stop/restart. The funding gate
     * proves the shadow was SYNCED at the last probe, but the SPV can be
     * stopped between that probe and the broadcast (the live
     * "SPV client not started" failure), so the shield calls this
     * immediately before spending. Prod wires
     * [L1ShadowSyncService.ensureSpvRunning] (starts the SPV if the
     * L1-shadow flag is on and it isn't already running, without a reset);
     * the default `{ true }` keeps host-JVM tests decoupled. Returns false
     * when the SPV cannot be brought up → the shield is a clean,
     * retryable [SdkWriteResult.NotBroadcast]. Post-cutover the SDK SPV is
     * the real wallet engine and always running, so this coupling
     * disappears (SDK issue #4065 — no external asset-lock intake).
     */
    private val ensureL1SpvRunning: suspend () -> Boolean = { true },
    /**
     * Arms the L1-shadow parity grace before the asset-lock self-spend.
     * The SDK does not yet debit spent multi-account inputs at broadcast
     * time (PR #4074 follow-up), so its balance view reads inflated until
     * the spend confirms — without the grace the parity decider mistakes
     * that window for corruption and hard-resets the shadow state (seen
     * live: a clean 0.2 shield triggering a full rescan). Prod wires
     * [L1ShadowSyncService.noteSelfSpendBroadcast], the same marker the
     * SDK L1 send path arms. Armed BEFORE the attempt: a NotBroadcast
     * leaves a harmless grace, a broadcast without one risks the reset.
     */
    private val noteSelfSpendBroadcast: () -> Unit = {},
    /**
     * Scope for the post-[ensureShieldedReady] pending-shield retry sweep
     * ([resumePendingWalletShields]); null (tests' default) disables the
     * automatic trigger — the method itself stays callable.
     */
    private val sweepScope: CoroutineScope? = null,
    /**
     * Seed the asset-lock kind ([AssetLockKind.SHIELD]) in-memory the instant a
     * wallet-shield's funding lock is known, so the first engine-feed
     * classification of this L1 lock already reads "Shielded" instead of
     * momentarily "Internal" — mirroring the transparent/invite paths. No-op
     * default keeps the host-JVM tests inert.
     */
    private val seedAssetLockKind: (displayHex: String, kind: AssetLockKind) -> Unit = { _, _ -> }
) : ShieldedBalanceService {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        sdkService: DashSdkService,
        dashPayConfig: DashPayConfig,
        l1ShadowSyncService: L1ShadowSyncService,
        applicationScope: CoroutineScope,
        assetLockKindResolver: AssetLockKindResolver
    ) : this(
        source = DashSdkShieldedSource(sdkService),
        dashPayConfig = dashPayConfig,
        // Lazy: Constants/native untouched at construction (the service
        // must stay inert until a flag-gated call). Path mirrors the SDK
        // example app's ShieldedService.dbPath naming, rooted in filesDir.
        shieldedDbPath = {
            val network = toSdkNetwork(Constants.NETWORK_PARAMETERS)
            File(context.filesDir, "shielded_tree_${network.networkName}.sqlite").absolutePath
        },
        displayHrp = { shieldedHrp(toSdkNetwork(Constants.NETWORK_PARAMETERS)) },
        l1Progress = { l1ShadowSyncService.progress.value },
        l1ProgressFlow = { l1ShadowSyncService.progress },
        ensureL1SpvRunning = { l1ShadowSyncService.ensureSpvRunning() },
        noteSelfSpendBroadcast = { l1ShadowSyncService.noteSelfSpendBroadcast() },
        sweepScope = applicationScope,
        seedAssetLockKind = { displayHex, kind -> assetLockKindResolver.seed(displayHex, kind) }
    )

    /** Serializes [ensureShieldedReady]/[stop] — the single-flight guarantee. */
    private val lock = Mutex()

    /**
     * Ready latch AND the flows' switchboard: the bound SDK wallet id
     * (lowercase hex) once a bring-up pass succeeded, null otherwise.
     */
    private val readyWalletIdHex = MutableStateFlow<String?>(null)

    /**
     * Live sync status for UI (see [ShieldedSyncStatus]). Driven by
     * [startSyncStatusPolling] once ready; stays [ShieldedSyncStatus.NOT_READY]
     * while the flag is off (nothing starts the poller).
     */
    private val _shieldedSyncStatus = MutableStateFlow(ShieldedSyncStatus.NOT_READY)
    override val shieldedSyncStatus: StateFlow<ShieldedSyncStatus> = _shieldedSyncStatus.asStateFlow()

    /**
     * "The last-known balance is stale" latch — see [shieldedBalanceMaybeStale].
     * Set the moment a successful local spend broadcasts (its note-store
     * change has not landed yet), cleared by [startSyncStatusPolling] on the
     * next completed sync pass.
     */
    private val _shieldedBalanceMaybeStale = MutableStateFlow(false)
    override val shieldedBalanceMaybeStale: StateFlow<Boolean> = _shieldedBalanceMaybeStale.asStateFlow()

    /**
     * Background pending-shield completions — see [walletShieldResumed].
     * Buffered so the sweep never suspends on a slow/absent collector.
     */
    private val _walletShieldResumed = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    override val walletShieldResumed: SharedFlow<Int> = _walletShieldResumed.asSharedFlow()

    /** The sync-status poll loop (see [startSyncStatusPolling]); null until ready. */
    private var syncStatusJob: Job? = null

    /** The last-known-balance persistence collector (see [startBalancePersistence]); null until ready. */
    private var balancePersistJob: Job? = null

    override suspend fun lastKnownShieldedBalance(): Dash? = try {
        dashPayConfig.getLastShieldedBalanceDuffs()?.let { Dash(it) }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("failed to read the last-known shielded balance; treating as absent", t)
        null
    }

    override suspend fun ensureShieldedReady(): Boolean {
        if (!isEnabled()) return false
        val ready = ensureShieldedReadyInner()
        if (ready) {
            // Begin (or keep) polling the SDK's pass-in-flight signal so the
            // UI can distinguish "still syncing" from a real zero balance.
            startSyncStatusPolling()
            // Persist the balance whenever it is trustworthy (READY) so the
            // last-known amount survives process death and the More card can
            // render it instantly on the next open (see [startBalancePersistence]).
            startBalancePersistence()
            // Staged-retry hook: finish any interrupted shieldFromWallet
            // (stage (b) after the L1 lock broadcast) in the background.
            // Cheap when nothing is pending (one Room query); serialized
            // with new wallet-shield writes by [walletShieldMutex].
            sweepScope?.launch {
                runCatching { resumePendingWalletShieldsInner() }
                    .onFailure {
                        if (it is CancellationException) throw it
                        log.warn("pending wallet-shield sweep failed; will retry on the next trigger", it)
                    }
            }
        }
        return ready
    }

    private suspend fun ensureShieldedReadyInner(): Boolean {
        return try {
            lock.withLock {
                if (readyWalletIdHex.value != null) return true

                if (!source.hasShieldedSupport()) {
                    log.info("shielded runtime unavailable: native build has no shielded support")
                    return false
                }
                val walletIdHex = source.boundWalletIdOrNull()
                if (walletIdHex == null) {
                    log.info("shielded runtime not started: app wallet not bound to the SDK yet")
                    return false
                }
                val walletId = walletIdFromHex(walletIdHex)
                if (walletId == null) {
                    log.warn("shielded runtime not started: malformed SDK wallet id")
                    return false
                }

                source.configureShielded(shieldedDbPath())
                source.bindShielded(walletId, listOf(DEFAULT_SHIELDED_ACCOUNT))
                if (!source.isShieldedSyncRunning()) {
                    source.startShieldedSync()
                }
                // Best-effort: start building the Halo 2 proving key now so
                // the first spend doesn't pay the ~30s warm-up on top of its
                // own proof. Idempotent; runs on a background thread SDK-side.
                runCatching { source.warmUpProver() }
                    .onFailure { log.warn("shielded prover warm-up failed (spends will build it lazily)", it) }

                readyWalletIdHex.value = walletIdHex
                log.info(
                    "shielded runtime ready on SDK wallet {}… (account {}, sync loop running)",
                    walletIdHex.take(8), DEFAULT_SHIELDED_ACCOUNT
                )
                true
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("shielded bring-up failed; will retry on the next trigger", t)
            false
        }
    }

    override suspend fun stop() {
        lock.withLock {
            if (readyWalletIdHex.value == null) return
            readyWalletIdHex.value = null
            syncStatusJob?.cancel()
            syncStatusJob = null
            balancePersistJob?.cancel()
            balancePersistJob = null
            _shieldedSyncStatus.value = ShieldedSyncStatus.NOT_READY
            _shieldedBalanceMaybeStale.value = false
            runCatching { source.stopShieldedSync() }
                .onFailure { log.warn("failed to stop the shielded sync loop", it) }
        }
    }

    /**
     * Poll the SDK's [ShieldedSource.isShieldedSyncing] pass-in-flight signal
     * and publish [shieldedSyncStatus]. Idempotent (a live loop is reused) and
     * scoped to [sweepScope] — with no scope (unit tests) the status stays at
     * its constructed value and callers exercise [mapShieldedSyncStatus]
     * directly.
     *
     * "First pass completed" is latched the first time we observe a pass go
     * in-flight-then-idle. A wallet that is already caught up may never show a
     * pass in flight, so a bounded idle settle ([SYNC_STATUS_SETTLE_POLLS]) also
     * latches completion — during a genuine multi-block re-scan the pass stays
     * in flight far longer than the settle window, so the placeholder is not
     * dismissed prematurely there.
     */
    private fun startSyncStatusPolling() {
        val scope = sweepScope ?: return
        if (syncStatusJob?.isActive == true) return
        syncStatusJob = scope.launch {
            var seenPassInFlight = false
            var firstPassCompleted = false
            var idlePolls = 0
            var prevInFlight = false
            while (isActive) {
                if (readyWalletIdHex.value == null) {
                    _shieldedSyncStatus.value = ShieldedSyncStatus.NOT_READY
                    break
                }
                val inFlight = runCatching { source.isShieldedSyncing() }
                    .getOrElse {
                        if (it is CancellationException) throw it
                        false
                    }
                if (inFlight) {
                    seenPassInFlight = true
                    idlePolls = 0
                } else {
                    idlePolls++
                    if (seenPassInFlight || idlePolls >= SYNC_STATUS_SETTLE_POLLS) {
                        firstPassCompleted = true
                    }
                }
                // A sync pass just finished (in-flight → idle transition): the
                // note store now reflects any local spend, so the last-known
                // balance is no longer stale. Gated on the TRANSITION (not
                // steady idle) so a spend's flag is never cleared before its
                // pass has been observed running.
                if (prevInFlight && !inFlight) {
                    _shieldedBalanceMaybeStale.value = false
                }
                prevInFlight = inFlight
                _shieldedSyncStatus.value = mapShieldedSyncStatus(
                    ready = true,
                    firstPassCompleted = firstPassCompleted,
                    passInFlight = inFlight
                )
                delay(SYNC_STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Persist the shielded balance whenever it is TRUSTWORTHY — a
     * [ShieldedSyncStatus.READY] runtime (bring-up done, first pass complete,
     * nothing in flight) — so [lastKnownShieldedBalance] survives process
     * death and the More card renders it instantly on the next open. Gating
     * on READY (never SYNCING) is what keeps the cache from being clobbered
     * by the transient zero the note store can read mid re-scan. Idempotent
     * (a live collector is reused) and scoped to [sweepScope] — with no scope
     * (unit tests) nothing is persisted and callers exercise the pure display
     * decision directly.
     */
    private fun startBalancePersistence() {
        val scope = sweepScope ?: return
        if (balancePersistJob?.isActive == true) return
        balancePersistJob = scope.launch {
            var lastPersisted: Long? = null
            combine(observeShieldedBalance(), _shieldedSyncStatus) { balance, status ->
                balance to status
            }.collect { (balance, status) ->
                if (status != ShieldedSyncStatus.READY) return@collect
                val duffs = balance.duffs
                if (duffs == lastPersisted) return@collect
                runCatching { dashPayConfig.setLastShieldedBalanceDuffs(duffs) }
                    .onSuccess { lastPersisted = duffs }
                    .onFailure {
                        if (it is CancellationException) throw it
                        log.warn("failed to persist the last-known shielded balance", it)
                    }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeShieldedBalance(): Flow<Dash> =
        readyWalletIdHex.flatMapLatest { hex ->
            val walletId = hex?.let(::walletIdFromHex)
            if (walletId == null) {
                flowOf(Dash.ZERO)
            } else {
                source.observeUnspentNotes(walletId)
                    .map { notes -> creditsToDash(notes.sumOf { it.value }) }
            }
        }

    override suspend fun isFundingNoteAnchoredForDenomination(denomination: Dash): Boolean {
        // Snapshot read only — never force a bring-up here (the gate is polled
        // on every sync/balance emission). Not-ready → not anchored (fail-closed).
        val walletId = readyWalletIdHex.value?.let(::walletIdFromHex) ?: return false
        return try {
            // Anchored = a recorded commitment-tree anchor, i.e. blockHeight > 0.
            // Require the confirmed unspent note set to cover the denomination:
            // a READY pool whose freshly-minted note is not yet anchored reads
            // false, holding the "still preparing" surface past READY.
            val anchoredCredits = source.unspentAnchoredNotes(walletId)
                .filter { it.blockHeight > 0 }
                .sumOf { it.value }
            creditsToDash(anchoredCredits) >= denomination
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("funding-note anchor read failed; treating the note as not yet anchored", t)
            false
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeShieldedActivity(): Flow<List<ShieldedActivityEntry>> =
        readyWalletIdHex.flatMapLatest { hex ->
            val walletId = hex?.let(::walletIdFromHex)
            if (walletId == null) {
                flowOf(emptyList())
            } else {
                source.observeActivity(walletId).map { rows ->
                    rows.mapNotNull(::toShieldedActivityEntry)
                        .sortedByDescending { it.timestampMs }
                }
            }
        }

    override suspend fun shieldedReceiveAddress(): String? {
        if (!ensureShieldedReady()) return null
        val walletId = readyWalletIdHex.value?.let(::walletIdFromHex) ?: return null
        return try {
            source.shieldedDefaultAddress(walletId)
                ?.let { raw -> encodeOrchardAddress(raw, displayHrp()) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("failed to derive the shielded receive address", t)
            null
        }
    }

    override suspend fun shieldFromCredits(amount: Dash): SdkWriteResult<Unit> =
        runShieldedWrite("shieldFromCredits", amount) { walletId, credits ->
            source.shield(walletId, credits)
        }

    // ── Dash Wallet → Shielded (Type 18, staged) ──────────────────────

    /**
     * Serializes [shieldFromWallet] and the retry sweep so a resume can't
     * race a fresh fund (mirrors the Rust-side per-wallet `shield_guard`,
     * but also keeps our before/after tracked-lock evidence coherent).
     */
    private val walletShieldMutex = Mutex()

    /** Per-process resume attempts per outpoint (each costs a ~30s proof). */
    private val resumeAttempts = HashMap<String, Int>()

    override suspend fun isWalletShieldingAvailable(): Boolean =
        isEnabled() && isL1FundingFlagOn() &&
            evaluateWalletFundingGate(safeProgress()).allowed

    override fun observeWalletShieldingAvailable(): Flow<Boolean> =
        l1ProgressFlow()
            .map { progress ->
                // Same gate as isWalletShieldingAvailable(), re-derived per
                // progress emission so an open screen unblocks the instant
                // the SDK engine's filter scan catches up to the chain tip.
                // Flags-off inert: both reads short-circuit to false and the
                // progress flow itself stays IDLE while the shadow is not
                // running.
                isEnabled() && isL1FundingFlagOn() &&
                    evaluateWalletFundingGate(progress).allowed
            }
            .distinctUntilChanged()

    override suspend fun shieldFromWallet(amount: Dash): SdkWriteResult<ShieldFromWalletOutcome> =
        shieldFromWalletInternal(amount, fundingPath = null)

    override suspend fun shieldMixedFundsFromWallet(
        amount: Dash,
        coinJoinAccountPath: String
    ): SdkWriteResult<ShieldFromWalletOutcome> =
        shieldFromWalletInternal(amount, fundingPath = coinJoinAccountPath)

    /**
     * The one staged Type-18 pipeline behind both [shieldFromWallet]
     * (`fundingPath == null` → the unmixed BIP44 account, today's behavior)
     * and [shieldMixedFundsFromWallet] (an explicit account-level path →
     * that ONE account). Everything else — gate, fee floor, mutex, tracked-
     * lock evidence, failure classification — is identical by construction,
     * so the mixed-funds migration inherits the hardened contract instead of
     * duplicating it.
     */
    private suspend fun shieldFromWalletInternal(
        amount: Dash,
        fundingPath: String?
    ): SdkWriteResult<ShieldFromWalletOutcome> {
        val operation = if (fundingPath == null) "shieldFromWallet" else "shieldMixedFundsFromWallet"
        if (!isEnabled()) return SdkWriteResult.NotBroadcast("flag off")
        if (!isL1FundingFlagOn()) {
            return notBroadcast(operation, "L1 shadow flag off", null)
        }
        if (!amount.isPositive) {
            return notBroadcast(operation, "non-positive amount", null)
        }

        // Preflights — nothing has been submitted if any of this fails.
        if (!ensureShieldedReadyInner()) {
            return notBroadcast(operation, "shielded runtime not ready", null)
        }
        val walletId = readyWalletIdHex.value?.let(::walletIdFromHex)
            ?: return notBroadcast(operation, "shielded runtime not ready", null)

        // Funding gate: the SDK builds the lock from its OWN SPV wallet,
        // so require live evidence that its scan is caught up to the chain
        // tip (SDK-only preconditions — see evaluateWalletFundingGate).
        val gate = evaluateWalletFundingGate(safeProgress())
        if (!gate.allowed) {
            return notBroadcast(operation, "L1 funding gate closed: ${gate.reason}", null)
        }

        // Fee-floor preflight: the recipient receives `lock − pool fee`
        // (pool fee = flat shielded fee + the protocol's asset-lock base
        // cost); Rust refuses a lock at or below the fee, but only AFTER
        // our own gate — reject here so that path is never exercised.
        val poolFeeDuffs = try {
            ceilCreditsToDuffs(source.estimateShieldFeeCredits()) + ASSET_LOCK_BASE_COST_DUFFS
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast(operation, "shield fee estimate failed", t)
        }
        if (amount.duffs <= poolFeeDuffs) {
            return notBroadcast(
                operation, "amount does not cover the shield pool fee ($poolFeeDuffs duffs)", null
            )
        }

        val recipient = try {
            source.shieldedDefaultAddress(walletId)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast(operation, "shielded address lookup failed", t)
        } ?: return notBroadcast(operation, "wallet has no bound shielded address", null)

        return walletShieldMutex.withLock {
            // Ensure the SDK's SPV is live immediately before spending: the
            // asset lock is built+broadcast through it, and in this interim
            // architecture that SPV is only the L1 shadow sync, which
            // lifecycle teardown and the recovery paths stop. The funding
            // gate proved the shadow was SYNCED at the last probe, but the
            // SPV can be stopped between that probe and here — the live
            // "SPV client not started" broadcast failure. Restart it in
            // place (no reset) so the broadcast has a running client; if it
            // can't be brought up, this is a clean, retryable NotBroadcast
            // (nothing was submitted). See [ensureL1SpvRunning] / SDK #4065.
            if (!ensureL1SpvRunning()) {
                return notBroadcast(operation, "L1 sync not running", null)
            }

            // Arm the parity self-spend grace BEFORE the attempt (see the
            // [noteSelfSpendBroadcast] seam doc): the asset lock is a
            // self-spend whose inputs the SDK doesn't debit until it
            // confirms, and without the grace the inflated-balance decider
            // hard-resets the shadow state right after a clean shield.
            noteSelfSpendBroadcast()

            // Evidence baseline: the tracked shielded-top-up locks BEFORE
            // the attempt. Refuse to spend if it cannot be read — the
            // post-failure classification below depends on it.
            val lockedBefore = try {
                source.walletShieldLocks(walletId).map { it.outPointHex }.toHashSet()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                return notBroadcast(operation, "cannot read the tracked asset-lock state", t)
            }

            // The single staged attempt: (a) build+broadcast the L1 asset
            // lock from the SDK wallet, (b) Halo 2 proof + Type 18 submit.
            try {
                if (fundingPath == null) {
                    source.fundFromAssetLock(walletId, recipient, amount.duffs)
                } else {
                    // SINGLE-ACCOUNT: every input comes from the account this
                    // path names (the DIP-9 CoinJoin account); BIP44 coins are
                    // never co-spent. Only change returns to BIP44 account 0.
                    source.fundFromAssetLockFromAccount(walletId, recipient, amount.duffs, fundingPath)
                }
                markLocalSpendPending()
                kickImmediateShieldedSync()
                // Seed the funding lock's kind as SHIELD (best-effort, post-
                // success) so the L1 asset lock renders "Shielded" on first
                // classification instead of "Internal". The new lock is the
                // outPointHex absent from lockedBefore; its txid part (before
                // ':') is ALREADY display hex (AssetLockEntity keys on display),
                // so it is the resolver's seed key directly.
                try {
                    source.walletShieldLocks(walletId)
                        .map { it.outPointHex }
                        .firstOrNull { it !in lockedBefore }
                        ?.substringBefore(':')
                        ?.takeIf { it.length == 64 }
                        ?.let { seedAssetLockKind(it, AssetLockKind.SHIELD) }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    log.warn("shield funding-lock kind seed failed; the row may momentarily read Internal", t)
                }
                SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                classifyWalletShieldFailure(operation, walletId, lockedBefore, t)
            }
        }
    }

    /**
     * Post-failure classification of a [shieldFromWallet] attempt, by
     * EVIDENCE first: a new tracked lock row proves stage (a) happened
     * (the row is written before broadcast Rust-side), so the failure is
     * stage (b) and the sweep will finish it. Only when no new row exists
     * does the shared error decision table decide, and its definitive
     * pre-broadcast codes map to NotBroadcast; everything else stays
     * Ambiguous because the SDK's persistence bridge is asynchronous —
     * the absence of a row is NOT proof that no lock was broadcast.
     */
    private suspend fun classifyWalletShieldFailure(
        operation: String,
        walletId: ByteArray,
        lockedBefore: Set<String>,
        failure: Throwable
    ): SdkWriteResult<ShieldFromWalletOutcome> {
        val newLocks = try {
            source.walletShieldLocks(walletId).filter { it.outPointHex !in lockedBefore && it.resumable }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.error("shielded {} failed and the tracked-lock evidence is unreadable", operation, failure)
            return SdkWriteResult.Ambiguous(failure)
        }
        if (newLocks.isNotEmpty()) {
            log.error(
                "shielded {}: the L1 asset lock is out ({} tracked outpoint(s)) but the shield " +
                    "transition did not complete — it will be resumed automatically",
                operation, newLocks.size, failure
            )
            return SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY)
        }
        return when (val classified = classifyBroadcastFailure(failure)) {
            is SdkWriteResult.NotBroadcast -> {
                log.warn("shielded {} rejected pre-broadcast (no lock tracked)", operation, failure)
                classified
            }
            else -> {
                log.error(
                    "shielded {} outcome unconfirmed and no lock tracked (yet) — the pending-shield " +
                        "sweep recovers any lock once the SDK persists it",
                    operation, failure
                )
                SdkWriteResult.Ambiguous(failure)
            }
        }
    }

    override suspend fun resumePendingWalletShields(): Int {
        if (!isEnabled()) return 0
        if (!ensureShieldedReadyInner()) return 0
        return resumePendingWalletShieldsInner()
    }

    /**
     * The sweep body: resume every resumable ([PendingWalletShieldLock.resumable])
     * shielded-top-up lock, sequentially, isolating per-lock failures and
     * capping attempts per outpoint per process (each attempt costs a
     * ~30s proof). Requires the ready latch; does NOT require the L1
     * funding gate — the lock already exists, so completing the shield
     * spends nothing new and strands nothing.
     */
    private suspend fun resumePendingWalletShieldsInner(): Int {
        val walletId = readyWalletIdHex.value?.let(::walletIdFromHex) ?: return 0
        return walletShieldMutex.withLock {
            val pending = try {
                source.walletShieldLocks(walletId).filter { it.resumable }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("pending wallet-shield sweep: cannot read the tracked asset locks", t)
                return 0
            }
            if (pending.isEmpty()) return 0

            val recipient = try {
                source.shieldedDefaultAddress(walletId)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("pending wallet-shield sweep: shielded address lookup failed", t)
                null
            } ?: return 0

            var resumed = 0
            for (lock in pending) {
                val attempts = resumeAttempts.getOrDefault(lock.outPointHex, 0)
                if (attempts >= MAX_RESUME_ATTEMPTS_PER_PROCESS) {
                    log.warn(
                        "pending wallet-shield lock {} skipped after {} failed attempts this " +
                            "process (retries resume on the next app start)",
                        lock.outPointHex, attempts
                    )
                    continue
                }
                val outPoint = parseOutPointHex(lock.outPointHex)
                if (outPoint == null) {
                    log.warn("pending wallet-shield lock has a malformed outpoint key; skipping")
                    continue
                }
                try {
                    resumeAttempts[lock.outPointHex] = attempts + 1
                    source.resumeFundFromAssetLock(walletId, outPoint.first, outPoint.second, recipient)
                    resumed++
                    log.info("pending wallet-shield lock {} resumed and consumed", lock.outPointHex)
                    // The direct path kicks this too — without it a shield
                    // completed via RESUME (interrupted stage (b)) sat on the
                    // ~60s tick before the balance appeared (observed live).
                    markLocalSpendPending()
                    kickImmediateShieldedSync()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    log.warn(
                        "pending wallet-shield lock {} resume failed (attempt {}); the lock stays " +
                            "tracked and is retried later",
                        lock.outPointHex, attempts + 1, t
                    )
                }
            }
            // The user was told this transfer "will finish automatically";
            // this sweep is the only place that knows it HAS, and it runs
            // with no UI attached — publish it so the app can say so (see
            // [walletShieldResumed]). tryEmit: announcing must never block
            // or fail the sweep.
            if (resumed > 0 && !_walletShieldResumed.tryEmit(resumed)) {
                log.warn("pending wallet-shield completion event dropped ({} resumed)", resumed)
            }
            resumed
        }
    }

    private fun safeProgress(): ShadowSyncProgress = try {
        l1Progress()
    } catch (e: Exception) {
        log.warn("failed to read the SDK L1 sync progress; funding gate stays closed", e)
        ShadowSyncProgress.IDLE
    }

    private suspend fun isL1FundingFlagOn(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) == true
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_L1_SHADOW; treating as off", e)
        false
    }

    override suspend fun transferShielded(
        toAddress: String,
        amount: Dash,
        memo: String?
    ): SdkWriteResult<Unit> {
        // Pure preflights first — provably nothing submitted.
        val recipientRaw43 = decodeOrchardAddress(toAddress, displayHrpSafe())
            ?: return notBroadcast("transferShielded", "malformed or wrong-network shielded address", null)
        val cleanMemo = memo?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanMemo != null && cleanMemo.toByteArray(Charsets.UTF_8).size > MAX_SHIELDED_MEMO_BYTES) {
            return notBroadcast("transferShielded", "memo exceeds $MAX_SHIELDED_MEMO_BYTES UTF-8 bytes", null)
        }
        return runShieldedWrite("transferShielded", amount) { walletId, credits ->
            source.transfer(walletId, recipientRaw43, credits, cleanMemo)
        }
    }

    override suspend fun unshieldToCredits(amount: Dash): SdkWriteResult<Unit> =
        runShieldedWrite("unshieldToCredits", amount) { walletId, credits ->
            val target = try {
                source.ownPlatformAddressOrNull(walletId)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw PreBroadcastRejection("platform address lookup failed", t)
            } ?: throw PreBroadcastRejection("no platform receive address in the SDK store yet")
            source.unshield(walletId, target, credits)
        }

    override suspend fun withdrawToCore(addressBase58: String, amount: Dash): SdkWriteResult<Unit> {
        val address = addressBase58.trim()
        if (address.isEmpty()) {
            return notBroadcast("withdrawToCore", "empty core address", null)
        }
        return runShieldedWrite("withdrawToCore", amount) { walletId, credits ->
            source.withdraw(walletId, address, credits, WITHDRAW_CORE_FEE_PER_BYTE)
        }
    }

    /**
     * Signals a failure that provably happened BEFORE anything could be
     * submitted, from inside a write block — mapped to
     * [SdkWriteResult.NotBroadcast] instead of the ambiguous default.
     */
    private class PreBroadcastRejection(
        val reason: String,
        cause: Throwable? = null
    ) : Exception(reason, cause)

    /**
     * Shared write orchestration: flag → amount conversion → runtime
     * ready → ONE broadcast attempt, classified by
     * [classifyBroadcastFailure] ([SdkDashPayWrites]' decision table —
     * it already places the shielded-specific codes: `ShieldedBroadcastFailed`
     * / `ShieldedNoRecordedAnchor` are definitive non-execution →
     * NotBroadcast; `ShieldedSpendUnconfirmed` is "may be on chain, notes
     * stay reserved, do NOT retry" → Ambiguous).
     */
    private suspend fun runShieldedWrite(
        operation: String,
        amount: Dash,
        block: suspend (walletId: ByteArray, amountCredits: Long) -> Unit
    ): SdkWriteResult<Unit> {
        if (!isEnabled()) return SdkWriteResult.NotBroadcast("flag off")
        if (!amount.isPositive) {
            return notBroadcast(operation, "non-positive amount", null)
        }
        val amountCredits = try {
            dashToCredits(amount)
        } catch (e: ArithmeticException) {
            return notBroadcast(operation, "amount out of range", e)
        }

        // Preflight — nothing has been submitted if any of this fails.
        if (!ensureShieldedReady()) {
            return notBroadcast(operation, "shielded runtime not ready", null)
        }
        val walletId = readyWalletIdHex.value?.let(::walletIdFromHex)
            ?: return notBroadcast(operation, "shielded runtime not ready", null)

        // The single broadcast attempt (~30s Halo 2 proof — callers show
        // indeterminate progress; there is no per-proof progress callback).
        return try {
            block(walletId, amountCredits)
            markLocalSpendPending()
            kickImmediateShieldedSync()
            SdkWriteResult.Broadcast(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (t is PreBroadcastRejection) {
                return notBroadcast(operation, t.reason, t.cause)
            }
            val classified = classifyBroadcastFailure(t)
            when (classified) {
                is SdkWriteResult.NotBroadcast ->
                    log.warn("shielded {} rejected pre-broadcast (notes released)", operation, t)
                is SdkWriteResult.Ambiguous ->
                    log.error(
                        "shielded {} outcome unconfirmed — it MAY be on chain and the spent " +
                            "notes stay reserved; do NOT retry (the next shielded sync reconciles)",
                        operation,
                        t
                    )
                is SdkWriteResult.Broadcast -> Unit // unreachable
            }
            classified
        }
    }

    /**
     * Best-effort immediate shielded sync pass after a successful op so the
     * balance/activity flows update right away instead of on the next ~60s
     * background tick (user-reported: credits took a minute to appear on
     * the More card after the first successful shield). Never affects the
     * op result — failures are logged and swallowed; the background loop
     * remains the source of truth.
     */
    override suspend fun syncNow() {
        if (readyWalletIdHex.value == null) return
        kickImmediateShieldedSync()
    }

    override suspend fun pendingWalletShieldLockCount(): Int? {
        val walletId = readyWalletIdHex.value?.let(::walletIdFromHex) ?: return null
        return try {
            source.walletShieldLocks(walletId).count { it.resumable }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("pending-lock count unavailable; cutover evidence stays UNKNOWN", t)
            null
        }
    }

    /**
     * Mark the last-known/cached balance STALE after a successful local
     * spend (see [shieldedBalanceMaybeStale]): the note-store change has not
     * landed yet, so a surface must show "Syncing…" rather than the stale
     * amount until the next completed sync pass clears it (in
     * [startSyncStatusPolling]). Only the WRITE paths call this — a plain
     * screen-entry [syncNow] refresh must NOT mark the balance stale.
     */
    private fun markLocalSpendPending() {
        _shieldedBalanceMaybeStale.value = true
    }

    private suspend fun kickImmediateShieldedSync() {
        try {
            source.syncShieldedNow()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("post-op immediate shielded sync failed; the 60s loop will catch up", t)
        }
    }

    private fun notBroadcast(operation: String, reason: String, cause: Throwable?): SdkWriteResult.NotBroadcast {
        log.info("shielded {} not attempted ({})", operation, reason, cause)
        return SdkWriteResult.NotBroadcast(reason, cause)
    }

    /** [displayHrp] with failures contained (a throw must not escape a preflight). */
    private fun displayHrpSafe(): String = try {
        displayHrp()
    } catch (e: Exception) {
        "" // matches no valid address HRP → the caller rejects as malformed
    }

    private suspend fun isEnabled(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_SHIELDED; treating as off", e)
        false
    }

    companion object {
        private val log = LoggerFactory.getLogger(ShieldedBalanceServiceImpl::class.java)

        /** The one ZIP-32 shielded account the app binds (example-app parity). */
        internal const val DEFAULT_SHIELDED_ACCOUNT = 0

        /**
         * L1 fee rate for shielded withdrawals, duffs/byte. DPP only
         * accepts Fibonacci-sequence rates; 1 is the default the SDK and
         * its example apps use.
         */
        internal const val WITHDRAW_CORE_FEE_PER_BYTE = 1

        /**
         * The protocol's asset-lock base cost in duffs
         * (`required_asset_lock_duff_balance_for_processing_start_for_address_funding`,
         * rs-platform-version `dpp_state_transition_versions` v1–v3: 50000).
         * Used ONLY as a preflight fee floor — Rust re-derives the exact
         * consensus value pre-broadcast.
         */
        internal const val ASSET_LOCK_BASE_COST_DUFFS = 50_000L

        /**
         * Resume attempts per stuck lock per process — each attempt costs
         * a ~30s Halo 2 proof, so a permanently failing lock must not be
         * re-proved on every screen open. The counter resets on app start.
         */
        internal const val MAX_RESUME_ATTEMPTS_PER_PROCESS = 3

        /** How often the sync-status poller samples the pass-in-flight signal. */
        internal const val SYNC_STATUS_POLL_INTERVAL_MS = 500L

        /**
         * Consecutive idle polls (no pass in flight) after which the runtime is
         * considered caught up even if a pass was never observed — the
         * already-synced-wallet fallback so the "syncing…" placeholder is not
         * shown indefinitely. At [SYNC_STATUS_POLL_INTERVAL_MS] this is ~3s.
         */
        internal const val SYNC_STATUS_SETTLE_POLLS = 6
    }
}
