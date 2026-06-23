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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.components.DASH_CURRENCY_CODE
import org.dash.wallet.common.util.toBigDecimal
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * DashDEX buy "Enter amount" screen (Figma node 35200-34693).
 *
 * Reached from the currency picker's BUY branch with the asset/currency the user chose.
 * Renders the design-system [org.dash.wallet.common.ui.components.EnterAmount] bar plus the
 * shared numeric keyboard ([org.dash.wallet.common.ui.enter_amount.NumericKeyboardCompose]).
 */
@AndroidEntryPoint
class DEXEnterAmountFragment : Fragment() {
    companion object {
        private val log = LoggerFactory.getLogger(DEXEnterAmountFragment::class.java)
    }

    private val viewModel by mayaViewModels<DEXEnterAmountViewModel>()
    private val mayaViewModel by mayaViewModels<MayaViewModel>()
    private val args by navArgs<DEXEnterAmountFragmentArgs>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Fiat prices of one DASH and one unit of the bought asset (in the user's selected fiat),
        // taken from the cached Maya pool list — used to convert the entered amount between the
        // fiat / DASH / asset display currencies. The fiat code comes from the rate itself so the
        // picker symbol matches the conversion.
        val dashPool = mayaViewModel.getPoolInfo(DASH_CURRENCY_CODE)
        val assetPool = mayaViewModel.getPoolInfo(args.currency)
        val fiatCode = dashPool?.assetPriceFiat?.currencyCode
            ?: assetPool?.assetPriceFiat?.currencyCode
            ?: args.fiatCurrency

        viewModel.setArguments(
            asset = args.asset,
            assetCurrencyCode = args.currency,
            fiatCurrencyCode = fiatCode,
            dashPriceFiat = dashPool?.assetPriceFiat?.toBigDecimal() ?: BigDecimal.ZERO,
            assetPriceFiat = assetPool?.assetPriceFiat?.toBigDecimal() ?: BigDecimal.ZERO
        )

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DEXEnterAmountScreen(
                    viewModel = viewModel,
                    onBackClick = { findNavController().popBackStack() },
                    onContinueClick = ::onContinue
                )
            }
        }
    }

    private fun onContinue(amount: String, currencyCode: String) {
        // The committed amount lives in the nav-graph-scoped DEXEnterAmountViewModel and is shared
        // with the refund-address step, so only the asset/currency need to travel as nav args.
        log.info("DEX buy: continue with amount={} {} for asset={}", amount, currencyCode, args.asset)
        findNavController().navigate(
            DEXEnterAmountFragmentDirections.dexEnterAmountToDexRefundAddress(
                asset = args.asset,
                currency = args.currency
            )
        )
    }
}