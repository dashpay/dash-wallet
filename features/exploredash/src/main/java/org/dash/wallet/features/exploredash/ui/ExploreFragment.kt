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
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.FragmentExploreBinding
import javax.inject.Inject

@AndroidEntryPoint
class ExploreFragment : Fragment(R.layout.fragment_explore) {
    private val binding by viewBinding(FragmentExploreBinding::bind)
    @Inject
    lateinit var analyticsService: AnalyticsService
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.toolbar.title = getString(R.string.explore_title)
        binding.titleBar.toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.merchantsBtn.setOnClickListener {
            analyticsService.logEvent(AnalyticsConstants.ExploreDash.WHERE_TO_SPEND, bundleOf())
            safeNavigate(ExploreFragmentDirections.exploreToSearch(ExploreTopic.Merchants))
        }

        binding.atmsBtn.setOnClickListener {
            analyticsService.logEvent(AnalyticsConstants.ExploreDash.PORTAL_ATM, bundleOf())
            safeNavigate(ExploreFragmentDirections.exploreToSearch(ExploreTopic.ATMs))
        }
    }
}
