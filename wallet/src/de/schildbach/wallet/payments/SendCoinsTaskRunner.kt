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
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.IDENTITY_ID
import org.dash.wallet.common.data.PaymentIntent
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.CoinJoinService
import de.schildbach.wallet.service.MixingStatus
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.util.AnrException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.CacheControl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.IOException
import org.bitcoin.protocols.payments.Protos
import org.bitcoin.protocols.payments.Protos.Payment
import org.bitcoinj.coinjoin.CoinJoinCoinSelector
import org.bitcoinj.core.*
import org.bitcoinj.crypto.IKey
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.protocols.payments.PaymentProtocol
import org.bitcoinj.protocols.payments.PaymentProtocolException.InvalidPaymentRequestURL
import org.bitcoinj.script.ScriptException
import org.bitcoinj.wallet.*
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.payments.parsers.DashPaymentIntentParser
import org.dash.wallet.common.services.DirectPayException
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.PaymentRecoveryMetadata
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import org.dash.wallet.common.transactions.ByAddressCoinSelector
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.call
import org.dash.wallet.common.util.ensureSuccessful
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.function.Consumer
import java.util.function.Predicate
import javax.inject.Inject

class SendCoinsTaskRunner @Inject constructor(
    private val walletData: WalletDataProvider,
    private val walletApplication: WalletApplication,
    private val securityFunctions: SecurityFunctions,
    private val packageInfoProvider: PackageInfoProvider,
    private val analyticsService: AnalyticsService,
    private val identityConfig: BlockchainIdentityConfig,
    coinJoinConfig: CoinJoinConfig,
    coinJoinService: CoinJoinService,
    private val identityRepository: IdentityRepository,
    private val platformRepo: PlatformRepo,
    private val metadataProvider: TransactionMetadataProvider,
    private val pendingPaymentVerifier: PendingDirectPaymentVerifier
) : SendPaymentService {
    companion object {
        private const val WALLET_EXCEPTION_MESSAGE = "this method can't be used before creating the wallet"
        private val MAX_NO_CHANGE_FEE = Coin.valueOf(10_0000).multiply(2) // 0.002 DASH
        private val log = LoggerFactory.getLogger(SendCoinsTaskRunner::class.java)
        /** How long a BIP70 payment waits for network evidence after an ambiguous HTTP failure. */
        private const val DEFAULT_AMBIGUOUS_SUBMISSION_WAIT_MS = 30_000L
    }

    @VisibleForTesting
    internal var ambiguousSubmissionWaitMs = DEFAULT_AMBIGUOUS_SUBMISSION_WAIT_MS

    /**
     * Client for the BIP70 payment POST. Connection-failure retries are off on purpose: OkHttp
     * would otherwise re-send the payment message on a fresh connection after a failure that may
     * already have reached the merchant, and report only the last (often unrelated, e.g. DNS)
     * failure. Seeing the first failure is what lets [isDefinitelyNotSubmitted] tell a request
     * that never left the device from one whose result is unknown.
     */
    private val directPayHttpClient by lazy {
        Constants.HTTP_CLIENT.newBuilder().retryOnConnectionFailure(false).build()
    }
    private var coinJoinSend = false
    private var coinJoinMode = CoinJoinMode.NONE
    private var coinJoinMixingState = MixingStatus.NOT_STARTED
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    init {
        coinJoinConfig
            .observeMode()
            .filterNotNull()
            .onEach { mode ->
                coinJoinMode = mode
                updateCoinJoinSend()
            }
            .launchIn(coroutineScope)
        coinJoinService
            .observeMixingState()
            .onEach { mixingState ->
                coinJoinMixingState = mixingState
                updateCoinJoinSend()
            }
            .launchIn(coroutineScope)
    }

    // use CoinJoin mode of Sending if CoinJoin is not OFF [CoinJoinMode.NONE]
    // and is not finishing [MixingStatus.FINISHING]
    private fun updateCoinJoinSend() {
        coinJoinSend = coinJoinMode != CoinJoinMode.NONE && coinJoinMixingState != MixingStatus.FINISHING
    }

    private val paymentIntentParser = DashPaymentIntentParser(NETWORK_PARAMETERS)

    /**
     * Waits until the inputs of any unresolved payment are protected again.
     *
     * Callers that build a transaction themselves must await this before doing so. The barriers
     * inside this class guard the commit, which is too late: coin selection happens in
     * createSendRequest, and locking an outpoint afterwards does not remove it from a transaction
     * that already chose it.
     */
    suspend fun awaitPaymentReadiness() = pendingPaymentVerifier.awaitRestored()

    /**
     * The transaction of an earlier submission for this same payment request whose outcome is
     * still unknown, or null if there is none.
     *
     * [awaitPaymentReadiness] and this answer different questions. That one stops a new payment
     * spending an uncertain one's outpoints; this one stops it being a second payment for the same
     * invoice, which restored locks do nothing about when the wallet has other funds to draw on.
     */
    suspend fun findUnresolvedSubmission(paymentRequestHash: ByteArray?): Sha256Hash? =
        pendingPaymentVerifier.unresolvedSubmissionFor(paymentRequestHash?.let { Constants.HEX.encode(it) })

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
        // Coin selection below must not be able to pick an outpoint reserved by an uncertain
        // payment whose locks have not been restored since the last process death.
        pendingPaymentVerifier.awaitRestored()
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)

        if (checkBalanceConditions && !wallet.isAddressMine(address)) {
            // This can throw LeftoverBalanceException
            walletData.checkSendingConditions(address, amount)
        }

        val sendRequest =
            createSendRequest(address, amount, coinSelector, emptyWallet, canSendLockedOutput = canSendLockedOutput)
        return sendCoins(
            sendRequest,
            checkBalanceConditions = false,
            beforeSending = beforeSending
        )
    }

    override suspend fun estimateNetworkFee(
        address: Address,
        amount: Coin,
        emptyWallet: Boolean
    ): SendPaymentService.TransactionDetails {
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

        return SendPaymentService.TransactionDetails(txFee?.toPlainString() ?: "", amountToSend, totalAmount)
    }

    override suspend fun payWithDashUrl(
        dashUri: String,
        serviceName: String?,
        recovery: PaymentRecoveryMetadata?,
        onTransactionCreated: (suspend (Sha256Hash) -> Unit)?
    ): Transaction =
        withContext(Dispatchers.IO) {
            pendingPaymentVerifier.awaitRestored()
            val paymentIntent = paymentIntentParser.parse(dashUri, false)
            createPaymentRequest(paymentIntent, serviceName, recovery, onTransactionCreated)
        }

    override suspend fun completeTransaction(sendRequest: SendRequest) {
        pendingPaymentVerifier.awaitRestored()
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
        pendingPaymentVerifier.awaitRestored()
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

        if (!basePaymentIntent.isExtendedBy(paymentIntent, true, NETWORK_PARAMETERS)) {
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
     * @throws PaymentSubmissionPendingException if the submission result is unknown, which now
     *   includes an explicit nack: it is answered only after delivery, so it says nothing about
     *   whether the payee kept the transaction
     * @throws IOException if the HTTP request fails before it could have reached the merchant
     */
    suspend fun sendDirectPayment(
        sendRequest: SendRequest,
        paymentIntent: PaymentIntent,
        serviceName: String? = null,
        recovery: PaymentRecoveryMetadata? = null
    ): Transaction = withContext(Dispatchers.IO) {
        pendingPaymentVerifier.awaitRestored()
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)

        signSendRequest(sendRequest)
        directPay(sendRequest, paymentIntent, serviceName, recovery)
    }

    private suspend fun createPaymentRequest(
        basePaymentIntent: PaymentIntent,
        serviceName: String?,
        recovery: PaymentRecoveryMetadata? = null,
        onTransactionCreated: (suspend (Sha256Hash) -> Unit)? = null
    ): Transaction {
        val requestUrl = basePaymentIntent.paymentRequestUrl
        if (requestUrl != null) {
            val paymentIntent = fetchPaymentRequest(basePaymentIntent)
            val sendRequest = createRequestFromPaymentIntent(paymentIntent)
            return sendPayment(paymentIntent, sendRequest, serviceName, recovery, onTransactionCreated)
        } else {
            val sendRequest = createRequestFromPaymentIntent(basePaymentIntent)
            val sendRequestForSigning = createSendRequest(
                false,
                basePaymentIntent,
                true,
                sendRequest.ensureMinRequiredFee
            )
            // Same ordering as the BIP70 branch: complete and sign locally so the transaction id
            // is final, let the caller record its order, and only then commit and broadcast.
            // Recording afterwards would mean a failed insert arriving when the payment has
            // already gone out, which sends callers down their failure path and leaves an
            // already-paid order with nothing durable behind it.
            val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
            Context.propagate(wallet.context)
            signSendRequest(sendRequestForSigning)
            wallet.completeTx(sendRequestForSigning)
            onTransactionCreated?.invoke(sendRequestForSigning.tx.txId)
            return sendCoins(sendRequestForSigning, txCompleted = true, serviceName = serviceName)
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
        serviceName: String?,
        recovery: PaymentRecoveryMetadata? = null,
        onTransactionCreated: (suspend (Sha256Hash) -> Unit)? = null
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
        return directPay(finalSendRequest, finalPaymentIntent, serviceName, recovery, onTransactionCreated)
    }

    /**
     * Completes and submits a direct payment via BIP70/BIP270 protocol.
     * This method completes the transaction, sends it via HTTP to the payment URL,
     * and handles the payment acknowledgment.
     *
     * The whole submission runs in a [NonCancellable] context: once the payment message may have
     * left the device, cancelling the caller (screen lock, activity recreation) must not abandon
     * the transaction in an unknown state.
     *
     * If the HTTP request fails after it may have reached the merchant, the signed transaction is
     * handed to [PendingDirectPaymentVerifier]: its inputs are locked and the network is watched
     * for it. If it shows up within [ambiguousSubmissionWaitMs] it is returned as sent; otherwise
     * [PaymentSubmissionPendingException] is thrown and verification continues in the background.
     *
     * @param sendRequest The send request (should already be created via createSendRequest)
     * @param finalPaymentIntent The payment intent containing the payment URL
     * @param serviceName Optional service name for transaction metadata
     * @return The committed transaction
     * @throws PaymentSubmissionPendingException if the submission result is unknown, a nack
     *   included
     * @throws IOException if the HTTP request fails before it could have reached the merchant
     */
    private suspend fun directPay(
        sendRequest: SendRequest,
        finalPaymentIntent: PaymentIntent,
        serviceName: String?,
        recovery: PaymentRecoveryMetadata? = null,
        onTransactionCreated: (suspend (Sha256Hash) -> Unit)? = null
    ): Transaction = withContext(NonCancellable) {
        log.info("completing sendRequest transaction")
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)
        wallet.completeTx(sendRequest)
        log.info("completed sendRequest transaction")
        serviceName?.let {
            metadataProvider.setTransactionService(sendRequest.tx.txId, serviceName)
        }
        // The transaction id is final here and nothing has been sent yet, so this is the last
        // point at which a caller can still record what it will need to recover the payment.
        // Everything after this can outlive the process: the payee may receive the transaction
        // even if we are killed before we learn the outcome.
        onTransactionCreated?.invoke(sendRequest.tx.txId)
        val refundAddress = wallet.freshAddress(KeyChain.KeyPurpose.REFUND)
        val payment = PaymentProtocol.createPaymentMessage(
            listOf(sendRequest.tx),
            finalPaymentIntent.amount,
            refundAddress,
            null,
            finalPaymentIntent.payeeData
        )

        val requestUrl = finalPaymentIntent.paymentUrl
            ?: throw InvalidPaymentRequestURL("Final payment intent URL is null")
        log.info("trying to send tx to {}", requestUrl)
        val timer = AnalyticsTimer(analyticsService, log, AnalyticsConstants.Process.PROCESS_BIP7O_SEND_PAYMENT)
        val request = buildOkHttpDirectPayRequest(requestUrl, payment)

        // Quarantine before the POST, not after a failure. From the moment the request leaves,
        // the payee may receive and broadcast this transaction, and the input locks live only in
        // memory. A process death mid-flight would otherwise restart with the order recorded but
        // no pending payment, no locks and nothing for resume() to find, leaving the same inputs
        // free to fund a retry of a payment that already went through.
        val verification = pendingPaymentVerifier.quarantine(
            sendRequest.tx,
            requestUrl,
            serviceName,
            recovery,
            finalPaymentIntent.paymentRequestHash?.let { Constants.HEX.encode(it) },
            origin = wallet
        )

        try {
            val response = directPayHttpClient.call(request)
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
                // A nack is not a refusal we can act on. It is reached only after the request
                // arrived and the payee answered, so by then it has had the transaction and may
                // have broadcast it whatever the memo says; the memo itself is a free-text field
                // the payee chooses. Treating it as proof of non-receipt used to free the inputs
                // and send callers into cleanup that deletes a gift card order, so a payee that
                // nacked and relayed anyway left a paid purchase with nothing to redeem. Falls
                // through to the ambiguous handling below, which is what an unanswered submission
                // gets and what this is.
                throw DirectPayException("Payment was not acknowledged by the server")
            }
        } catch (e: Exception) {
            val tx = sendRequest.tx

            if (isDefinitelyNotSubmitted(e)) {
                log.warn("Payment submission failed before the request could be sent: ${tx.txId}", e)
                pendingPaymentVerifier.cancelQuarantine(tx)
                throw e
            }

            log.warn("Payment submission failed, but transaction may have been sent: ${tx.txId}", e)
            // Leave the quarantine standing: the payee may have received the payment and
            // broadcast it. The verifier is application-scoped and has the transaction on disk,
            // so it keeps going after this call, the purchase screen and even the process.
            val result = withTimeoutOrNull(ambiguousSubmissionWaitMs) { verification.await() }

            if (result != null) {
                log.info("Transaction found on network despite HTTP failure: ${tx.txId}")
                return@withContext result
            }

            // Deliberately not distinguishing "verification finished without confirmation" from
            // "still watching". Finishing means the inputs were released, or the retention
            // horizon passed, and neither proves the payee never received the transaction: it
            // chooses when to relay. Reporting a failure here sends callers into cleanup that
            // deletes the order rows the verifier keeps on purpose at watch expiry, and a later
            // broadcast then leaves a debit with nothing to redeem against.
            log.warn("Transaction ${tx.txId} not confirmed on the network, reporting it as pending")
            throw PaymentSubmissionPendingException(tx.txId, e)
        }

        // Acknowledged, so the payee has the payment whatever happens from here - but "here" may
        // belong to a different wallet than the one that signed. This whole function is
        // NonCancellable and the wipe listener drains only the verifier's watches, so an answer
        // that arrives late runs on regardless, and by then a wipe may have installed a
        // replacement that passes the readiness barrier precisely because the wipe emptied the
        // pending store. maybeCommitTx does not check whose transaction it is given.
        val sent = try {
            sendCoins(sendRequest, txCompleted = true, checkBalanceConditions = true, originWallet = wallet)
        } catch (e: Exception) {
            // Committing locally can still fail, and several of those failures land before
            // maybeCommitTx: a leftover-balance check, verification, the database. The payment is
            // out there regardless, so this is not a failure to report as one. Reporting it as
            // such would send callers down their cleanup path and delete the order they recorded
            // before sending, leaving the watch to commit an acknowledged purchase with nothing
            // to redeem against. Leave the quarantine standing and call it what it is: submitted,
            // outcome not yet reflected locally.
            log.error("payment was acknowledged but could not be committed locally: {}", sendRequest.tx.txId, e)
            throw PaymentSubmissionPendingException(sendRequest.tx.txId, e)
        }

        // Committed, so the outcome is certain. Apply the recovery metadata first: the
        // transaction is in the wallet only now, so this is the earliest the provider can be
        // written, and the quarantine record is the only durable copy of it. Cancelling first
        // would leave a process death here with a committed payment, a recorded order and no way
        // to attribute it, which is exactly what makes an order unretrievable.
        try {
            // Same wallet, same reason: attribution would write metadata for a transaction the
            // replacement does not hold, and cancelling would free outpoints and drop records
            // belonging to a store the wipe has already emptied.
            if (!pendingPaymentVerifier.stillOwnedBy(wallet)) {
                throw IllegalStateException("the wallet that sent ${sendRequest.tx.txId} has been wiped")
            }
            applyGiftCardRecoveryMetadata(sendRequest.tx.txId, serviceName, recovery)
            pendingPaymentVerifier.cancelQuarantine(sendRequest.tx)
        } catch (e: Exception) {
            // Keep the quarantine: it still holds what is needed to attribute this later.
            log.error("could not attribute committed payment {}, keeping its recovery record", sendRequest.tx.txId, e)
        }
        sent
    }

    /**
     * Records the provider and merchant icon of a gift card purchase now that its transaction is
     * in the wallet. Harmless to repeat: the purchase screen writes the same thing on its success
     * path, and doing it here means it survives a process death before that runs.
     */
    private suspend fun applyGiftCardRecoveryMetadata(
        txId: Sha256Hash,
        serviceName: String?,
        recovery: PaymentRecoveryMetadata?
    ) {
        if (recovery?.isGiftCardPurchase == true && serviceName != null) {
            metadataProvider.markGiftCardTransaction(txId, serviceName, recovery.merchantIconUrl)
        }
    }

    /**
     * True when the failure happened before any bytes could reach the server (DNS or TCP connect
     * failure), so the merchant definitely did not receive the payment. Anything else - a
     * dropped connection, a read timeout, a reset HTTP/2 stream - may have been processed by the
     * merchant and is treated as ambiguous. Relies on [directPayHttpClient] not retrying, so the
     * exception describes the only attempt made.
     */
    private fun isDefinitelyNotSubmitted(e: Exception): Boolean {
        return e is UnknownHostException || e is ConnectException || e is NoRouteToHostException
    }

    fun createSendRequest(
        mayEditAmount: Boolean,
        paymentIntent: PaymentIntent,
        signInputs: Boolean,
        forceEnsureMinRequiredFee: Boolean,
        useCoinJoinGreedy: Boolean
    ): SendRequest {
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)
        val sendRequest = paymentIntent.toSendRequest(NETWORK_PARAMETERS)
        sendRequest.coinSelector = getCoinSelector(useCoinJoinGreedy)
        sendRequest.useInstantSend = false
        sendRequest.feePerKb = Constants.ECONOMIC_FEE
        sendRequest.ensureMinRequiredFee = forceEnsureMinRequiredFee
        sendRequest.signInputs = signInputs
        val walletBalance = wallet.getBalance(getMaxOutputCoinSelector())
        sendRequest.emptyWallet = mayEditAmount && walletBalance == paymentIntent.amount
        if (!sendRequest.emptyWallet && useCoinJoinGreedy && coinJoinSend) {
            sendRequest.returnChange = false
        }

        return sendRequest
    }

    fun createSendRequest(
        mayEditAmount: Boolean,
        paymentIntent: PaymentIntent,
        signInputs: Boolean,
        forceEnsureMinRequiredFee: Boolean
    ): SendRequest {
        val firstSendRequest = createSendRequest(
            mayEditAmount,
            paymentIntent,
            signInputs = true,
            forceEnsureMinRequiredFee,
            useCoinJoinGreedy = coinJoinSend
        )
        signSendRequest(firstSendRequest)
        walletData.wallet!!.completeTx(firstSendRequest)

        // check for dust
        val secondSendRequest = if (checkDust(firstSendRequest)) {
            val sendRequest = createSendRequest(
                false,
                paymentIntent,
                signInputs = false,
                forceEnsureMinRequiredFee = true,
                useCoinJoinGreedy = coinJoinSend
            )
            signSendRequest(sendRequest)
            walletData.wallet!!.completeTx(sendRequest)
            sendRequest
        } else {
            firstSendRequest
        }

        // check for high fees when using coinjoin/greedy
        return if (isFeeTooHigh(secondSendRequest.tx)) {
            log.info("fee was found to be too high: {}", secondSendRequest.tx.fee)
            createSendRequest(
                mayEditAmount,
                paymentIntent,
                signInputs,
                forceEnsureMinRequiredFee,
                useCoinJoinGreedy = false
            )
        } else {
            createSendRequest(
                mayEditAmount,
                paymentIntent,
                signInputs,
                forceEnsureMinRequiredFee,
                useCoinJoinGreedy = coinJoinSend
            )
        }
    }

    fun createAssetLockSendRequest(
        mayEditAmount: Boolean,
        paymentIntent: PaymentIntent,
        signInputs: Boolean,
        forceEnsureMinRequiredFee: Boolean,
        topUpKey: ECKey,
        useCoinJoinGreedy: Boolean = true
    ): SendRequest {
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)
        val sendRequest = SendRequest.assetLock(wallet.params, topUpKey, paymentIntent.amount)
        sendRequest.coinSelector = getCoinSelector(useCoinJoinGreedy)
        sendRequest.useInstantSend = false
        sendRequest.feePerKb = Constants.ECONOMIC_FEE
        sendRequest.ensureMinRequiredFee = forceEnsureMinRequiredFee
        sendRequest.signInputs = signInputs
        val walletBalance = wallet.getBalance(getMaxOutputCoinSelector())
        sendRequest.emptyWallet = mayEditAmount && walletBalance == paymentIntent.amount
        if (!sendRequest.emptyWallet && useCoinJoinGreedy && coinJoinSend) {
            sendRequest.returnChange = false
        }

        return sendRequest
    }

    fun createAssetLockSendRequest(
        mayEditAmount: Boolean,
        paymentIntent: PaymentIntent,
        signInputs: Boolean,
        forceEnsureMinRequiredFee: Boolean,
        topUpKey: ECKey
    ): SendRequest {
        val firstSendRequest = createAssetLockSendRequest(
            mayEditAmount,
            paymentIntent,
            signInputs = true,
            forceEnsureMinRequiredFee,
            topUpKey,
            useCoinJoinGreedy = coinJoinSend
        )
        signSendRequest(firstSendRequest)
        walletData.wallet!!.completeTx(firstSendRequest)

        // check for dust
        val secondSendRequest = if (checkDust(firstSendRequest)) {
            val sendRequest = createAssetLockSendRequest(
                false,
                paymentIntent,
                signInputs = false,
                forceEnsureMinRequiredFee = true,
                topUpKey,
                useCoinJoinGreedy = coinJoinSend
            )
            signSendRequest(sendRequest)
            walletData.wallet!!.completeTx(sendRequest)
            sendRequest
        } else {
            firstSendRequest
        }

        // check for high fees when using coinjoin/greedy
        return if (isFeeTooHigh(secondSendRequest.tx)) {
            createAssetLockSendRequest(
                mayEditAmount,
                paymentIntent,
                signInputs,
                forceEnsureMinRequiredFee,
                topUpKey,
                useCoinJoinGreedy = false
            )
        } else {
            createAssetLockSendRequest(
                mayEditAmount,
                paymentIntent,
                signInputs,
                forceEnsureMinRequiredFee,
                topUpKey,
                useCoinJoinGreedy = coinJoinSend
            )
        }
    }

    @VisibleForTesting
    fun createSendRequest(
        address: Address,
        amount: Coin,
        coinSelector: CoinSelector? = null,
        emptyWallet: Boolean = false,
        forceMinFee: Boolean = true,
        canSendLockedOutput: Predicate<TransactionOutput>? = null,
        useCoinJoinGreedy: Boolean = true
    ): SendRequest {
        return SendRequest.to(address, amount).apply {
            this.feePerKb = Constants.ECONOMIC_FEE
            this.ensureMinRequiredFee = forceMinFee
            this.emptyWallet = emptyWallet

            val selector = coinSelector ?: getCoinSelector(useCoinJoinGreedy)
            this.canUseLockedOutputPredicate = canSendLockedOutput
            this.coinSelector = selector

            if (selector is ByAddressCoinSelector) {
                changeAddress = selector.address
            }
        }
    }

    private fun getCoinSelector(useCoinJoinGreedy: Boolean) = if (coinJoinSend) {
        // mixed only
        CoinJoinCoinSelector(walletData.wallet, false, useCoinJoinGreedy)
    } else {
        // collect all coins, mixed and unmixed
        ZeroConfCoinSelector.get()
    }

    private fun getMaxOutputCoinSelector() = if (coinJoinSend) {
        // mixed only
        MaxOutputAmountCoinJoinCoinSelector(walletData.wallet!!)
    } else {
        // collect all coins, mixed and unmixed
        MaxOutputAmountCoinSelector()
    }

    @Throws(LeftoverBalanceException::class)
    suspend fun sendCoins(
        sendRequest: SendRequest,
        txCompleted: Boolean = false,
        checkBalanceConditions: Boolean = true,
        beforeSending: Consumer<Transaction>? = null,
        serviceName: String? = null,
        originWallet: Wallet? = null
    ): Transaction = withContext(Dispatchers.IO) {
        // Callers may have built this request before restoration finished, so wait here too.
        pendingPaymentVerifier.awaitRestored()
        // A caller that signed against a particular wallet passes it, and this commits to that one
        // rather than to whatever is installed by the time we get here. The two differ only after
        // a wipe, and then committing is the harm: maybeCommitTx does not ask whether the
        // transaction belongs to the wallet it is handed, so the old wallet's payment would be
        // imported into the replacement and broadcast from it, without needing any of its keys.
        if (originWallet != null && !pendingPaymentVerifier.stillOwnedBy(originWallet)) {
            throw IllegalStateException("the wallet that signed ${sendRequest.tx.txId} has been wiped")
        }
        val wallet = originWallet ?: walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)
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
                metadataProvider.setTransactionService(sendRequest.tx.txId, serviceName)
            }
            log.info("send successful, transaction committed in {}: {} ", watch, transaction.txId.toString())
            log.info("  transaction: {}", transaction.toStringHex())
            walletApplication.broadcastTransaction(transaction)
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
                /**
                 * One-shot, so this payment is written to the wire at most once.
                 *
                 * retryOnConnectionFailure(false) is not enough on its own: OkHttp still follows
                 * up at the HTTP level, and a 503 with Retry-After: 0 replays the request without
                 * consulting that setting. An endpoint could take delivery of the payment, answer
                 * 503 and then refuse the follow-up connection, leaving us holding a
                 * ConnectException that looks like it never went out. A one-shot body makes
                 * RetryAndFollowUpInterceptor return the 503 instead of replaying, so the failure
                 * we classify is the one that actually happened.
                 */
                override fun isOneShot(): Boolean = true

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

    override fun isFeeTooHigh(tx: Transaction): Boolean {
        return if (coinJoinSend) {
            tx.fee > MAX_NO_CHANGE_FEE
        } else {
            false
        }
    }
}
