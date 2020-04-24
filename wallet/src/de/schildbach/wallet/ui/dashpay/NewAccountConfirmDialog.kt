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
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.BaseBottomSheetDialogFragment
import de.schildbach.wallet.ui.SingleActionSharedViewModel
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.dialog_new_account_confirm.*
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.util.GenericUtils

class NewAccountConfirmDialog : BaseBottomSheetDialogFragment() {

    companion object {

        private const val ARG_USERNAME = "arg_username"
        private const val ARG_UPGRADE_FEE = "arg_upgrade_fee"

        @JvmStatic
        fun createDialog(upgradeFee: Long, username: String): DialogFragment {
            val dialog = NewAccountConfirmDialog()
            val bundle = Bundle()
            bundle.putString(ARG_USERNAME, username)
            bundle.putLong(ARG_UPGRADE_FEE, upgradeFee)
            dialog.arguments = bundle
            return dialog
        }
    }

    private lateinit var viewModel: NewAccountConfirmDialogViewModel
    private lateinit var sharedViewModel: SingleActionSharedViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_new_account_confirm, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        agree_check.setOnCheckedChangeListener { _, isChecked ->
            confirm.isEnabled = isChecked
        }

        confirm.setOnClickListener {
            dismiss()
            sharedViewModel.clickConfirmButtonEvent.call(true)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        sharedViewModel = activity?.run {
            ViewModelProviders.of(this)[SingleActionSharedViewModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")
        viewModel = ViewModelProviders.of(this).get(NewAccountConfirmDialogViewModel::class.java)
        viewModel.exchangeRateData.observe(viewLifecycleOwner, Observer {
            updateView()
        })
        updateView()
    }

    private fun updateView() {
        val upgradeFee = Coin.valueOf(arguments!!.getLong(ARG_UPGRADE_FEE))

        val upgradeFeeStr = MonetaryFormat.BTC.noCode().format(upgradeFee).toString()
        val fiatUpgradeFee = viewModel.exchangeRate?.coinToFiat(upgradeFee)
        // if the exchange rate is not available, then show "Not Available"
        val upgradeFeeFiatStr = if (fiatUpgradeFee != null) Constants.LOCAL_FORMAT.format(fiatUpgradeFee).toString() else getString(R.string.transaction_row_rate_not_available)
        val fiatSymbol = if (fiatUpgradeFee != null) GenericUtils.currencySymbol(fiatUpgradeFee.currencyCode) else ""

        input_value.text = upgradeFeeStr
        fiat_symbol.text = upgradeFeeFiatStr
        fiat_value.text = fiatSymbol

        val username = "<b>“${arguments!!.getString(ARG_USERNAME)}”</b>"
        @Suppress("DEPRECATION")
        message.text = Html.fromHtml(getString(R.string.new_account_confirm_message, username))
    }
}
