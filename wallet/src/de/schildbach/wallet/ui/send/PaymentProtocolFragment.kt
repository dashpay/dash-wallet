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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.CheckPinDialog
import de.schildbach.wallet.ui.CheckPinSharedModel
import de.schildbach.wallet.ui.InputParser
import de.schildbach.wallet.ui.TransactionResultActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_payment_protocol.*
import kotlinx.android.synthetic.main.view_payment_request_details.*
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Transaction
import org.bitcoinj.protocols.payments.PaymentProtocolException
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.SendRequest
import org.dash.wallet.common.util.GenericUtils
import org.slf4j.LoggerFactory

class PaymentProtocolFragment : Fragment() {

    companion object {

        private val log = LoggerFactory.getLogger(PaymentProtocolFragment::class.java)

        private const val ARGS_PAYMENT_INTENT = "payment_intent"

        private const val VIEW_LOADING = 0
        private const val VIEW_PAYMENT = 1
        private const val VIEW_ERROR = 2

        @JvmStatic
        fun newInstance(paymentIntent: PaymentIntent?): Fragment {
            val args = Bundle().apply {
                putParcelable(ARGS_PAYMENT_INTENT, paymentIntent)
            }
            val fragment = PaymentProtocolFragment()
            fragment.arguments = args
            return fragment
        }
    }

    private var userAuthorizedDuring = false
    private lateinit var paymentProtocolModel: PaymentProtocolViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_payment_protocol, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view_flipper.inAnimation = AnimationUtils.loadAnimation(context, android.R.anim.fade_in)

        val closeActivityOnClickListener = View.OnClickListener {
            activity!!.finish()
        }
        close_button.setOnClickListener(closeActivityOnClickListener)
        error_view.setOnCloseClickListener(closeActivityOnClickListener)
        error_view.setOnCancelClickListener(closeActivityOnClickListener)
        confirm_payment.setOnClickListener {
            authenticateOrConfirm()
        }
    }

    private fun authenticateOrConfirm() {
        val config = paymentProtocolModel.walletApplication.configuration
        if (isUserAuthorized() && (!config.spendingConfirmationEnabled || paymentProtocolModel.baseSendRequest == null)) {
            confirmWhenAuthorizedAndNoException()
        } else {
            val thresholdAmount = Coin.parseCoin(config.biometricLimit.toString())
            val amount = paymentProtocolModel.finalPaymentIntent!!.amount
            if (amount.isLessThan(thresholdAmount)) {
                CheckPinDialog.show(activity!!, 0, false)
            } else {
                CheckPinDialog.show(activity!!, 0, true)
            }
            val checkPinSharedModel = ViewModelProviders.of(activity!!)[CheckPinSharedModel::class.java]
            checkPinSharedModel.onCorrectPinCallback.observe(viewLifecycleOwner, Observer<Pair<Int?, String?>> { (_, _) ->
                confirmWhenAuthorizedAndNoException()
            })
        }
    }

    private fun confirmWhenAuthorizedAndNoException() {
        if(paymentProtocolModel.finalPaymentIntent!!.expired) {
            showRequestExpiredMessage()
            return
        }
        if (paymentProtocolModel.baseSendRequest != null) {
            paymentProtocolModel.signAndSendPayment()
        } else {
            handleSendRequestException()
        }
    }

    private fun showRequestExpiredMessage() {
        error_view.title = R.string.payment_request_expired_title
        error_view.setMessage(null)
        error_view.hideConfirmButton()
        view_flipper.displayedChild = VIEW_ERROR
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        initModel()

        if (savedInstanceState == null) {
            val paymentIntent = arguments!!.getParcelable<PaymentIntent>(ARGS_PAYMENT_INTENT)
            paymentProtocolModel.basePaymentIntent.value = Resource.success(paymentIntent)
        }
    }

    private fun initModel() {
        paymentProtocolModel = ViewModelProviders.of(this).get(PaymentProtocolViewModel::class.java)
        paymentProtocolModel.exchangeRateData.observe(viewLifecycleOwner, Observer {})
        paymentProtocolModel.basePaymentIntent.observe(viewLifecycleOwner, Observer {
            when (it.status) {
                Status.LOADING -> {
                    view_flipper.displayedChild = VIEW_LOADING
                }
                Status.SUCCESS -> {
                    val paymentIntent: PaymentIntent = it.data!!
                    if (!paymentIntent.hasPaymentRequestUrl()) {
                        throw UnsupportedOperationException(PaymentProtocolFragment::class.java.simpleName
                                + "class should be used to handle Payment requests (BIP70 and BIP270)")
                    }
                    when {
                        paymentIntent.isHttpPaymentRequestUrl -> {
                            paymentProtocolModel.requestPaymentRequest(paymentIntent)
                        }
                        paymentIntent.isBluetoothPaymentRequestUrl -> {
                            log.warn("PaymentRequest via Bluetooth is not supported anymore")
                            throw UnsupportedOperationException(SendCoinsFragment::class.java.simpleName
                                    + "class should be used to handle this type of payment $paymentIntent")
                        }
                        else -> {
                            log.warn("Incorrect payment type $paymentIntent")
                            throw UnsupportedOperationException(SendCoinsFragment::class.java.simpleName
                                    + "class should be used to handle this type of payment $paymentIntent")

                        }
                    }
                }
                Status.ERROR -> {
                    InputParser.dialog(activity, { _, _ -> activity!!.finish() }, 0, it.message!!)
                }
            }
        })
        paymentProtocolModel.sendRequestLiveData.observe(viewLifecycleOwner, Observer {
            when (it.status) {
                Status.LOADING -> {
                    view_flipper.displayedChild = VIEW_LOADING
                }
                Status.SUCCESS -> {
                    displayRequest(paymentProtocolModel.finalPaymentIntent!!, it!!.data!!)
                    view_flipper.displayedChild = VIEW_PAYMENT
                }
                Status.ERROR -> {
                    if (it.exception is PaymentProtocolException.Expired) {
                        showRequestExpiredMessage()
                    } else if (paymentProtocolModel.finalPaymentIntent == null) {
                        // server error
                        error_view.title = R.string.payment_request_unable_to_connect
                        error_view.message = R.string.payment_request_please_try_again
                        error_view.details = it.message
                        error_view.setOnConfirmClickListener(R.string.payment_request_try_again, View.OnClickListener {
                            paymentProtocolModel.requestPaymentRequest(paymentProtocolModel.basePaymentIntentValue)
                        })
                        view_flipper.displayedChild = VIEW_ERROR
                    } else {
                        // sendRequest creating error (eg InsufficientMoneyException)
                        displayRequest(paymentProtocolModel.finalPaymentIntent!!, null)
                        view_flipper.displayedChild = VIEW_PAYMENT
                        if (isUserAuthorized() && (paymentProtocolModel.baseSendRequest == null)) {
                            handleSendRequestException()
                        }
                    }
                }
            }
        })
        paymentProtocolModel.directPaymentAckLiveData.observe(viewLifecycleOwner, Observer {
            when (it.status) {
                Status.LOADING -> {
                    view_flipper.displayedChild = VIEW_LOADING
                }
                Status.SUCCESS -> {
                    userAuthorizedDuring = true
                    paymentProtocolModel.commitAndBroadcast(it.data!!.first)
                }
                Status.ERROR -> {
                    if (isAdded) {
                        view_flipper.displayedChild = VIEW_ERROR
                        error_view.title = R.string.payment_request_problem_title
                        error_view.setMessage(it.message)
                        error_view.setOnConfirmClickListener(R.string.payment_request_try_again, View.OnClickListener { _ ->
                            paymentProtocolModel.directPay(it.data!!.first)
                        })
                        error_view.setOnCancelClickListener(R.string.payment_request_skip, View.OnClickListener { _ ->
                            showTransactionResult(it.data!!.first.tx)
                        })
                    } else {
                        showTransactionResult(it.data!!.first.tx)
                    }
                }
            }
        })
        paymentProtocolModel.onSendCoinsOffline.observe(viewLifecycleOwner,
                Observer<Pair<SendCoinsBaseViewModel.SendCoinsOfflineStatus, Any?>> { (status, data) ->
                    when (status) {
                        SendCoinsBaseViewModel.SendCoinsOfflineStatus.SENDING -> {
                            view_flipper.displayedChild = VIEW_LOADING
                        }
                        SendCoinsBaseViewModel.SendCoinsOfflineStatus.SUCCESS -> {
                            showTransactionResult((data as SendRequest).tx)
                        }
                        else -> {
                            view_flipper.displayedChild = VIEW_ERROR
                            error_view.title = R.string.payment_request_unable_to_send
                            error_view.message = R.string.payment_request_please_try_again
                            error_view.setOnConfirmClickListener(R.string.payment_request_try_again, View.OnClickListener {
                                paymentProtocolModel.commitAndBroadcast(data as SendRequest)
                            })
                        }
                    }
                })
    }

    private fun showTransactionResult(transaction: Transaction) {
        val paymentMemo = paymentProtocolModel.finalPaymentIntent!!.memo
        val payeeVerifiedBy = paymentProtocolModel.finalPaymentIntent!!.payeeVerifiedBy
        activity!!.run {
            val transactionResultIntent = TransactionResultActivity.createIntent(
                    this, intent.action, transaction, isUserAuthorized(), paymentMemo, payeeVerifiedBy)
            startActivity(transactionResultIntent)
            finish()
        }
    }

    private fun handleSendRequestException() {
        val exception = paymentProtocolModel.sendRequestLiveData.value!!.exception!!
        log.error("unable to handle payment request $exception")
        when (exception) {
            is InsufficientMoneyException -> {
                showInsufficientMoneyDialog()
            }
            else -> {
                showErrorDialog(exception)
            }
        }
    }

    private fun showInsufficientMoneyDialog() {
        val dialogBuilder = AlertDialog.Builder(context!!)
        dialogBuilder.setTitle(R.string.payment_protocol_insufficient_funds_error_title)
        dialogBuilder.setMessage(R.string.payment_protocol_insufficient_funds_error_message)
        dialogBuilder.setPositiveButton(android.R.string.ok, null)
        dialogBuilder.create().show()
    }

    private fun showErrorDialog(exception: Exception) {
        val dialogBuilder = AlertDialog.Builder(context!!)
        dialogBuilder.setTitle(R.string.payment_protocol_default_error_title)
        if (exception.message != null) {
            dialogBuilder.setMessage(exception.message)
        } else {
            dialogBuilder.setMessage(exception.toString())
        }
        dialogBuilder.setPositiveButton(android.R.string.ok, null)
        dialogBuilder.create().show()
    }

    private fun displayRequest(paymentIntent: PaymentIntent, sendRequest: SendRequest?) {

        val amount = paymentIntent.amount
        val amountStr = MonetaryFormat.BTC.noCode().format(amount).toString()

        val fiatAmount = paymentProtocolModel.exchangeRate?.coinToFiat(amount)
        val fiatAmountStr: String
        val fiatSymbol: String
        if (fiatAmount != null) {
            fiatAmountStr = Constants.LOCAL_FORMAT.format(fiatAmount).toString()
            fiatSymbol = GenericUtils.currencySymbol(fiatAmount.currencyCode)
        } else {
            fiatAmountStr = getString(R.string.transaction_row_rate_not_available)
            fiatSymbol = ""
        }
        val txFee = if (sendRequest != null) sendRequest.tx.fee else PaymentProtocolViewModel.FAKE_FEE_FOR_EXCEPTIONS

        input_value.text = amountStr
        fiat_value.text = fiatAmountStr
        fiat_symbol.text = fiatSymbol
        transaction_fee.text = txFee.toPlainString()
        total_amount.text = amount.add(txFee).toPlainString()

        memo.text = paymentIntent.memo
        payee_secured_by.text = paymentIntent.payeeVerifiedBy
                ?: getString(R.string.send_coins_fragment_payee_verified_by_unknown)

        val forceMarqueeOnClickListener = View.OnClickListener {
            it.isSelected = false
            it.isSelected = true
        }
        payee_secured_by.setOnClickListener(forceMarqueeOnClickListener)
    }

    private fun isUserAuthorized(): Boolean {
        return (activity as SendCoinsActivity).isUserAuthorized || userAuthorizedDuring
    }
}