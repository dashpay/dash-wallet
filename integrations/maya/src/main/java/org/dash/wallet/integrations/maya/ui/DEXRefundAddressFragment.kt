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

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.scan.ScanActivity
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.maya.payments.MayaCurrencyList
import org.slf4j.LoggerFactory
import java.math.RoundingMode

/**
 * DashDEX buy "Enter refund address" screen (Figma node 35199-9405).
 *
 * Reached after [DEXEnterAmountFragment] with the asset/currency the user is buying. The refund
 * address entered here must be valid for that asset's chain — funds are returned to it if the swap
 * fails. The entered amount is not passed as a nav arg: [DEXEnterAmountViewModel] is nav-graph
 * scoped (see [mayaViewModels] -> navGraphViewModels(R.id.nav_maya)), so the same instance — and
 * the amount committed in it — is shared across the whole Maya nav graph and read back here.
 */
@AndroidEntryPoint
class DEXRefundAddressFragment : Fragment() {
    companion object {
        private val log = LoggerFactory.getLogger(DEXRefundAddressFragment::class.java)
    }

    private val viewModel by mayaViewModels<DEXRefundAddressViewModel>()

    // Same nav-graph-scoped instance used by DEXEnterAmountFragment: carries the committed amount.
    private val enterAmountViewModel by mayaViewModels<DEXEnterAmountViewModel>()
    private val args by navArgs<DEXRefundAddressFragmentArgs>()

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)?.let { scanned ->
                viewModel.onAddressChanged(viewModel.normalizeCase(scanned.trim()))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel.setArguments(asset = args.asset, currencyCode = args.currency)

        // Once the buy order is created with SwapKit, carry its deposit address (and the sell amount
        // used to build the payment URI) to the receive screen — which no longer calls createBuyOrder.
        viewModel.onOrderCreated.observe(viewLifecycleOwner) { order ->
            log.info("DEX buy: order created for asset={}, navigating to receive", args.asset)
            safeNavigate(
                DEXRefundAddressFragmentDirections.dexRefundAddressToDexReceive(
                    asset = args.asset,
                    currency = args.currency,
                    sellAmount = order.sellAmount,
                    depositAddress = order.depositAddress,
                    memo = order.memo.orEmpty()
                )
            )
        }

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashWalletTheme {
                    DEXRefundAddressScreen(
                        viewModel = viewModel,
                        onBackClick = { findNavController().popBackStack() },
                        onScanClick = { launchScanner(this) },
                        onPasteClick = ::pasteFromClipboard,
                        onContinueClick = ::onContinue
                    )
                }
            }
        }
    }

    private fun launchScanner(clickView: View) {
        scanLauncher.launch(ScanActivity.getTransitionIntent(requireActivity(), clickView))
    }

    private fun pasteFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val pasted = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(requireContext())?.toString()
        if (!pasted.isNullOrBlank()) {
            viewModel.onAddressChanged(viewModel.normalizeCase(pasted.trim()))
        }
    }

    private fun onContinue() {
        // The entered amount is shared via the nav-graph-scoped DEXEnterAmountViewModel; convert it to
        // the human-unit crypto sell amount and hand it to the ViewModel, which validates the address
        // and creates the buy order with SwapKit. Navigation happens via onOrderCreated on success.
        // Quantized DOWN to the asset's on-chain decimals (formatSwapAmount): the same string is
        // registered with the SwapKit quote and encoded in the deposit URI, so the deposited amount
        // can never fall short of the quoted one — NEAR Intents refunds even a one-base-unit deficit.
        val crypto = enterAmountViewModel.enteredAmount().crypto
        val sellAmount = MayaCurrencyList[args.asset]?.formatSwapAmount(crypto)
            ?: crypto.setScale(FALLBACK_SELL_AMOUNT_SCALE, RoundingMode.DOWN)
                .stripTrailingZeros()
                .toPlainString()
        log.info("DEX buy: continue for asset={} sellAmount={}", args.asset, sellAmount)
        viewModel.submitOrder(sellAmount)
    }
}

private const val FALLBACK_SELL_AMOUNT_SCALE = 8
