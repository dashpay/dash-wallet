/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.integrations.maya.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.dash.wallet.integrations.maya.utils.SwapDirection
import org.slf4j.LoggerFactory

@AndroidEntryPoint
class MayaCryptoCurrencyPickerFragment : Fragment() {
    companion object {
        private val log = LoggerFactory.getLogger(MayaCryptoCurrencyPickerFragment::class.java)
    }

    private val viewModel by mayaViewModels<MayaViewModel>()

    // The graph-scoped ViewModel captured while the nav_maya graph is guaranteed on the back
    // stack. onDestroy also runs when the whole flow is popped to Home (after a completed
    // swap), where resolving the navGraphViewModels lazy for the first time throws — which a
    // restored back-stack instance whose view was never recreated would otherwise do.
    private var resolvedViewModel: MayaViewModel? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        resolvedViewModel = viewModel
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashWalletTheme {
                    MayaCryptoCurrencyPickerScreen(
                        viewModel = viewModel,
                        onBackClick = { findNavController().popBackStack() },
                        onCoinClick = ::onCoinSelected,
                        onShowError = ::showErrorAlert
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The search query lives in the nav-graph-scoped MayaViewModel, so it would
        // otherwise persist back to the portal and into the next visit. Clear it only
        // when the picker is popped off the back stack (returning to the portal):
        // isRemoving is false on a configuration change and while the fragment sits on
        // the back stack after navigating forward, so the query survives rotation and a
        // forward-then-back trip. Use the instance captured in onCreateView rather than
        // the lazy: re-resolving it here crashes once the whole flow was popped to Home.
        if (isRemoving) {
            resolvedViewModel?.onSearchQuery("")
        }
    }

    private fun showErrorAlert(code: Int) {
        var messageId = R.string.loading_error

        if (code == 400 || code == 408 || code >= 500) messageId = R.string.maya_error_not_available
        if (code == 403 || code >= 400) messageId = R.string.maya_error_report_issue

        AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.maya_error),
            getString(messageId),
            getString(android.R.string.ok)
        ).show(requireActivity()) {
            viewModel.errorHandled()
        }
    }

    private fun onCoinSelected(asset: String) {
        // Defense-in-depth: re-check halt status before navigating. The Compose row
        // already disables clicks for halted/unavailable coins, but the asset can
        // transition to halted between render and tap.
        val pool = viewModel.poolList.value.firstOrNull { it.asset == asset } ?: return
        val inboundAddress = viewModel.getInboundAddress(pool.asset)
        if (inboundAddress != null && !inboundAddress.halted && !pool.mayaHalted) {
            clickListener(pool)
        }
    }

    private fun clickListener(pool: PoolInfo) {
        if (viewModel.swapDirection.value == SwapDirection.SELL) {
            log.info("currency picker: navigating to address input for {}", pool.asset)
            safeNavigate(
                MayaCryptoCurrencyPickerFragmentDirections.mayaCurrencyPickerToAddressInput(
                    pool.currencyCode,
                    pool.asset,
                    getString(R.string.maya_address_input_title, pool.currencyCode),
                    getString(R.string.maya_address_input_hint, pool.currencyCode)
                )
            )
        } else {
            log.info("currency picker: navigating to DEX enter amount for {}", pool.asset)
            safeNavigate(
                MayaCryptoCurrencyPickerFragmentDirections.mayaCurrencyPickerToDexEnterAmount(
                    asset = pool.asset,
                    currency = pool.currencyCode
                )
            )
        }
    }
}
