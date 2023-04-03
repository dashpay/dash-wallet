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

package de.schildbach.wallet.ui.main

import android.os.Bundle
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.HeaderBalanceFragmentBinding
import org.bitcoinj.core.Coin
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils

class HeaderBalanceFragment : Fragment(R.layout.header_balance_fragment) {
    private val viewModel by activityViewModels<MainViewModel>()
    private val binding by viewBinding(HeaderBalanceFragmentBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.walletBalanceDash.setApplyMarkup(false)
        binding.walletBalanceDash.setFormat(viewModel.balanceDashFormat)
        binding.walletBalanceDash.setAmount(Coin.ZERO)

        binding.walletBalanceLocal.setInsignificantRelativeSize(1f)
        binding.walletBalanceLocal.setStrikeThru(!Constants.IS_PROD_BUILD)
        requireView().setOnClickListener { viewModel.triggerHideBalance() }

        viewModel.isBlockchainSynced.observe(viewLifecycleOwner) { updateView() }
        viewModel.exchangeRate.observe(viewLifecycleOwner) { updateView() }
        viewModel.balance.observe(viewLifecycleOwner) { updateView() }
        viewModel.hideBalance.observe(viewLifecycleOwner) { updateView() }
    }

    private fun updateView() {
        if (!isAdded) {
            return
        }

        if (viewModel.hideBalance.value == true) {
            binding.balanceGroup.visibility = View.INVISIBLE
            binding.showBalanceButton.visibility = View.VISIBLE
            return
        }

        binding.balanceGroup.visibility = View.VISIBLE
        binding.hideShowBalanceHint.visibility = View.INVISIBLE
        binding.showBalanceButton.visibility = View.GONE

        if (viewModel.isBlockchainSynced.value != true) {
            binding.syncingIndicator.isVisible = true
            startSyncingIndicatorAnimation()
        } else {
            binding.syncingIndicator.isVisible = false

            if (binding.syncingIndicator.animation != null) {
                binding.syncingIndicator.animation.cancel()
            }
        }

        val balance = viewModel.balance.value ?: Coin.ZERO
        binding.walletBalanceDash.setAmount(balance)

        if (balance.isPositive) {
            viewModel.exchangeRate.value?.let { exchangeRate ->
                val rate = org.bitcoinj.utils.ExchangeRate(
                    Coin.COIN,
                    exchangeRate.fiat
                )

                val localValue = rate.coinToFiat(balance)
                binding.walletBalanceLocal.visibility = View.VISIBLE
                val currencySymbol = GenericUtils.currencySymbol(exchangeRate.currencyCode)
                binding.walletBalanceLocal.setFormat(Constants.LOCAL_FORMAT.code(0, currencySymbol))
                binding.walletBalanceLocal.setAmount(localValue)
            }
        }
    }

    private fun startSyncingIndicatorAnimation() {
        val currentAnimation = binding.syncingIndicator.animation

        if (currentAnimation == null || currentAnimation.hasEnded()) {
            val alphaAnimation = AlphaAnimation(0.2f, 0.8f)
            alphaAnimation.duration = 833
            alphaAnimation.repeatCount = Animation.INFINITE
            alphaAnimation.repeatMode = Animation.REVERSE
            binding.syncingIndicator.startAnimation(alphaAnimation)
        }
    }
}
