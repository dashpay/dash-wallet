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

package de.schildbach.wallet.ui.explore

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.main.MainViewModel
import de.schildbach.wallet.ui.staking.StakingActivity
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.databinding.FragmentExploreBinding
import org.dash.wallet.features.exploredash.ui.explore.ExploreTopic
import java.util.*

@AndroidEntryPoint
class ExploreFragment : Fragment(R.layout.fragment_explore) {
    private val binding by viewBinding(FragmentExploreBinding::bind)
    private val viewModel: MainViewModel by activityViewModels()

    private val stakingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Constants.USER_BUY_SELL_DASH) {
            safeNavigate(ExploreFragmentDirections.exploreToBuySell())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.merchantsBtn.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Explore.WHERE_TO_SPEND)
            safeNavigate(ExploreFragmentDirections.exploreToSearch(ExploreTopic.Merchants))
        }

        binding.atmsBtn.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Explore.PORTAL_ATM)
            safeNavigate(ExploreFragmentDirections.exploreToSearch(ExploreTopic.ATMs))
        }

        binding.stakingBtn.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.CrowdNode.STAKING_ENTRY)
            handleStakingNavigation()
        }

        viewModel.stakingAPY.observe(viewLifecycleOwner) {
            setAPY(it)
        }

        // load the last APY value
        viewModel.getLastStakingAPY()
    }

    private fun handleStakingNavigation() {
        lifecycleScope.launch {
            if (viewModel.isBlockchainSynced.value == true) {
                stakingLauncher.launch(Intent(requireContext(), StakingActivity::class.java))
            } else {
                val openWebsite = AdaptiveDialog.create(
                    null,
                    getString(R.string.chain_syncing),
                    getString(R.string.crowdnode_wait_for_sync),
                    getString(R.string.button_close),
                    getString(R.string.crowdnode_open_website)
                ).showAsync(requireActivity())

                if (openWebsite == true) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.crowdnode_website)))
                    startActivity(browserIntent)
                }
            }
        }
    }

    private fun setAPY(apy: Double) {
        if (apy != 0.0) {
            binding.stakingApyContainer.isVisible = true
            binding.stakingApy.text = getString(
                R.string.explore_staking_current_apy,
                String.format(Locale.getDefault(), "%.1f", apy)
            )
        } else {
            // hide the APY container if we don't have a value yet
            binding.stakingApyContainer.isVisible = false
        }
    }
}
