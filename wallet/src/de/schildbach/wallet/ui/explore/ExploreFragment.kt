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

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.FragmentExploreBinding
import org.dash.wallet.features.exploredash.ui.ExploreTopic
import org.dash.wallet.features.exploredash.ui.ExploreViewModel

@AndroidEntryPoint
class ExploreFragment : Fragment(R.layout.fragment_explore) {
    private val binding by viewBinding(FragmentExploreBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.merchantsBtn.setOnClickListener {
            val intent = ExploreActivity.createIntent(requireContext(), ExploreTopic.Merchants)
            startActivity(intent)
        }

        binding.atmsBtn.setOnClickListener {
            val intent = ExploreActivity.createIntent(requireContext(), ExploreTopic.ATMs)
            startActivity(intent)
        }

        binding.faucetBtn.setOnClickListener {
            val intent = ExploreActivity.createIntent(requireContext(), ExploreTopic.Faucet)
            startActivity(intent)
        }
    }
}
