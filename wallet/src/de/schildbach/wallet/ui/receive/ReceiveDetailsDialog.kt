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

package de.schildbach.wallet.ui.receive


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import de.schildbach.wallet.ui.BaseBottomSheetDialogFragment
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.dialog_confirm_transaction.*
import kotlinx.android.synthetic.main.dialog_receive_details.*
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.util.GenericUtils

private const val ARG_DASH_AMOUNT = "arg_dash_amount"
private const val ARG_FIAT_AMOUNT = "arg_fiat_amount"

class ReceiveDetailsDialog : BaseBottomSheetDialogFragment() {

    companion object {

        @JvmStatic
        fun createDialog(dashAmount: Coin, fiatAmount: Fiat?): DialogFragment {
            val dialog = ReceiveDetailsDialog()
            val bundle = Bundle()
            bundle.putSerializable(ARG_DASH_AMOUNT, dashAmount)
            bundle.putSerializable(ARG_FIAT_AMOUNT, fiatAmount)
            dialog.arguments = bundle
            return dialog
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_receive_details, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments!!.apply {
            val dashAmount = getSerializable(ARG_DASH_AMOUNT) as Coin
            val fiatAmount = getSerializable(ARG_FIAT_AMOUNT) as Fiat?

            receive_info.amount = dashAmount
            input_value.text = MonetaryFormat.BTC.noCode().format(dashAmount).toString()
            if (fiatAmount != null) {
                fiat_symbol.text = GenericUtils.currencySymbol(fiatAmount.currencyCode)
                fiat_value.text = fiatAmount.toPlainString()
            } else {
                fiat_symbol.visibility = View.GONE
                fiat_value.visibility = View.GONE
            }
        }
    }
}
