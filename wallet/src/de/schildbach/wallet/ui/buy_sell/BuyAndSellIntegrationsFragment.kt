/*
 * Copyright 2021 Dash Core Group.
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

package de.schildbach.wallet.ui.buy_sell

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.ServiceType
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.openCustomTab
import org.dash.wallet.common.util.safeNavigate
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@AndroidEntryPoint
class BuyAndSellIntegrationsFragment : Fragment() {
    companion object {
        val log: Logger = LoggerFactory.getLogger(BuyAndSellIntegrationsFragment::class.java)
    }

    private val viewModel by viewModels<BuyAndSellViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        log.info("starting Buy and Sell Dash fragment")

        lifecycleScope.launchWhenResumed {
            checkLiquidStatus()
        }

        return ComposeView(requireContext()).apply {
            setContent {
                BuyAndSellScreen(
                    onBackClick = {
                        findNavController().popBackStack()
                    },
                    onTopperClick = {
                        lifecycleScope.launch {
                            val uri = viewModel.topperBuyUrl(getString(R.string.dash_wallet_name))
                            viewModel.logEvent(AnalyticsConstants.Topper.ENTER_BUY_SELL)
                            requireActivity().openCustomTab(uri)
                        }
                    },
                    onUpholdClick = {
                        viewModel.logEnterUphold()
                        safeNavigate(BuyAndSellIntegrationsFragmentDirections.buySellToUphold())
                    },
                    onCoinbaseClick = {
                        viewModel.logEnterCoinbase()
                        if (viewModel.isCoinbaseAuthenticated) {
                            safeNavigate(BuyAndSellIntegrationsFragmentDirections.buySellToCoinbase())
                        } else {
                            safeNavigate(
                                BuyAndSellIntegrationsFragmentDirections.buySellToOverview(
                                    ServiceType.COINBASE
                                )
                            )
                        }
                    },
                    onMayaClick = {
                        safeNavigate(BuyAndSellIntegrationsFragmentDirections.buySellToMaya())
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateBalances()
        viewModel.updateServicesStatus()
    }

    private fun checkLiquidStatus() {
        val liquidClient = LiquidClient.getInstance()

        if (liquidClient.isAuthenticated) {
            AdaptiveDialog.custom(R.layout.dialog_liquid_unavailable).apply {
                isCancelable = false
            }.show(requireActivity()) {
                liquidClient.clearLiquidData()
            }
        }
    }
}
