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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.ui.explore.ExploreTopic
import org.dash.wallet.features.exploredash.ui.explore.dialogs.ExploreDashInfoDialog

@AndroidEntryPoint
class ExploreFragment : Fragment() {
    private val viewModel: ExploreEntryViewModel by viewModels()

    private val screenState = mutableStateOf(
        ExploreScreenState(showFaucet = false)
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashWalletTheme {
                    ExploreScreen(
                        state = screenState.value,
                        onBackClick = { findNavController().popBackStack() },
                        onWhereToSpendClick = ::handleMerchantsNavigation,
                        onAtmsClick = {
                            safeNavigate(ExploreFragmentDirections.exploreToSearch(ExploreTopic.ATMs))
                        },
                        onFaucetClick = {
                            safeNavigate(ExploreFragmentDirections.exploreToFaucet())
                        }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = MaterialFadeThrough()

        screenState.value = screenState.value.copy(showFaucet = viewModel.isTestNet())
    }

    private fun handleMerchantsNavigation() {
        lifecycleScope.launch {
            if (viewModel.isInfoShown()) {
                safeNavigate(ExploreFragmentDirections.exploreToSearch(ExploreTopic.Merchants))
            } else {
                ExploreDashInfoDialog().show(requireActivity()) {
                    viewModel.setIsInfoShown(true)
                    safeNavigate(ExploreFragmentDirections.exploreToSearch(ExploreTopic.Merchants))
                }
            }
        }
    }
}
