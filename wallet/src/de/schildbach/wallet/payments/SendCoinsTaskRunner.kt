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

import android.net.Uri
import androidx.annotation.VisibleForTesting
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.offline.DirectPaymentTask
import de.schildbach.wallet.offline.HttpDirectPaymentTask
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.ui.util.InputParser.StringInputParser
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import kotlinx.coroutines.*
import org.bitcoinj.core.*
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.protocols.payments.PaymentProtocol
import org.bitcoinj.script.ScriptException
import org.bitcoinj.wallet.*
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.transactions.ByAddressCoinSelector
import org.dash.wallet.common.util.Constants
import org.slf4j.LoggerFactory
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SendCoinsTaskRunner @Inject constructor(
    private val walletData: WalletDataProvider,
    private val walletApplication: WalletApplication,
    private val securityFunctions: SecurityFunctions
) : SendPaymentService {
    private val log = LoggerFactory.getLogger(SendCoinsTaskRunner::class.java)

    @Throws(LeftoverBalanceException::class)
    override suspend fun sendCoins(
        address: Address,
        amount: Coin,
        coinSelector: CoinSelector?,
        emptyWallet: Boolean,
        checkBalanceConditions: Boolean
    ): Transaction {
        val wallet = walletData.wallet ?: throw RuntimeException("this method can't be used before creating the wallet")
        Context.propagate(wallet.context)

        if (checkBalanceConditions && !wallet.isAddressMine(address)) {
            // This can throw LeftoverBalanceException
            walletData.checkSendingConditions(address, amount)
        }

        val sendRequest = createSendRequest(address, amount, coinSelector, emptyWallet)
        return sendCoins(sendRequest, checkBalanceConditions = false)
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun estimateNetworkFee(
        address: Address,
        amount: Coin,
        emptyWallet: Boolean
    ): SendPaymentService.TransactionDetails {
        val wallet = walletData.wallet ?: throw RuntimeException("this method can't be used before creating the wallet")
        var sendRequest = createSendRequest(address, amount, null, emptyWallet, false)
        val securityGuard = SecurityGuard()
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

    override suspend fun payWithDashUrl(dashUri: String): Transaction {
        val wallet = walletData.wallet!!
        return suspendCancellableCoroutine { coroutine ->
            object : StringInputParser(dashUri, false) {
                override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                    createPaymentRequest(coroutine, paymentIntent, wallet)
                }

                override fun handlePrivateKey(key: PrefixedChecksummedBytes) {
                    if (coroutine.isActive) {
                        coroutine.resumeWithException(UnsupportedOperationException())
                    }
                }

                @Throws(VerificationException::class)
                override fun handleDirectTransaction(transaction: Transaction) {
                    if (coroutine.isActive) {
                        coroutine.resumeWithException(UnsupportedOperationException())
                    }
                }

                override fun error(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                    if (coroutine.isActive) {
                        coroutine.resumeWithException(UnsupportedOperationException())
                    }
                }
            }.parse()
        }
    }

    private fun createHTTPSendRequest(
        coroutine: CancellableContinuation<Transaction>,
        paymentIntent: PaymentIntent,
        wallet: Wallet
    ): SendRequest? {
        if (coroutine.isActive) {
            Context.propagate(de.schildbach.wallet.Constants.CONTEXT)
            try {
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
            } catch (x: Exception) {
                x.printStackTrace()
                coroutine.resumeWithException(x)
            }
        }
        return null
    }

    private fun sendPayment(
        coroutine: CancellableContinuation<Transaction>,
        basePaymentIntent: PaymentIntent,
        finalPaymentIntent: PaymentIntent,
        sendRequest: SendRequest,
        wallet: Wallet
    ) {
        val finalSendRequest = createSendRequest(
            false,
            finalPaymentIntent,
            true,
            sendRequest.ensureMinRequiredFee
        )
        signSendRequest(finalSendRequest)
        directPay(coroutine, finalSendRequest, wallet, finalPaymentIntent)
    }

    private fun directPay(
        coroutine: CancellableContinuation<Transaction>,
        sendRequest: SendRequest,
        wallet: Wallet,
        finalPaymentIntent: PaymentIntent
    ) {
        wallet.completeTx(sendRequest)
        val refundAddress = wallet.freshAddress(KeyChain.KeyPurpose.REFUND)
        val payment = PaymentProtocol.createPaymentMessage(listOf(sendRequest.tx), finalPaymentIntent.amount, refundAddress, null, finalPaymentIntent.payeeData)

        val callback: DirectPaymentTask.ResultCallback = object : DirectPaymentTask.ResultCallback {
            override fun onResult(ack: Boolean) {
                val scope =
                    CoroutineScope(coroutine.context)
                scope.launch(Dispatchers.IO) {
                    coroutine.resume(
                        sendCoins(
                            sendRequest,
                            txCompleted = true,
                            checkBalanceConditions = true
                        )
                    )
                }
            }

            override fun onFail(messageResId: Int, vararg messageArgs: Any) {
                val message = StringBuilder().apply {
                    if (BuildConfig.DEBUG && messageArgs[0] == 415) {
                        val host = Uri.parse(finalPaymentIntent!!.paymentUrl).host
                        appendLine(host)
                        appendLine(walletApplication.getString(messageResId, *messageArgs))
                        appendLine(PaymentProtocol.MIMETYPE_PAYMENT)
                        appendLine()
                    }
                    appendLine(walletApplication.getString(R.string.payment_request_problem_message))
                }
                coroutine.resumeWithException(Exception(message.toString()))
            }
        }

        val scope =
            CoroutineScope(coroutine.context)
        scope.launch(Dispatchers.IO) {
            finalPaymentIntent.paymentUrl?.let {
                HttpDirectPaymentTask.HttpPaymentTask(
                    callback,
                    it,
                    walletApplication.httpUserAgent()
                ).send(payment)
            }
        }
    }

    fun createPaymentRequest(
        coroutine: CancellableContinuation<Transaction>,
        basePaymentIntent: PaymentIntent,
        wallet: Wallet
    ) {
        val requestCallback: RequestPaymentRequestTask.ResultCallback = object : RequestPaymentRequestTask.ResultCallback {

            override fun onPaymentIntent(paymentIntent: PaymentIntent) {
                if (basePaymentIntent.isExtendedBy(paymentIntent, true)) {
                    createHTTPSendRequest(coroutine, paymentIntent, wallet)?.let {
                        sendPayment(coroutine, basePaymentIntent, paymentIntent, it, wallet)
                    }
                } else {
                    log.info("BIP72 trust check failed")
                }
            }

            override fun onFail(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                // finalPaymentIntent = null
                if (ex != null) {
                    val errorMessage =
                        if (messageResId > 0) walletApplication.getString(messageResId, *messageArgs)
                        else ex.message!!
                    coroutine.resumeWithException(Exception(errorMessage))
                } else {
                    val errorMessage = walletApplication.getString(messageResId, *messageArgs)
                    coroutine.resumeWithException(Exception(errorMessage))
                }
            }
        }
        val scope =
            CoroutineScope(coroutine.context)
        scope.launch(Dispatchers.IO) {
            basePaymentIntent.paymentRequestUrl?.let {
                HttpRequestTask(
                    requestCallback,
                    walletApplication.httpUserAgent()
                ).requestPaymentRequest(it)
            }
        }
    }

    private fun createSendRequest(
        mayEditAmount: Boolean,
        paymentIntent: PaymentIntent,
        signInputs: Boolean,
        forceEnsureMinRequiredFee: Boolean
    ): SendRequest {
        val wallet = walletData.wallet
        paymentIntent.setInstantX(false) // to make sure the correct instance of Transaction class is used in toSendRequest() method
        val sendRequest = paymentIntent.toSendRequest()
        sendRequest.coinSelector = ZeroConfCoinSelector.get()
        sendRequest.useInstantSend = false
        sendRequest.feePerKb = Constants.ECONOMIC_FEE
        sendRequest.ensureMinRequiredFee = forceEnsureMinRequiredFee
        sendRequest.signInputs = signInputs

        val walletBalance = wallet?.getBalance(MaxOutputAmountCoinSelector())
        sendRequest.emptyWallet = mayEditAmount && walletBalance == paymentIntent.amount

        return sendRequest
    }

    @VisibleForTesting
    fun createSendRequest(
        address: Address,
        amount: Coin,
        coinSelector: CoinSelector? = null,
        emptyWallet: Boolean = false,
        forceMinFee: Boolean = true
    ): SendRequest {
        return SendRequest.to(address, amount).apply {
            this.feePerKb = Constants.ECONOMIC_FEE
            this.ensureMinRequiredFee = forceMinFee
            this.emptyWallet = emptyWallet

            val selector = coinSelector ?: ZeroConfCoinSelector.get()
            this.coinSelector = selector

            if (selector is ByAddressCoinSelector) {
                changeAddress = selector.address
            }
        }
    }

    @Throws(LeftoverBalanceException::class)
    suspend fun sendCoins(
        sendRequest: SendRequest,
        txCompleted: Boolean = false,
        checkBalanceConditions: Boolean = true
    ): Transaction = withContext(Dispatchers.IO) {
        val wallet = walletData.wallet ?: throw RuntimeException("this method can't be used before creating the wallet")
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
            log.info("send successful, transaction committed: {}", transaction.txId.toString())
            walletApplication.broadcastTransaction(transaction)
            transaction
        } catch (ex: Exception) {
            when (ex) {
                is InsufficientMoneyException -> ex.missing?.run {
                    log.info("send failed, {} missing", toFriendlyString())
                } ?: log.info("send failed, insufficient coins")
                is ECKey.KeyIsEncryptedException -> log.info("send failed, key is encrypted: {}", ex.message)
                is KeyCrypterException -> log.info("send failed, key crypter exception: {}", ex.message)
                is Wallet.CouldNotAdjustDownwards -> log.info("send failed, could not adjust downwards: {}", ex.message)
                is Wallet.CompletionException -> log.info("send failed, cannot complete: {}", ex.message)
            }
            throw ex
        }
    }

    fun signSendRequest(sendRequest: SendRequest) {
        val wallet = walletData.wallet ?: throw RuntimeException("this method can't be used before creating the wallet")

        val securityGuard = SecurityGuard()
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
}
