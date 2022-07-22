/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.FragmentExploreBinding
import java.util.*

@AndroidEntryPoint
@FlowPreview
@ExperimentalCoroutinesApi
class ExploreFragment : Fragment(R.layout.fragment_explore) {
    private val binding by viewBinding(FragmentExploreBinding::bind)
    private val viewModel: ExploreViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            requireActivity().finish()
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
            viewModel.openStaking()
        }

        binding.stakingApy.text = getString(
            R.string.explore_staking_current_apy,
            String.format(Locale.getDefault(), "%.1f", viewModel.getCrowdNodeAPY())
        )
    }
}
