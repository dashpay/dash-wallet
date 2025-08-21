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
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.IDENTITY_ID
import de.schildbach.wallet.payments.parsers.PaymentIntentParser
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.CoinJoinService
import de.schildbach.wallet.service.MixingStatus
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
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
import org.bitcoinj.uri.BitcoinURI
import org.bitcoinj.wallet.*
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.DirectPayException
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.transactions.ByAddressCoinSelector
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.call
import org.dash.wallet.common.util.ensureSuccessful
import org.slf4j.LoggerFactory
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
    private val platformRepo: PlatformRepo,
    private val metadataProvider: TransactionMetadataProvider
) : SendPaymentService {
    companion object {
        private const val WALLET_EXCEPTION_MESSAGE = "this method can't be used before creating the wallet"
        private val log = LoggerFactory.getLogger(SendCoinsTaskRunner::class.java)
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

        val sendRequest = createSendRequest(address, amount, coinSelector, emptyWallet, canSendLockedOutput = canSendLockedOutput)
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

    override suspend fun payWithDashUrl(dashUri: String, serviceName: String?): Transaction {
        return withContext(Dispatchers.IO) {
            val paymentIntent = PaymentIntentParser.parse(dashUri, false)
            createPaymentRequest(paymentIntent, serviceName)
        }
    }

    private suspend fun createPaymentRequest(basePaymentIntent: PaymentIntent, serviceName: String?): Transaction {
        val requestUrl = basePaymentIntent.paymentRequestUrl
        if (requestUrl != null) {

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

            val paymentIntent = PaymentIntentParser.parse(byteStream, contentType)

            if (!basePaymentIntent.isExtendedBy(paymentIntent, true)) {
                log.info("BIP72 trust check failed")
                throw IllegalStateException("BIP72 trust check failed: $requestUrl")
            }

            val sendRequest = createRequestFromPaymentIntent(paymentIntent)
            return sendPayment(paymentIntent, sendRequest, serviceName)
        } else {
            val sendRequest = createRequestFromPaymentIntent(basePaymentIntent)
            val sendRequestForSigning = SendRequest.forTx(sendRequest.tx)
            return sendCoins(sendRequestForSigning, serviceName = serviceName)
        }
    }

    private fun createRequestFromPaymentIntent(paymentIntent: PaymentIntent): SendRequest {
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(de.schildbach.wallet.Constants.CONTEXT)
        var sendRequest = createSendRequest(
            false,
            paymentIntent,
            signInputs = false,
            forceEnsureMinRequiredFee = false
        )

        wallet.completeTx(sendRequest)
        if (checkDust(sendRequest)) {
            sendRequest = createSendRequest(
                false,
                paymentIntent,
                signInputs = false,
                forceEnsureMinRequiredFee = true
            )
            wallet.completeTx(sendRequest)
        }

        return sendRequest
    }

    private suspend fun sendPayment(
        finalPaymentIntent: PaymentIntent,
        sendRequest: SendRequest,
        serviceName: String?
    ): Transaction {
        log.info("creating final sendRequest({}, ..., {}", finalPaymentIntent.paymentUrl, serviceName)
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

    private suspend fun directPay(
        sendRequest: SendRequest,
        finalPaymentIntent: PaymentIntent,
        serviceName: String?
    ): Transaction {
        log.info("completing sendRequest transaction")
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        wallet.completeTx(sendRequest)
        log.info("completed sendRequest transaction")
        serviceName?.let {
            metadataProvider.setTransactionService(sendRequest.tx.txId, serviceName)
        }
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
                        wallet.commitTx(tx)
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
        val sendRequest = paymentIntent.toSendRequest()
        sendRequest.coinSelector = getCoinSelector()
        sendRequest.useInstantSend = false
        sendRequest.feePerKb = Constants.ECONOMIC_FEE
        sendRequest.ensureMinRequiredFee = forceEnsureMinRequiredFee
        sendRequest.signInputs = signInputs

        val walletBalance = wallet.getBalance(getMaxOutputCoinSelector())
        sendRequest.emptyWallet = mayEditAmount && walletBalance == paymentIntent.amount

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
        val sendRequest = SendRequest.assetLock(wallet.params, topUpKey, paymentIntent.amount)
        sendRequest.coinSelector = getCoinSelector()
        sendRequest.useInstantSend = false
        sendRequest.feePerKb = Constants.ECONOMIC_FEE
        sendRequest.ensureMinRequiredFee = forceEnsureMinRequiredFee
        sendRequest.signInputs = signInputs

        val walletBalance = wallet.getBalance(getMaxOutputCoinSelector())
        sendRequest.emptyWallet = mayEditAmount && walletBalance == paymentIntent.amount

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
            this.feePerKb = Constants.ECONOMIC_FEE
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

    private fun getCoinSelector() = if (coinJoinSend) {
        // mixed only
        CoinJoinCoinSelector(walletData.wallet)
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
        serviceName: String? = null
    ): Transaction = withContext(Dispatchers.IO) {
        val wallet = walletData.wallet ?: throw RuntimeException(WALLET_EXCEPTION_MESSAGE)
        Context.propagate(wallet.context)

        if (checkBalanceConditions) {
            checkBalanceConditions(wallet, sendRequest.tx)
        }
        signSendRequest(sendRequest)

        try {
            log.info("sending: {}", sendRequest)

            if (txCompleted) {
                wallet.commitTx(sendRequest.tx)
            } else {
                wallet.sendCoinsOffline(sendRequest)
            }

            val transaction = sendRequest.tx
            beforeSending?.accept(transaction)
            serviceName?.let {
                metadataProvider.setTransactionService(sendRequest.tx.txId, serviceName)
            }
            log.info("send successful, transaction committed: {}", transaction.txId.toString())
            walletApplication.broadcastTransaction(transaction)
            logSendTxEvent(transaction, wallet)
            transaction
        } catch (ex: Exception) {
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

    private suspend fun logSendTxEvent(
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
                platformRepo.blockchainIdentity.getContactForTransaction(transaction) != null
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
