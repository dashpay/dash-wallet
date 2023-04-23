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

package de.schildbach.wallet.ui.payments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogReceiveDetailsBinding
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.toFormattedString
import javax.inject.Inject

@AndroidEntryPoint
class ReceiveDetailsDialog : OffsetDialogFragment(R.layout.dialog_receive_details) {
    companion object {
        private const val ARG_DASH_AMOUNT = "arg_dash_amount"
        private const val ARG_FIAT_AMOUNT = "arg_fiat_amount"
        private const val ARG_ADDRESS = "arg_address"

        @JvmStatic
        fun createDialog(address: Address, dashAmount: Coin, fiatAmount: Fiat?): OffsetDialogFragment {
            val dialog = ReceiveDetailsDialog()
            val bundle = Bundle()
            bundle.putSerializable(ARG_ADDRESS, address)
            bundle.putSerializable(ARG_DASH_AMOUNT, dashAmount)
            bundle.putSerializable(ARG_FIAT_AMOUNT, fiatAmount)
            dialog.arguments = bundle
            return dialog
        }
    }

    override val backgroundStyle = R.style.PrimaryBackground
    private val binding by viewBinding(DialogReceiveDetailsBinding::bind)
    @Inject lateinit var configuration: Configuration

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireArguments().apply {
            val address = getSerializable(ARG_ADDRESS) as Address
            val dashAmount = getSerializable(ARG_DASH_AMOUNT) as Coin
            val fiatAmount = getSerializable(ARG_FIAT_AMOUNT) as Fiat?

            binding.receiveInfo.setInfo(address, dashAmount)
            binding.amount.inputValue.text = MonetaryFormat.BTC
                .repeatOptionalDecimals(1, Configuration.PREFS_DEFAULT_BTC_PRECISION)
                .minDecimals(0).noCode().format(dashAmount)

            if (fiatAmount != null) {
                binding.amount.fiatValue.text = fiatAmount.toFormattedString()
            } else {
                binding.amount.fiatValue.isVisible = false
            }
        }
    }
}
