/*
 * Copyright 2019 Dash Core Group
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
import android.text.TextUtils
import android.view.View
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogConfirmTransactionBinding
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@AndroidEntryPoint
class ConfirmTransactionDialog(
    private val onTransactionConfirmed: ((Boolean) -> Unit)? = null
) : OffsetDialogFragment(R.layout.dialog_confirm_transaction) {

    companion object {
        private val TAG = ConfirmTransactionDialog::class.java.simpleName
        private const val ARG_ADDRESS = "arg_address"
        private const val ARG_AMOUNT = "arg_amount"
        private const val ARG_EXCHANGE_RATE = "arg_exchange_rate"
        private const val ARG_FEE = "arg_fee"
        private const val ARG_TOTAL = "arg_total"
        private const val ARG_BUTTON_TEXT = "arg_button_text"
        private const val ARG_PAYEE_NAME = "arg_payee_name"
        private const val ARG_PAYEE_VERIFIED_BY = "arg_payee_verified_by"

        private fun setBundle(
            address: String,
            amount: Coin,
            exchangeRate: ExchangeRate?,
            fee: String,
            total: String,
            payeeName: String? = null,
            payeeVerifiedBy: String? = null,
            buttonText: String? = null
        ): Bundle {
            return Bundle().apply {
                putString(ARG_ADDRESS, address)
                putSerializable(ARG_AMOUNT, amount)
                putSerializable(ARG_EXCHANGE_RATE, exchangeRate)
                putString(ARG_FEE, fee)
                putString(ARG_TOTAL, total)
                putString(ARG_PAYEE_NAME, payeeName)
                putString(ARG_PAYEE_VERIFIED_BY, payeeVerifiedBy)
                putString(ARG_BUTTON_TEXT, buttonText)
            }
        }

        private fun show(
            confirmTransactionDialog: ConfirmTransactionDialog,
            bundle: Bundle,
            activity: FragmentActivity
        ) {
            confirmTransactionDialog.arguments = bundle
            confirmTransactionDialog.show(activity.supportFragmentManager, TAG)
        }

        suspend fun showDialogAsync(
            activity: FragmentActivity,
            address: String,
            amount: Coin,
            exchangeRate: ExchangeRate?,
            fee: String,
            total: String
        ) = suspendCancellableCoroutine { coroutine ->
            val confirmTransactionDialog = ConfirmTransactionDialog {
                if (coroutine.isActive) {
                    coroutine.resume(it)
                }
            }
            try {
                val bundle = setBundle(address, amount, exchangeRate, fee, total)
                show(confirmTransactionDialog, bundle, activity)
            } catch (e: Exception) {
                if (coroutine.isActive) {
                    coroutine.resumeWithException(e)
                }
            }
        }
    }

    private val binding by viewBinding(DialogConfirmTransactionBinding::bind)
    override val backgroundStyle = R.style.PrimaryBackground

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.amountView.dashToFiat = true
        binding.amountView.showCurrencySelector = false

        requireArguments().apply {
            val exchangeRate = getSerializable(ARG_EXCHANGE_RATE) as? ExchangeRate

            binding.amountView.setMonetaryInput(getSerializable(ARG_AMOUNT) as? Coin ?: Coin.ZERO)
            binding.amountView.exchangeRate = exchangeRate
            binding.transactionFee.text = getString(ARG_FEE)
            binding.totalAmount.text = getString(ARG_TOTAL)

            val payeeName = getString(ARG_PAYEE_NAME)
            val payeeVerifiedBy = getString(ARG_PAYEE_VERIFIED_BY)

            if (payeeName != null && payeeVerifiedBy != null) {
                binding.address.text = payeeName
                binding.payeeSecuredBy.text = payeeVerifiedBy
                binding.payeeVerifiedByPane.visibility = View.VISIBLE
                val forceMarqueeOnClickListener = View.OnClickListener {
                    it.isSelected = false
                    it.isSelected = true
                }
                binding.address.setOnClickListener(forceMarqueeOnClickListener)
                binding.payeeSecuredBy.setOnClickListener(forceMarqueeOnClickListener)
            } else {
                binding.address.ellipsize = TextUtils.TruncateAt.MIDDLE
                binding.address.text = getString(ARG_ADDRESS)
            }
            getString(ARG_BUTTON_TEXT)?.run {
                binding.confirmPayment.text = this
            }
        }

        binding.confirmPayment.setOnClickListener {
            onTransactionConfirmed?.invoke(true)
            dismiss()
        }

        binding.dismissBtn.setOnClickListener {
            dismiss()
        }
    }
}
