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

package org.dash.wallet.integrations.maya.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.R as CommonR
import org.slf4j.LoggerFactory
import java.math.RoundingMode

/**
 * DashDEX buy "Send {COIN} to this address" screen (Figma node 35042-51682).
 *
 * Final step of the buy flow: shows the SwapKit deposit address (+ QR) the user must send the
 * chosen crypto to. Reached from [DEXRefundAddressFragment] with the asset/currency and the
 * validated refund address. The entered amount is read from the shared, nav-graph-scoped
 * [DEXEnterAmountViewModel] (see [mayaViewModels]).
 *
 * The deposit address comes from a SwapKit buy-swap call that is not implemented yet, so the
 * screen renders a loading state — see [DEXReceiveViewModel.loadDepositAddress].
 */
@AndroidEntryPoint
class DEXReceiveFragment : Fragment() {
    companion object {
        private val log = LoggerFactory.getLogger(DEXReceiveFragment::class.java)
    }

    private val viewModel by mayaViewModels<DEXReceiveViewModel>()
    // Same nav-graph-scoped instance used by the earlier steps: carries the committed amount, which
    // the (future) buy-swap call in the ViewModel needs as the sell amount.
    private val enterAmountViewModel by mayaViewModels<DEXEnterAmountViewModel>()
    private val args by navArgs<DEXReceiveFragmentArgs>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // The amount of the chosen crypto the user is sending in, as a human-unit decimal — the
        // sellAmount for the SwapKit buy quote. Comes from the shared enter-amount step.
        val sellAmount = enterAmountViewModel.enteredAmount().crypto
            .setScale(8, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        viewModel.setArguments(
            asset = args.asset,
            currencyCode = args.currency,
            refundAddress = args.refundAddress,
            sellAmount = sellAmount
        )
        log.info(
            "DEX buy: receive screen for asset={} sellAmount={} refundAddress={}",
            args.asset, sellAmount, args.refundAddress
        )
        viewModel.loadDepositAddress()

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DEXReceiveScreen(
                    viewModel = viewModel,
                    onBackClick = { findNavController().popBackStack() },
                    // "Back home" exits the whole buy flow to the wallet home (WalletFragment).
                    // navController.graph is the root nav_home graph, whose start destination is
                    // walletFragment — same "go home" pattern as MayaConvertResultFragment.
                    onBackHomeClick = {
                        val navController = findNavController()
                        navController.popBackStack(navController.graph.startDestinationId, false)
                    },
                    onCopyClick = ::copyToClipboard
                )
            }
        }
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("dex_deposit_uri", text))
        Toast.makeText(requireContext(), CommonR.string.copied, Toast.LENGTH_SHORT).show()
    }
}