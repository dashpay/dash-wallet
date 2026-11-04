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
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogReceiveDetailsBinding
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.money.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.toFormattedString
import javax.inject.Inject
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toSha256Hash
import de.schildbach.wallet.util.toFormattedString

@AndroidEntryPoint
class ReceiveDetailsDialog : OffsetDialogFragment(R.layout.dialog_receive_details) {
    companion object {
        private const val ARG_DASH_AMOUNT = "arg_dash_amount"
        private const val ARG_FIAT_AMOUNT = "arg_fiat_amount"
        private const val ARG_ADDRESS = "arg_address"
        private const val ARG_REQUEST_ADDRESS = "arg_request_address"
        private const val ARG_REQUEST_ADDRESS_LABEL = "arg_request_address_label"

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

        /**
         * The same dialog for an address [PaymentURI][org.dash.wallet.common.payments.parsers.PaymentURI]
         * cannot carry (Advanced Receive's shielded and Platform addresses and identity ID):
         * the QR holds [ReceiveViewModel.amountRequestPayload] and the address shows under
         * [addressLabel]. There is no username row.
         */
        fun createAmountRequestDialog(
            addressLabel: String,
            address: String,
            dashAmount: Coin,
            fiatAmount: Fiat?
        ): OffsetDialogFragment {
            val dialog = ReceiveDetailsDialog()
            dialog.arguments = Bundle().apply {
                putString(ARG_REQUEST_ADDRESS, address)
                putString(ARG_REQUEST_ADDRESS_LABEL, addressLabel)
                putSerializable(ARG_DASH_AMOUNT, dashAmount)
                putSerializable(ARG_FIAT_AMOUNT, fiatAmount)
            }
            return dialog
        }
    }

    override val backgroundStyle = R.style.PrimaryBackground
    private val binding by viewBinding(DialogReceiveDetailsBinding::bind)
    @Inject lateinit var configuration: Configuration
    private val viewModel by viewModels<PaymentsViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        val requestAddress = args.getString(ARG_REQUEST_ADDRESS)
        if (requestAddress != null) {
            showAmountRequest(args, requestAddress)
            return
        }

        args.apply {
            val address = getSerializable(ARG_ADDRESS) as Address
            val dashAmount = getSerializable(ARG_DASH_AMOUNT) as Coin
            val fiatAmount = getSerializable(ARG_FIAT_AMOUNT) as Fiat?

            binding.receiveInfo.setInfo(address.toBase58(), dashAmount?.let { org.dash.wallet.common.money.Dash(it.value) })
            showAmount(dashAmount, fiatAmount)
            viewModel.dashPayProfile.observe(viewLifecycleOwner) {
                binding.receiveInfo.setProfile(it?.username, it?.displayName, it?.avatarUrl, it?.avatarHash)
            }
        }
    }

    /** [createAmountRequestDialog]: never through PaymentURI, and no DashPay profile. */
    private fun showAmountRequest(args: Bundle, address: String) {
        val label = args.getString(ARG_REQUEST_ADDRESS_LABEL).orEmpty()
        val dashAmount = checkNotNull(args.getSerializable(ARG_DASH_AMOUNT) as? Coin) { "no amount" }
        val fiatAmount = args.getSerializable(ARG_FIAT_AMOUNT) as? Fiat
        val payload = ReceiveViewModel.amountRequestPayload(address, dashAmount.toNeutralCoin())
        binding.receiveInfo.setPaymentRequest(label, address, payload)
        showAmount(dashAmount, fiatAmount)
    }

    private fun showAmount(dashAmount: Coin, fiatAmount: Fiat?) {
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
