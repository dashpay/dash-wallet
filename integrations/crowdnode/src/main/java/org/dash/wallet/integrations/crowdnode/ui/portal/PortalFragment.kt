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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.bitcoinj.core.Coin
import org.bitcoinj.params.MainNetParams
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.copy
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentPortalBinding

@AndroidEntryPoint
class PortalFragment : Fragment(R.layout.fragment_portal) {
    private val binding by viewBinding(FragmentPortalBinding::bind)
    private val viewModel by viewModels<PortalViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.walletBalanceDash.setFormat(viewModel.dashFormat)
        binding.walletBalanceDash.setApplyMarkup(false)
        binding.walletBalanceDash.setAmount(Coin.ZERO)

        binding.depositBtn.setOnClickListener {
            viewModel.deposit()
        }

        binding.onlineAccountBtn.setOnClickListener {
            val url = if (viewModel.networkParameters == MainNetParams.get()) {
                getString(R.string.crowdnode_login_page, viewModel.account.toBase58())
            } else {
                getString(R.string.crowdnode_login_test_page, viewModel.account.toBase58())
            }
            safeNavigate(PortalFragmentDirections.portalToOnlineAccountInfo(url))
        }

        binding.supportBtn.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.crowdnode_support_url)))
            startActivity(browserIntent)
        }

        binding.unlinkAccountBtn.setOnClickListener {
            // TODO: online account
            findNavController().popBackStack()
        }

        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_info) {
                AdaptiveDialog.create(
                    R.drawable.ic_info_blue_encircled,
                    getString(R.string.crowdnode_your_address_title),
                    viewModel.account.toBase58(),
                    getString(R.string.button_close),
                    getString(R.string.button_copy_address)
                ).show(requireActivity()) { toCopy ->
                    if (toCopy == true) {
                        viewModel.account.toBase58().copy(requireActivity(), "dash address")
                    }
                }
            }

            true
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