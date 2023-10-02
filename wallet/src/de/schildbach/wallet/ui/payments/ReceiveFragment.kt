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
import android.view.View
import android.view.View.TEXT_ALIGNMENT_CENTER
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentReceiveBinding
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.enter_amount.EnterAmountFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.viewBinding
import javax.inject.Inject

@AndroidEntryPoint
class ReceiveFragment : Fragment(R.layout.fragment_receive) {
    private val enterAmountViewModel by activityViewModels<EnterAmountViewModel>()
    private val binding by viewBinding(FragmentReceiveBinding::bind)
    @Inject lateinit var walletData: WalletDataProvider
    @Inject lateinit var analytics: AnalyticsService

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener { findNavController().popBackStack() }

        if (savedInstanceState == null) {
            val fragment = EnterAmountFragment.newInstance(
                isMaxButtonVisible = false,
                showCurrencySelector = true
            )
            childFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.enter_amount_fragment_placeholder, fragment)
            }
            fragment.setViewDetails(
                getString(R.string.receive_title),
                TextView(requireContext()).apply {
                    setTextColor(resources.getColor(R.color.content_tertiary, null))
                    text = getString(R.string.receive_enter_amount_message)
                    textAlignment = TEXT_ALIGNMENT_CENTER
                }
            )
        }

        enterAmountViewModel.onContinueEvent.observe(viewLifecycleOwner) {
            analytics.logEvent(AnalyticsConstants.SendReceive.ENTER_AMOUNT_RECEIVE, mapOf())
            val dashAmount = it.first
            val fiatAmount = it.second
            val address = walletData.freshReceiveAddress()
            val dialogFragment = ReceiveDetailsDialog.createDialog(address, dashAmount, fiatAmount)
            dialogFragment.show(requireActivity())
        }
    }

    override fun onDestroy() {
        enterAmountViewModel.resetCurrency()
        super.onDestroy()
    }
}
