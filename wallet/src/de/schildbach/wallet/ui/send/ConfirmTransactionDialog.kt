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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProviders
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import de.schildbach.wallet.ui.BaseBottomSheetDialogFragment
import de.schildbach.wallet.ui.SingleActionSharedViewModel
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.dialog_confirm_transaction.*


class ConfirmTransactionDialog : BaseBottomSheetDialogFragment() {

    companion object {

        private const val ARG_ADDRESS = "arg_address"
        private const val ARG_AMOUNT = "arg_amount"
        private const val ARG_AMOUNT_FIAT = "arg_amount_fiat"
        private const val ARG_FIAT_SYMBOL = "arg_fiat_symbol"
        private const val ARG_FEE = "arg_fee"
        private const val ARG_TOTAL = "arg_total"
        private const val ARG_BUTTON_TEXT = "arg_button_text"
        private const val ARG_PAYEE_NAME = "arg_payee_name"
        private const val ARG_PAYEE_VERIFIED_BY = "arg_payee_verified_by"

        @JvmStatic
        fun createDialog(address: String, amount: String, amountFiat: String, fiatSymbol: String, fee: String, total: String,
                         payeeName: String? = null, payeeVerifiedBy: String? = null, buttonText: String? = null): DialogFragment {
            val dialog = ConfirmTransactionDialog()
            val bundle = Bundle()
            bundle.putString(ARG_ADDRESS, address)
            bundle.putString(ARG_AMOUNT, amount)
            bundle.putString(ARG_AMOUNT_FIAT, amountFiat)
            bundle.putString(ARG_FIAT_SYMBOL, fiatSymbol)
            bundle.putString(ARG_FEE, fee)
            bundle.putString(ARG_TOTAL, total)
            bundle.putString(ARG_PAYEE_NAME, payeeName)
            bundle.putString(ARG_PAYEE_VERIFIED_BY, payeeVerifiedBy)
            bundle.putString(ARG_BUTTON_TEXT, buttonText)
            dialog.arguments = bundle
            return dialog
        }
    }

    private lateinit var sharedViewModel: SingleActionSharedViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_confirm_transaction, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments!!.apply {
            input_value.text = getString(ARG_AMOUNT)
            fiat_symbol.text = getString(ARG_FIAT_SYMBOL)
            fiat_value.text = getString(ARG_AMOUNT_FIAT)
            transaction_fee.text = getString(ARG_FEE)
            total_amount.text = getString(ARG_TOTAL)
            val payeeName = getString(ARG_PAYEE_NAME)
            val payeeVerifiedBy = getString(ARG_PAYEE_VERIFIED_BY)
            if (payeeName != null && payeeVerifiedBy != null) {
                address.text = payeeName
                payee_secured_by.text = payeeVerifiedBy
                payee_verified_by_pane.visibility = View.VISIBLE
                val forceMarqueeOnClickListener = View.OnClickListener {
                    it.isSelected = false
                    it.isSelected = true
                }
                address.setOnClickListener(forceMarqueeOnClickListener)
                payee_secured_by.setOnClickListener(forceMarqueeOnClickListener)
            } else {
                address.ellipsize = TextUtils.TruncateAt.MIDDLE
                address.text = getString(ARG_ADDRESS)
            }
            getString(ARG_BUTTON_TEXT)?.run {
                confirm_payment.text = this
            }
        }
        collapse_button.setOnClickListener {
            dismiss()
        }
        confirm_payment.setOnClickListener {
            dismiss()
            sharedViewModel.clickConfirmButtonEvent.call(true)
        }
        dialog?.setOnShowListener { dialog ->
            // apply wrap_content height
            val d = dialog as BottomSheetDialog
            val bottomSheet = d.findViewById<FrameLayout>(R.id.design_bottom_sheet)
            val coordinatorLayout = bottomSheet!!.parent as CoordinatorLayout
            val bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
            bottomSheetBehavior.peekHeight = bottomSheet.height
            coordinatorLayout.parent.requestLayout()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        sharedViewModel = activity?.run {
            ViewModelProviders.of(this)[SingleActionSharedViewModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")
    }
}
