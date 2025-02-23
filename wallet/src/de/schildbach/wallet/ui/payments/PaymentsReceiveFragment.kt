/*
 * Copyright 2022 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.payments

import android.os.Bundle
import android.view.Gravity.CENTER_VERTICAL
import android.view.View
import android.widget.FrameLayout
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentPaymentsReceiveBinding
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.viewBinding
import javax.inject.Inject

@AndroidEntryPoint
class PaymentsReceiveFragment : Fragment(R.layout.fragment_payments_receive) {
    private val binding by viewBinding(FragmentPaymentsReceiveBinding::bind)
    private val viewModel by viewModels<PaymentsViewModel>()

    companion object {
        private const val SHOW_IMPORT_PRIVATE_KEY_ARG = "showImportPrivateKey"
        private const val CENTER_VERTICALLY_KEY_ARG = "centerVertically"

        @JvmStatic
        fun newInstance(): PaymentsReceiveFragment {
            return PaymentsReceiveFragment().apply {
                arguments = bundleOf(
                    SHOW_IMPORT_PRIVATE_KEY_ARG to true,
                    CENTER_VERTICALLY_KEY_ARG to false
                )
            }
        }
    }

    private val args by navArgs<PaymentsReceiveFragmentArgs>()
    @Inject lateinit var analytics: AnalyticsService
    @Inject lateinit var walletDataProvider: WalletDataProvider

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        analytics.logEvent(AnalyticsConstants.SendReceive.SHOW_QR_CODE, mapOf())

        binding.receiveInfo.setOnSpecifyAmountClicked {
            analytics.logEvent(AnalyticsConstants.SendReceive.SPECIFY_AMOUNT, mapOf())
            findNavController().navigate(PaymentsFragmentDirections.paymentsToReceive())
        }
        binding.receiveInfo.setOnAddressClicked {
            analytics.logEvent(AnalyticsConstants.SendReceive.COPY_ADDRESS, mapOf())
        }
        binding.receiveInfo.setOnShareClicked {
            analytics.logEvent(AnalyticsConstants.SendReceive.SHARE, mapOf())
        }

        binding.receiveInfo.setInfo(walletDataProvider.freshReceiveAddress(), null)

        binding.importPrivateKeyBtn.isVisible = args.showImportPrivateKey
        binding.importPrivateKeyBtn.setOnClickListener {
            SweepWalletActivity.start(requireContext(), false)
        }

        if (args.centerVertically) {
            binding.content.updateLayoutParams<FrameLayout.LayoutParams> {
                this.gravity = CENTER_VERTICAL
                topMargin = -100
            }
        }

        viewModel.dashPayProfile.observe(viewLifecycleOwner) {
            binding.receiveInfo.setProfile(it?.username, it?.displayName, it?.avatarUrl, it?.avatarHash)
        }
    }
}
