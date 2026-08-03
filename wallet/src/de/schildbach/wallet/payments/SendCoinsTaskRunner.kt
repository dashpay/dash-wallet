/*
 * Copyright 2022 Dash Core Group.
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
package de.schildbach.wallet.payments

import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import com.google.common.base.Stopwatch
import de.schildbach.wallet.Constants.NETWORK_PARAMETERS
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.IDENTITY_ID
import org.dash.wallet.common.data.PaymentIntent
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.L1SendProbeService
import de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService
import de.schildbach.wallet.service.platform.sdk.SdkL1SendService
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.util.AnrException
import kotlinx.coroutines.*
import okhttp3.CacheControl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.IOException
import org.dash.wallet.common.payments.bip70.Protos
import org.dash.wallet.common.payments.bip70.Protos.Payment
import org.bitcoinj.core.*
import org.bitcoinj.crypto.IKey
import org.bitcoinj.crypto.KeyCrypterException
import org.dash.wallet.common.payments.bip70.PaymentProtocol
import org.dash.wallet.common.payments.bip70.PaymentProtocolException.InvalidPaymentRequestURL
import org.bitcoinj.script.ScriptException
import org.bitcoinj.wallet.*
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.transactions.ExactOutputsSelector
import de.schildbach.wallet.transactions.toTxInfo
import de.schildbach.wallet.util.toCoin
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toDash
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.DashPaymentIntentParser
import org.dash.wallet.common.payments.parsers.Scripts
import org.dash.wallet.common.services.DirectPayException
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import de.schildbach.wallet.transactions.ByAddressCoinSelector
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.call
import org.dash.wallet.common.util.ensureSuccessful
import org.slf4j.LoggerFactory
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.services.SpendSelection
import org.bitcoinj.script.ScriptPattern
import org.bitcoinj.core.Sha256Hash
import java.util.function.Consumer
import java.util.function.Predicate
import javax.inject.Inject
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toSha256Hash

/**
 * Phase 5d: where a dashj-typed send routes once the cutover state is
 * consulted. PURE so the decision is host-testable in isolation:
 *
 * - [DASHJ]: cutover not committed — today's unchanged dashj path
 *   (byte-identical for every install until a deliberate COMMIT).
 * - [SDK_BRIDGED]: cutover committed and the send is a simple
 *   pay-to-address — broadcast via the SDK, then bridge the tx into the
 *   dashj wallet so the caller still gets the live dashj [Transaction].
 * - [FAIL_CLOSED]: cutover committed but the send needs dashj-only
 *   machinery the SDK has no equivalent for (a custom [CoinSelector] or a
 *   `canSendLockedOutput` predicate — CrowdNode), OR it is an empty-wallet
 *   send-all on the TYPED overloads. Failing with a clear error BEATS
 *   falling through to dashj: its peergroup is held, so a dashj "send"
 *   would commit a tx that silently queues and then broadcasts on a later
 *   ROLLBACK, long after the user was told it failed.
 *
 * Why the typed overloads' `emptyWallet` FAILS CLOSED (funds-critical):
 * their only emptyWallet caller is CrowdNode ([sendCoinsSelected]), whose
 * deposit-all is a TWO-step flow — topUp (drain everything to the account
 * address, `SpendSelection.Any` ⇒ no selector) then deposit (ByAddress
 * selector ⇒ always FAIL_CLOSED post-cutover). Routing step 1 to the SDK
 * drain would succeed and step 2 would then deterministically throw —
 * wedging the FULL balance at the account address (half-executed
 * deposit-all). Refusing step 1 makes the failure atomic: no funds move.
 * Only the MAIN-UI funnel path ([extractSdkRoutablePayment] with
 * `sendAll = true` — a single-shot send-max with no dependent follow-up)
 * may route a send-all to the SDK drain.
 */
enum class CutoverSendRoute { DASHJ, SDK_BRIDGED, FAIL_CLOSED }

/**
 * Phase 5d fail-closed error: the cutover is committed but this send needs
 * dashj-only machinery the SDK send surface cannot reproduce (custom coin
 * selection, locked-output predicates, multi-recipient/BIP70 payments,
 * typed-overload send-all). Nothing was built or broadcast. The send UI maps
 * this TYPE to its own honest "payment type not supported" copy
 * ([de.schildbach.wallet.ui.send.classifySendFailure]) — the message here is
 * for logs only and must never be shown verbatim to a user.
 */
class SendNotSdkRoutableException(message: String) : IllegalStateException(message)

/**
 * Phase 5d fail-closed error: the cutover is committed but the SDK engine
 * refused to attempt the send because its L1 funding gate is closed — the
 * engine is not running or its compact-filter scan has not caught up to the
 * chain tip ([de.schildbach.wallet.service.platform.sdk.L1_FUNDING_GATE_CLOSED_REASON]).
 * This is the ONE send failure that genuinely means "not synced yet", and the
 * only one the UI may present as such. Nothing was broadcast.
 */
class SendEngineNotSyncedException(message: String) : IllegalStateException(message)

/**
 * Phase 5d: the exception for a post-cutover [SdkWriteResult.NotBroadcast] —
 * the SDK send was refused pre-broadcast and there is no dashj fallback (the
 * held engine would queue-not-send). Typed by REASON so the UI can map it
 * honestly: a closed funding gate (the engine's scan not caught up — see
 * [de.schildbach.wallet.service.platform.sdk.L1_FUNDING_GATE_CLOSED_REASON])
 * is the not-synced case; anything else is a plain [IllegalStateException]
 * the UI renders as a generic failure, never as "not synced" and never
 * verbatim. Pure — host-testable.
 */
fun sdkSendNotAttemptedException(reason: String): IllegalStateException =
    if (reason.contains(de.schildbach.wallet.service.platform.sdk.L1_FUNDING_GATE_CLOSED_REASON)) {
        SendEngineNotSyncedException(
            "cutover committed but the SDK engine cannot fund a send yet ($reason)"
        )
    } else {
        IllegalStateException(
            "cutover committed but the SDK send was not attempted ($reason) — " +
                "dashj cannot broadcast while held"
        )
    }

/** The pure routing decision — see [CutoverSendRoute]. */
fun cutoverSendRoute(
    cutoverCommitted: Boolean,
    hasCustomSelector: Boolean,
    hasLockedOutputPredicate: Boolean,
    emptyWallet: Boolean
): CutoverSendRoute = when {
    !cutoverCommitted -> CutoverSendRoute.DASHJ
    // dashj-only machinery the SDK cannot honor — the predicate would be
    // silently DROPPED by an SDK route, so it fail-closes exactly like a
    // custom selector even when the selector is null.
    hasCustomSelector || hasLockedOutputPredicate -> CutoverSendRoute.FAIL_CLOSED
    // Typed-overload send-all: FAIL_CLOSED to keep CrowdNode's two-step
    // deposit-all atomic (see the enum KDoc). The main-UI funnel routes
    // its send-all separately via extractSdkRoutablePayment.
    emptyWallet -> CutoverSendRoute.FAIL_CLOSED
    else -> CutoverSendRoute.SDK_BRIDGED // simple pay-to-address
}

/**
 * Phase 5d: the ONE payment a [SendRequest] carries when — and ONLY when —
 * the SDK send surface can take it over ([extractSdkRoutablePayment]).
 *
 * @property sendAll the request is dashj's `emptyWallet` send-all; the SDK
 *   route drains via `SelectionStrategy::All` and [amount] is display-typed
 *   (the engine delivers `total − fee`).
 */
data class SdkRoutablePayment(val address: Address, val amount: Coin, val sendAll: Boolean)

/**
 * Phase 5d: the payment of a [SendRequest] when — and ONLY when — it is a
 * simple single-recipient pay-to-address (plain or send-all) the SDK send
 * surface can take over; null means "not SDK-routable, fail closed". The
 * main send UI builds and signs its own SendRequest for the fee preview
 * and submits it straight to the [SendCoinsTaskRunner.sendCoins] funnel
 * (observed live: the first cutover send rehearsal hit the funnel backstop
 * instead of the typed overload's routing), so the funnel needs this
 * extraction to route those sends post-cutover.
 *
 * Empty-wallet (send-all) requests ARE routable since Step B: the single
 * foreign output identifies the destination and the SDK drain
 * ([SdkL1SendService] with `emptyWallet = true`) reproduces the semantics
 * (all spendable inputs, deliverable = everything minus fee, no change).
 *
 * Deliberately conservative — anything the SDK send can't reproduce
 * faithfully returns null:
 * - a custom [CoinSelector] (anything but the default zero-conf one):
 *   CrowdNode-style selection the SDK can't honor;
 * - locked-output predicates: dashj-only machinery;
 * - any non-standard output script, or multiple foreign recipients
 *   (BIP70 multi-output): the payment cannot be identified unambiguously.
 *
 * SELF-sends (observed on-device, 11.10.44: a plain send to the wallet's
 * OWN receive address threw the fail-closed backstop): the recipient and
 * the change output are BOTH "mine", so there are zero foreign outputs and
 * the outputs alone cannot name the payment. Resolution, strictest first:
 * - [intendedRecipient] (the send UI's payment-intent address, threaded
 *   through the funnel) names it: the output(s) paying that address ARE the
 *   payment — summed when the change lands on the same address — and any
 *   other own output is change. An intent NO output pays proves a mismatch
 *   and refuses.
 * - no intent, exactly ONE output: unambiguous (a raw single-output
 *   self-send, or a send-all-to-self) — that output is the payment;
 *   `emptyWallet` carries through as [SdkRoutablePayment.sendAll] exactly
 *   like the foreign case, NEVER forced (a forced drain would spend the
 *   whole wallet on a small self-send).
 * - no intent, several own outputs: recipient vs change cannot be told
 *   apart — never guess, fail closed.
 *
 * Pure given [isMine] — host-testable without a wallet.
 */
fun extractSdkRoutablePayment(
    sendRequest: SendRequest,
    params: NetworkParameters,
    intendedRecipient: Address? = null,
    isMine: (Address) -> Boolean
): SdkRoutablePayment? {
    val selector = sendRequest.coinSelector
    if (selector != null && selector !is ZeroConfCoinSelector) return null
    if (sendRequest.canUseLockedOutputPredicate != null) return null
    val resolvedOutputs = ArrayList<Pair<Address, Coin>>(sendRequest.tx.outputs.size)
    for (output in sendRequest.tx.outputs) {
        // A script we can't resolve to an address (OP_RETURN payloads,
        // exotic types) makes the payment unidentifiable — not routable.
        val address = try {
            output.scriptPubKey.getToAddress(params)
        } catch (e: Exception) {
            return null
        }
        resolvedOutputs += address to output.value
    }
    val foreignOutputs = resolvedOutputs.filter { (address, _) -> !isMine(address) }
    // The common case: exactly one output NOT ours is THE payment (any own
    // outputs are change). Unchanged behavior, intent or not.
    foreignOutputs.singleOrNull()?.let { (address, amount) ->
        return SdkRoutablePayment(address, amount, sendAll = sendRequest.emptyWallet)
    }
    // Multiple foreign recipients (BIP70 multi-output): ambiguous, refuse.
    if (foreignOutputs.isNotEmpty()) return null
    // Zero foreign outputs: a SELF-send. The UI's intent names the payment.
    if (intendedRecipient != null) {
        val paidToIntent = resolvedOutputs.filter { (address, _) -> address == intendedRecipient }
        if (paidToIntent.isEmpty()) return null // the request doesn't match the stated intent
        val amount = paidToIntent.fold(Coin.ZERO) { sum, (_, value) -> sum.add(value) }
        return SdkRoutablePayment(intendedRecipient, amount, sendAll = sendRequest.emptyWallet)
    }
    // No intent: only a single-output self-send is unambiguous.
    resolvedOutputs.singleOrNull()?.let { (address, amount) ->
        return SdkRoutablePayment(address, amount, sendAll = sendRequest.emptyWallet)
    }
    return null
}

/**
 * Post-cutover BIP70/BIP270 (issue #1520 Phase 1B item 1): the
 * `(base58 address, duffs)` recipients of a payment intent when — and ONLY
 * when — EVERY output is a standard pay-to-address script (P2PKH/P2SH)
 * with a positive amount, i.e. expressible by the SDK's address-only tx
 * builder. Null means "not SDK-routable, fail closed" — a non-standard
 * output script (the builder has no raw-script surface yet) or a
 * zero/absent amount (payer-chooses requests never reach the direct-pay
 * leg with unset amounts on the dashj path either).
 *
 * Pure — host-testable without a wallet.
 */
fun extractBip70Recipients(
    paymentIntent: PaymentIntent,
    network: AddressNetwork
): List<Pair<String, Long>>? {
    val outputs = paymentIntent.outputs ?: return null
    if (outputs.isEmpty()) return null
    val recipients = ArrayList<Pair<String, Long>>(outputs.size)
    for (output in outputs) {
        val script = output.scriptData
        val address = if (Scripts.isP2PKH(script) || Scripts.isP2SH(script)) {
            Scripts.addressOf(script, network)
        } else {
            null
        } ?: return null
        val amountDuffs = output.amount?.value ?: return null
        if (amountDuffs <= 0) return null
        recipients += address to amountDuffs
    }
    return recipients
}

class SendCoinsTaskRunner @Inject constructor(
    private val walletData: WalletData,
    private val walletApplication: WalletApplication,
    private val securityFunctions: SecurityFunctions,
    private val packageInfoProvider: PackageInfoProvider,
    private val analyticsService: AnalyticsService,
    private val identityConfig: BlockchainIdentityConfig,
    private val identityRepository: IdentityRepository,
    private val platformRepo: PlatformRepo,
    private val metadataProvider: TransactionMetadataProvider,
    private val sdkL1SendService: SdkL1SendService,
    private val l1ShadowSyncService: L1ShadowSyncService,
    private val l1SendProbeService: L1SendProbeService,
    private val bridgedTransactionFactory: de.schildbach.wallet.service.platform.sdk.SdkBridgedTransactionFactory
) : WalletSendPaymentService {
    companion object {
        private const val WALLET_EXCEPTION_MESSAGE = "this method can't be used before creating the wallet"
        private val log = LoggerFactory.getLogger(SendCoinsTaskRunner::class.java)
    }
    private val paymentIntentParser = DashPaymentIntentParser(org.dash.wallet.common.payments.parsers.AddressNetwork.fromId(NETWORK_PARAMETERS.id))

    @Throws(LeftoverBalanceException::class)
    override suspend fun sendCoins(
        address: Address,
        amount: Coin,
        coinSelector: CoinSelector?,
        emptyWallet: Boolean,
        checkBalanceConditions: Boolean,
        beforeSending: Consumer<Transaction>?,
        canSendLockedOutput: Predicate<TransactionOutput>?
    ): Transaction {
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)

        if (checkBalanceConditions && !wallet.isAddressMine(address)) {
            // This can throw LeftoverBalanceException
            walletData.checkSendingConditions(address, amount)
        }

        // Phase 5d: once the cutover is committed the dashj engine is held,
        // so this path must not build/commit a dashj send (see
        // [cutoverSendRoute]). Pre-cutover the route is DASHJ and the code
        // below is byte-identical to today.
        when (
            cutoverSendRoute(
                cutoverCommitted = sdkL1SendService.cutoverCommitted(),
                hasCustomSelector = coinSelector != null,
                hasLockedOutputPredicate = canSendLockedOutput != null,
                emptyWallet = emptyWallet
            )
        ) {
            CutoverSendRoute.DASHJ -> Unit // unchanged path below
            CutoverSendRoute.SDK_BRIDGED -> return sendViaSdkBridged(address, amount, beforeSending, emptyWallet)
            CutoverSendRoute.FAIL_CLOSED -> throw SendNotSdkRoutableException(
                "cutover committed: this send type (custom coin selection, locked-output " +
                    "predicate, or typed-overload send-all) is not SDK-routable and dashj cannot " +
                    "broadcast while held"
            )
        }

        val sendRequest =
            createSendRequest(address, amount, coinSelector, emptyWallet, canSendLockedOutput = canSendLockedOutput)
        return sendCoins(
            sendRequest,
            checkBalanceConditions = false,
            beforeSending = beforeSending
        )
    }

    /**
     * Phase 5d SDK-routed send for the dashj-typed callers, used ONLY when
     * the cutover is committed: broadcast via the SDK, then SYNCHRONOUSLY
     * bridge the tx into the dashj wallet so the caller gets the LIVE dashj
     * [Transaction] its listeners/metadata expect. [beforeSending] runs on
     * the bridged instance — the tx is already broadcast at that point, so
     * only metadata-style mutations (memo, exchange rate) take effect;
     * that matches how every current caller uses it.
     *
     * Failure semantics (money-path, deliberately explicit):
     * - SDK [SdkWriteResult.NotBroadcast] → throw. NEVER fall back to dashj
     *   here: the held engine would queue-not-send (see [cutoverSendRoute]).
     * - [SdkWriteResult.Ambiguous] → rethrow, never retried (double-pay risk).
     * - broadcast OK but bridge failed → throw with the txid in the message.
     *   The coins ARE on the network; the wallet reconciles via the next
     *   sync, and the error text says exactly that.
     */
    private suspend fun sendViaSdkBridged(
        address: Address,
        amount: Coin,
        beforeSending: Consumer<Transaction>?,
        emptyWallet: Boolean = false
    ): Transaction {
        val result = sdkL1SendService.sendToAddress(address.toBase58(), Dash(amount.value), emptyWallet = emptyWallet)
        return when (result) {
            is SdkWriteResult.Broadcast -> {
                when (val bridged = bridgedTransactionFactory.bridge(result.value)) {
                    is de.schildbach.wallet.service.platform.sdk.BridgedTxResult.Bridged -> {
                        beforeSending?.accept(bridged.transaction)
                        bridged.transaction
                    }
                    is de.schildbach.wallet.service.platform.sdk.BridgedTxResult.NotBridged -> throw RuntimeException(
                        "send broadcast via SDK (txid ${result.value}) but the wallet display bridge " +
                            "failed (${bridged.reason}) — the funds ARE sent; the transaction appears " +
                            "after the next sync"
                    )
                }
            }
            is SdkWriteResult.Ambiguous ->
                throw (result.cause as? Exception ?: RuntimeException(result.cause))
            is SdkWriteResult.NotBroadcast -> throw sdkSendNotAttemptedException(result.reason)
        }
    }

    /**
     * Phase 5b (`docs/kotlin-sdk-migration-plan.md`): this NEUTRAL overload —
     * the integrations path (Coinbase / Maya / feature modules) — is the ONLY
     * send routed through the Kotlin SDK, behind
     * [de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.USE_KOTLIN_SDK_L1_SEND]
     * (default OFF ⇒ this method is byte-for-byte the old dashj path apart
     * from one DataStore flag read). The dashj-typed overload above — the
     * main send UI, CrowdNode's selector/locked-output sends, BIP70 — stays
     * untouched this phase: those call sites depend on dashj-specific
     * machinery (custom [CoinSelector]s, `canSendLockedOutput` predicates,
     * the returned [Transaction] object for listeners/metadata) that the
     * SDK send surface has no equivalent for yet; they cut over in Phase 5c
     * after this narrow path has soak-validated on real funds.
     *
     * Routing contract (same as [SdkDashPayWrites]):
     * - `Broadcast(txid)` → return the txid; the dashj send MUST NOT run
     *   (callers only consume the returned txid hex — identical either way).
     * - `NotBroadcast` → fall through to the unchanged dashj path.
     * - `Ambiguous` → rethrow like a dashj broadcast failure; NEVER retry
     *   via dashj (its coin selection may pick different UTXOs — a dashj
     *   retry after a maybe-sent SDK tx is a potential double PAY).
     *
     * The SDK route replicates this path's only pre-send condition (the
     * leftover-balance check) via `beforeBroadcast`, so
     * [LeftoverBalanceException] surfaces identically on both routes.
     *
     * `emptyWallet = true` here (Coinbase's transfer-max is the only such
     * caller) DELIBERATELY keeps the post-cutover SDK drain, unlike the
     * dashj-typed overloads (which FAIL_CLOSED — see [cutoverSendRoute]):
     * this is a single-shot send-all with no dependent follow-up send, so
     * it cannot half-execute like CrowdNode's two-step deposit-all, and
     * [SdkL1SendService] independently refuses the drain while any
     * app-locked (CrowdNode) output exists.
     */
    @Throws(LeftoverBalanceException::class)
    override suspend fun sendCoins(
        address: String,
        amount: Dash,
        emptyWallet: Boolean,
        checkBalanceConditions: Boolean
    ): String {
        val sdkResult = sdkL1SendService.sendToAddress(address, amount, emptyWallet) {
            // Same conditions the dashj-typed overload enforces before its
            // broadcast; throws (e.g. LeftoverBalanceException) propagate
            // to the caller exactly as they do on the dashj path.
            val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
            val dashAddress = Address.fromString(walletData.networkParameters, address)
            if (checkBalanceConditions && !wallet.isAddressMine(dashAddress)) {
                walletData.checkSendingConditions(dashAddress, amount.toCoin())
            }
        }
        when (sdkResult) {
            is SdkWriteResult.Broadcast -> {
                // Phase 5c.0/5c.1 fee-parity + bridge-feasibility probes
                // (L1SendProbeService): DEBUG-only, fire-and-forget, contains
                // every failure — the send result is already decided here.
                if (BuildConfig.DEBUG) {
                    l1SendProbeService.probeSdkSendInBackground(
                        txidHex = sdkResult.value,
                        addressBase58 = address,
                        amountDuffs = amount.duffs,
                        emptyWallet = emptyWallet
                    ) { dashjDryRunFeeDuffs(address, amount, emptyWallet) }
                }
                return sdkResult.value
            }
            is SdkWriteResult.Ambiguous ->
                throw (sdkResult.cause as? Exception ?: RuntimeException(sdkResult.cause))
            is SdkWriteResult.NotBroadcast ->
                // Phase 5d: post-cutover there is no dashj fallback — the held
                // engine would queue-not-send (see cutoverSendRoute). Pre-cutover
                // this is the unchanged fall-through to the dashj path below.
                if (sdkL1SendService.cutoverCommitted()) {
                    throw sdkSendNotAttemptedException(sdkResult.reason)
                }
        }

        val dashAddress = Address.fromString(walletData.networkParameters, address)
        // Phase 5c.0 dashj-route baseline (DEBUG-only): capture the dry-run
        // estimate starting BEFORE the send commits its spend, then log the
        // actual-vs-estimated comparison after; detached, never blocks or
        // fails the send.
        val baselineEstimate = if (BuildConfig.DEBUG) {
            l1SendProbeService.dryRunEstimateAsync { dashjDryRunFeeDuffs(address, amount, emptyWallet) }
        } else {
            null
        }
        val transaction = try {
            sendCoins(
                dashAddress,
                amount.toCoin(),
                emptyWallet = emptyWallet,
                checkBalanceConditions = checkBalanceConditions
            )
        } catch (t: Throwable) {
            baselineEstimate?.cancel()
            throw t
        }
        if (baselineEstimate != null) {
            l1SendProbeService.probeDashjSendInBackground(
                tx = transaction,
                addressBase58 = address,
                amountDuffs = amount.duffs,
                emptyWallet = emptyWallet,
                estimatedFeeDuffs = baselineEstimate
            )
        }
        return transaction.txId.toString()
    }

    /**
     * The 5c.0 probe's dashj dry-run: the EXISTING [estimateNetworkFee]
     * path for the same `{address, amount, emptyWallet}`, reduced to the
     * fee in duffs (null when the completed request reports no fee).
     * Debug-probe-only; throws propagate to the probe's containment.
     */
    private suspend fun dashjDryRunFeeDuffs(address: String, amount: Dash, emptyWallet: Boolean): Long? {
        val details = estimateNetworkFee(
            Address.fromString(walletData.networkParameters, address),
            amount.toCoin(),
            emptyWallet
        )
        return details.fee.takeIf { it.isNotEmpty() }?.let { Coin.parseCoin(it).value }
    }

    override suspend fun estimateNetworkFee(
        address: String,
        amount: Dash,
        emptyWallet: Boolean
    ): SendPaymentService.TransactionEstimate {
        val dashAddress = Address.fromString(walletData.networkParameters, address)
        val details = estimateNetworkFee(dashAddress, amount.toCoin(), emptyWallet)
        return SendPaymentService.TransactionEstimate(details.fee, details.amountToSend.toDash(), details.totalAmount)
    }

    override suspend fun estimateNetworkFee(
        address: Address,
        amount: Coin,
        emptyWallet: Boolean
    ): WalletSendPaymentService.TransactionDetails {
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)
        var sendRequest = createSendRequest(address, amount, null, emptyWallet, false)
        val securityGuard = SecurityGuard.getInstance()
        val password = securityGuard.retrievePassword()
        val encryptionKey = securityFunctions.deriveKey(wallet, password)
        sendRequest.aesKey = encryptionKey
        wallet.completeTx(sendRequest)

        if (checkDust(sendRequest)) {
            sendRequest = createSendRequest(address, amount, null, emptyWallet)
            wallet.completeTx(sendRequest)
        }

        val txFee: Coin? = sendRequest.tx.fee

        val amountToSend = if (sendRequest.emptyWallet) {
            amount.minus(txFee)
        } else {
            amount
        }

        val totalAmount = if (sendRequest.emptyWallet || txFee == null) {
            amount.toPlainString()
        } else {
            amount.add(txFee).toPlainString()
        }

        return WalletSendPaymentService.TransactionDetails(txFee?.toPlainString() ?: "", amountToSend, totalAmount)
    }

    /**
     * Phase 5d: exposes the SDK cutover-commit state to the send UI's dry-run
     * via the already-injected [SdkL1SendService] — no direct SdkL1SendService
     * dependency needed in the ViewModel. Read-only, commits/broadcasts nothing.
     *
     * Post-commit the dashj engine is held with 0 UTXOs, so a dashj-based
     * dry-run (`wallet.completeTx`) always throws InsufficientMoneyException and
     * wrongly blocks the Send button; the dry-run consults this to validate
     * affordability against the SDK-overlaid balance instead. Pre-commit this is
     * false and the dry-run keeps its unchanged dashj `completeTx` path.
     */
    suspend fun isCutoverCommitted(): Boolean = sdkL1SendService.cutoverCommitted()

    override suspend fun payWithDashUrlTx(dashUri: String, serviceName: String?): Transaction =
        withContext(Dispatchers.IO) {
            val paymentIntent = paymentIntentParser.parse(dashUri, false)
            createPaymentRequest(paymentIntent, serviceName)
        }

    override suspend fun payWithDashUrl(dashUri: String, serviceName: String?): TxInfo =
        payWithDashUrlTx(dashUri, serviceName)
            .toTxInfo(walletData.transactionBag, walletData.networkParameters)

    @Throws(LeftoverBalanceException::class)
    override suspend fun sendCoinsSelected(
        address: String,
        amount: Dash,
        selection: SpendSelection,
        emptyWallet: Boolean,
        checkBalanceConditions: Boolean,
        lockSentOutputsTo: String?,
        canSpendLockedOutputsTo: String?
    ): TxInfo {
        val params = walletData.networkParameters
        val dashjAddress = Address.fromBase58(params, address)
        val selector = when (selection) {
            is SpendSelection.Any -> null
            is SpendSelection.ByAddress -> ByAddressCoinSelector(Address.fromBase58(params, selection.address))
            is SpendSelection.ExactOutput -> {
                val tx = walletData.getTransaction(Sha256Hash.wrap(selection.txId))
                    ?: throw IllegalArgumentException("unknown transaction: " + selection.txId)
                ExactOutputsSelector(listOf(tx.getOutput(selection.outputIndex.toLong())))
            }
        }
        val lockAddress = lockSentOutputsTo?.let { Address.fromBase58(params, it) }
        val canSpendAddress = canSpendLockedOutputsTo?.let { Address.fromBase58(params, it) }
        val tx = sendCoins(
            dashjAddress,
            amount.toCoin(),
            selector,
            emptyWallet,
            checkBalanceConditions,
            beforeSending = lockAddress?.let { la ->
                Consumer<Transaction> { t -> lockOutputsPayingTo(t, la) }
            },
            canSendLockedOutput = canSpendAddress?.let { ca ->
                Predicate<TransactionOutput> { it.scriptPubKey.getToAddress(params) == ca }
            }
        )
        return tx.toTxInfo(walletData.transactionBag, params)
    }

    /** Locks the P2PKH outputs of [tx] paying [address] (the CrowdNode account-output locking). */
    private fun lockOutputsPayingTo(tx: Transaction, address: Address) {
        val params = walletData.networkParameters
        tx.outputs.filter { output ->
            ScriptPattern.isP2PKH(output.scriptPubKey) &&
                Address.fromPubKeyHash(params, ScriptPattern.extractHashFromP2PKH(output.scriptPubKey)) == address
        }.forEach { output ->
            walletData.lockOutput(output.outPointFor)
        }
    }

    override suspend fun completeTransaction(sendRequest: SendRequest) {
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        val securityGuard = SecurityGuard.getInstance()
        val password = securityGuard.retrievePassword()
        val encryptionKey = securityFunctions.deriveKey(wallet, password)
        sendRequest.aesKey = encryptionKey
        sendRequest.coinSelector = ZeroConfCoinSelector.get() // default coin selector
        wallet.completeTx(sendRequest)
        sendRequest.aesKey = null
    }

    override suspend fun signTransaction(sendRequest: SendRequest) {
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        val securityGuard = SecurityGuard.getInstance()
        val password = securityGuard.retrievePassword()
        val encryptionKey = securityFunctions.deriveKey(wallet, password)
        sendRequest.aesKey = encryptionKey
        wallet.signTransaction(sendRequest)
        sendRequest.aesKey = null
    }

    override suspend fun sendTransaction(sendRequest: SendRequest): Transaction {
        return sendCoins(sendRequest, txCompleted = true, checkBalanceConditions = false)
    }

    /**
     * Fetches a BIP70/BIP270 payment request from the given URL.
     * @param basePaymentIntent The base payment intent containing the payment request URL
     * @return The parsed PaymentIntent from the payment request
     * @throws IOException if the request fails
     * @throws IllegalStateException if BIP72 trust check fails
     */
    suspend fun fetchPaymentRequest(basePaymentIntent: PaymentIntent): PaymentIntent = withContext(Dispatchers.IO) {
        val requestUrl = basePaymentIntent.paymentRequestUrl
            ?: throw IllegalArgumentException("Payment intent must have a payment request URL")

        log.info("requesting payment request from {}", requestUrl)
        val timer = AnalyticsTimer(analyticsService, log, AnalyticsConstants.Process.PROCESS_BIP7O_GET_PAYMENT_REQUEST)
        val request = buildOkHttpPaymentRequest(requestUrl)
        val response = Constants.HTTP_CLIENT.call(request)
        response.ensureSuccessful()
        requestUrl.toUri().host?.let {
            timer.logTiming(hashMapOf(AnalyticsConstants.Parameter.ARG1 to it))
        }
        log.info("payment request received")

        val contentType = response.header("Content-Type")
        val byteStream = response.body?.byteStream()

        if (byteStream == null || contentType.isNullOrEmpty()) {
            throw IOException("Null response for the payment request: $requestUrl")
        }

        val paymentIntent = paymentIntentParser.parse(byteStream, contentType)

        if (!basePaymentIntent.isExtendedBy(paymentIntent, true, de.schildbach.wallet.Constants.ADDRESS_NETWORK)) {
            log.info("BIP72 trust check failed")
            throw IllegalStateException("BIP72 trust check failed: $requestUrl")
        }

        paymentIntent
    }

    /**
     * Sends a direct payment via BIP70/BIP270 protocol.
     * This method signs the transaction, completes it, sends it via HTTP to the payment URL,
     * and handles the payment acknowledgment.
     *
     * @param sendRequest The send request (should already be created via createSendRequest)
     * @param paymentIntent The payment intent containing the payment URL
     * @param serviceName Optional service name for transaction metadata
     * @return The committed transaction
     * @throws DirectPayException if the payment is not acknowledged
     * @throws IOException if the HTTP request fails
     */
    suspend fun sendDirectPayment(
        sendRequest: SendRequest,
        paymentIntent: PaymentIntent,
        serviceName: String? = null
    ): Transaction = withContext(Dispatchers.IO) {
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)

        signSendRequest(sendRequest)
        directPay(sendRequest, paymentIntent, serviceName)
    }

    private suspend fun createPaymentRequest(basePaymentIntent: PaymentIntent, serviceName: String?): Transaction {
        val requestUrl = basePaymentIntent.paymentRequestUrl
        if (requestUrl != null) {
            val paymentIntent = fetchPaymentRequest(basePaymentIntent)
            val sendRequest = createRequestFromPaymentIntent(paymentIntent)
            return sendPayment(paymentIntent, sendRequest, serviceName)
        } else {
            val sendRequest = createRequestFromPaymentIntent(basePaymentIntent)
            val sendRequestForSigning = createSendRequest(
                false,
                basePaymentIntent,
                true,
                sendRequest.ensureMinRequiredFee
            )
            return sendCoins(sendRequestForSigning, serviceName = serviceName)
        }
    }

    private fun createRequestFromPaymentIntent(paymentIntent: PaymentIntent): SendRequest {
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)
        val sendRequest = createSendRequest(
            false,
            paymentIntent,
            signInputs = false,
            forceEnsureMinRequiredFee = false
        )

        return sendRequest
    }

    private suspend fun sendPayment(
        finalPaymentIntent: PaymentIntent,
        sendRequest: SendRequest,
        serviceName: String?
    ): Transaction {
        log.info("creating final sendRequest({}, ..., {})", finalPaymentIntent.paymentUrl, serviceName)
        val finalSendRequest = createSendRequest(
            false,
            finalPaymentIntent,
            true,
            sendRequest.ensureMinRequiredFee
        )
        signSendRequest(finalSendRequest)
        log.info("created final send Request")
        return directPay(finalSendRequest, finalPaymentIntent, serviceName)
    }

    /**
     * Completes and submits a direct payment via BIP70/BIP270 protocol.
     * This method completes the transaction, sends it via HTTP to the payment URL,
     * and handles the payment acknowledgment.
     *
     * @param sendRequest The send request (should already be created via createSendRequest)
     * @param finalPaymentIntent The payment intent containing the payment URL
     * @param serviceName Optional service name for transaction metadata
     * @return The committed transaction
     * @throws DirectPayException if the payment is not acknowledged
     * @throws IOException if the HTTP request fails
     */
    private suspend fun directPay(
        sendRequest: SendRequest,
        finalPaymentIntent: PaymentIntent,
        serviceName: String?
    ): Transaction {
        // Phase 5d / issue #1520 Phase 1B item 1: post-cutover the dashj
        // engine is held (completeTx would build a tx nothing can
        // broadcast), so the whole direct-pay leg runs on the SDK's
        // deferred build/broadcast surface instead. Pre-cutover the code
        // below is byte-identical to today.
        if (sdkL1SendService.cutoverCommitted()) {
            return directPayViaSdk(finalPaymentIntent, serviceName)
        }
        log.info("completing sendRequest transaction")
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)
        wallet.completeTx(sendRequest)
        log.info("completed sendRequest transaction")
        serviceName?.let {
            metadataProvider.setTransactionService(sendRequest.tx.txId.toTxId(), serviceName)
        }
        val refundAddress = wallet.freshAddress(KeyChain.KeyPurpose.REFUND)
        // The old common PaymentProtocol.createPaymentMessage verified each tx before
        // serializing; the neutral version can't (no dashj), so verify here.
        sendRequest.tx.verify()
        val payment = PaymentProtocol.createPaymentMessage(
            listOf(sendRequest.tx.unsafeBitcoinSerialize()),
            finalPaymentIntent.amount,
            refundAddress.toBase58(),
            null,
            finalPaymentIntent.payeeData
        )

        val requestUrl = finalPaymentIntent.paymentUrl
            ?: throw InvalidPaymentRequestURL("Final payment intent URL is null")
        log.info("trying to send tx to {}", requestUrl)
        val timer = AnalyticsTimer(analyticsService, log, AnalyticsConstants.Process.PROCESS_BIP7O_SEND_PAYMENT)
        val request = buildOkHttpDirectPayRequest(requestUrl, payment)
        try {
            val response = Constants.HTTP_CLIENT.call(request)
            response.ensureSuccessful()
            requestUrl.toUri().host?.let {
                timer.logTiming(hashMapOf(AnalyticsConstants.Parameter.ARG1 to it))
            }
            log.info("tx sent via http")

            val byteStream = response.body?.byteStream()
                ?: throw IOException("Null response for the payment request: $requestUrl")

            val paymentAck = byteStream.use { Protos.PaymentACK.parseFrom(byteStream) }
            val acknowledged = PaymentProtocol.parsePaymentAck(paymentAck).memo != "nack"
            log.info("received {} via http", if (acknowledged) "ack" else "nack")

            if (!acknowledged) {
                throw DirectPayException("Payment was not acknowledged by the server")
            }
        } catch (e: Exception) {
            if (e !is DirectPayException) {
                log.warn("Payment submission failed, but transaction may have been sent: ${sendRequest.tx.txId}", e)
                val tx = sendRequest.tx
                val delays = listOf(0L, 1000L, 3000L, 5000L)

                for (delayMs in delays) {
                    delay(delayMs)
                    if (isTransactionOnNetwork(tx)) {
                        log.info("Transaction found on network despite HTTP timeout: ${tx.txId}")
                        // The BIP70 server may have broadcast the tx and our wallet may have already
                        // picked it up via the P2P network — use maybeCommitTx to avoid throwing
                        // "commitTx called on the same transaction twice" in that race.
                        if (!wallet.maybeCommitTx(tx)) {
                            log.info("tx was already in the wallet (received via network): {}", tx.txId)
                        }
                        return tx
                    }
                }

                log.warn("Transaction not found on network after timeout, treating as failed: ${tx.txId}")
                // throw exception below
            }
            throw e
        }


        return sendCoins(sendRequest, txCompleted = true, checkBalanceConditions = true)
    }

    /**
     * The merchant ACKED the BIP70 payment but the wallet-side display
     * bridge failed. The payment IS made — callers must treat this as
     * success-with-degraded-display (the tx appears after the next sync)
     * and MUST NOT rebuild and resend: the merchant holds the acked
     * signed tx, so a rebuilt retry with fresh inputs could double-pay.
     */
    class Bip70AckedDisplayException(message: String) : RuntimeException(message)

    /**
     * Post-cutover BIP70/BIP270, the BUILD half (issue #1520 Phase 1B
     * item 1): the SDK builds + signs the payment-request tx with its
     * inputs RESERVED — nothing broadcast. [SdkDeferredPayment.feeDuffs]
     * is the EXACT fee of the tx [sendPrebuiltDirectPayment] will submit,
     * so this doubles as the confirm-screen fee preview (no dashj
     * `completeTx` dry-run). The caller owns the reservation: follow with
     * exactly one of [sendPrebuiltDirectPayment] or
     * [releaseDeferredPayment].
     *
     * Fails closed (IllegalStateException) when any output is not a
     * standard pay-to-address script ([extractBip70Recipients] null): the
     * SDK builder is address-only. Also runs the same pre-send
     * leftover-balance condition the dashj path enforces at commit time.
     */
    suspend fun buildDeferredBip70Payment(paymentIntent: PaymentIntent): de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment {
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)
        val recipients = extractBip70Recipients(paymentIntent, de.schildbach.wallet.Constants.ADDRESS_NETWORK)
            ?: throw SendNotSdkRoutableException(
                "cutover committed: this payment request has an output the SDK builder cannot " +
                    "express (non-address script or missing amount) — not SDK-routable"
            )
        if (paymentIntent.paymentUrl == null) {
            // Fail fast BEFORE reserving inputs for a payment that could
            // never be submitted.
            throw InvalidPaymentRequestURL("Final payment intent URL is null")
        }

        // The same pre-send condition the dashj path enforces at commit
        // time (checkBalanceConditions → the FIRST foreign output).
        for ((addressBase58, amountDuffs) in recipients) {
            val address = Address.fromString(NETWORK_PARAMETERS, addressBase58)
            if (!wallet.isAddressMine(address)) {
                walletData.checkSendingConditions(address, Coin.valueOf(amountDuffs))
                break
            }
        }

        return sdkL1SendService.buildDeferredPayment(recipients)
    }

    /** Pass-through: release a [buildDeferredBip70Payment] reservation (abandoned preview). */
    suspend fun releaseDeferredPayment(payment: de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment) =
        sdkL1SendService.releaseDeferredPayment(payment)

    /**
     * Post-cutover BIP70/BIP270 direct payment: build + submit in one step
     * — the path for flows WITHOUT a preview screen ([payWithDashUrl]).
     * The preview UI ([de.schildbach.wallet.ui.send.PaymentProtocolViewModel])
     * instead calls [buildDeferredBip70Payment] at preview time and
     * [sendPrebuiltDirectPayment] on confirm.
     */
    private suspend fun directPayViaSdk(
        finalPaymentIntent: PaymentIntent,
        serviceName: String?
    ): Transaction {
        val payment = buildDeferredBip70Payment(finalPaymentIntent)
        return sendPrebuiltDirectPayment(payment, finalPaymentIntent, serviceName)
    }

    /**
     * Post-cutover BIP70/BIP270, the SUBMIT half: the BIP70 `Payment`
     * message carries [payment]'s signed raw bytes; ONLY a merchant ack
     * broadcasts the tx, which then enters the dashj wallet through the
     * same bridge every SDK send uses ([SdkBridgedTransactionFactory] —
     * `maybeCommitTx` inside, so the "merchant broadcast it first" race
     * is handled exactly like the dashj path).
     *
     * Failure semantics (dashj-path parity unless noted):
     * - PRE-ACK failure (transport error, nack): release the reservation
     *   and rethrow — the dashj path likewise treats an unacked payment
     *   as never-sent; [payment] is DEAD afterwards (a retry must
     *   rebuild). NOTE the narrowed recovery window: pre-cutover a
     *   merchant-broadcast tx could still be discovered via dashj's bloom
     *   relay during its 0/1/3/5s poll; the SDK's compact-filter view
     *   cannot see unmined txs, so that rescue is gone. The release keeps
     *   a user retry from wedging on reserved inputs; an abandoned tx the
     *   merchant DOES later broadcast conflicts with (never doubles) the
     *   retry, because both spend the same reserved-then-released inputs.
     * - POST-ACK: the reservation is NEVER released — the merchant holds
     *   the signed tx, and releasing would let a retry select different
     *   inputs and double-pay if the merchant broadcasts the original. An
     *   SDK broadcast refusal here is logged and tolerated (CTX broadcasts
     *   the acked tx itself; the dashj path never verified its own
     *   announce either), and a display-bridge failure throws the typed
     *   [Bip70AckedDisplayException] so callers cannot mistake an acked
     *   payment for a retryable failure.
     */
    suspend fun sendPrebuiltDirectPayment(
        payment: de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment,
        finalPaymentIntent: PaymentIntent,
        serviceName: String? = null
    ): Transaction {
        val requestUrl = finalPaymentIntent.paymentUrl
            ?: throw InvalidPaymentRequestURL("Final payment intent URL is null")

        try {
            serviceName?.let {
                metadataProvider.setTransactionService(TxId.wrap(payment.txidHex), it)
            }
            // refund_to comes from the SDK's persisted address pool — no
            // dashj keychain on this route. Null → omit the refund output
            // (optional per BIP70) rather than fall back to dashj.
            val refundAddress = sdkL1SendService.refundAddressOrNull()
            if (refundAddress == null) {
                log.warn("no SDK refund address available; sending the Payment message without refund_to")
            }
            val paymentMessage = PaymentProtocol.createPaymentMessage(
                listOf(payment.rawTxBytes),
                finalPaymentIntent.amount,
                refundAddress,
                null,
                finalPaymentIntent.payeeData
            )

            log.info("trying to send tx {} to {}", payment.txidHex, requestUrl)
            val timer = AnalyticsTimer(analyticsService, log, AnalyticsConstants.Process.PROCESS_BIP7O_SEND_PAYMENT)
            val response = Constants.HTTP_CLIENT.call(buildOkHttpDirectPayRequest(requestUrl, paymentMessage))
            response.ensureSuccessful()
            requestUrl.toUri().host?.let {
                timer.logTiming(hashMapOf(AnalyticsConstants.Parameter.ARG1 to it))
            }
            log.info("tx sent via http")

            val byteStream = response.body?.byteStream()
                ?: throw IOException("Null response for the payment request: $requestUrl")
            val paymentAck = byteStream.use { Protos.PaymentACK.parseFrom(it) }
            val acknowledged = PaymentProtocol.parsePaymentAck(paymentAck).memo != "nack"
            log.info("received {} via http", if (acknowledged) "ack" else "nack")
            if (!acknowledged) {
                throw DirectPayException("Payment was not acknowledged by the server")
            }
        } catch (t: Throwable) {
            // Pre-ack: the merchant never accepted the payment — free the
            // inputs for an immediate retry (see the method KDoc for the
            // narrowed merchant-broadcast recovery window this accepts).
            withContext(NonCancellable) {
                sdkL1SendService.releaseDeferredPayment(payment)
            }
            throw t
        }

        // Acked: the payment IS made from the merchant's side. Broadcast
        // ourselves too (identical bytes/txid — a merchant broadcast makes
        // this a harmless duplicate announce), but never release.
        when (val result = sdkL1SendService.broadcastDeferredPayment(payment)) {
            is SdkWriteResult.Broadcast ->
                log.info("BIP70 tx {} broadcast via the SDK", result.value)
            is SdkWriteResult.NotBroadcast ->
                log.error(
                    "BIP70 tx {} acked but the SDK broadcast was refused pre-network ({}) — " +
                        "relying on the merchant's broadcast; the inputs stay reserved until " +
                        "the engine's sync/TTL reclaims them",
                    payment.txidHex, result.reason, result.cause
                )
            is SdkWriteResult.Ambiguous ->
                log.error(
                    "BIP70 tx {} acked; the SDK broadcast outcome is unconfirmed — the " +
                        "merchant's broadcast covers delivery",
                    payment.txidHex, result.cause
                )
        }

        return when (
            val bridged = bridgedTransactionFactory.bridge(payment.txidHex, payment.rawTxBytes)
        ) {
            is de.schildbach.wallet.service.platform.sdk.BridgedTxResult.Bridged ->
                bridged.transaction
            is de.schildbach.wallet.service.platform.sdk.BridgedTxResult.NotBridged -> throw Bip70AckedDisplayException(
                "BIP70 payment acked (txid ${payment.txidHex}) but the wallet display bridge " +
                    "failed (${bridged.reason}) — the payment IS made; the transaction appears " +
                    "after the next sync"
            )
        }
    }

    private fun isTransactionOnNetwork(transaction: Transaction): Boolean {
        return try {
            val wallet = walletData.wallet ?: return false
            val inWalletTx = wallet.getTransaction(transaction.txId)
            val confidence = (inWalletTx ?: transaction).confidence ?: return false

            // If we have the wallet’s instance, also accept network source as proof
            (inWalletTx != null && confidence.source == TransactionConfidence.Source.NETWORK) ||
                    confidence.isChainLocked ||
                    confidence.isTransactionLocked ||
                    confidence.numBroadcastPeers() > 0
        } catch (e: Exception) {
            log.debug("Error checking transaction network status: ${e.message}")
            false
        }
    }

    fun createSendRequest(
        mayEditAmount: Boolean,
        paymentIntent: PaymentIntent,
        signInputs: Boolean,
        forceEnsureMinRequiredFee: Boolean
    ): SendRequest {
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)
        val sendRequest = paymentIntent.toSendRequest(NETWORK_PARAMETERS)
        sendRequest.coinSelector = getCoinSelector()
        sendRequest.useInstantSend = false
        sendRequest.feePerKb = Constants.ECONOMIC_FEE.toDashjCoin()
        sendRequest.ensureMinRequiredFee = forceEnsureMinRequiredFee
        sendRequest.signInputs = signInputs
        val walletBalance = wallet.getBalance(getMaxOutputCoinSelector())
        sendRequest.emptyWallet = mayEditAmount && walletBalance.value == paymentIntent.amount?.value

        return sendRequest
    }

    fun createAssetLockSendRequest(
        mayEditAmount: Boolean,
        paymentIntent: PaymentIntent,
        signInputs: Boolean,
        forceEnsureMinRequiredFee: Boolean,
        topUpKey: ECKey
    ): SendRequest {
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)
        val sendRequest = SendRequest.assetLock(wallet.params, topUpKey, paymentIntent.amount.toDashjCoin())
        sendRequest.coinSelector = getCoinSelector()
        sendRequest.useInstantSend = false
        sendRequest.feePerKb = Constants.ECONOMIC_FEE.toDashjCoin()
        sendRequest.ensureMinRequiredFee = forceEnsureMinRequiredFee
        sendRequest.signInputs = signInputs
        val walletBalance = wallet.getBalance(getMaxOutputCoinSelector())
        sendRequest.emptyWallet = mayEditAmount && walletBalance.value == paymentIntent.amount?.value

        return sendRequest
    }

    @VisibleForTesting
    fun createSendRequest(
        address: Address,
        amount: Coin,
        coinSelector: CoinSelector? = null,
        emptyWallet: Boolean = false,
        forceMinFee: Boolean = true,
        canSendLockedOutput: Predicate<TransactionOutput>? = null
    ): SendRequest {
        return SendRequest.to(address, amount).apply {
            this.feePerKb = Constants.ECONOMIC_FEE.toDashjCoin()
            this.ensureMinRequiredFee = forceMinFee
            this.emptyWallet = emptyWallet

            val selector = coinSelector ?: getCoinSelector()
            this.canUseLockedOutputPredicate = canSendLockedOutput
            this.coinSelector = selector

            if (selector is ByAddressCoinSelector) {
                changeAddress = selector.address
            }
        }
    }

    // collect all coins, including those mixed by older app versions
    private fun getCoinSelector() = ZeroConfCoinSelector.get()

    private fun getMaxOutputCoinSelector() = MaxOutputAmountCoinSelector()

    @Throws(LeftoverBalanceException::class)
    suspend fun sendCoins(
        sendRequest: SendRequest,
        txCompleted: Boolean = false,
        checkBalanceConditions: Boolean = true,
        beforeSending: Consumer<Transaction>? = null,
        serviceName: String? = null,
        intendedRecipient: Address? = null
    ): Transaction = withContext(Dispatchers.IO) {
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)
        // Phase 5d routing for direct-SendRequest callers — the MAIN send UI
        // included (it builds/signs its own request for the fee preview and
        // submits it here, bypassing the typed overload; observed live in the
        // first send rehearsal). Post-cutover a dashj broadcast would
        // queue-not-send on the held peergroup, so: route a simple
        // single-recipient payment through the SDK bridge (the dashj-built tx
        // is discarded UNCOMMITTED — the SDK does its own selection/signing),
        // including the main UI's send-max (Step B: sendAll → the SDK drain,
        // the ONE send-all route allowed post-cutover), and FAIL CLOSED on
        // anything the SDK can't reproduce (BIP70 multi-output, CrowdNode
        // selectors/locked outputs).
        if (sdkL1SendService.cutoverCommitted()) {
            val payment = extractSdkRoutablePayment(
                sendRequest,
                walletData.networkParameters,
                intendedRecipient
            ) { address ->
                try {
                    wallet.isAddressMine(address)
                } catch (e: Exception) {
                    false
                }
            }
            if (payment != null) {
                log.info(
                    "cutover committed: routing the SendRequest payment via the SDK bridge " +
                        "({} duffs to {}…{})",
                    payment.amount.value,
                    payment.address.toBase58().take(8),
                    if (payment.sendAll) ", send-all" else ""
                )
                return@withContext sendViaSdkBridged(
                    payment.address, payment.amount, beforeSending, payment.sendAll
                )
            }
            throw SendNotSdkRoutableException(
                "cutover committed: this send is not SDK-routable (multi-recipient, unresolved " +
                    "self-send, or custom selection) and dashj cannot broadcast while held"
            )
        }
        val watch = Stopwatch.createStarted()
        val currentThread = Thread.currentThread()
        val monitorJob = launch(Dispatchers.IO) {
            delay(1000)
            log.warn("sendCoins is taking longer than 1 second")
            try {
                val anrException = AnrException(currentThread)
                anrException.logProcessMap()
            } catch (e: Exception) {
                log.error("Failed to dump thread traces during executeDryrun", e)
            }
        }

        if (checkBalanceConditions) {
            checkBalanceConditions(wallet, sendRequest.tx)
        }

        try {
            log.info("sending: {}", sendRequest)

            if (txCompleted) {
                // Use maybeCommitTx to avoid "commitTx called on the same transaction twice":
                // a BIP70 merchant (e.g. CTX) may broadcast the tx to the network as soon as it
                // receives the payment message, so our wallet can pick it up via the P2P peer
                // group and add it to the pending pool before we reach this commit.
                if (!wallet.maybeCommitTx(sendRequest.tx)) {
                    log.info(
                        "tx was already in the wallet (likely received via network broadcast): {}",
                        sendRequest.tx.txId
                    )
                }
            } else {
                signSendRequest(sendRequest)
                wallet.sendCoinsOffline(sendRequest)
            }

            val transaction = sendRequest.tx
            beforeSending?.accept(transaction)
            serviceName?.let {
                metadataProvider.setTransactionService(sendRequest.tx.txId.toTxId(), serviceName)
            }
            log.info("send successful, transaction committed in {}: {} ", watch, transaction.txId.toString())
            log.info("  transaction: {}", transaction.toStringHex())
            walletApplication.broadcastTransaction(transaction)
            // EVERY dashj spend of the shared UTXOs (main send UI, CrowdNode,
            // BIP70 — they all funnel through here) briefly inflates the SDK's
            // shadow view by the fee until the next mined block, exactly like
            // a Phase 5b SDK self-spend. Arm the same grace marker so the
            // parity decider's INFLATED auto-reset cannot false-fire while a
            // block is pending; a plain timestamp write, no-op cost when the
            // shadow flag is off. Never affects the send result.
            runCatching { l1ShadowSyncService.noteSelfSpendBroadcast() }
                .onFailure { log.warn("failed to record the self-spend marker", it) }
            logSendTxEvent(transaction, wallet)
            monitorJob.cancel()
            transaction
        } catch (ex: Exception) {
            monitorJob.cancel()
            when (ex) {
                is InsufficientMoneyException -> ex.missing?.run {
                    log.info("send failed, {} missing", toFriendlyString())
                } ?: log.info("send failed, insufficient coins")
                is IKey.KeyIsEncryptedException -> log.info("send failed, key is encrypted: {}", ex.message)
                is KeyCrypterException -> log.info("send failed, key crypter exception: {}", ex.message)
                is Wallet.CouldNotAdjustDownwards -> log.info("send failed, could not adjust downwards: {}", ex.message)
                is Wallet.CompletionException -> log.info("send failed, cannot complete: {}", ex.message)
            }
            throw ex
        }
    }

    suspend fun logSendTxEvent(
        transaction: Transaction,
        wallet: Wallet
    ) = logSendTxEvent(transaction, wallet, identityConfig, identityRepository, analyticsService)

    fun signSendRequest(sendRequest: SendRequest) {
        val wallet = walletData.wallet ?: throw RuntimeException("this method can't be used before creating the wallet")
        Context.propagate(wallet.context)

        val securityGuard = SecurityGuard.getInstance()
        val password = securityGuard.retrievePassword()
        val encryptionKey = securityFunctions.deriveKey(wallet, password)

        sendRequest.aesKey = encryptionKey
    }

    private fun checkDust(req: SendRequest): Boolean {
        if (req.tx != null) {
            for (output in req.tx.outputs) {
                if (output.isDust) return true
            }
        }
        return false
    }

    @Throws(LeftoverBalanceException::class)
    private fun checkBalanceConditions(wallet: Wallet, tx: Transaction) {
        for (output in tx.outputs) {
            try {
                if (!output.isMine(wallet)) {
                    val script = output.scriptPubKey
                    val address = script.getToAddress(
                        de.schildbach.wallet.Constants.NETWORK_PARAMETERS,
                        true
                    )
                    walletData.checkSendingConditions(address, output.value)
                    return
                }
            } catch (ignored: ScriptException) { }
        }
    }

    private fun buildOkHttpPaymentRequest(requestUrl: String): Request {
        return Request.Builder()
            .url(requestUrl)
            .cacheControl(CacheControl.Builder().noCache().build())
            .header("Accept", PaymentProtocol.MIMETYPE_PAYMENTREQUEST)
            .header("User-Agent", packageInfoProvider.httpUserAgent())
            .build()
    }

    private fun buildOkHttpDirectPayRequest(requestUrl: String, payment: Payment): Request {
        return Request.Builder()
            .url(requestUrl)
            .cacheControl(CacheControl.Builder().noCache().build())
            .header("Accept", PaymentProtocol.MIMETYPE_PAYMENTACK)
            .header("User-Agent", packageInfoProvider.httpUserAgent())
            .post(object : RequestBody() {
                override fun contentType(): MediaType? {
                    return PaymentProtocol.MIMETYPE_PAYMENT.toMediaTypeOrNull()
                }

                override fun contentLength(): Long {
                    return payment.serializedSize.toLong()
                }

                override fun writeTo(sink: BufferedSink) {
                    payment.writeTo(sink.outputStream())
                }
            })
            .build()
    }

}

/**
 * The dashj send tail's analytics (SEND_TX / SEND_TX_CONTACT with the value
 * sent, identity users only) — extracted from [SendCoinsTaskRunner] so the
 * Phase 5c.2 bridged-commit tail
 * ([de.schildbach.wallet.service.platform.sdk.SdkBridgedTransactionFactory])
 * runs the exact same hook as the dashj path.
 */
suspend fun logSendTxEvent(
    transaction: Transaction,
    wallet: Wallet,
    identityConfig: BlockchainIdentityConfig,
    identityRepository: IdentityRepository,
    analyticsService: AnalyticsService
) {
    identityConfig.get(IDENTITY_ID)?.let {
        val valueSent: Long = transaction.outputs.filter {
            !it.isMine(wallet)
        }.sumOf {
            it.value.value
        }
        val isSentToContact = try {
            identityRepository.blockchainIdentity?.getContactForTransaction(transaction) != null
        } catch (e: Exception) {
            false
        }
        analyticsService.logEvent(
            AnalyticsConstants.SendReceive.SEND_TX,
            mapOf(
                AnalyticsConstants.Parameter.VALUE to valueSent
            )
        )
        if (isSentToContact) {
            analyticsService.logEvent(
                AnalyticsConstants.SendReceive.SEND_TX_CONTACT,
                mapOf(
                    AnalyticsConstants.Parameter.VALUE to valueSent
                )
            )
        }
    }
}
