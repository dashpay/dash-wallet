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
import android.view.View
import android.view.animation.AnimationUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.transactions.TransactionResultActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentPaymentProtocolBinding
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Transaction
import org.bitcoinj.protocols.payments.PaymentProtocolException
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.SendRequest
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.slf4j.LoggerFactory
import javax.inject.Inject

@AndroidEntryPoint
class PaymentProtocolFragment : Fragment(R.layout.fragment_payment_protocol) {

    companion object {

        private val log = LoggerFactory.getLogger(PaymentProtocolFragment::class.java)

        private const val VIEW_LOADING = 0
        private const val VIEW_PAYMENT = 1
        private const val VIEW_ERROR = 2
    }

    private var userAuthorizedDuring = false
    private val viewModel by viewModels<PaymentProtocolViewModel>()
    private val binding by viewBinding(FragmentPaymentProtocolBinding::bind)
    private val args by navArgs<PaymentProtocolFragmentArgs>()
    @Inject lateinit var config: Configuration
    @Inject lateinit var authManager: AuthenticationManager

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.viewFlipper.inAnimation = AnimationUtils.loadAnimation(context, android.R.anim.fade_in)

        val closeActivityOnClickListener = View.OnClickListener {
            requireActivity().finish()
        }
        binding.paymentRequest.closeButton.setOnClickListener(closeActivityOnClickListener)
        binding.errorView.setOnCloseClickListener(closeActivityOnClickListener)
        binding.errorView.setOnCancelClickListener(closeActivityOnClickListener)
        binding.paymentRequest.confirmPayment.setOnClickListener {
            authenticateOrConfirm()
        }

        initObservers()
        viewModel.initPaymentIntent(args.paymentIntent)
    }

    private fun authenticateOrConfirm() {
        if (userAuthorizedDuring && (!config.spendingConfirmationEnabled || viewModel.baseSendRequest == null)) {
            confirmWhenAuthorizedAndNoException()
        } else {
            val thresholdAmount = Coin.parseCoin(config.biometricLimit.toString())
            val amount = viewModel.finalPaymentIntent!!.amount
            authManager.authenticate(requireActivity(), !amount.isLessThan(thresholdAmount)) { pin ->
                pin?.let { confirmWhenAuthorizedAndNoException() }
            }
        }
    }

    private fun confirmWhenAuthorizedAndNoException() {
        if (viewModel.finalPaymentIntent!!.expired) {
            showRequestExpiredMessage()
            return
        }

        if (viewModel.baseSendRequest != null) {
            lifecycleScope.launch {
                viewModel.sendPayment()
            }
        } else {
            handleSendRequestException()
        }
    }

    private fun showRequestExpiredMessage() {
        binding.errorView.title = R.string.payment_request_expired_title
        binding.errorView.setMessage(null)
        binding.errorView.hideConfirmButton()
        binding.viewFlipper.displayedChild = VIEW_ERROR
    }

    private fun initObservers() {
        viewModel.exchangeRateData.observe(viewLifecycleOwner) {
            if (viewModel.finalPaymentIntent != null && it != null) {
                displayRequest(viewModel.finalPaymentIntent!!, null)
            }
        }
        viewModel.sendRequestLiveData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.LOADING -> {
                    binding.viewFlipper.displayedChild = VIEW_LOADING
                }
                Status.SUCCESS -> {
                    displayRequest(viewModel.finalPaymentIntent!!, it!!.data!!)
                    binding.viewFlipper.displayedChild = VIEW_PAYMENT
                }
                Status.ERROR -> {
                    if (it.exception is PaymentProtocolException.Expired) {
                        showRequestExpiredMessage()
                    } else if (viewModel.finalPaymentIntent == null) {
                        // server error
                        binding.errorView.title = R.string.payment_request_unable_to_connect
                        binding.errorView.message = R.string.payment_request_please_try_again
                        binding.errorView.details = it.message
                        binding.errorView.setOnConfirmClickListener(R.string.payment_request_try_again) {
                            viewModel.requestPaymentRequest(viewModel.basePaymentIntent)
                        }
                        binding.viewFlipper.displayedChild = VIEW_ERROR
                    } else {
                        // sendRequest creating error (eg InsufficientMoneyException)
                        displayRequest(viewModel.finalPaymentIntent!!, null)
                        binding.viewFlipper.displayedChild = VIEW_PAYMENT
                        if (userAuthorizedDuring && (viewModel.baseSendRequest == null)) {
                            handleSendRequestException()
                        }
                    }
                }
            }
        }
        viewModel.directPaymentAckLiveData.observe(viewLifecycleOwner) { ack ->
            when (ack.status) {
                Status.LOADING -> {
                    binding.viewFlipper.displayedChild = VIEW_LOADING
                }
                Status.SUCCESS -> {
                    userAuthorizedDuring = true
                    commitSendRequest(ack.data!!.first)
                }
                Status.ERROR -> {
                    if (isAdded) {
                        binding.viewFlipper.displayedChild = VIEW_ERROR
                        binding.errorView.title = R.string.payment_request_problem_title
                        binding.errorView.setMessage(ack.message)
                        binding.errorView.setOnConfirmClickListener(R.string.payment_request_try_again) {
                            viewModel.directPay(ack.data!!.first)
                        }
                        binding.errorView.setOnCancelClickListener(R.string.payment_request_skip) {
                            showTransactionResult(ack.data!!.first.tx)
                        }
                    } else {
                        showTransactionResult(ack.data!!.first.tx)
                    }
                }
            }
        }
    }

    private fun commitSendRequest(sendRequest: SendRequest) {
        lifecycleScope.launch {
            try {
                binding.viewFlipper.displayedChild = VIEW_LOADING
                val transaction = viewModel.commitAndBroadcast(sendRequest)
                showTransactionResult(transaction)
            } catch (ex: Exception) {
                binding.viewFlipper.displayedChild = VIEW_ERROR
                binding.errorView.title = R.string.payment_request_unable_to_send
                binding.errorView.message = R.string.payment_request_please_try_again
                binding.errorView.setOnConfirmClickListener(R.string.payment_request_try_again) {
                    commitSendRequest(sendRequest)
                }
            }
        }
    }

    private fun showTransactionResult(transaction: Transaction) {
        val paymentMemo = viewModel.finalPaymentIntent!!.memo
        val payeeVerifiedBy = viewModel.finalPaymentIntent!!.payeeVerifiedBy
        requireActivity().run {
            val transactionResultIntent = TransactionResultActivity.createIntent(
                this,
                intent.action,
                transaction,
                userAuthorizedDuring,
                paymentMemo,
                payeeVerifiedBy
            )
            startActivity(transactionResultIntent)
            finish()
        }
    }

    private fun handleSendRequestException() {
        val exception = viewModel.sendRequestLiveData.value!!.exception!!
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
        AdaptiveDialog.create(
            R.drawable.ic_error,
            title = getString(R.string.payment_protocol_insufficient_funds_error_title),
            message = getString(R.string.payment_protocol_insufficient_funds_error_message),
            getString(android.R.string.ok)
        ).show(requireActivity())
    }

    private fun showErrorDialog(exception: Exception) {
        AdaptiveDialog.create(
            R.drawable.ic_error,
            title = getString(R.string.payment_protocol_default_error_title),
            message = if (exception.message.isNullOrEmpty()) exception.toString() else exception.message!!,
            getString(android.R.string.ok)
        ).show(requireActivity())
    }

    private fun displayRequest(paymentIntent: PaymentIntent, sendRequest: SendRequest?) {
        val amount = paymentIntent.amount
        val amountStr = MonetaryFormat.BTC.noCode().format(amount).toString()

        val fiatAmount = viewModel.exchangeRate?.coinToFiat(amount)
        val fiatAmountStr = if (fiatAmount != null) {
            GenericUtils.fiatToString(fiatAmount)
        } else {
            getString(R.string.rate_not_available)
        }
        val txFee = if (sendRequest != null) sendRequest.tx.fee else PaymentProtocolViewModel.FAKE_FEE_FOR_EXCEPTIONS

        binding.paymentRequest.amount.inputValue.text = amountStr
        binding.paymentRequest.amount.fiatValue.text = fiatAmountStr
        binding.paymentRequest.transactionFee.text = txFee.toPlainString()
        binding.paymentRequest.totalAmount.text = amount.add(txFee).toPlainString()

        binding.paymentRequest.memo.text = paymentIntent.memo
        binding.paymentRequest.payeeSecuredBy.text = paymentIntent.payeeVerifiedBy
            ?: getString(R.string.send_coins_fragment_payee_verified_by_unknown)

        val forceMarqueeOnClickListener = View.OnClickListener {
            it.isSelected = false
            it.isSelected = true
        }
        binding.paymentRequest.payeeSecuredBy.setOnClickListener(forceMarqueeOnClickListener)
    }
}
