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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    suspend fun startShieldedSync()

    suspend fun stopShieldedSync()

    /** Kick the ~30s Halo 2 proving-key build onto a background thread. Idempotent. */
    suspend fun warmUpProver()

    /** Live unspent notes for the wallet, from the SDK's Room store. */
    fun observeUnspentNotes(walletId: ByteArray): Flow<List<ShieldedNoteEntity>>

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

    override suspend fun startShieldedSync() = manager().startShieldedSync()

    override suspend fun stopShieldedSync() = manager().stopShieldedSync()

    override suspend fun warmUpProver() = ShieldedProver.warmUp()

    override fun observeUnspentNotes(walletId: ByteArray): Flow<List<ShieldedNoteEntity>> =
        flow { emitAll(database().shieldedDao().observeUnspentNotesByWallet(walletId)) }

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
 */
@Singleton
class ShieldedBalanceServiceImpl internal constructor(
    private val source: ShieldedSource,
    private val dashPayConfig: DashPayConfig,
    private val shieldedDbPath: () -> String,
    private val displayHrp: () -> String
) : ShieldedBalanceService {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        sdkService: DashSdkService,
        dashPayConfig: DashPayConfig
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
        displayHrp = { shieldedHrp(toSdkNetwork(Constants.NETWORK_PARAMETERS)) }
    )

    /** Serializes [ensureShieldedReady]/[stop] — the single-flight guarantee. */
    private val lock = Mutex()

    /**
     * Ready latch AND the flows' switchboard: the bound SDK wallet id
     * (lowercase hex) once a bring-up pass succeeded, null otherwise.
     */
    private val readyWalletIdHex = MutableStateFlow<String?>(null)

    override suspend fun ensureShieldedReady(): Boolean {
        if (!isEnabled()) return false
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
            runCatching { source.stopShieldedSync() }
                .onFailure { log.warn("failed to stop the shielded sync loop", it) }
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
    }
}
