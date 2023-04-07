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
import androidx.core.view.isInvisible
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

        viewModel.exchangeRate.observe(viewLifecycleOwner) { updateBalance() }
        viewModel.balance.observe(viewLifecycleOwner) { updateBalance() }

        viewModel.isBlockchainSynced.observe(viewLifecycleOwner) { isSynced ->
            if (isSynced) {
                binding.syncingIndicator.isInvisible = true
                binding.syncingIndicator.animation?.cancel()
            } else {
                binding.syncingIndicator.isInvisible = false
                startSyncingIndicatorAnimation()
            }
        }

        viewModel.hideBalance.observe(viewLifecycleOwner) { hideBalance ->
            binding.balanceGroup.isInvisible = hideBalance
            binding.showBalanceButton.isInvisible = !hideBalance
        }

        viewModel.showTapToHideHint.observe(viewLifecycleOwner) { showHint ->
            binding.hideBalanceHintText.isVisible = showHint ?: true
        }
    }

    private fun updateBalance() {
        val balance = viewModel.balance.value ?: Coin.ZERO
        binding.walletBalanceDash.setAmount(balance)
        viewModel.exchangeRate.value?.let { exchangeRate ->
            val rate = org.bitcoinj.utils.ExchangeRate(
                Coin.COIN,
                exchangeRate.fiat
            )

            val localValue = rate.coinToFiat(balance)
            val currencySymbol = GenericUtils.currencySymbol(exchangeRate.currencyCode)
            binding.walletBalanceLocal.setFormat(viewModel.fiatFormat.code(0, currencySymbol))
            binding.walletBalanceLocal.setAmount(localValue)
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
