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
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.slf4j.LoggerFactory
import org.dash.wallet.common.R as CommonR

/**
 * DashDEX buy "Send {COIN} to this address" screen (Figma node 35042-51682).
 *
 * Final step of the buy flow: shows the SwapKit deposit address (+ QR) the user must send the
 * chosen crypto to. Reached from [DEXRefundAddressFragment], which already created the buy order
 * with SwapKit and passes the resolved deposit address (and the sell amount used to build the
 * payment URI) as nav args — so this screen is purely presentational.
 */
@AndroidEntryPoint
class DEXReceiveFragment : Fragment() {
    companion object {
        private val log = LoggerFactory.getLogger(DEXReceiveFragment::class.java)
    }

    private val viewModel by mayaViewModels<DEXReceiveViewModel>()
    private val args by navArgs<DEXReceiveFragmentArgs>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel.setArguments(
            asset = args.asset,
            currencyCode = args.currency,
            sellAmount = args.sellAmount,
            depositAddress = args.depositAddress,
            memo = args.memo
        )
        log.info(
            "DEX buy: receive screen for asset={} sellAmount={} deposit={}",
            args.asset,
            args.sellAmount,
            args.depositAddress
        )

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashWalletTheme {
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
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("dex_deposit_uri", text))
        Toast.makeText(requireContext(), CommonR.string.copied, Toast.LENGTH_SHORT).show()
    }
}
