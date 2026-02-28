/*
 * Copyright 2020 Dash Core Group.
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

package de.schildbach.wallet.ui.dashpay

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.CreateUsernameActivity
import de.schildbach.wallet.ui.main.MainViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentUpgradeToEvolutionBinding
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.ui.viewBinding

@AndroidEntryPoint
class UpgradeToEvolutionFragment : Fragment(R.layout.fragment_upgrade_to_evolution) {

    private val mainViewModel by activityViewModels<MainViewModel>()
    private val binding by viewBinding(FragmentUpgradeToEvolutionBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = MaterialFadeThrough()

        binding.upgradeBtn.setOnClickListener {
            startActivity(Intent(requireContext(), CreateUsernameActivity::class.java))
            findNavController().popBackStack()
        }

        mainViewModel.isAbleToCreateIdentityLiveData.observe(viewLifecycleOwner) { isAble ->
            binding.upgradeBtn.isEnabled = isAble
            binding.balanceRequirementDisclaimer.isVisible = !isAble
        }

        binding.balanceRequirementDisclaimer.text = getString(
            R.string.dashpay_min_balance_disclaimer,
            MonetaryFormat.BTC.format(Constants.DASH_PAY_FEE)
        )
    }

    override fun onResume() {
        super.onResume()
        binding.upgradeBtn.isEnabled = mainViewModel.isAbleToCreateIdentity
    }
}
