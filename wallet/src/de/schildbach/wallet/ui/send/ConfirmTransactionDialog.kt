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

        private const val ARGUMENT_ADDRESS = "argument_address"
        private const val ARGUMENT_AMOUNT = "argument_amount"
        private const val ARGUMENT_AMOUNT_FIAT = "argument_amount_fiat"
        private const val ARGUMENT_FIAT_SYMBOL = "argument_fiat_symbol"
        private const val ARGUMENT_FEE = "argument_fee"

        @JvmStatic
        fun createDialog(address: String, amount: String, amountFiat: String, fiatSymbol: String, fee: String): DialogFragment {
            val dialog = ConfirmTransactionDialog()
            val bundle = Bundle()
            bundle.putString(ARGUMENT_ADDRESS, address)
            bundle.putString(ARGUMENT_AMOUNT, amount)
            bundle.putString(ARGUMENT_AMOUNT_FIAT, amountFiat)
            bundle.putString(ARGUMENT_FIAT_SYMBOL, fiatSymbol)
            bundle.putString(ARGUMENT_FEE, fee)
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
            input_value.text = getString(ARGUMENT_AMOUNT)
            fiat_symbol.text = getString(ARGUMENT_FIAT_SYMBOL)
            fiat_value.text = getString(ARGUMENT_AMOUNT_FIAT)
            address.text = getString(ARGUMENT_ADDRESS)
            transaction_fee.text = getString(ARGUMENT_FEE)
            total_amount.text = input_value.text //amount + fee
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
