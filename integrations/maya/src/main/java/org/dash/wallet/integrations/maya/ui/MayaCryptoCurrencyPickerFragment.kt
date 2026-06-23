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
import org.dash.wallet.common.ui.components.ModalDialog
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MayaCryptoCurrencyPickerScreen(
                    viewModel = viewModel,
                    onBackClick = { findNavController().popBackStack() },
                    onCoinClick = ::onCoinSelected,
                    onShowError = ::showErrorAlert
                )
            }
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
        log.info("currency picker: navigating to address input for {}", pool.asset)
        if (viewModel.swapDirection.value == SwapDirection.SELL) {
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
