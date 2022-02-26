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

package org.dash.wallet.integrations.crowdnode.ui.portal

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentPortalBinding

@AndroidEntryPoint
class PortalFragment : Fragment(R.layout.fragment_portal) {
    private val binding by viewBinding(FragmentPortalBinding::bind)
    private val viewModel by viewModels<PortalViewModel>()

    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(6).optionalDecimals()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.walletBalanceDash.setFormat(dashFormat)
        binding.walletBalanceDash.setApplyMarkup(false)
        binding.walletBalanceDash.setAmount(Coin.ZERO)

        binding.depositBtn.setOnClickListener {
            viewModel.deposit()
        }

        binding.onlineAccountBtn.setOnClickListener {
            safeNavigate(PortalFragmentDirections.portalToOnlineAccountInfo())
        }

        viewModel.balance.observe(viewLifecycleOwner) { balance ->
            binding.walletBalanceDash.setAmount(balance)
            updateFiatAmount(balance, viewModel.exchangeRate.value)
        }

        viewModel.exchangeRate.observe(viewLifecycleOwner) { rate ->
            updateFiatAmount(viewModel.balance.value, rate)
        }
    }

    private fun updateFiatAmount(balance: Coin?, exchangeRate: ExchangeRate?) {
        val fiatRate = exchangeRate?.fiat

        if (balance != null && fiatRate != null) {
            val rate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, fiatRate)
            val fiatValue = rate.coinToFiat(balance)
            binding.walletBalanceLocal.text = GenericUtils.fiatToString(fiatValue)
        }
    }
}