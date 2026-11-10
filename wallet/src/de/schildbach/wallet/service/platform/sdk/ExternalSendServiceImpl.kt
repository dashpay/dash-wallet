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

import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.payments.SendEngineNotSyncedException
import de.schildbach.wallet.payments.SendNotSdkRoutableException
import de.schildbach.wallet.payments.SendSignerLockedException
import de.schildbach.wallet.payments.PendingPaymentFundingGate
import de.schildbach.wallet.payments.isCompleteTxFailure
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.shielded.assetLockMaxFeeReserve
import de.schildbach.wallet.ui.shielded.shieldedMaxFeeAdjustment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.services.InsufficientFundsException
import org.dash.wallet.common.services.PaymentStillConfirmingException
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.WalletSession
import org.dash.wallet.common.util.readRetryDelayMs
import org.dash.wallet.common.util.retryReadErrors
import org.dashfoundation.dashsdk.credits.FundingInput
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

// ── Pure helpers ──────────────────────────────────────────────────────

/**
 * The [NotSentReason] for a [SdkWriteResult.NotBroadcast] — the reason text and the
 * cause chain are matched against the named reasons the sibling services emit, so the
 * UI never sees SDK text. Unmatched → [NotSentReason.GENERIC].
 */
internal fun notSentReasonFor(result: SdkWriteResult.NotBroadcast): NotSentReason {
    val texts = buildList {
        add(result.reason)
        var t: Throwable? = result.cause
        var depth = 0
        while (t != null && depth < 10) {
            t.message?.let(::add)
            t = t.cause
            depth++
        }
    }
    fun any(vararg needles: String) = texts.any { text -> needles.any { text.contains(it) } }
    return when {
        any(L1_PENDING_PAYMENT_UNPROTECTED_REASON, REASON_SHIELDED_IDENTITY_DEBIT_PENDING) ->
            NotSentReason.PAYMENT_STILL_CONFIRMING
        any(L1_SIGNER_LOCKED_REASON, REASON_PRE_BROADCAST_ASSET_LOCK_SIGNING) -> NotSentReason.SIGNER_LOCKED
        any(
            L1_FUNDING_GATE_CLOSED_REASON,
            "shielded runtime not ready",
            "L1 sync not running"
        ) -> NotSentReason.NOT_SYNCED
        any(
            REASON_PRE_BROADCAST_BUILD_SHORTFALL,
            REASON_PRE_BROADCAST_ASSET_LOCK_SELECTION,
            "Insufficient shielded balance",
            "asset lock coin selection is short",
            "does not cover the shield pool fee"
        ) -> NotSentReason.INSUFFICIENT_FUNDS
        result.reason == "flag off" || any("L1 shadow flag off") -> NotSentReason.NOT_SUPPORTED
        else -> NotSentReason.GENERIC
    }
}

/**
 * Classify a failure THROWN by the Core send ([SendPaymentService.sendCoins],
 * post-cutover the SDK route): only the typed refusals the send path raises strictly
 * before broadcasting are [ExternalSendResult.NotSent]; a submission-pending failure and
 * anything unknown is [ExternalSendResult.MayHaveGoneThrough].
 *
 * Every [IllegalStateException] / [IllegalArgumentException] on that path is a
 * pre-broadcast refusal: `sdkSendNotAttemptedException` (NotBroadcast with no fallback),
 * the wiped-wallet `ensureStillOwned` refusal and a foreign [WalletSession]. The
 * ambiguous SDK outcome is a [PaymentSubmissionPendingException], checked first.
 */
internal fun classifyCoreSendThrowable(e: Exception): ExternalSendResult = when (e) {
    is PaymentSubmissionPendingException -> ExternalSendResult.MayHaveGoneThrough
    is PaymentStillConfirmingException ->
        ExternalSendResult.NotSent(NotSentReason.PAYMENT_STILL_CONFIRMING)
    is SendEngineNotSyncedException -> ExternalSendResult.NotSent(NotSentReason.NOT_SYNCED)
    is SendSignerLockedException -> ExternalSendResult.NotSent(NotSentReason.SIGNER_LOCKED)
    is SendNotSdkRoutableException -> ExternalSendResult.NotSent(NotSentReason.NOT_SUPPORTED)
    is InsufficientFundsException -> ExternalSendResult.NotSent(NotSentReason.INSUFFICIENT_FUNDS)
    is org.bitcoinj.core.InsufficientMoneyException ->
        ExternalSendResult.NotSent(NotSentReason.INSUFFICIENT_FUNDS)
    is IllegalStateException, is IllegalArgumentException -> ExternalSendResult.NotSent(NotSentReason.GENERIC)
    else -> if (isCompleteTxFailure(e)) {
        ExternalSendResult.NotSent(NotSentReason.GENERIC)
    } else {
        ExternalSendResult.MayHaveGoneThrough
    }
}

/**
 * Pick Platform-address inputs that spend EXACTLY [targetCredits] (largest balances
 * first), or null when the addresses cannot cover it — never a partial spend.
 */
internal fun selectPlatformInputs(candidates: List<FundingInput>, targetCredits: Long): List<FundingInput>? {
    if (targetCredits <= 0) return null
    val picked = packOverageInputs(candidates, targetCredits)
    val covered = picked.fold(0L) { acc, input -> Math.addExact(acc, input.credits) }
    return picked.takeIf { covered == targetCredits }
}

/**
 * The Core fee rate of a Platform → Core withdrawal, duffs/byte: the minimum Fibonacci rate
 * DPP accepts, the rate the shielded withdrawal uses.
 */
internal const val PLATFORM_WITHDRAW_CORE_FEE_PER_BYTE = ShieldedBalanceServiceImpl.WITHDRAW_CORE_FEE_PER_BYTE

/** The DIP-17 Platform payment account a Platform → Core withdrawal spends from. */
internal const val PLATFORM_WITHDRAW_ACCOUNT_INDEX = 0

/** Size of an asset-unlock transaction in bytes (dashcore `ASSET_UNLOCK_TX_SIZE`). */
private const val ASSET_UNLOCK_TX_SIZE_BYTES = 190L

/**
 * The smallest Platform → Core withdrawal, in credits, at [PLATFORM_WITHDRAW_CORE_FEE_PER_BYTE]:
 * `min_withdrawal_amount` (1 000 000 credits, rs-platform-version system limits v2–v4) plus the
 * Core fee of the asset unlock, which from protocol version 14 is carved out of the withdrawn
 * amount (rs-dpp `min_withdrawal_amount_with_core_fee`: 190 bytes × the rate × 1000 credits per
 * duff). The SDK exposes no reader for it, and DPP would reject a smaller withdrawal only while
 * validating the signed transition, so [ExternalSendService.send] refuses it before anything is
 * selected or signed.
 */
internal const val PLATFORM_MIN_WITHDRAWAL_CREDITS =
    1_000_000L + ASSET_UNLOCK_TX_SIZE_BYTES * PLATFORM_WITHDRAW_CORE_FEE_PER_BYTE * CREDITS_PER_DUFF

/** The largest Platform → Core withdrawal, in credits (`max_withdrawal_amount`, 500 DASH, system limits v1–v4). */
internal const val PLATFORM_MAX_WITHDRAWAL_CREDITS = 50_000_000_000_000L

/** DPP's `max_address_inputs` (dpp state-transition versions v2–v4): at most this many inputs per transition. */
internal const val PLATFORM_MAX_ADDRESS_INPUTS = 16

/** Unsigned lexicographic order of 20-byte address hashes: the order of rs-dpp's `PlatformAddress` (P2PKH). */
private val ADDRESS_HASH_ORDER = Comparator<FundingInput> { a, b ->
    val x = a.hash
    val y = b.hash
    for (i in 0 until minOf(x.size, y.size)) {
        val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
        if (c != 0) return@Comparator c
    }
    x.size - y.size
}

/**
 * Inputs for a partial Platform → Core withdrawal (`withdrawCredits` with explicit inputs)
 * that take EXACTLY [targetCredits] in total, or null when the addresses cannot cover it.
 *
 * The SDK charges the Platform transition fee to the input whose address sorts FIRST
 * (`DeductFromInput(0)` over the inputs ordered by address), out of what that address KEEPS.
 * So the chosen set's first-sorting input (the fee payer) keeps at least [feeReserveCredits]
 * of its balance; every other input may be drawn in full. No input ever takes more than its
 * address's balance, every input takes at least [minInputCredits] (DPP rejects the whole
 * transition otherwise), only P2PKH addresses (the only kind the wallet signs) are used, and
 * at most [maxInputs] inputs are chosen. Of the feasible sets the one with the fewest inputs
 * (the fee grows with the input count) is returned, the fee payer first.
 */
internal fun selectPlatformWithdrawalInputs(
    candidates: List<FundingInput>,
    targetCredits: Long,
    feeReserveCredits: Long,
    minInputCredits: Long,
    maxInputs: Int = PLATFORM_MAX_ADDRESS_INPUTS
): List<FundingInput>? {
    if (targetCredits <= 0 || feeReserveCredits < 0 || maxInputs < 1) return null
    val min = maxOf(minInputCredits, 1L)
    val usable = candidates
        .filter { it.addressType == 0 && it.hash.size == 20 && it.credits >= min }
        .sortedWith(ADDRESS_HASH_ORDER)
    var best: List<FundingInput>? = null
    for ((index, payer) in usable.withIndex()) {
        val payerCap = payer.credits - feeReserveCredits
        if (payerCap < min) continue
        // Only addresses sorting AFTER the payer may join it: otherwise one of them would pay.
        val others = usable.subList(index + 1, usable.size).sortedByDescending { it.credits }
        val maxOthers = minOf(maxInputs - 1, others.size)
        for (count in 0..maxOthers) {
            if (best != null && count + 1 >= best.size) break
            val picked = others.take(count)
            val lowest = try {
                Math.multiplyExact(min, (count + 1).toLong())
            } catch (e: ArithmeticException) {
                break
            }
            val highest = picked.fold(payerCap) { acc, input -> saturatingAdd(acc, input.credits) }
            if (targetCredits < lowest) break
            if (targetCredits > highest) continue
            // Every input starts at the minimum; the rest is drawn from the others first
            // (in full if need be), then from the payer, which never exceeds payerCap.
            var extra = targetCredits - lowest
            val inputs = picked.map { input ->
                val add = minOf(extra, input.credits - min)
                extra -= add
                FundingInput(input.addressType, input.hash.copyOf(), min + add)
            }
            best = listOf(FundingInput(payer.addressType, payer.hash.copyOf(), min + extra)) + inputs
            break
        }
    }
    return best
}

private fun saturatingAdd(a: Long, b: Long): Long = try {
    Math.addExact(a, b)
} catch (e: ArithmeticException) {
    Long.MAX_VALUE
}

/**
 * The pool fee, in duffs, a Core → Shielded asset lock pays on top of what the recipient
 * receives: Rust derives `shield_amount = lock_value − pool_fee` with
 * `pool_fee = compute_minimum_shielded_fee(2) + asset_lock_base_cost`
 * (rs-platform-wallet `fund_from_asset_lock.rs`), both versioned consensus constants — so
 * for a given protocol version it does not depend on the request. [shieldFeeCredits] is the
 * SDK's 2-action shield fee ([ShieldedFeeKind.TRANSFER], `FeeKind.TransferOrShield`), rounded
 * UP to whole duffs, so a lock of `amount + poolFee` pays the recipient at least `amount`
 * (exactly, or less than one duff more).
 */
internal fun shieldPoolFeeDuffs(shieldFeeCredits: Long): Long =
    Math.addExact(ceilCreditsToDuffs(shieldFeeCredits), ShieldedBalanceServiceImpl.ASSET_LOCK_BASE_COST_DUFFS)

/**
 * The [retryReadErrors] `onError` for an external-send observation: logs that [what] is
 * unreadable (so unknown) and when it is re-read ([retryDelayMs] of the attempt).
 */
internal fun logReadRetry(
    what: String,
    retryDelayMs: (Long) -> Long = ::readRetryDelayMs
): (Exception, Long) -> Unit = { cause, attempt ->
    retryLog.warn("external send: {} unreadable; unknown, retrying in {} ms", what, retryDelayMs(attempt), cause)
}

private val retryLog = LoggerFactory.getLogger("de.schildbach.wallet.service.platform.sdk.ExternalSendRetry")

/**
 * The SDK's AUTO-withdrawal preflight ([ExternalSendSource.platformWithdrawalPreflight]):
 * whether the account can fund any withdrawal, and the transition fee it reserves on its fee
 * payer for all of its withdrawable (≥ minimum input) addresses, in credits (0 unless
 * [canWithdraw]). DPP's minimum fee grows with the input count, so for a subset of those
 * addresses it is an upper bound.
 */
internal data class PlatformWithdrawalPreflight(val canWithdraw: Boolean, val estimatedFeeCredits: Long)

// ── Source seam ───────────────────────────────────────────────────────

/**
 * Seam over everything [ExternalSendServiceImpl] touches (the SDK, the sibling send
 * services, config), so the gating / wallet-pinning / no-double-broadcast orchestration
 * is host-JVM unit-testable — the real calls need `libdash_sdk`. Raw native writes
 * THROW (classified by the caller with [classifyBroadcastFailure]); sibling-service
 * writes return their own [SdkWriteResult].
 */
internal interface ExternalSendSource {
    /** The dashj wallet session for a Core send; non-suspending (captured first). */
    fun currentCoreSession(): WalletSession?

    /** Same contract as [SdkDashPayWriteSource.boundWalletIdOrNull]. */
    suspend fun boundWalletIdOrNull(): String?

    /**
     * The SDK wallet bound right now, WITHOUT suspending or starting the SDK (null when none,
     * or not exactly one, is loaded): what the confirm step captures.
     */
    fun currentBoundWalletIdOrNull(): String?

    /** [SdkL1SendService.cutoverCommittedOrThrow]: throws when unreadable. */
    suspend fun cutoverCommittedOrThrow(): Boolean

    /** `USE_KOTLIN_SDK_SHIELDED`, error-preserving: throws when unreadable. */
    suspend fun shieldedEnabledOrThrow(): Boolean

    /** The user's registered identity (base58), null when there is none; throws when unreadable. */
    suspend fun ownIdentityIdOrThrow(): String?

    /**
     * [ownIdentityIdOrThrow] as it changes; emits [Result.failure] on a read error and keeps
     * observing (re-subscribes with back-off), never completing on a transient failure.
     */
    fun observeOwnIdentityId(): Flow<Result<String?>>

    /** The 32-byte ids of the identities [walletIdHex] MANAGES (watched identities excluded). */
    suspend fun managedIdentityIds(walletIdHex: String): List<ByteArray>

    // Reads
    fun observeCoreMaxSendableDuffs(): Flow<Long?>

    /**
     * The Core balance an asset lock can spend — the SDK's CONFIRMED balance (in a block or
     * InstantSend-locked), the figure the internal shield-from-wallet Max uses — in duffs;
     * null when unknown or the cutover is not committed (fail closed).
     */
    suspend fun coreAssetLockEligibleDuffs(): Long?

    fun observeShieldedBalance(): Flow<Dash?>
    suspend fun platformAddressBalances(walletIdHex: String): List<FundingInput>
    suspend fun identityBalanceCredits(identityIdBase58: String): Long?

    /** True when Platform has the identity, false when it answered "none"; throws on failure. */
    suspend fun identityExists(identityIdBase58: String): Boolean

    /** The identity's DPNS label, null when it has none; throws on failure. */
    suspend fun usernameOf(identityIdBase58: String): String?
    suspend fun shieldedFeeCredits(kind: ShieldedFeeKind): Long?

    /** The SDK wallet's spendable UTXO count for the Max shield reserve, null when unknown. */
    suspend fun spendableUtxoCount(): Int?

    // Writes
    suspend fun sendCore(address: String, amount: Dash, emptyWallet: Boolean, session: WalletSession?): String
    suspend fun shieldedWithdrawToCore(walletIdHex: String, address: String, amount: Dash): SdkWriteResult<Unit>
    suspend fun shieldedTransfer(walletIdHex: String, address: String, amount: Dash): SdkWriteResult<Unit>
    suspend fun shieldedUnshield(walletIdHex: String, address: String, amount: Dash): SdkWriteResult<Unit>
    suspend fun shieldedTopUpIdentity(walletIdHex: String, identityId: ByteArray, amount: Dash): SdkWriteResult<Unit>
    suspend fun shieldFromCore(
        walletIdHex: String,
        address: String,
        amount: Dash
    ): SdkWriteResult<ShieldFromWalletOutcome>
    suspend fun coreTopUpIdentity(identityIdBase58: String, amountDuffs: Long): SdkWriteResult<Long>
    suspend fun platformTransfer(walletIdHex: String, to: PlatformAddressBytes, amountCredits: Long)
    suspend fun platformTopUpIdentity(walletIdHex: String, identityId: ByteArray, inputs: List<FundingInput>)
    suspend fun identityWithdraw(walletIdHex: String, fromId: ByteArray, amountCredits: Long, coreAddress: String)
    suspend fun identityTransferToAddress(
        walletIdHex: String,
        fromId: ByteArray,
        to: PlatformAddressBytes,
        amountCredits: Long
    )
    suspend fun identityTransfer(walletIdHex: String, fromId: ByteArray, toId: ByteArray, amountCredits: Long)

    // Platform → Core (partial withdrawal)

    /**
     * The SDK's AUTO-withdrawal preflight of [walletIdHex]'s Platform payment account
     * ([PLATFORM_WITHDRAW_ACCOUNT_INDEX]) at [PLATFORM_WITHDRAW_CORE_FEE_PER_BYTE]
     * (`preflightWithdrawal`, an on-chain read; signs and spends nothing); throws when
     * unreadable.
     */
    suspend fun platformWithdrawalPreflight(walletIdHex: String): PlatformWithdrawalPreflight

    /** The version-locked minimum input amount of a Platform-address transition, in credits; throws when unreadable. */
    suspend fun platformMinInputCredits(walletIdHex: String): Long

    /**
     * `withdrawCredits(coreAddress, inputs, …)`: withdraw exactly [inputs] (each one's credits
     * from its address) to [coreAddress] at [PLATFORM_WITHDRAW_CORE_FEE_PER_BYTE]. Throws on
     * failure (classified by [classifyBroadcastFailure]).
     */
    suspend fun platformWithdraw(walletIdHex: String, coreAddress: String, inputs: List<FundingInput>)

    // → Shielded (third-party Orchard address); the [ShieldedBalanceService] write pipeline

    /** [ShieldedBalanceService.shieldFromCreditsToAddress] (Type 15 to [address]). */
    suspend fun platformShieldTo(walletIdHex: String, address: String, amount: Dash): SdkWriteResult<Unit>

    /** [ShieldedBalanceService.shieldFromIdentityToAddress] (Type 21 from [fromId] to [address]). */
    suspend fun identityShieldTo(
        walletIdHex: String,
        fromId: ByteArray,
        address: String,
        amount: Dash
    ): SdkWriteResult<Unit>

    // Core → Platform (`fundFromAssetLockExternal`)

    /**
     * The L1 funding gate ([SdkL1SendService.probeSendGate]): true only while the SDK's own
     * Core wallet has a complete, current view of the chain to build an asset lock from.
     */
    fun coreFundingGateOpen(): Boolean

    /**
     * Run [build], a FRESH asset-lock build, under the pending-payment funding gate's shared
     * selection lock ([de.schildbach.wallet.payments.PendingPaymentFundingGate]); null (nothing
     * run) while an uncertain earlier payment's inputs are exposed or a wallet wipe ran.
     */
    suspend fun <T> withFreshCoreFunding(build: suspend () -> T): T?

    /**
     * A P2PKH address of [walletIdHex]'s own Platform payment account
     * ([EXTERNAL_FUNDING_PLATFORM_ACCOUNT_INDEX]) other than [excludeHash], for the remainder:
     * the lowest unused index, else the lowest index (the selection the wallet's other
     * self-payments use). Null when there is none; throws when unreadable.
     */
    suspend fun ownPlatformRemainderAddress(walletIdHex: String, excludeHash: ByteArray): PlatformAddressBytes?

    /**
     * `fundFromAssetLockExternal` of [record] from its wallet (BIP44 account
     * [EXTERNAL_FUNDING_ACCOUNT_INDEX]): locks [PendingPlatformFunding.lockDuffs] and pays
     * [PendingPlatformFunding.recipients]. [onAssetLockBuilt] gets the new lock's outpoint key
     * synchronously on an SDK thread, before broadcast. Throws on failure.
     */
    suspend fun fundPlatformExternal(record: PendingPlatformFunding, onAssetLockBuilt: (outPointHex: String) -> Unit)

    /**
     * `resumeFundFromAssetLockExternal` of [record]'s tracked lock
     * ([PendingPlatformFunding.outPointHex], required) with exactly its recipients. Throws on
     * failure (an already-consumed lock as `AssetLockAlreadyConsumed`, see
     * [isAssetLockAlreadyConsumed]).
     */
    suspend fun resumePlatformFundingExternal(record: PendingPlatformFunding)

    /**
     * The SDK's tracked status of [walletIdHex]'s asset lock [outPointHex]
     * ([ASSET_LOCK_STATUS_CONSUMED] = done), null when the SDK tracks no such lock of that
     * wallet; throws when unreadable.
     */
    suspend fun trackedAssetLockStatus(walletIdHex: String, outPointHex: String): Int?

    /**
     * Whether [walletIdHex] has any Platform-address funding lock
     * ([ASSET_LOCK_FUNDING_TYPE_ADDRESS_TOPUP]) that is not Consumed; throws when unreadable.
     */
    suspend fun hasUnconsumedAddressFundingLocks(walletIdHex: String): Boolean
}

/** Production [ExternalSendSource]. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class DashSdkExternalSendSource(
    private val sdkService: DashSdkService,
    private val sendPaymentService: SendPaymentService,
    private val sdkL1SendService: SdkL1SendService,
    private val cutoverUiDataService: CutoverUiDataService,
    private val shieldedBalanceService: ShieldedBalanceService,
    private val transparentTopUp: SdkTransparentTopUp,
    private val usernameQueries: SdkUsernameQueries,
    private val dashPayConfig: DashPayConfig,
    private val identityConfig: BlockchainIdentityConfig,
    private val walletData: WalletData,
    private val fundingGate: PendingPaymentFundingGate
) : ExternalSendSource {

    private suspend fun manager(): org.dashfoundation.dashsdk.wallet.PlatformWalletManager {
        sdkService.ensureStarted()
        return checkNotNull(sdkService.walletManagerOrNull()) { "SDK wallet manager missing after ensureStarted()" }
    }

    /** The PINNED wallet — never "whichever is current". */
    private suspend fun wallet(walletIdHex: String): org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet =
        checkNotNull(manager().wallets.value[walletIdHex]) { "SDK wallet not loaded" }

    override fun currentCoreSession(): WalletSession? = sendPaymentService.currentWalletSession()

    override suspend fun boundWalletIdOrNull(): String? = manager().wallets.value.keys.singleOrNull()

    override fun currentBoundWalletIdOrNull(): String? = sdkService.loadedWalletIds().singleOrNull()

    override suspend fun cutoverCommittedOrThrow(): Boolean = sdkL1SendService.cutoverCommittedOrThrow()

    override suspend fun shieldedEnabledOrThrow(): Boolean =
        dashPayConfig.getPreservingErrors(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true

    override suspend fun ownIdentityIdOrThrow(): String? = ownIdentity(
        identityConfig.getPreservingErrors(BlockchainIdentityConfig.IDENTITY_ID),
        identityConfig.getPreservingErrors(BlockchainIdentityConfig.CREATION_STATE)
    )

    override fun observeOwnIdentityId(): Flow<Result<String?>> =
        combine(
            identityConfig.observePreservingErrors(BlockchainIdentityConfig.IDENTITY_ID),
            identityConfig.observePreservingErrors(BlockchainIdentityConfig.CREATION_STATE)
        ) { id, state -> Result.success(ownIdentity(id, state)) }
            .retryReadErrors(
                Result.failure(IllegalStateException("identity record unreadable")),
                onError = logReadRetry("identity record")
            )

    /** A registered identity; an unknown creation state throws (unknown, not "none"). */
    private fun ownIdentity(id: String?, state: String?): String? {
        val creation = IdentityCreationState.valueOf(state ?: IdentityCreationState.NONE.name)
        return id?.takeIf { it.isNotBlank() && creation >= IdentityCreationState.IDENTITY_REGISTERED }
    }

    override suspend fun managedIdentityIds(walletIdHex: String): List<ByteArray> =
        wallet(walletIdHex).inMemoryIdentityStates().filter { !it.watched }.map { it.identityId }

    override fun observeCoreMaxSendableDuffs(): Flow<Long?> =
        combine(
            sdkL1SendService.cutoverCommittedFlow(),
            cutoverUiDataService.sdkMaxSendable,
            cutoverUiDataService.sdkTotalBalance
        ) { committed, maxSendable, total ->
            // Only the SDK's own figures; never the frozen dashj balance.
            if (committed == true) (maxSendable ?: total)?.value else null
        }

    override suspend fun coreAssetLockEligibleDuffs(): Long? =
        combine(
            sdkL1SendService.cutoverCommittedFlow(),
            cutoverUiDataService.sdkConfirmedBalance
        ) { committed, confirmed ->
            // Only the SDK's own confirmed figure (ShieldedTransferViewModel's post-cutover
            // source); never the frozen dashj ChainLocked fallback.
            if (committed == true) confirmed?.value else null
        }.first()

    override fun observeShieldedBalance(): Flow<Dash?> =
        flow { emitAll(dashPayConfig.observePreservingErrors(DashPayConfig.USE_KOTLIN_SDK_SHIELDED)) }
            // Any other DashPayConfig write re-emits the flag; don't restart the balance feeds for it.
            .distinctUntilChanged()
            .flatMapLatest { enabled ->
                if (enabled != true) {
                    flowOf(null)
                } else {
                    combine(
                        shieldedBalanceService.observeShieldedBalance(),
                        shieldedBalanceService.shieldedSyncStatus,
                        shieldedBalanceService.shieldedBalanceMaybeStale
                    ) { balance, status, stale ->
                        if (status == ShieldedSyncStatus.READY && !stale) balance else null
                    }
                }
            }
            .retryReadErrors(null, onError = logReadRetry("shielded balance"))

    override suspend fun platformAddressBalances(walletIdHex: String): List<FundingInput> =
        wallet(walletIdHex).addressesWithBalances()

    override suspend fun identityBalanceCredits(identityIdBase58: String): Long? {
        sdkService.ensureStarted()
        val sdk = checkNotNull(sdkService.sdkOrNull()) { "SDK runtime missing after ensureStarted()" }
        return sdk.identities.fetchBalance(identityIdBase58)
    }

    override suspend fun identityExists(identityIdBase58: String): Boolean {
        sdkService.ensureStarted()
        val sdk = checkNotNull(sdkService.sdkOrNull()) { "SDK runtime missing after ensureStarted()" }
        return sdk.identities.fetch(identityIdBase58) != null
    }

    override suspend fun usernameOf(identityIdBase58: String): String? {
        val documents = checkNotNull(usernameQueries.getDomainDocumentsByOwnerOrNull(identityIdBase58)) {
            "DPNS lookup unavailable"
        }
        return documents.firstNotNullOfOrNull { it.data["label"] as? String }
    }

    override suspend fun shieldedFeeCredits(kind: ShieldedFeeKind): Long? =
        shieldedBalanceService.estimateShieldedFeeCredits(kind)

    override suspend fun spendableUtxoCount(): Int? = walletData.spendableUtxoCount().takeIf { it > 0 }

    override suspend fun sendCore(address: String, amount: Dash, emptyWallet: Boolean, session: WalletSession?): String =
        sendPaymentService.sendCoins(address, amount, emptyWallet = emptyWallet, session = session)

    override suspend fun shieldedWithdrawToCore(walletIdHex: String, address: String, amount: Dash) =
        shieldedBalanceService.withdrawToCoreExternal(address, amount, walletIdHex)

    override suspend fun shieldedTransfer(walletIdHex: String, address: String, amount: Dash) =
        shieldedBalanceService.transferShieldedExternal(address, amount, walletIdHex)

    override suspend fun shieldedUnshield(walletIdHex: String, address: String, amount: Dash) =
        shieldedBalanceService.unshieldToPlatformAddress(address, amount, walletIdHex)

    override suspend fun shieldedTopUpIdentity(walletIdHex: String, identityId: ByteArray, amount: Dash) =
        shieldedBalanceService.topUpIdentityFromShielded(identityId, amount, walletIdHex)

    override suspend fun shieldFromCore(walletIdHex: String, address: String, amount: Dash) =
        shieldedBalanceService.shieldFromWalletToAddress(address, amount, walletIdHex)

    override suspend fun coreTopUpIdentity(identityIdBase58: String, amountDuffs: Long): SdkWriteResult<Long> =
        transparentTopUp.topUp(identityIdBase58, amountDuffs, newUserIntent = true)

    override suspend fun platformTransfer(walletIdHex: String, to: PlatformAddressBytes, amountCredits: Long) {
        val manager = manager()
        wallet(walletIdHex).transferCredits(
            outputs = listOf(
                org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet.CreditOutput(to.addressType, to.hash, amountCredits)
            ),
            signerHandle = manager.signerHandle
        )
    }

    override suspend fun platformTopUpIdentity(walletIdHex: String, identityId: ByteArray, inputs: List<FundingInput>) {
        val manager = manager()
        manager.identityCredits.topUpFromAddresses(
            walletHandle = wallet(walletIdHex).handle,
            identityId = identityId,
            inputs = inputs,
            signerHandle = manager.signerHandle
        )
    }

    override suspend fun identityWithdraw(walletIdHex: String, fromId: ByteArray, amountCredits: Long, coreAddress: String) {
        val manager = manager()
        manager.identityCredits.withdraw(
            walletHandle = wallet(walletIdHex).handle,
            identityId = fromId,
            amount = amountCredits,
            toAddress = coreAddress,
            signerHandle = manager.signerHandle
        )
    }

    override suspend fun identityTransferToAddress(
        walletIdHex: String,
        fromId: ByteArray,
        to: PlatformAddressBytes,
        amountCredits: Long
    ) {
        val manager = manager()
        manager.identityCredits.transferToAddresses(
            walletHandle = wallet(walletIdHex).handle,
            fromIdentityId = fromId,
            outputs = listOf(FundingInput(to.addressType, to.hash, amountCredits)),
            signerHandle = manager.signerHandle
        )
    }

    override suspend fun identityTransfer(walletIdHex: String, fromId: ByteArray, toId: ByteArray, amountCredits: Long) {
        val manager = manager()
        manager.identityCredits.transfer(
            walletHandle = wallet(walletIdHex).handle,
            fromIdentityId = fromId,
            toIdentityId = toId,
            amount = amountCredits,
            signerHandle = manager.signerHandle
        )
    }

    override suspend fun platformWithdrawalPreflight(walletIdHex: String): PlatformWithdrawalPreflight {
        val preflight = wallet(walletIdHex).preflightWithdrawal(
            accountIndex = PLATFORM_WITHDRAW_ACCOUNT_INDEX,
            coreFeePerByte = PLATFORM_WITHDRAW_CORE_FEE_PER_BYTE
        )
        return PlatformWithdrawalPreflight(preflight.canWithdraw, preflight.estimatedFee)
    }

    override suspend fun platformMinInputCredits(walletIdHex: String): Long = wallet(walletIdHex).minAmounts().minInput

    override suspend fun platformWithdraw(walletIdHex: String, coreAddress: String, inputs: List<FundingInput>) {
        val manager = manager()
        wallet(walletIdHex).withdrawCredits(
            coreAddress = coreAddress,
            inputs = inputs.map {
                org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet.WithdrawalInput(it.addressType, it.hash, it.credits)
            },
            coreFeePerByte = PLATFORM_WITHDRAW_CORE_FEE_PER_BYTE,
            signerHandle = manager.signerHandle,
            accountIndex = PLATFORM_WITHDRAW_ACCOUNT_INDEX
        )
    }

    override suspend fun platformShieldTo(walletIdHex: String, address: String, amount: Dash) =
        shieldedBalanceService.shieldFromCreditsToAddress(address, amount, walletIdHex)

    override suspend fun identityShieldTo(walletIdHex: String, fromId: ByteArray, address: String, amount: Dash) =
        shieldedBalanceService.shieldFromIdentityToAddress(fromId, address, amount, walletIdHex)

    override fun coreFundingGateOpen(): Boolean = sdkL1SendService.probeSendGate().allowed

    override suspend fun <T> withFreshCoreFunding(build: suspend () -> T): T? =
        fundingGate.selectionLock.selectIfAllowed({ fundingGate.isFreshFundingBlocked() }, build)?.value

    private suspend fun database(): org.dashfoundation.dashsdk.persistence.DashDatabase {
        sdkService.ensureStarted()
        return checkNotNull(sdkService.databaseOrNull()) { "SDK database missing after ensureStarted()" }
    }

    private fun walletIdBytes(walletIdHex: String): ByteArray =
        checkNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }

    override suspend fun ownPlatformRemainderAddress(walletIdHex: String, excludeHash: ByteArray): PlatformAddressBytes? {
        val rows = database().platformAddressDao().observeByWallet(walletIdBytes(walletIdHex)).first()
            .filter {
                it.accountIndex == EXTERNAL_FUNDING_PLATFORM_ACCOUNT_INDEX &&
                    it.addressType == 0 &&
                    it.addressHash.size == 20 &&
                    !it.addressHash.contentEquals(excludeHash)
            }
        val row = rows.filter { !it.isUsed }.minByOrNull { it.addressIndex } ?: rows.minByOrNull { it.addressIndex }
        return row?.let { PlatformAddressBytes(0, it.addressHash.copyOf()) }
    }

    override suspend fun fundPlatformExternal(record: PendingPlatformFunding, onAssetLockBuilt: (String) -> Unit) {
        val manager = manager()
        wallet(record.walletIdHex).fundFromAssetLockExternal(
            amountDuffs = record.lockDuffs,
            fundingAccountIndex = EXTERNAL_FUNDING_ACCOUNT_INDEX,
            platformAccountIndex = EXTERNAL_FUNDING_PLATFORM_ACCOUNT_INDEX,
            recipients = record.recipients,
            signerHandle = manager.signerHandle,
            coreSignerHandle = manager.mnemonicResolverHandle,
            onAssetLockBuilt = { outPoint -> onAssetLockBuilt(outPoint.outPointHex) }
        )
    }

    override suspend fun resumePlatformFundingExternal(record: PendingPlatformFunding) {
        val outPoint = checkNotNull(record.outPointHex?.let(::parseOutPointHex)) { "no lock outpoint recorded" }
        val manager = manager()
        wallet(record.walletIdHex).resumeFundFromAssetLockExternal(
            outPointTxid = outPoint.first,
            outPointVout = outPoint.second,
            platformAccountIndex = EXTERNAL_FUNDING_PLATFORM_ACCOUNT_INDEX,
            recipients = record.recipients,
            signerHandle = manager.signerHandle,
            coreSignerHandle = manager.mnemonicResolverHandle
        )
    }

    override suspend fun trackedAssetLockStatus(walletIdHex: String, outPointHex: String): Int? {
        val walletId = walletIdBytes(walletIdHex)
        return database().assetLockDao().getByOutPointHex(outPointHex)
            ?.takeIf { it.walletId.contentEquals(walletId) }
            ?.statusRaw
    }

    override suspend fun hasUnconsumedAddressFundingLocks(walletIdHex: String): Boolean =
        database().assetLockDao()
            .observeByWalletAndFundingType(walletIdBytes(walletIdHex), ASSET_LOCK_FUNDING_TYPE_ADDRESS_TOPUP)
            .first()
            .any { it.statusRaw != ASSET_LOCK_STATUS_CONSUMED }

    private companion object {
        private val log = LoggerFactory.getLogger(DashSdkExternalSendSource::class.java)
    }
}

// ── Service ───────────────────────────────────────────────────────────

/**
 * Default [ExternalSendService]. Orchestration only; every money move goes through an
 * existing hardened path (the Core send funnel, [ShieldedBalanceService]'s write
 * pipeline, [SdkTransparentTopUp]) or one SDK credits call classified by
 * [classifyBroadcastFailure].
 *
 * Funds safety, in order, for every [send]:
 * 1. the Core [WalletSession] is captured before anything suspends (and the SDK wallet id
 *    by the caller, at confirmation);
 * 2. single-flight: a concurrent send is refused ([NotSentReason.IN_PROGRESS]);
 * 3. cutover committed (and the shielded flag for a shielded leg), error-preserving;
 * 4. the SDK wallet the caller captured at confirmation (non-suspending,
 *    [ExternalSendService.currentWalletIdOrNull]) must be the bound one — a different or
 *    missing wallet is [NotSentReason.GENERIC] with nothing called; then
 *    [externalSendSupport] is enforced here (not only in the UI) with the own-identity
 *    facts read from the SDK;
 * 5. right before the one write, the bound wallet is read again — a different wallet
 *    is [NotSentReason.GENERIC] with nothing called — and the write is pinned to the
 *    captured id;
 * 6. the outcome: Broadcast → Sent, NotBroadcast → NotSent, Ambiguous / anything
 *    unclassified → [ExternalSendResult.MayHaveGoneThrough] (terminal). Core → Platform
 *    additionally persists its recipients before the call and is never NotSent once its
 *    asset lock was built ([sendCoreToPlatform], [resumePendingPlatformFunding]).
 */
@Singleton
class ExternalSendServiceImpl internal constructor(
    private val source: ExternalSendSource,
    private val network: AddressNetwork,
    private val platformFundingStore: PlatformFundingStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val balancePollMs: Long = BALANCE_POLL_MS,
    private val retryDelayMs: (Long) -> Long = ::readRetryDelayMs,
    private val newFundingId: () -> String = { UUID.randomUUID().toString() },
    /**
     * Seed the asset-lock kind ([AssetLockKind.PLATFORM]) in-memory the instant a
     * Core → Platform lock is built, so the first classification of the L1 lock reads
     * "Platform" instead of momentarily "Internal" while the SDK's `asset_locks` row
     * lands — mirroring the shield/top-up/invite paths. No-op default keeps the
     * host-JVM tests inert.
     */
    private val seedAssetLockKind: (displayHex: String, kind: AssetLockKind) -> Unit = { _, _ -> }
) : ExternalSendService {

    @Inject
    constructor(
        sdkService: DashSdkService,
        sendPaymentService: SendPaymentService,
        sdkL1SendService: SdkL1SendService,
        cutoverUiDataService: CutoverUiDataService,
        shieldedBalanceService: ShieldedBalanceService,
        transparentTopUp: SdkTransparentTopUp,
        usernameQueries: SdkUsernameQueries,
        dashPayConfig: DashPayConfig,
        identityConfig: BlockchainIdentityConfig,
        walletData: WalletData,
        fundingGate: PendingPaymentFundingGate,
        assetLockKindResolver: AssetLockKindResolver
    ) : this(
        source = DashSdkExternalSendSource(
            sdkService, sendPaymentService, sdkL1SendService, cutoverUiDataService, shieldedBalanceService,
            transparentTopUp, usernameQueries, dashPayConfig, identityConfig, walletData, fundingGate
        ),
        network = AddressNetwork.fromId(Constants.NETWORK_PARAMETERS.id),
        platformFundingStore = DashPayConfigPlatformFundingStore(dashPayConfig),
        seedAssetLockKind = { displayHex, kind -> assetLockKindResolver.seed(displayHex, kind) }
    )

    /** Single-flight for [send] AND [resumePendingPlatformFunding]: the one owner of the funding record. */
    private val inFlight = AtomicBoolean(false)

    /** [resumePendingPlatformFunding] attempts per funding id, this process. */
    private val platformResumeAttempts = ConcurrentHashMap<String, Int>()

    // ── Reads ─────────────────────────────────────────────────────────

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeBalance(source: SendBalance): Flow<Dash?> = when (source) {
        SendBalance.CORE -> this.source.observeCoreMaxSendableDuffs().map { duffs -> duffs?.let(::Dash) }
        SendBalance.SHIELDED -> this.source.observeShieldedBalance()
        SendBalance.PLATFORM -> polled { walletIdHex ->
            val credits = this.source.platformAddressBalances(walletIdHex)
                .fold(0L) { acc, input -> Math.addExact(acc, input.credits) }
            creditsToDash(credits)
        }
        // distinctUntilChanged: an unrelated config write re-emits the same identity, which
        // would otherwise restart the poll and re-fetch the balance at once.
        SendBalance.IDENTITY -> this.source.observeOwnIdentityId().distinctUntilChanged().flatMapLatest { own ->
            val id = own.getOrNull()
            if (own.isFailure || id == null) {
                flowOf(null)
            } else {
                polled { _ -> this.source.identityBalanceCredits(id)?.takeIf { it >= 0 }?.let(::creditsToDash) }
            }
        }
    }.retryReadErrors(null, retryDelayMs, logReadRetry("$source balance", retryDelayMs)).distinctUntilChanged()

    /** Poll [read] (null on failure) on IO every [balancePollMs] while collected. */
    private fun polled(read: suspend (walletIdHex: String) -> Dash?): Flow<Dash?> = flow {
        while (true) {
            val value = try {
                withContext(ioDispatcher) { source.boundWalletIdOrNull()?.let { read(it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("external send: balance read failed; unknown", e)
                null
            }
            emit(value)
            delay(balancePollMs)
        }
    }

    override fun observeHasIdentity(): Flow<Boolean?> =
        source.observeOwnIdentityId()
            .map<Result<String?>, Boolean?> { result -> if (result.isFailure) null else result.getOrNull() != null }
            .retryReadErrors(null, retryDelayMs, logReadRetry("identity record", retryDelayMs))
            .distinctUntilChanged()

    override fun currentWalletIdOrNull(): String? = try {
        source.currentBoundWalletIdOrNull()
    } catch (e: Exception) {
        log.warn("external send: bound wallet unreadable", e)
        null
    }

    override suspend fun isOwnIdentity(identityIdBase58: String): Boolean? {
        val target = decodeIdentityId(identityIdBase58) ?: return false
        return try {
            withContext(ioDispatcher) {
                val walletIdHex = source.boundWalletIdOrNull() ?: return@withContext null
                source.managedIdentityIds(walletIdHex).any { it.contentEquals(target) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("external send: managed-identity lookup failed; unknown", e)
            null
        }
    }

    override suspend fun resolveIdentity(identityIdBase58: String): IdentityResolution {
        val id = identityIdBase58.trim()
        if (decodeIdentityId(id) == null) return IdentityResolution.NotFound
        val exists = try {
            withContext(ioDispatcher) { source.identityExists(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("external send: identity lookup failed; unknown", e)
            return IdentityResolution.Unknown
        }
        if (!exists) return IdentityResolution.NotFound
        // The name is display-only: a failed name lookup still confirms the identity.
        val username = try {
            withContext(ioDispatcher) { source.usernameOf(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("external send: username lookup failed; showing the identity without a name", e)
            null
        }
        return IdentityResolution.Found(username)
    }

    override suspend fun estimateFee(source: SendBalance, destinationKind: SendBalance): FeeEstimate? =
        try {
            withContext(ioDispatcher) { estimateFeeInner(source, destinationKind) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("external send: fee estimate failed; unknown", e)
            null
        }

    /**
     * - Core → Core: the SDK send's size-based fee reserve ([sendAllFeeReserveDuffs] over the
     *   spendable UTXO count; the flat [SEND_ALL_FEE_RESERVE_DUFFS] when unknown), an upper
     *   bound the real (smaller) fee never exceeds. A Max (drain) takes it OUT of the amount;
     *   any other send pays it on top.
     * - Core → Shielded: the shield pool fee ([shieldPoolFeeDuffs]), paid ON TOP of the
     *   amount ([send] locks `amount + pool fee`, the recipient receives `amount`); the L1
     *   fee of the lock is extra and unknown, so not exact.
     * - Core → Platform: [coreToPlatformFeeDuffs], paid ON TOP of the amount — the L1
     *   asset-lock fee reserve (an upper bound) plus the Platform-fee margin, whose unused
     *   part lands on the wallet's own Platform address. Not exact; null when the UTXO
     *   count is unknown.
     * - Shielded → *: the flat single-note pool fee of the transition; more notes cost more.
     * - Core → Identity, Platform → *, Identity → *: Rust meters these at execution; no
     *   pre-send figure exists, so null. (Platform → Core's withdrawal preflight estimate is
     *   only the fee payer's reserve, not the fee of this exact set of inputs, and the Core
     *   fee of the asset unlock comes out of the amount on top; Platform/Identity → Shielded
     *   add the shielded compute fee to the metered one.)
     */
    private suspend fun estimateFeeInner(source: SendBalance, destinationKind: SendBalance): FeeEstimate? =
        when (source) {
            SendBalance.CORE -> when (destinationKind) {
                SendBalance.CORE -> FeeEstimate(Dash(coreSendFeeReserveDuffs()), exact = false)
                SendBalance.SHIELDED -> this.source.shieldedFeeCredits(ShieldedFeeKind.TRANSFER)?.let {
                    FeeEstimate(Dash(shieldPoolFeeDuffs(it)), exact = false)
                }
                SendBalance.PLATFORM -> this.source.spendableUtxoCount()?.let {
                    FeeEstimate(Dash(coreToPlatformFeeDuffs(it)), exact = false)
                }
                SendBalance.IDENTITY -> null
            }
            SendBalance.SHIELDED -> this.source.shieldedFeeCredits(shieldedKindFor(destinationKind))?.let {
                FeeEstimate(Dash(ceilCreditsToDuffs(it)), exact = false)
            }
            SendBalance.PLATFORM, SendBalance.IDENTITY -> null
        }

    /**
     * - Core → Core: the SDK's max-sendable L1 figure, GROSS of the drain fee (the drain
     *   delivers it minus the fee — the Basic send's `sendAllBalance` semantics). Null (Max
     *   unavailable) unless it exceeds the fee reserve [estimateFee] shows, so the confirmed
     *   recipient amount (amount − fee) is always positive.
     * - Core → Shielded: [coreToShieldedMaxDuffs] — from the asset-lock-eligible balance,
     *   less the L1 fee reserve and the shield pool fee.
     * - Core → Platform: [coreToPlatformMaxDuffsOrNull] — the same eligible balance less the
     *   L1 fee reserve and the Platform-fee margin.
     * - Shielded → *: the READY balance minus the single-note pool fee; a Max send also
     *   retries once with the exact deficit the note selection reports.
     * - Everything else: null (no pre-send fee figure).
     */
    override suspend fun maxSendable(source: SendBalance, destinationKind: SendBalance): Dash? = try {
        when (source) {
            SendBalance.CORE -> when (destinationKind) {
                SendBalance.CORE -> withContext(ioDispatcher) { coreToCoreMaxDuffs() }?.let(::Dash)
                SendBalance.SHIELDED -> withContext(ioDispatcher) { coreToShieldedMaxDuffs() }?.let(::Dash)
                SendBalance.PLATFORM -> withContext(ioDispatcher) { coreToPlatformMaxDuffsOrNull() }?.let(::Dash)
                SendBalance.IDENTITY -> null
            }
            SendBalance.SHIELDED -> {
                val balance = this.source.observeShieldedBalance().first()
                val feeCredits = withContext(ioDispatcher) {
                    this@ExternalSendServiceImpl.source.shieldedFeeCredits(shieldedKindFor(destinationKind))
                }
                if (balance == null || feeCredits == null) {
                    null
                } else {
                    (balance - Dash(ceilCreditsToDuffs(feeCredits))).takeIf { it.isPositive }
                }
            }
            SendBalance.PLATFORM, SendBalance.IDENTITY -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("external send: max sendable unreadable", e)
        null
    }

    /** The Core send fee reserve: size-based, the flat reserve when the UTXO count is unknown. */
    private suspend fun coreSendFeeReserveDuffs(): Long = sendAllFeeReserveDuffs(source.spendableUtxoCount())

    /**
     * The Core → Core Max (gross, drained): the pooled max sendable, null when unknown or not
     * above the fee reserve (Max unavailable — the drain would deliver nothing). Throws when a
     * read fails.
     */
    private suspend fun coreToCoreMaxDuffs(): Long? {
        val pooled = source.observeCoreMaxSendableDuffs().first() ?: return null
        return pooled.takeIf { it > coreSendFeeReserveDuffs() }
    }

    /**
     * The Core → Shielded Max, mirroring the internal shield-from-wallet Max's source
     * ([ExternalSendSource.coreAssetLockEligibleDuffs]): eligible − the L1 asset-lock fee
     * reserve ([assetLockMaxFeeReserve], reserved up front) − the shield pool fee. Null when
     * any figure is unknown or the result is not positive. Throws when a read fails.
     */
    private suspend fun coreToShieldedMaxDuffs(): Long? {
        val eligible = source.coreAssetLockEligibleDuffs() ?: return null
        val utxoCount = source.spendableUtxoCount() ?: return null
        val feeCredits = source.shieldedFeeCredits(ShieldedFeeKind.TRANSFER) ?: return null
        val max = eligible - assetLockMaxFeeReserve(utxoCount).duffs - shieldPoolFeeDuffs(feeCredits)
        return max.takeIf { it > 0 }
    }

    /**
     * The Core → Platform Max: [coreToPlatformMaxDuffs] of the asset-lock-eligible balance,
     * null when a figure is unknown, the result is not positive, or it is below the Platform
     * minimum output. Throws when a read fails.
     */
    private suspend fun coreToPlatformMaxDuffsOrNull(): Long? {
        val eligible = source.coreAssetLockEligibleDuffs() ?: return null
        val utxoCount = source.spendableUtxoCount() ?: return null
        return coreToPlatformMaxDuffs(eligible, utxoCount)
            ?.takeIf { it >= ceilCreditsToDuffs(PLATFORM_MIN_OUTPUT_CREDITS) }
    }

    // ── Send ──────────────────────────────────────────────────────────

    override suspend fun send(
        source: SendBalance,
        destination: SendDestination,
        amount: Dash,
        isMax: Boolean,
        expectedWalletIdHex: String?
    ): ExternalSendResult {
        // (1) Captured before anything can suspend.
        val coreSession = if (source == SendBalance.CORE && destination is SendDestination.Core) {
            this.source.currentCoreSession()
        } else {
            null
        }
        // (2) Single-flight.
        if (!inFlight.compareAndSet(false, true)) {
            log.warn("external send refused: another external send is in progress")
            return ExternalSendResult.NotSent(NotSentReason.IN_PROGRESS)
        }
        return try {
            withContext(ioDispatcher) {
                sendInner(source, destination, amount, isMax, coreSession, expectedWalletIdHex)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Every pre-submission step returns its own NotSent; an exception escaping here
            // came from a write path and may have submitted.
            log.error("external send {} → {} failed unclassified; outcome unknown", source, kindOf(destination), e)
            ExternalSendResult.MayHaveGoneThrough
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun sendInner(
        source: SendBalance,
        destination: SendDestination,
        amount: Dash,
        isMax: Boolean,
        coreSession: WalletSession?,
        expectedWalletIdHex: String?
    ): ExternalSendResult {
        val pair = "$source → ${kindOf(destination)}"
        if (!amount.isPositive) return notSent(pair, NotSentReason.GENERIC, "non-positive amount")
        // The destination must be a valid one of THIS network (the UI parsed it, but the
        // service does not trust that).
        if (!destinationValid(destination)) return notSent(pair, NotSentReason.GENERIC, "invalid destination")

        // (3) Engine ownership / flags, error-preserving.
        val committed = readOrNull("cutover state") { this.source.cutoverCommittedOrThrow() }
            ?: return notSent(pair, NotSentReason.GENERIC, "cutover state unreadable")
        if (!committed) return notSent(pair, NotSentReason.NOT_SUPPORTED, "cutover not committed")
        if (source == SendBalance.SHIELDED || destination is SendDestination.Shielded) {
            val shielded = readOrNull("shielded flag") { this.source.shieldedEnabledOrThrow() }
                ?: return notSent(pair, NotSentReason.GENERIC, "shielded flag unreadable")
            if (!shielded) return notSent(pair, NotSentReason.NOT_SUPPORTED, "shielded flag off")
        }

        // (4) The wallet the user confirmed with (captured by the caller without suspending)
        // must be the one bound now; every later check and write is pinned to it.
        val walletIdHex = expectedWalletIdHex
            ?: return notSent(pair, NotSentReason.GENERIC, "no SDK wallet captured at confirmation")
        if (!stillBound(pair, walletIdHex)) return ExternalSendResult.NotSent(NotSentReason.GENERIC)
        val ownIdentity = readOrNull("own identity") { Result.success(this.source.ownIdentityIdOrThrow()) }
            ?: return notSent(pair, NotSentReason.GENERIC, "identity record unreadable")
        val ownIdentityId = ownIdentity.getOrNull()
        val destinationIsOwnIdentity: Boolean? = when (destination) {
            is SendDestination.Identity -> if (source == SendBalance.CORE) {
                decodeIdentityId(destination.identityIdBase58)?.let { target ->
                    readOrNull("managed identities") {
                        this.source.managedIdentityIds(walletIdHex).any { it.contentEquals(target) }
                    }
                }
            } else {
                ownIdentityId?.let { decodeIdentityId(it) }?.let { own ->
                    decodeIdentityId(destination.identityIdBase58)?.contentEquals(own)
                }
            }
            else -> null
        }
        val support = externalSendSupport(source, destination, ownIdentityId != null, destinationIsOwnIdentity)
        if (support != SendSupport.SUPPORTED) {
            return notSent(pair, NotSentReason.NOT_SUPPORTED, "pair not supported ($support)")
        }

        // The identity source must be one this wallet manages (it signs with its keys).
        val ownIdentityBytes: ByteArray? = if (source == SendBalance.IDENTITY) {
            val own = ownIdentityId?.let(::decodeIdentityId)
                ?: return notSent(pair, NotSentReason.GENERIC, "malformed own identity id")
            val managed = readOrNull("managed identities") {
                this.source.managedIdentityIds(walletIdHex).any { it.contentEquals(own) }
            } ?: return notSent(pair, NotSentReason.GENERIC, "managed identities unreadable")
            if (!managed) return notSent(pair, NotSentReason.NOT_SUPPORTED, "own identity not managed by the SDK wallet")
            own
        } else {
            null
        }

        // Unit conversions, before anything is submitted.
        val amountCredits = try {
            dashToCredits(amount)
        } catch (e: ArithmeticException) {
            return notSent(pair, NotSentReason.GENERIC, "amount out of range")
        }

        // (5)+(6) The one write.
        return when (source) {
            SendBalance.CORE -> sendFromCore(pair, walletIdHex, destination, amount, amountCredits, isMax, coreSession)
            SendBalance.SHIELDED -> sendFromShielded(pair, walletIdHex, destination, amount, isMax)
            SendBalance.PLATFORM -> sendFromPlatform(pair, walletIdHex, destination, amount, amountCredits)
            SendBalance.IDENTITY -> sendFromIdentity(
                pair,
                walletIdHex,
                checkNotNull(ownIdentityBytes) { "identity source without its id" },
                destination,
                amount,
                amountCredits
            )
        }
    }

    private suspend fun sendFromCore(
        pair: String,
        walletIdHex: String,
        destination: SendDestination,
        amount: Dash,
        amountCredits: Long,
        isMax: Boolean,
        coreSession: WalletSession?
    ): ExternalSendResult {
        return when (destination) {
            is SendDestination.Core -> sendCoreToCore(pair, walletIdHex, destination, amount, isMax, coreSession)
            is SendDestination.Shielded -> sendCoreToShielded(pair, walletIdHex, destination, amount)
            is SendDestination.Identity -> {
                if (!stillBound(pair, walletIdHex)) {
                    ExternalSendResult.NotSent(NotSentReason.GENERIC)
                } else {
                    mapWrite(pair, this.source.coreTopUpIdentity(destination.identityIdBase58, amount.duffs))
                }
            }
            is SendDestination.Platform -> sendCoreToPlatform(pair, walletIdHex, destination, amount, amountCredits)
        }
    }

    /**
     * `fundFromAssetLockExternal`: lock `amount + margin` ([platformFundingLockDuffs]), pay the
     * payee exactly [amountCredits], the remainder (fee payer) to one of the wallet's own
     * Platform addresses. The SDK's resume contract is honoured in order:
     * 1. the recipients are persisted ([PlatformFundingStore.write]) BEFORE the call — a failed
     *    write sends nothing — and at most one record is pending (a second send is refused);
     * 2. `onAssetLockBuilt` records the lock's outpoint against them, durably, before the lock
     *    is broadcast;
     * 3. the record is cleared on success, and when no lock was built and the call provably
     *    sent nothing (NotSent). Once the lock was built any failure keeps the record (the
     *    [resumePendingPlatformFunding] sweep pays the same payee) and is never NotSent.
     */
    private suspend fun sendCoreToPlatform(
        pair: String,
        walletIdHex: String,
        destination: SendDestination.Platform,
        amount: Dash,
        amountCredits: Long
    ): ExternalSendResult {
        val to = decodePlatformAddress(destination.address, network)
            ?: return notSent(pair, NotSentReason.GENERIC, "invalid platform address")
        if (to.addressType != 0) return notSent(pair, NotSentReason.NOT_SUPPORTED, "P2SH platform address")
        if (amountCredits < PLATFORM_MIN_OUTPUT_CREDITS) {
            return notSent(pair, NotSentReason.GENERIC, "below the platform minimum output")
        }
        val lockDuffs = try {
            platformFundingLockDuffs(amount.duffs)
        } catch (e: ArithmeticException) {
            return notSent(pair, NotSentReason.GENERIC, "amount out of range")
        }
        val pending = try {
            platformFundingStore.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("external send {}: pending platform funding unreadable; not sent", pair, e)
            return ExternalSendResult.NotSent(NotSentReason.GENERIC)
        }
        if (pending != null) {
            return notSent(pair, NotSentReason.PAYMENT_STILL_CONFIRMING, "an earlier Core → Platform send is pending")
        }
        val gateOpen = readOrNull("L1 funding gate") { this.source.coreFundingGateOpen() }
        if (gateOpen != true) return notSent(pair, NotSentReason.NOT_SYNCED, "L1 funding gate closed")
        val remainder = readOrNull("own platform address") {
            this.source.ownPlatformRemainderAddress(walletIdHex, to.hash)
        } ?: return notSent(pair, NotSentReason.GENERIC, "no own platform address for the remainder")
        val record = try {
            PendingPlatformFunding(newFundingId(), walletIdHex, lockDuffs, to, amountCredits, remainder, outPointHex = null)
        } catch (e: IllegalArgumentException) {
            return notSent(pair, NotSentReason.GENERIC, "malformed funding record")
        }
        try {
            platformFundingStore.write(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("external send {}: could not persist the recipients; not sent", pair, e)
            return ExternalSendResult.NotSent(NotSentReason.GENERIC)
        }

        // From here the record exists: every path below either clears it (provably nothing
        // built) or leaves it for the sweep.
        val builtOutPoint = AtomicReference<String?>(null)
        val outcome: SdkWriteResult<Unit> = if (!stillBound(pair, walletIdHex)) {
            SdkWriteResult.NotBroadcast("the bound wallet changed")
        } else {
            try {
                this.source.withFreshCoreFunding {
                    this.source.fundPlatformExternal(record) { outPointHex ->
                        // SDK thread, before broadcast: short, no SDK calls, never throws.
                        builtOutPoint.set(outPointHex)
                        try {
                            platformFundingStore.writeBlocking(record.withOutPoint(outPointHex))
                        } catch (e: Exception) {
                            log.error("external send {}: could not record the lock outpoint (retried after the call)", pair, e)
                        }
                        seedPlatformLockKind(pair, outPointHex)
                    }
                }?.let { SdkWriteResult.Broadcast(Unit) }
                    ?: SdkWriteResult.NotBroadcast(L1_PENDING_PAYMENT_UNPROTECTED_REASON)
            } catch (e: CancellationException) {
                // A built lock keeps its record; make its outpoint durable before unwinding.
                builtOutPoint.get()?.let { outPoint ->
                    withContext(NonCancellable) { ensureOutPointRecorded(pair, record, outPoint) }
                }
                throw e
            } catch (e: Exception) {
                classifyBroadcastFailure(e)
            } catch (e: LinkageError) {
                SdkWriteResult.NotBroadcast("native library unavailable", e)
            }
        }

        val outPointHex = builtOutPoint.get()
        if (outPointHex == null) {
            // onAssetLockBuilt never fired: no lock was built, so nothing was broadcast.
            return when (outcome) {
                is SdkWriteResult.NotBroadcast -> {
                    clearPlatformFunding(pair, record.id)
                    mapWrite(pair, outcome)
                }
                is SdkWriteResult.Broadcast -> {
                    clearPlatformFunding(pair, record.id)
                    log.info("external send {}: sent", pair)
                    ExternalSendResult.Sent(null)
                }
                // Kept: the sweep clears it once no unconsumed funding lock exists.
                is SdkWriteResult.Ambiguous -> mapWrite(pair, outcome)
            }
        }
        if (outcome is SdkWriteResult.Broadcast) {
            clearPlatformFunding(pair, record.id)
            log.info("external send {}: sent", pair)
            return ExternalSendResult.Sent(null)
        }
        // The lock was built (and may be on the network): never NotSent. Make sure the
        // outpoint is durable even if the callback's write failed; the sweep pays the payee.
        ensureOutPointRecorded(pair, record, outPointHex)
        log.warn(
            "external send {}: lock built, funding not confirmed ({}) — may have gone through; resumed automatically",
            pair,
            outcome.javaClass.simpleName,
            (outcome as? SdkWriteResult.NotBroadcast)?.cause ?: (outcome as? SdkWriteResult.Ambiguous)?.cause
        )
        return ExternalSendResult.MayHaveGoneThrough
    }

    /**
     * Seed [AssetLockKind.PLATFORM] for the lock [outPointHex] (`<display txid>:<vout>`;
     * the txid part is already the resolver's display-hex key). Best-effort and never
     * throws: it runs on the SDK's `onAssetLockBuilt` thread, and a miss only delays the
     * "Platform" label until the SDK's durable funding type is readable.
     */
    private fun seedPlatformLockKind(pair: String, outPointHex: String) {
        try {
            outPointHex.substringBefore(':')
                .takeIf { it.length == 64 }
                ?.let { seedAssetLockKind(it, AssetLockKind.PLATFORM) }
        } catch (e: Exception) {
            log.warn("external send {}: lock kind seed failed; the row may momentarily read Internal", pair, e)
        }
    }

    /** Re-record [outPointHex] against [record] unless the store already holds exactly that. */
    private suspend fun ensureOutPointRecorded(pair: String, record: PendingPlatformFunding, outPointHex: String) {
        try {
            val stored = platformFundingStore.read()
            if (stored == null || stored.id != record.id || stored.outPointHex != outPointHex) {
                platformFundingStore.write(record.withOutPoint(outPointHex))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("external send {}: could not record the lock outpoint; the lock cannot be resumed automatically", pair, e)
        }
    }

    private suspend fun clearPlatformFunding(pair: String, id: String) {
        try {
            platformFundingStore.clear(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Left behind: the sweep clears it (no lock, or a consumed one).
            log.warn("external send {}: could not clear the pending platform funding", pair, e)
        }
    }

    // ── Core → Platform resume ────────────────────────────────────────

    override suspend fun resumePendingPlatformFunding(): Int = try {
        withContext(ioDispatcher) { resumePendingPlatformFundingInner() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("pending platform funding sweep failed; retried on the next trigger", e)
        0
    }

    /**
     * The sweep. It owns the record only while it holds [inFlight] (a running [send] may have
     * just written a record whose lock is not built yet), and acts only for the bound wallet.
     * - No outpoint: clear when the SDK has no unconsumed Platform-address funding lock of the
     *   wallet (the build never happened, or its lock is done); otherwise keep it — that lock
     *   cannot be attributed by amount or timing.
     * - Outpoint, lock Consumed: done, clear.
     * - Outpoint, lock not tracked: keep (nothing to resume, nothing to prove it never went out).
     * - Outpoint, lock not Consumed: resume with exactly the persisted recipients, at most
     *   [MAX_PLATFORM_RESUME_ATTEMPTS_PER_PROCESS] times per process; clear on success or when
     *   Platform answers already consumed.
     */
    private suspend fun resumePendingPlatformFundingInner(): Int {
        val first = try {
            platformFundingStore.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("pending platform funding unreadable or malformed; kept, not resumed", e)
            return 0
        } ?: return 0
        if (!inFlight.compareAndSet(false, true)) {
            log.info("pending platform funding {}: an external send is running; sweep skipped", first.id)
            return 0
        }
        try {
            // Re-read as the owner: the send that held [inFlight] may have changed it.
            val record = platformFundingStore.read() ?: return 0
            val committed = readOrNull("cutover state") { this.source.cutoverCommittedOrThrow() }
            if (committed != true) return 0
            val bound = readOrNull("bound wallet") { this.source.boundWalletIdOrNull() }
            if (bound == null || !bound.equals(record.walletIdHex, ignoreCase = true)) {
                log.warn("pending platform funding {}: not of the bound wallet; kept", record.id)
                return 0
            }
            val outPointHex = record.outPointHex
            if (outPointHex == null) {
                val unconsumed = readOrNull("address funding locks") {
                    this.source.hasUnconsumedAddressFundingLocks(record.walletIdHex)
                } ?: return 0
                if (unconsumed) {
                    log.warn("pending platform funding {}: no outpoint recorded and a funding lock is open; kept", record.id)
                } else {
                    platformFundingStore.clear(record.id)
                    log.info("pending platform funding {}: no lock was left open; cleared", record.id)
                }
                return 0
            }
            val status = try {
                this.source.trackedAssetLockStatus(record.walletIdHex, outPointHex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("pending platform funding {}: lock status unreadable; kept", record.id, e)
                return 0
            }
            if (status == null) {
                log.warn("pending platform funding {}: the SDK does not track its lock; kept", record.id)
                return 0
            }
            if (status == ASSET_LOCK_STATUS_CONSUMED) {
                platformFundingStore.clear(record.id)
                log.info("pending platform funding {}: lock consumed; cleared", record.id)
                return 0
            }
            val attempts = platformResumeAttempts.getOrDefault(record.id, 0)
            if (attempts >= MAX_PLATFORM_RESUME_ATTEMPTS_PER_PROCESS) {
                log.warn("pending platform funding {}: {} attempts this process; retried on the next start", record.id, attempts)
                return 0
            }
            platformResumeAttempts[record.id] = attempts + 1
            if (!stillBound("Core → Platform resume", record.walletIdHex)) return 0
            try {
                this.source.resumePlatformFundingExternal(record)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAssetLockAlreadyConsumed(e)) {
                    platformFundingStore.clear(record.id)
                    log.warn("pending platform funding {}: Platform reports the lock already consumed; cleared", record.id)
                } else {
                    log.warn("pending platform funding {}: resume failed (attempt {}); kept", record.id, attempts + 1, e)
                }
                return 0
            } catch (e: LinkageError) {
                log.warn("pending platform funding {}: native library unavailable; kept", record.id, e)
                return 0
            }
            platformFundingStore.clear(record.id)
            log.info("pending platform funding {}: resumed and consumed", record.id)
            return 1
        } finally {
            inFlight.set(false)
        }
    }

    /**
     * A Max is the SDK drain (`emptyWallet`), whose amount is display-only: it spends
     * whatever is spendable when it runs. So the drain is only started while the SDK's
     * max-sendable figure is still exactly the [amount] the user confirmed; anything else
     * (funds arrived or left in between, or unreadable) is NotSent with nothing spent.
     */
    private suspend fun sendCoreToCore(
        pair: String,
        walletIdHex: String,
        destination: SendDestination.Core,
        amount: Dash,
        isMax: Boolean,
        coreSession: WalletSession?
    ): ExternalSendResult {
        if (isMax) {
            val maxNow = try {
                this.source.observeCoreMaxSendableDuffs().first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("external send {}: max sendable unreadable before the drain; not sent", pair, e)
                return ExternalSendResult.NotSent(NotSentReason.GENERIC)
            }
            if (maxNow != amount.duffs) {
                return notSent(pair, NotSentReason.BALANCE_CHANGED, "the balance changed since the Max was confirmed")
            }
        }
        if (!stillBound(pair, walletIdHex)) return ExternalSendResult.NotSent(NotSentReason.GENERIC)
        return try {
            val txId = this.source.sendCore(destination.address, amount, emptyWallet = isMax, session = coreSession)
            log.info("external send {}: sent", pair)
            ExternalSendResult.Sent(txId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            classifyCoreSendThrowable(e).also { log.warn("external send {}: {}", pair, it, e) }
        }
    }

    /**
     * The recipient receives the confirmed [amount]: the lock is `amount + pool fee`
     * ([shieldPoolFeeDuffs]), the fee Rust carves out of the lock. A Max already left the L1
     * fee reserve out of [amount] ([coreToShieldedMaxDuffs]), so there is no Max retry.
     */
    private suspend fun sendCoreToShielded(
        pair: String,
        walletIdHex: String,
        destination: SendDestination.Shielded,
        amount: Dash
    ): ExternalSendResult {
        val feeCredits = readOrNull("shield pool fee") { this.source.shieldedFeeCredits(ShieldedFeeKind.TRANSFER) }
            ?: return notSent(pair, NotSentReason.GENERIC, "shield pool fee unknown")
        val lock = try {
            Dash(Math.addExact(amount.duffs, shieldPoolFeeDuffs(feeCredits)))
        } catch (e: ArithmeticException) {
            return notSent(pair, NotSentReason.GENERIC, "amount out of range")
        }
        return when (val result = shieldFromCoreChecked(pair, walletIdHex, destination.address, lock)) {
            is SdkWriteResult.Broadcast -> when (result.value) {
                ShieldFromWalletOutcome.COMPLETED -> ExternalSendResult.Sent(null)
                ShieldFromWalletOutcome.SHIELD_PENDING_RETRY -> {
                    log.warn("external send {}: lock out, shield not submitted — returns to own shielded", pair)
                    ExternalSendResult.InterruptedFundsReturnToShielded
                }
            }
            else -> mapWrite(pair, result)
        }
    }

    private suspend fun shieldFromCoreChecked(
        pair: String,
        walletIdHex: String,
        address: String,
        amount: Dash
    ): SdkWriteResult<ShieldFromWalletOutcome> =
        if (!stillBound(pair, walletIdHex)) {
            SdkWriteResult.NotBroadcast("the bound wallet changed")
        } else {
            this.source.shieldFromCore(walletIdHex, address, amount)
        }

    private suspend fun sendFromShielded(
        pair: String,
        walletIdHex: String,
        destination: SendDestination,
        amount: Dash,
        isMax: Boolean
    ): ExternalSendResult {
        val identityId = (destination as? SendDestination.Identity)?.let { decodeIdentityId(it.identityIdBase58) }
        suspend fun attempt(value: Dash): SdkWriteResult<Unit> {
            if (!stillBound(pair, walletIdHex)) return SdkWriteResult.NotBroadcast("the bound wallet changed")
            return when (destination) {
                is SendDestination.Core -> this.source.shieldedWithdrawToCore(walletIdHex, destination.address, value)
                is SendDestination.Shielded -> this.source.shieldedTransfer(walletIdHex, destination.address, value)
                is SendDestination.Platform -> this.source.shieldedUnshield(walletIdHex, destination.address, value)
                is SendDestination.Identity -> this.source.shieldedTopUpIdentity(
                    walletIdHex,
                    checkNotNull(identityId) { "identity destination validated above" },
                    value
                )
            }
        }
        var result = attempt(amount)
        val first = result
        if (isMax && first is SdkWriteResult.NotBroadcast) {
            val adjustment = shieldedMaxFeeAdjustment(amount, first)
            if (adjustment != null) {
                log.info("external send {}: max spend short by the pool fee; retrying once", pair)
                result = attempt(adjustment.adjustedAmount)
            }
        }
        return mapWrite(pair, result)
    }

    private suspend fun sendFromPlatform(
        pair: String,
        walletIdHex: String,
        destination: SendDestination,
        amount: Dash,
        amountCredits: Long
    ): ExternalSendResult {
        return when (destination) {
            is SendDestination.Core -> sendPlatformToCore(pair, walletIdHex, destination, amountCredits)
            is SendDestination.Shielded -> shieldedWrite(pair, walletIdHex) {
                this.source.platformShieldTo(walletIdHex, destination.address, amount)
            }
            is SendDestination.Platform -> {
                val to = decodePlatformAddress(destination.address, network)
                    ?: return notSent(pair, NotSentReason.GENERIC, "invalid platform address")
                nativeWrite(pair, walletIdHex) { this.source.platformTransfer(walletIdHex, to, amountCredits) }
            }
            is SendDestination.Identity -> {
                val identityId = decodeIdentityId(destination.identityIdBase58)
                    ?: return notSent(pair, NotSentReason.GENERIC, "invalid identity id")
                val candidates = readOrNull("platform address balances") {
                    this.source.platformAddressBalances(walletIdHex)
                } ?: return notSent(pair, NotSentReason.GENERIC, "platform address balances unreadable")
                val inputs = selectPlatformInputs(candidates, amountCredits)
                    ?: return notSent(pair, NotSentReason.INSUFFICIENT_FUNDS, "platform addresses cannot cover the amount")
                nativeWrite(pair, walletIdHex) { this.source.platformTopUpIdentity(walletIdHex, identityId, inputs) }
            }
        }
    }

    /**
     * Partial withdrawal: the inputs take exactly [amountCredits] ([selectPlatformWithdrawalInputs]
     * over the cached address balances), the fee payer keeping the SDK preflight's estimated
     * transition fee. [amountCredits] is the GROSS withdrawal: the Core fee of the asset unlock
     * comes out of it, as for Identity → Core. Every refusal here happens before anything is
     * signed (NotSent); the SDK re-checks each input against its on-chain balance before
     * signing.
     */
    private suspend fun sendPlatformToCore(
        pair: String,
        walletIdHex: String,
        destination: SendDestination.Core,
        amountCredits: Long
    ): ExternalSendResult {
        if (amountCredits < PLATFORM_MIN_WITHDRAWAL_CREDITS) {
            return notSent(pair, NotSentReason.GENERIC, "below the platform withdrawal minimum")
        }
        if (amountCredits > PLATFORM_MAX_WITHDRAWAL_CREDITS) {
            return notSent(pair, NotSentReason.GENERIC, "above the platform withdrawal maximum")
        }
        val candidates = readOrNull("platform address balances") { this.source.platformAddressBalances(walletIdHex) }
            ?: return notSent(pair, NotSentReason.GENERIC, "platform address balances unreadable")
        val minInput = readOrNull("platform minimum input") { this.source.platformMinInputCredits(walletIdHex) }
            ?.takeIf { it > 0 }
            ?: return notSent(pair, NotSentReason.GENERIC, "platform minimum input unknown")
        val preflight = readOrNull("withdrawal preflight") { this.source.platformWithdrawalPreflight(walletIdHex) }
            ?: return notSent(pair, NotSentReason.GENERIC, "withdrawal preflight unreadable")
        if (!preflight.canWithdraw) {
            return notSent(pair, NotSentReason.INSUFFICIENT_FUNDS, "the platform addresses cannot fund a withdrawal")
        }
        // A zero estimate would let the fee payer be drained: unknown, not free.
        val feeReserve = preflight.estimatedFeeCredits.takeIf { it > 0 }
            ?: return notSent(pair, NotSentReason.GENERIC, "withdrawal fee estimate unknown")
        val inputs = selectPlatformWithdrawalInputs(candidates, amountCredits, feeReserve, minInput)
            ?: return notSent(pair, NotSentReason.INSUFFICIENT_FUNDS, "platform addresses cannot cover the amount and fee")
        return nativeWrite(pair, walletIdHex) { this.source.platformWithdraw(walletIdHex, destination.address, inputs) }
    }

    private suspend fun sendFromIdentity(
        pair: String,
        walletIdHex: String,
        fromId: ByteArray,
        destination: SendDestination,
        amount: Dash,
        amountCredits: Long
    ): ExternalSendResult {
        return when (destination) {
            is SendDestination.Core ->
                nativeWrite(pair, walletIdHex) {
                    this.source.identityWithdraw(walletIdHex, fromId, amountCredits, destination.address)
                }
            is SendDestination.Platform -> {
                val to = decodePlatformAddress(destination.address, network)
                    ?: return notSent(pair, NotSentReason.GENERIC, "invalid platform address")
                nativeWrite(pair, walletIdHex) {
                    this.source.identityTransferToAddress(walletIdHex, fromId, to, amountCredits)
                }
            }
            is SendDestination.Identity -> {
                val toId = decodeIdentityId(destination.identityIdBase58)
                    ?: return notSent(pair, NotSentReason.GENERIC, "invalid identity id")
                nativeWrite(pair, walletIdHex) { this.source.identityTransfer(walletIdHex, fromId, toId, amountCredits) }
            }
            is SendDestination.Shielded -> shieldedWrite(pair, walletIdHex) {
                this.source.identityShieldTo(walletIdHex, fromId, destination.address, amount)
            }
        }
    }

    /**
     * One [ShieldedBalanceService] write (it pins [walletIdHex] itself and classifies its own
     * outcome), after the bound-wallet re-check; mapped by [mapWrite].
     */
    private suspend fun shieldedWrite(
        pair: String,
        walletIdHex: String,
        call: suspend () -> SdkWriteResult<Unit>
    ): ExternalSendResult {
        if (!stillBound(pair, walletIdHex)) return ExternalSendResult.NotSent(NotSentReason.GENERIC)
        val result = call()
        if (result is SdkWriteResult.Broadcast) log.info("external send {}: sent", pair)
        return mapWrite(pair, result)
    }

    /** One raw SDK credits call, after the wallet re-check, classified by [classifyBroadcastFailure]. */
    private suspend fun nativeWrite(pair: String, walletIdHex: String, call: suspend () -> Unit): ExternalSendResult {
        if (!stillBound(pair, walletIdHex)) return ExternalSendResult.NotSent(NotSentReason.GENERIC)
        val result: SdkWriteResult<Unit> = try {
            call()
            SdkWriteResult.Broadcast(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            classifyBroadcastFailure(e)
        } catch (e: LinkageError) {
            // A native-boundary failure before the call ran (e.g. UnsatisfiedLinkError).
            SdkWriteResult.NotBroadcast("native library unavailable", e)
        }
        if (result is SdkWriteResult.Broadcast) log.info("external send {}: sent", pair)
        return mapWrite(pair, result)
    }

    private fun mapWrite(pair: String, result: SdkWriteResult<*>): ExternalSendResult = when (result) {
        is SdkWriteResult.Broadcast -> ExternalSendResult.Sent(null)
        is SdkWriteResult.NotBroadcast -> {
            val reason = notSentReasonFor(result)
            log.warn("external send {}: not sent ({})", pair, reason, result.cause)
            ExternalSendResult.NotSent(reason)
        }
        is SdkWriteResult.Ambiguous -> {
            log.error("external send {}: outcome unconfirmed — may have gone through; do NOT retry", pair, result.cause)
            ExternalSendResult.MayHaveGoneThrough
        }
    }

    /** Re-read the bound SDK wallet right before a write: it must still be [walletIdHex]. */
    private suspend fun stillBound(pair: String, walletIdHex: String): Boolean {
        val now = readOrNull("bound wallet") { this.source.boundWalletIdOrNull() }
        val same = now != null && now.equals(walletIdHex, ignoreCase = true)
        if (!same) log.warn("external send {}: the bound SDK wallet changed before submission; not sent", pair)
        return same
    }

    private fun destinationValid(destination: SendDestination): Boolean = when (val parsed =
        detectSendDestination(destination.display, network)
    ) {
        is DestinationParse.Valid -> parsed.destination::class == destination::class
        else -> false
    }

    private suspend fun <T : Any> readOrNull(what: String, read: suspend () -> T?): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("external send: {} unreadable", what, e)
        null
    }

    private fun notSent(pair: String, reason: NotSentReason, why: String): ExternalSendResult.NotSent {
        log.info("external send {}: not sent ({}: {})", pair, reason, why)
        return ExternalSendResult.NotSent(reason)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExternalSendServiceImpl::class.java)

        /** How often the Platform / identity balances (no live feed) are re-read while observed. */
        internal const val BALANCE_POLL_MS = 15_000L

        /** Unattended Core → Platform resume attempts per funding, per process. */
        internal const val MAX_PLATFORM_RESUME_ATTEMPTS_PER_PROCESS = 3

        private fun kindOf(destination: SendDestination): String = when (destination) {
            is SendDestination.Core -> "Core"
            is SendDestination.Shielded -> "Shielded"
            is SendDestination.Platform -> "Platform"
            is SendDestination.Identity -> "Identity"
        }

        private fun shieldedKindFor(destinationKind: SendBalance): ShieldedFeeKind = when (destinationKind) {
            SendBalance.CORE -> ShieldedFeeKind.WITHDRAWAL
            SendBalance.SHIELDED -> ShieldedFeeKind.TRANSFER
            SendBalance.PLATFORM -> ShieldedFeeKind.UNSHIELD
            SendBalance.IDENTITY -> ShieldedFeeKind.IDENTITY_TOP_UP
        }
    }
}
