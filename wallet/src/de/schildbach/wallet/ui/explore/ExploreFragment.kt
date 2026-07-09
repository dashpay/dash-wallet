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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.staking.StakingActivity
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.ui.explore.ExploreTopic
import org.dash.wallet.features.exploredash.ui.explore.dialogs.ExploreDashInfoDialog

@AndroidEntryPoint
class ExploreFragment : Fragment() {
    private val viewModel: ExploreEntryViewModel by viewModels()

    private val screenState = mutableStateOf(
        ExploreScreenState(
            showFaucet = false,
            showStaking = false,
            apy = 0.0,
            showWithdrawalBanner = false
        )
    )

    private val stakingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Constants.USER_BUY_SELL_DASH) {
            safeNavigate(ExploreFragmentDirections.exploreToBuySell())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ExploreScreen(
                    state = screenState.value,
                    onBackClick = { findNavController().popBackStack() },
                    onWhereToSpendClick = ::handleMerchantsNavigation,
                    onAtmsClick = {
                        safeNavigate(ExploreFragmentDirections.exploreToSearch(ExploreTopic.ATMs))
                    },
                    onStakingClick = {
                        viewModel.logEvent(AnalyticsConstants.CrowdNode.STAKING_ENTRY)
                        handleStakingNavigation()
                    },
                    onFaucetClick = {
                        safeNavigate(ExploreFragmentDirections.exploreToFaucet())
                    },
                    onWithdrawClick = {
                        viewModel.logEvent(AnalyticsConstants.CrowdNode.STAKING_ENTRY)
                        handleStakingNavigation(goToWithdraw = true)
                    }
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = MaterialFadeThrough()

        screenState.value = screenState.value.copy(showFaucet = viewModel.isTestNet())

        // CrowdNode functionality is limited: the staking entry point is only shown
        // if the active wallet is already associated with a CrowdNode account
        viewModel.hasCrowdNodeAccount.observe(viewLifecycleOwner) { hasAccount ->
            screenState.value = screenState.value.copy(showStaking = hasAccount)
        }

        viewModel.stakingAPY.observe(viewLifecycleOwner) { apy ->
            screenState.value = screenState.value.copy(apy = apy)
        }

        // Persistent, non-dismissible reminder to withdraw a remaining CrowdNode balance.
        viewModel.showWithdrawalBanner.observe(viewLifecycleOwner) { show ->
            screenState.value = screenState.value.copy(showWithdrawalBanner = show)
        }

        // load the last APY value
        viewModel.getLastStakingAPY()
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

    private fun handleStakingNavigation(goToWithdraw: Boolean = false) {
        lifecycleScope.launch {
            if (viewModel.isBlockchainSynced.value == true) {
                stakingLauncher.launch(StakingActivity.createIntent(requireContext(), goToWithdraw))
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
}
