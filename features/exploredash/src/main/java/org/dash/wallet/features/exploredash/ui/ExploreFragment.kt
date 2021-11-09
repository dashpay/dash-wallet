/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dash.wallet.features.exploredash.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.FragmentExploreBinding

@AndroidEntryPoint
class ExploreFragment : Fragment(R.layout.fragment_explore) {
    private val binding by viewBinding(FragmentExploreBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.toolbar.title = getString(R.string.explore_title)
        binding.titleBar.toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.merchantsBtn.setOnClickListener {
            safeNavigate(ExploreFragmentDirections.exploreToSearch(ExploreTopic.Merchants))
        }

        binding.atmsBtn.setOnClickListener {
            safeNavigate(ExploreFragmentDirections.exploreToSearch(ExploreTopic.ATMs))
        }
    }
}
