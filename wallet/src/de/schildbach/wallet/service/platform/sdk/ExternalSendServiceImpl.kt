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
import de.schildbach.wallet.payments.isCompleteTxFailure
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.shielded.assetLockMaxFeeReserve
import de.schildbach.wallet.ui.shielded.isAssetLockSelectionShort
import de.schildbach.wallet.ui.shielded.shieldedMaxFeeAdjustment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.services.InsufficientFundsException
import org.dash.wallet.common.services.PaymentStillConfirmingException
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.WalletSession
import org.dash.wallet.common.util.readRetryDelayMs
import org.dashfoundation.dashsdk.credits.FundingInput
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
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
        any(L1_PENDING_PAYMENT_UNPROTECTED_REASON) -> NotSentReason.PAYMENT_STILL_CONFIRMING
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
 * Keep a live observation alive across read failures: on a (non-cancellation) [Exception]
 * emit [unknown] and re-subscribe after [retryDelayMs] (attempt counted from 0) — never
 * complete on a transient error. A [Throwable] that is not an [Exception] is rethrown.
 */
internal fun <T> Flow<T>.retryReadErrors(
    what: String,
    unknown: T,
    retryDelayMs: (Long) -> Long = ::readRetryDelayMs
): Flow<T> = retryWhen { cause, attempt ->
    if (cause is CancellationException || cause !is Exception) {
        false
    } else {
        val delayMs = retryDelayMs(attempt)
        retryLog.warn("external send: {} unreadable; unknown, retrying in {} ms", what, delayMs, cause)
        emit(unknown)
        delay(delayMs)
        true
    }
}

private val retryLog = LoggerFactory.getLogger("de.schildbach.wallet.service.platform.sdk.ExternalSendRetry")

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
    private val walletData: WalletData
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
            .retryReadErrors("identity record", Result.failure(IllegalStateException("identity record unreadable")))

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

    override fun observeShieldedBalance(): Flow<Dash?> =
        flow { emitAll(dashPayConfig.observePreservingErrors(DashPayConfig.USE_KOTLIN_SDK_SHIELDED)) }
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
            .retryReadErrors("shielded balance", null)

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
 *    unclassified → [ExternalSendResult.MayHaveGoneThrough] (terminal).
 */
@Singleton
class ExternalSendServiceImpl internal constructor(
    private val source: ExternalSendSource,
    private val network: AddressNetwork,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val balancePollMs: Long = BALANCE_POLL_MS,
    private val retryDelayMs: (Long) -> Long = ::readRetryDelayMs
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
        walletData: WalletData
    ) : this(
        source = DashSdkExternalSendSource(
            sdkService, sendPaymentService, sdkL1SendService, cutoverUiDataService, shieldedBalanceService,
            transparentTopUp, usernameQueries, dashPayConfig, identityConfig, walletData
        ),
        network = AddressNetwork.fromId(Constants.NETWORK_PARAMETERS.id)
    )

    private val inFlight = AtomicBoolean(false)

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
        SendBalance.IDENTITY -> this.source.observeOwnIdentityId().flatMapLatest { own ->
            val id = own.getOrNull()
            if (own.isFailure || id == null) {
                flowOf(null)
            } else {
                polled { _ -> this.source.identityBalanceCredits(id)?.takeIf { it >= 0 }?.let(::creditsToDash) }
            }
        }
    }.retryReadErrors("$source balance", null, retryDelayMs).distinctUntilChanged()

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
            .retryReadErrors("identity record", null, retryDelayMs)
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

    override suspend fun estimateFee(source: SendBalance, destination: SendDestination, amount: Dash): FeeEstimate? =
        try {
            withContext(ioDispatcher) { estimateFeeInner(source, destination) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("external send: fee estimate failed; unknown", e)
            null
        }

    /**
     * - Core → Core: the SDK send's fee reserve ([SEND_ALL_FEE_RESERVE_DUFFS]), an upper
     *   bound the real (smaller) fee never exceeds — the same figure the Basic send shows.
     *   A Max (drain) takes it OUT of the amount; any other send pays it on top.
     * - Core → Shielded: the shield pool fee ([shieldPoolFeeDuffs]), paid ON TOP of the
     *   amount ([send] locks `amount + pool fee`, the recipient receives `amount`); the L1
     *   fee of the lock is extra and unknown, so not exact.
     * - Shielded → *: the flat single-note pool fee of the transition; more notes cost more.
     * - Core → Identity, Platform → *, Identity → *: Rust meters these at execution; no
     *   pre-send figure exists, so null.
     */
    private suspend fun estimateFeeInner(source: SendBalance, destination: SendDestination): FeeEstimate? =
        when (source) {
            SendBalance.CORE -> when (destination) {
                is SendDestination.Core -> FeeEstimate(Dash(SEND_ALL_FEE_RESERVE_DUFFS), exact = false)
                is SendDestination.Shielded -> this.source.shieldedFeeCredits(ShieldedFeeKind.TRANSFER)?.let {
                    FeeEstimate(Dash(shieldPoolFeeDuffs(it)), exact = false)
                }
                else -> null
            }
            SendBalance.SHIELDED -> this.source.shieldedFeeCredits(shieldedKindFor(destination))?.let {
                FeeEstimate(Dash(ceilCreditsToDuffs(it)), exact = false)
            }
            SendBalance.PLATFORM, SendBalance.IDENTITY -> null
        }

    /**
     * - Core → Core: the SDK's max-sendable L1 figure, GROSS of the drain fee (the drain
     *   delivers it minus the fee — the Basic send's `sendAllBalance` semantics).
     * - Core → Shielded: that figure minus the shield pool fee (what the recipient receives
     *   when the whole balance is locked); a Max send retries once with an L1 reserve if
     *   the lock selection comes up short.
     * - Shielded → *: the READY balance minus the single-note pool fee; a Max send also
     *   retries once with the exact deficit the note selection reports.
     * - Everything else: null (no pre-send fee figure).
     */
    override suspend fun maxSendable(source: SendBalance, destination: SendDestination): Dash? = try {
        when (source) {
            SendBalance.CORE -> when (destination) {
                is SendDestination.Core ->
                    this.source.observeCoreMaxSendableDuffs().first()?.takeIf { it > 0 }?.let(::Dash)
                is SendDestination.Shielded -> {
                    val max = this.source.observeCoreMaxSendableDuffs().first()
                    val feeCredits = withContext(ioDispatcher) {
                        this@ExternalSendServiceImpl.source.shieldedFeeCredits(ShieldedFeeKind.TRANSFER)
                    }
                    if (max == null || feeCredits == null) {
                        null
                    } else {
                        (max - shieldPoolFeeDuffs(feeCredits)).takeIf { it > 0 }?.let(::Dash)
                    }
                }
                else -> null
            }
            SendBalance.SHIELDED -> {
                val balance = this.source.observeShieldedBalance().first()
                val feeCredits = withContext(ioDispatcher) {
                    this@ExternalSendServiceImpl.source.shieldedFeeCredits(shieldedKindFor(destination))
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
            SendBalance.CORE -> sendFromCore(pair, walletIdHex, destination, amount, isMax, coreSession)
            SendBalance.SHIELDED -> sendFromShielded(pair, walletIdHex, destination, amount, isMax)
            SendBalance.PLATFORM -> sendFromPlatform(pair, walletIdHex, destination, amountCredits)
            SendBalance.IDENTITY -> sendFromIdentity(
                pair,
                walletIdHex,
                checkNotNull(ownIdentityBytes) { "identity source without its id" },
                destination,
                amountCredits
            )
        }
    }

    private suspend fun sendFromCore(
        pair: String,
        walletIdHex: String,
        destination: SendDestination,
        amount: Dash,
        isMax: Boolean,
        coreSession: WalletSession?
    ): ExternalSendResult {
        return when (destination) {
            is SendDestination.Core -> sendCoreToCore(pair, walletIdHex, destination, amount, isMax, coreSession)
            is SendDestination.Shielded -> sendCoreToShielded(pair, walletIdHex, destination, amount, isMax)
            is SendDestination.Identity -> {
                if (!stillBound(pair, walletIdHex)) {
                    ExternalSendResult.NotSent(NotSentReason.GENERIC)
                } else {
                    mapWrite(pair, this.source.coreTopUpIdentity(destination.identityIdBase58, amount.duffs))
                }
            }
            is SendDestination.Platform -> notSent(pair, NotSentReason.NOT_SUPPORTED, "unreachable: gated")
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
     * ([shieldPoolFeeDuffs]), the fee Rust carves out of the lock. A Max whose lock
     * selection comes up short (the L1 fee) retries once with an L1 reserve taken off the
     * lock, so the recipient then receives that much less (never more than confirmed).
     */
    private suspend fun sendCoreToShielded(
        pair: String,
        walletIdHex: String,
        destination: SendDestination.Shielded,
        amount: Dash,
        isMax: Boolean
    ): ExternalSendResult {
        val feeCredits = readOrNull("shield pool fee") { this.source.shieldedFeeCredits(ShieldedFeeKind.TRANSFER) }
            ?: return notSent(pair, NotSentReason.GENERIC, "shield pool fee unknown")
        val lock = try {
            Dash(Math.addExact(amount.duffs, shieldPoolFeeDuffs(feeCredits)))
        } catch (e: ArithmeticException) {
            return notSent(pair, NotSentReason.GENERIC, "amount out of range")
        }
        var result = shieldFromCoreChecked(pair, walletIdHex, destination.address, lock)
        val first = result
        if (isMax && first is SdkWriteResult.NotBroadcast && isAssetLockSelectionShort(first)) {
            val reserve = this.source.spendableUtxoCount()?.let(::assetLockMaxFeeReserve)
            val adjusted = reserve?.let { lock - it }
            if (adjusted != null && adjusted.isPositive) {
                log.info("external send {}: max shield short; retrying once with an L1 fee reserve", pair)
                result = shieldFromCoreChecked(pair, walletIdHex, destination.address, adjusted)
            }
        }
        return when (result) {
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
        return when (result) {
            is SdkWriteResult.Broadcast -> ExternalSendResult.Sent(null)
            else -> mapWrite(pair, result)
        }
    }

    private suspend fun sendFromPlatform(
        pair: String,
        walletIdHex: String,
        destination: SendDestination,
        amountCredits: Long
    ): ExternalSendResult {
        return when (destination) {
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
            else -> notSent(pair, NotSentReason.NOT_SUPPORTED, "unreachable: gated")
        }
    }

    private suspend fun sendFromIdentity(
        pair: String,
        walletIdHex: String,
        fromId: ByteArray,
        destination: SendDestination,
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
            is SendDestination.Shielded -> notSent(pair, NotSentReason.NOT_SUPPORTED, "unreachable: gated")
        }
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
        return if (result is SdkWriteResult.Broadcast) {
            log.info("external send {}: sent", pair)
            ExternalSendResult.Sent(null)
        } else {
            mapWrite(pair, result)
        }
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

        private fun kindOf(destination: SendDestination): String = when (destination) {
            is SendDestination.Core -> "Core"
            is SendDestination.Shielded -> "Shielded"
            is SendDestination.Platform -> "Platform"
            is SendDestination.Identity -> "Identity"
        }

        private fun shieldedKindFor(destination: SendDestination): ShieldedFeeKind = when (destination) {
            is SendDestination.Core -> ShieldedFeeKind.WITHDRAWAL
            is SendDestination.Shielded -> ShieldedFeeKind.TRANSFER
            is SendDestination.Platform -> ShieldedFeeKind.UNSHIELD
            is SendDestination.Identity -> ShieldedFeeKind.IDENTITY_TOP_UP
        }
    }
}
