/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.schildbach.wallet.ui.send

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.security.SecurityGuard
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.ZeroConfCoinSelector
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.Constants
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.ui.dialogs.MinimumBalanceDialog

open class SendCoinsBaseViewModel(application: Application) : AndroidViewModel(application) {
    enum class SendCoinsOfflineStatus {
        SENDING,
        SUCCESS,
        INSUFFICIENT_MONEY,
        INVALID_ENCRYPTION_KEY,
        EMPTY_WALLET_FAILED,
        FAILURE,
        CANCELED
    }

    val walletApplication = application as WalletApplication
    val wallet = walletApplication.wallet!!

    val basePaymentIntent = MutableLiveData<Resource<PaymentIntent>>()
    val basePaymentIntentValue: PaymentIntent
        get() = basePaymentIntent.value!!.data!!
    val basePaymentIntentReady: Boolean
        get() = basePaymentIntent.value != null

    val onSendCoinsOffline = MutableLiveData<Pair<SendCoinsOfflineStatus, Any?>>()

    protected val backgroundHandler: Handler
    protected val callbackHandler: Handler

    lateinit var finalSendRequest: SendRequest

    lateinit var sentTransaction: Transaction

    init {
        val backgroundThread = HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND)
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
        callbackHandler = Looper.myLooper()?.let { Handler(it) }!!
    }

    fun createSendRequest(wallet: Wallet, mayEditAmount: Boolean, paymentIntent: PaymentIntent, signInputs: Boolean, forceEnsureMinRequiredFee: Boolean): SendRequest {

        paymentIntent.setInstantX(false) //to make sure the correct instance of Transaction class is used in toSendRequest() method
        val sendRequest = paymentIntent.toSendRequest()
        sendRequest.coinSelector = ZeroConfCoinSelector.get()
        sendRequest.useInstantSend = false
        sendRequest.feePerKb = Constants.ECONOMIC_FEE
        sendRequest.ensureMinRequiredFee = forceEnsureMinRequiredFee
        sendRequest.signInputs = signInputs

        val walletBalance = wallet.getBalance(Wallet.BalanceType.ESTIMATED)
        sendRequest.emptyWallet = mayEditAmount && walletBalance == paymentIntent.amount

        return sendRequest
    }

    fun checkDust(req: SendRequest): Boolean {
        if (req.tx != null) {
            for (output in req.tx.outputs) {
                if (output.isDust) return true
            }
        }
        return false
    }

    open fun signAndSendPayment(paymentIntent: PaymentIntent, ensureMinRequiredFee: Boolean, exchangeRate: ExchangeRate? = null, memo: String? = null) {

        onSendCoinsOffline.value = Pair(SendCoinsOfflineStatus.SENDING, null)

        val securityGuard: SecurityGuard
        try {
            securityGuard = SecurityGuard()
        } catch (e: java.lang.Exception) {
            onSendCoinsOffline.value = Pair(SendCoinsOfflineStatus.FAILURE, IllegalStateException("Unable to instantiate SecurityGuard"))
            return
        }

        object : DeriveKeyTask(backgroundHandler, walletApplication.scryptIterationsTarget()) {

            override fun onSuccess(encryptionKey: KeyParameter, wasChanged: Boolean) {
                if (wasChanged) {
                    walletApplication.backupWallet()
                }
                finalSendRequest = createSendRequest(wallet, basePaymentIntentValue.mayEditAmount(), paymentIntent, true, ensureMinRequiredFee)
                finalSendRequest.aesKey = encryptionKey
                finalSendRequest.exchangeRate = exchangeRate
                finalSendRequest.memo = memo
                signAndSendPayment(finalSendRequest)
            }

            override fun onFailure(ex: KeyCrypterException?) {
                onSendCoinsOffline.value = Pair(SendCoinsOfflineStatus.FAILURE, ex)
            }
        }.deriveKey(wallet, securityGuard.retrievePassword())
    }

    protected open fun signAndSendPayment(
        sendRequest: SendRequest,
        txAlreadyCompleted: Boolean = false,
        checkBalanceConditions: Boolean = true
    ) {

        object : SendCoinsOfflineTask(wallet, walletApplication, backgroundHandler) {

            override fun onSuccess(transaction: Transaction) {
                walletApplication.broadcastTransaction(transaction)
                sentTransaction = transaction
                onSendCoinsOffline.value = Pair(SendCoinsOfflineStatus.SUCCESS, sendRequest)
            }

            override fun onInsufficientMoney(missing: Coin) {
                onSendCoinsOffline.value = Pair(SendCoinsOfflineStatus.INSUFFICIENT_MONEY, missing)
            }

            override fun onLeftoverBalanceError(ex: LeftoverBalanceException) {
                // TODO: this viewModel should not handle UI actions like a dialog.
                // Move this to a more appropriate place once the sending flow is
                // more straightforward to support a clear retry logic.
                (walletApplication.currentActivity as? FragmentActivity)?.let {
                    MinimumBalanceDialog().show(it) { result ->
                        if (result == true) {
                            signAndSendPayment(sendRequest, txAlreadyCompleted, false)
                        } else {
                            onSendCoinsOffline.value = Pair(SendCoinsOfflineStatus.CANCELED, null)
                        }
                    }
                }
            }

            override fun onInvalidEncryptionKey() {
                onSendCoinsOffline.value = Pair(SendCoinsOfflineStatus.INVALID_ENCRYPTION_KEY, null)
            }

            override fun onEmptyWalletFailed() {
                onSendCoinsOffline.value = Pair(SendCoinsOfflineStatus.EMPTY_WALLET_FAILED, null)
            }

            override fun onFailure(exception: Exception) {
                onSendCoinsOffline.value = Pair(SendCoinsOfflineStatus.FAILURE, exception)
            }

        }.sendCoinsOffline(sendRequest, txAlreadyCompleted, checkBalanceConditions) // send asynchronously
    }

    override fun onCleared() {
        backgroundHandler.looper.quit()
        callbackHandler.removeCallbacksAndMessages(null)
        super.onCleared()
    }
}