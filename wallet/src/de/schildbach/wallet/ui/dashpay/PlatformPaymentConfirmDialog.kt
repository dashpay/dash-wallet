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

package de.schildbach.wallet.ui.dashpay

import android.os.Bundle
import android.view.View
import androidx.core.text.HtmlCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.SingleActionSharedViewModel
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.dialog_platform_payment_confirm.*
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.util.GenericUtils

@AndroidEntryPoint
class PlatformPaymentConfirmDialog : OffsetDialogFragment(R.layout.dialog_platform_payment_confirm) {

    companion object {

        private const val ARG_TITLE = "arg_title"
        private const val ARG_MESSAGE = "arg_message"
        private const val ARG_AMOUNT = "arg_amount"
        private const val ARG_ISINVITE = "arg_isinvite"

        @JvmStatic
        fun createDialog(title: String, messageHtml: String, amount: Coin? = null, isInvite: Boolean = false): DialogFragment {
            val dialog = PlatformPaymentConfirmDialog()
            dialog.arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_MESSAGE, messageHtml)
                putBoolean(ARG_ISINVITE, isInvite)
                if (amount != null) {
                    putLong(ARG_AMOUNT, amount.value)
                }
            }
            return dialog
        }
    }

    private val title by lazy {
        requireArguments().getString(ARG_TITLE)!!
    }

    private val message by lazy {
        requireArguments().getString(ARG_MESSAGE)!!
    }

    private val amount by lazy {
        Coin.valueOf(requireArguments().getLong(ARG_AMOUNT))
    }

    private lateinit var viewModel: NewAccountConfirmDialogViewModel
    private lateinit var sharedViewModel: SharedViewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val amountSpecified = requireArguments().containsKey(ARG_AMOUNT)
        fee_container.visibility = if (amountSpecified) View.VISIBLE else View.GONE
        no_fee_placeholder.visibility = if (amountSpecified) View.GONE else View.VISIBLE
        if (requireArguments().getBoolean(ARG_ISINVITE)) {
            confirm.text = getString(R.string.invitation_confirm_button_text)
        }

        agree_check.setOnCheckedChangeListener { _, isChecked ->
            confirm.isEnabled = isChecked
        }

        confirm.setOnClickListener {
            dismiss()
            sharedViewModel.clickConfirmButtonEvent.value = true
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        sharedViewModel = activity?.run {
            ViewModelProvider(this)[SharedViewModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")
        viewModel = ViewModelProvider(this).get(NewAccountConfirmDialogViewModel::class.java)
        viewModel.exchangeRateData.observe(viewLifecycleOwner, Observer {
            updateView()
        })
        updateView()
    }

    private fun updateView() {
        title_view.text = title
        message_view.text = HtmlCompat.fromHtml(message, HtmlCompat.FROM_HTML_MODE_COMPACT)

        val amountStr = MonetaryFormat.BTC.noCode().format(amount).toString()
        val fiatAmount = viewModel.exchangeRate?.coinToFiat(amount)
        // if the exchange rate is not available, then show "Not Available"
        val fiatAmountStr = if (fiatAmount != null) Constants.LOCAL_FORMAT.format(fiatAmount).toString() else getString(R.string.transaction_row_rate_not_available)
        val fiatSymbol = if (fiatAmount != null) GenericUtils.currencySymbol(fiatAmount.currencyCode) else ""

        dash_amount_view.text = amountStr
        fiat_symbol_view.text = fiatSymbol
        fiat_amount_view.text = fiatAmountStr
    }

    class SharedViewModel : SingleActionSharedViewModel()
}
