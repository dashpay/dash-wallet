/*
 * Copyright 2026 Dash Core Group.
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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.SystemActionsService
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.util.shareText
import javax.inject.Inject

/**
 * The Receive page of [AdvancedPaymentsFragment]: hosts [ReceivePage] in its ViewPager2.
 * The basic payments screen keeps [PaymentsReceiveFragment].
 *
 * ViewPager2 lifts only the selected page to RESUMED, so the address is (re)read when the
 * user is actually on Receive, as in [PaymentsReceiveFragment].
 */
@AndroidEntryPoint
class AdvancedReceiveFragment : Fragment() {

    companion object {
        fun newInstance() = AdvancedReceiveFragment()
    }

    private val viewModel by viewModels<ReceiveViewModel>()

    @Inject
    lateinit var systemActions: SystemActionsService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashWalletTheme {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    ReceivePage(
                        uiState = uiState,
                        onSelectTab = viewModel::selectTab,
                        onCopyUsername = { username ->
                            systemActions.copyText(username, "Dash Username")
                        },
                        onCopyAddress = { address ->
                            viewModel.logEvent(AnalyticsConstants.SendReceive.COPY_ADDRESS)
                            systemActions.copyText(address, "Dash address")
                        },
                        onShareAddress = { address ->
                            viewModel.logEvent(AnalyticsConstants.SendReceive.SHARE)
                            requireContext().shareText(
                                address,
                                getString(org.dash.wallet.common.R.string.request_coins_share_dialog_title)
                            )
                        },
                        onSpecifyAmount = ::specifyAmount
                    )
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.logEvent(AnalyticsConstants.SendReceive.SHOW_QR_CODE)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.refresh()
            }
        }
    }

    /**
     * Core opens the amount screen as it always has: [ReceiveFragment] reads a fresh address
     * once the amount is entered. The other tabs pass only the balance; [ReceiveFragment] reads
     * that balance's address from the wallet ([AmountRequestAddressResolver]), never from its
     * arguments, and the request is a bare `dash:` URI ([ReceiveViewModel.amountRequestPayload]).
     */
    private fun specifyAmount(tab: ReceiveBalanceTab, address: String?) {
        val navController = findNavController()
        // A second tap while the transition runs would find the action missing.
        if (navController.currentDestination?.id != R.id.advancedPaymentsFragment) return
        val directions = if (tab == ReceiveBalanceTab.CORE) {
            PaymentsFragmentDirections.paymentsToReceive()
        } else {
            // The button is disabled without an address; nothing to request otherwise.
            if (address == null) return
            PaymentsFragmentDirections.paymentsToReceive(requestBalance = tab.name)
        }
        viewModel.logEvent(AnalyticsConstants.SendReceive.SPECIFY_AMOUNT)
        navController.navigate(directions)
    }
}
