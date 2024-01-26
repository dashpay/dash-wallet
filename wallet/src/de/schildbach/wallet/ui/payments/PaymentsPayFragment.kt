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

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import org.dash.wallet.common.payments.parsers.PaymentIntentParserException
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentPaymentsPayBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.payments.parsers.DashPaymentIntentParser
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.scan.ScanActivity
import org.dash.wallet.common.ui.viewBinding
import javax.inject.Inject

@AndroidEntryPoint
class PaymentsPayFragment : Fragment(R.layout.fragment_payments_pay) {
    companion object {
        @JvmStatic
        fun newInstance() = PaymentsPayFragment()
    }

    @Inject lateinit var analytics: AnalyticsService
    private val binding by viewBinding(FragmentPaymentsPayBinding::bind)

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val intent = result.data

        if (result.resultCode == Activity.RESULT_OK && intent != null) {
            val input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
            input?.let { handleString(input) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.scanBtn.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.SendReceive.SCAN_TO_SEND, mapOf())
            val intent = ScanActivity.getTransitionIntent(activity, binding.scanBtn)
            scanLauncher.launch(intent)
        }

        binding.sendBtn.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.SendReceive.SEND_TO_ADDRESS, mapOf())
            val destination = findNavController().currentDestination as? FragmentNavigator.Destination

            if (destination?.className == PaymentsFragment::class.java.name) {
                findNavController().navigate(PaymentsFragmentDirections.paymentsToAddressInput())
            }
        }
    }

    private fun handleString(input: String) {
        lifecycleScope.launch {
            try {
                val paymentIntent = DashPaymentIntentParser(Constants.NETWORK_PARAMETERS).parse(input, true)
                SendCoinsActivity.start(requireContext(), paymentIntent)
            } catch (ex: PaymentIntentParserException) {
                AdaptiveDialog.create(
                    R.drawable.ic_error,
                    getString(R.string.error),
                    ex.localizedMessage.format(resources),
                    getString(R.string.button_dismiss)
                ).show(requireActivity())
            }
        }
    }
}
