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

import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.shielded.shieldedMaxFeeAdjustment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.util.readRetryDelayMs
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam over everything [InternalTransferServiceImpl] touches besides [ExternalSendService], so
 * the gating / own-destination / wallet-pinning orchestration is host-JVM unit-testable.
 */
internal interface InternalTransferSource {
    /** [SdkL1SendService.cutoverCommittedOrThrow]: throws when unreadable. */
    suspend fun cutoverCommittedOrThrow(): Boolean

    /** `USE_KOTLIN_SDK_SHIELDED`, error-preserving: throws when unreadable. */
    suspend fun shieldedEnabledOrThrow(): Boolean

    /** `USE_KOTLIN_SDK_SHIELDED` as it changes; a read error is thrown into the flow. */
    fun observeShieldedFlag(): Flow<Boolean?>

    /** The SDK wallet bound now (null when none, or not exactly one, is loaded). */
    suspend fun boundWalletIdOrNull(): String?

    /** The user's registered identity (base58), null when there is none; throws when unreadable. */
    suspend fun ownIdentityIdOrThrow(): String?

    /**
     * The wallet's own UNADVERTISED Core address (`WalletData.unadvertisedDestinationLive`, the
     * SDK engine's internal chain post-cutover), base58. Throws when none can be obtained; never
     * the advertised receive address. Blocking: call off the main thread.
     */
    fun unadvertisedCoreAddress(): String

    /** [ShieldedBalanceService.platformReceiveAddress]: the wallet's own Platform address, or null. */
    suspend fun ownPlatformAddress(): String?

    suspend fun shieldedFeeCredits(kind: ShieldedFeeKind): Long?

    suspend fun shieldFromWallet(amount: Dash, walletIdHex: String): SdkWriteResult<ShieldFromWalletOutcome>
    suspend fun shieldFromCredits(amount: Dash, walletIdHex: String): SdkWriteResult<Unit>
    suspend fun unshieldToCredits(amount: Dash, walletIdHex: String): SdkWriteResult<Unit>
}

/** Production [InternalTransferSource]. */
internal class DashSdkInternalTransferSource(
    private val sdkService: DashSdkService,
    private val sdkL1SendService: SdkL1SendService,
    private val shieldedBalanceService: ShieldedBalanceService,
    private val dashPayConfig: DashPayConfig,
    private val identityConfig: BlockchainIdentityConfig,
    private val walletData: WalletData
) : InternalTransferSource {

    override suspend fun cutoverCommittedOrThrow(): Boolean = sdkL1SendService.cutoverCommittedOrThrow()

    override suspend fun shieldedEnabledOrThrow(): Boolean =
        dashPayConfig.getPreservingErrors(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true

    override fun observeShieldedFlag(): Flow<Boolean?> =
        flow { emitAll(dashPayConfig.observePreservingErrors(DashPayConfig.USE_KOTLIN_SDK_SHIELDED)) }

    override suspend fun boundWalletIdOrNull(): String? {
        sdkService.ensureStarted()
        val manager = checkNotNull(sdkService.walletManagerOrNull()) { "SDK wallet manager missing after ensureStarted()" }
        return manager.wallets.value.keys.singleOrNull()
    }

    override suspend fun ownIdentityIdOrThrow(): String? = ownRegisteredIdentity(
        identityConfig.getPreservingErrors(BlockchainIdentityConfig.IDENTITY_ID),
        identityConfig.getPreservingErrors(BlockchainIdentityConfig.CREATION_STATE)
    )

    override fun unadvertisedCoreAddress(): String = walletData.unadvertisedDestinationLive().toBase58()

    override suspend fun ownPlatformAddress(): String? = shieldedBalanceService.platformReceiveAddress()

    override suspend fun shieldedFeeCredits(kind: ShieldedFeeKind): Long? =
        shieldedBalanceService.estimateShieldedFeeCredits(kind)

    override suspend fun shieldFromWallet(amount: Dash, walletIdHex: String) =
        shieldedBalanceService.shieldFromWalletPinned(amount, walletIdHex)

    override suspend fun shieldFromCredits(amount: Dash, walletIdHex: String) =
        shieldedBalanceService.shieldFromCreditsPinned(amount, walletIdHex)

    override suspend fun unshieldToCredits(amount: Dash, walletIdHex: String) =
        shieldedBalanceService.unshieldToCreditsPinned(amount, walletIdHex)
}

/**
 * Default [InternalTransferService]. Orchestration only: every money move is either an
 * [ExternalSendService.send] to the resolved OWN destination (that route's gating, pinning and
 * classification apply unchanged) or one of [ShieldedBalanceService]'s pinned self routes.
 *
 * Routes (the 9 supported pairs of [internalTransferSupport]):
 * - Core → Shielded: [ShieldedBalanceService.shieldFromWalletPinned] of `amount + pool fee`
 *   ([shieldPoolFeeDuffs]), so the own shielded balance receives the confirmed amount (the
 *   External Send's Core → Shielded convention). A lock that went out with the shield still
 *   pending is [ExternalSendResult.CompletesAutomatically].
 * - Core → Identity, Shielded → Identity, Platform → Identity: [ExternalSendService.send] to
 *   the user's own identity id.
 * - Shielded → Core, Identity → Core: [ExternalSendService.send] to the UNADVERTISED Core
 *   address ([InternalTransferSource.unadvertisedCoreAddress]); unavailable = Not sent.
 * - Shielded → Platform: [ShieldedBalanceService.unshieldToCreditsPinned] (own Platform address),
 *   with the shielded Max fee retry.
 * - Platform → Shielded: [ShieldedBalanceService.shieldFromCreditsPinned].
 * - Identity → Platform: [ExternalSendService.send] to the wallet's own Platform address
 *   ([ShieldedBalanceService.platformReceiveAddress]); unavailable = Not sent.
 *
 * Funds safety, in order, for every [transfer]: single-flight; positive amount; the pair matrix;
 * cutover committed and (for a shielded leg) the shielded flag, error-preserving; the wallet
 * captured at confirmation is the bound one; the own destination is resolved (fail closed);
 * the wallet is re-checked right before the write, which is pinned to it. Broadcast → Sent,
 * NotBroadcast → Not sent, Ambiguous or anything unclassified → may have gone through.
 */
@Singleton
class InternalTransferServiceImpl internal constructor(
    private val externalSendService: ExternalSendService,
    private val source: InternalTransferSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelayMs: (Long) -> Long = ::readRetryDelayMs
) : InternalTransferService {

    @Inject
    constructor(
        externalSendService: ExternalSendService,
        sdkService: DashSdkService,
        sdkL1SendService: SdkL1SendService,
        shieldedBalanceService: ShieldedBalanceService,
        dashPayConfig: DashPayConfig,
        identityConfig: BlockchainIdentityConfig,
        walletData: WalletData
    ) : this(
        externalSendService,
        DashSdkInternalTransferSource(
            sdkService, sdkL1SendService, shieldedBalanceService, dashPayConfig, identityConfig, walletData
        )
    )

    private val inFlight = AtomicBoolean(false)

    // ── Reads ─────────────────────────────────────────────────────────

    override fun observeBalance(balance: SendBalance): Flow<Dash?> = externalSendService.observeBalance(balance)

    override fun observeHasIdentity(): Flow<Boolean?> = externalSendService.observeHasIdentity()

    override fun observeShieldedEnabled(): Flow<Boolean> =
        source.observeShieldedFlag()
            .map<Boolean?, Boolean> { it == true }
            .retryReadErrors("shielded flag", false, retryDelayMs)
            .distinctUntilChanged()

    override fun currentWalletIdOrNull(): String? = externalSendService.currentWalletIdOrNull()

    /**
     * Every supported pair's figure depends only on the pair, so the External Send's per-pair
     * estimate applies ([ExternalSendService.estimateFeeForPair]): Core → Shielded the shield
     * pool fee (on top), Shielded → * the flat single-note pool fee; Core → Identity,
     * Platform → * and Identity → * are metered by Rust at execution, so null.
     */
    override suspend fun estimateFee(from: SendBalance, to: SendBalance, amount: Dash): FeeEstimate? =
        if (internalTransferPairSupported(from, to)) externalSendService.estimateFeeForPair(from, to, amount) else null

    /** [ExternalSendService.maxSendableForPair] for a supported pair (null for metered pairs). */
    override suspend fun maxSendable(from: SendBalance, to: SendBalance): Dash? =
        if (internalTransferPairSupported(from, to)) externalSendService.maxSendableForPair(from, to) else null

    // ── Transfer ──────────────────────────────────────────────────────

    override suspend fun transfer(
        from: SendBalance,
        to: SendBalance,
        amount: Dash,
        isMax: Boolean,
        expectedWalletIdHex: String?
    ): ExternalSendResult {
        if (!inFlight.compareAndSet(false, true)) {
            log.warn("internal transfer refused: another internal transfer is in progress")
            return ExternalSendResult.NotSent(NotSentReason.IN_PROGRESS)
        }
        return try {
            withContext(ioDispatcher) { transferInner(from, to, amount, isMax, expectedWalletIdHex) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Every pre-submission step returns its own NotSent; an exception escaping here came
            // from a write path and may have submitted.
            log.error("internal transfer {} → {} failed unclassified; outcome unknown", from, to, e)
            ExternalSendResult.MayHaveGoneThrough
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun transferInner(
        from: SendBalance,
        to: SendBalance,
        amount: Dash,
        isMax: Boolean,
        expectedWalletIdHex: String?
    ): ExternalSendResult {
        val pair = "$from → $to"
        if (!amount.isPositive) return notSent(pair, NotSentReason.GENERIC, "non-positive amount")
        if (from == to || !internalTransferPairSupported(from, to)) {
            return notSent(pair, NotSentReason.NOT_SUPPORTED, "pair not supported")
        }

        // Engine ownership / flags, error-preserving.
        val committed = readOrNull("cutover state") { source.cutoverCommittedOrThrow() }
            ?: return notSent(pair, NotSentReason.GENERIC, "cutover state unreadable")
        if (!committed) return notSent(pair, NotSentReason.NOT_SUPPORTED, "cutover not committed")
        val shieldedEnabled = if (internalTransferHasShieldedLeg(from, to)) {
            readOrNull("shielded flag") { source.shieldedEnabledOrThrow() }
                ?: return notSent(pair, NotSentReason.GENERIC, "shielded flag unreadable")
        } else {
            false
        }
        val ownIdentityId: String? = if (from == SendBalance.IDENTITY || to == SendBalance.IDENTITY) {
            val own = readOrNull("own identity") { Result.success(source.ownIdentityIdOrThrow()) }
                ?: return notSent(pair, NotSentReason.GENERIC, "identity record unreadable")
            own.getOrNull()
        } else {
            null
        }
        val support = internalTransferSupport(from, to, shieldedEnabled, hasIdentity = ownIdentityId != null)
        if (support != InternalTransferSupport.SUPPORTED) {
            return notSent(pair, NotSentReason.NOT_SUPPORTED, "pair not supported ($support)")
        }

        // The wallet the user confirmed with must be the one bound now.
        val walletIdHex = expectedWalletIdHex
            ?: return notSent(pair, NotSentReason.GENERIC, "no SDK wallet captured at confirmation")
        if (!stillBound(pair, walletIdHex)) return ExternalSendResult.NotSent(NotSentReason.GENERIC)

        return when (to) {
            SendBalance.CORE -> {
                // Never the advertised receive address (checklist 4.15): no fallback.
                val address = try {
                    source.unadvertisedCoreAddress()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("internal transfer {}: no unadvertised Core address; not sent", pair, e)
                    return ExternalSendResult.NotSent(NotSentReason.GENERIC)
                }
                delegate(pair, walletIdHex, from, SendDestination.Core(address), amount, isMax)
            }
            SendBalance.IDENTITY -> {
                val id = ownIdentityId ?: return notSent(pair, NotSentReason.GENERIC, "no own identity")
                delegate(pair, walletIdHex, from, SendDestination.Identity(id), amount, isMax)
            }
            SendBalance.PLATFORM -> when (from) {
                SendBalance.SHIELDED -> unshieldToOwnPlatform(pair, walletIdHex, amount, isMax)
                else -> {
                    val address = readOrNull("own platform address") { source.ownPlatformAddress() }
                        ?: return notSent(pair, NotSentReason.GENERIC, "own platform address unavailable")
                    delegate(pair, walletIdHex, from, SendDestination.Platform(address), amount, isMax)
                }
            }
            SendBalance.SHIELDED -> when (from) {
                SendBalance.CORE -> shieldFromOwnCore(pair, walletIdHex, amount)
                else -> {
                    if (!stillBound(pair, walletIdHex)) return ExternalSendResult.NotSent(NotSentReason.GENERIC)
                    mapWrite(pair, source.shieldFromCredits(amount, walletIdHex))
                }
            }
        }
    }

    /** An existing External Send route to the resolved own [destination], after a wallet re-check. */
    private suspend fun delegate(
        pair: String,
        walletIdHex: String,
        from: SendBalance,
        destination: SendDestination,
        amount: Dash,
        isMax: Boolean
    ): ExternalSendResult {
        if (!stillBound(pair, walletIdHex)) return ExternalSendResult.NotSent(NotSentReason.GENERIC)
        return externalSendService.send(from, destination, amount, isMax, walletIdHex)
    }

    /** Self-shield of `amount + pool fee`: the own shielded balance receives [amount]. */
    private suspend fun shieldFromOwnCore(pair: String, walletIdHex: String, amount: Dash): ExternalSendResult {
        val feeCredits = readOrNull("shield pool fee") { source.shieldedFeeCredits(ShieldedFeeKind.TRANSFER) }
            ?: return notSent(pair, NotSentReason.GENERIC, "shield pool fee unknown")
        val lock = try {
            Dash(Math.addExact(amount.duffs, shieldPoolFeeDuffs(feeCredits)))
        } catch (e: ArithmeticException) {
            return notSent(pair, NotSentReason.GENERIC, "amount out of range")
        }
        if (!stillBound(pair, walletIdHex)) return ExternalSendResult.NotSent(NotSentReason.GENERIC)
        return when (val result = source.shieldFromWallet(lock, walletIdHex)) {
            is SdkWriteResult.Broadcast -> when (result.value) {
                ShieldFromWalletOutcome.COMPLETED -> ExternalSendResult.Sent(null)
                ShieldFromWalletOutcome.SHIELD_PENDING_RETRY -> {
                    log.warn("internal transfer {}: lock out, shield pending — the sweep completes it", pair)
                    ExternalSendResult.CompletesAutomatically
                }
            }
            else -> mapWrite(pair, result)
        }
    }

    /** Unshield to the own Platform address; a Max retries once with the reported fee deficit. */
    private suspend fun unshieldToOwnPlatform(
        pair: String,
        walletIdHex: String,
        amount: Dash,
        isMax: Boolean
    ): ExternalSendResult {
        suspend fun attempt(value: Dash): SdkWriteResult<Unit> {
            if (!stillBound(pair, walletIdHex)) return SdkWriteResult.NotBroadcast("the bound wallet changed")
            return source.unshieldToCredits(value, walletIdHex)
        }
        var result = attempt(amount)
        val first = result
        if (isMax && first is SdkWriteResult.NotBroadcast) {
            val adjustment = shieldedMaxFeeAdjustment(amount, first)
            if (adjustment != null) {
                log.info("internal transfer {}: max spend short by the pool fee; retrying once", pair)
                result = attempt(adjustment.adjustedAmount)
            }
        }
        return mapWrite(pair, result)
    }

    private fun mapWrite(pair: String, result: SdkWriteResult<*>): ExternalSendResult = when (result) {
        is SdkWriteResult.Broadcast -> {
            log.info("internal transfer {}: sent", pair)
            ExternalSendResult.Sent(null)
        }
        is SdkWriteResult.NotBroadcast -> {
            val reason = notSentReasonFor(result)
            log.warn("internal transfer {}: not sent ({})", pair, reason, result.cause)
            ExternalSendResult.NotSent(reason)
        }
        is SdkWriteResult.Ambiguous -> {
            log.error("internal transfer {}: outcome unconfirmed — may have gone through; do NOT retry", pair, result.cause)
            ExternalSendResult.MayHaveGoneThrough
        }
    }

    /** Re-read the bound SDK wallet: it must still be [walletIdHex]. */
    private suspend fun stillBound(pair: String, walletIdHex: String): Boolean {
        val now = readOrNull("bound wallet") { source.boundWalletIdOrNull() }
        val same = now != null && now.equals(walletIdHex, ignoreCase = true)
        if (!same) log.warn("internal transfer {}: the bound SDK wallet changed before submission; not sent", pair)
        return same
    }

    private suspend fun <T : Any> readOrNull(what: String, read: suspend () -> T?): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("internal transfer: {} unreadable", what, e)
        null
    }

    private fun notSent(pair: String, reason: NotSentReason, why: String): ExternalSendResult.NotSent {
        log.info("internal transfer {}: not sent ({}: {})", pair, reason, why)
        return ExternalSendResult.NotSent(reason)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(InternalTransferServiceImpl::class.java)
    }
}
