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
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.BlockchainStateDao
import de.schildbach.wallet.ui.staking.StakingActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.FragmentExploreBinding
import org.dash.wallet.features.exploredash.ui.ExploreTopic
import javax.inject.Inject

@AndroidEntryPoint
@FlowPreview
@ExperimentalCoroutinesApi
class ExploreFragment : Fragment(R.layout.fragment_explore) {
    private val binding by viewBinding(FragmentExploreBinding::bind)
    @Inject
    lateinit var blockChainDao: BlockchainStateDao
    @Inject
    lateinit var analytics: AnalyticsService

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

        binding.stakingBtn.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.CrowdNode.STAKING_ENTRY, bundleOf())
            handleStakingNavigation()
        }

        binding.faucetBtn.setOnClickListener {
            val intent = ExploreActivity.createIntent(requireContext(), ExploreTopic.Faucet)
            startActivity(intent)
        }
    }

    private fun handleStakingNavigation() {
        lifecycleScope.launch {
            if (isSynced()) {
                startActivity(Intent(requireContext(), StakingActivity::class.java))
            } else {
                val openWebsite = AdaptiveDialog.create(
                    null,
                    getString(de.schildbach.wallet_test.R.string.chain_syncing),
                    getString(de.schildbach.wallet_test.R.string.crowdnode_wait_for_sync),
                    getString(de.schildbach.wallet_test.R.string.button_close),
                    getString(de.schildbach.wallet_test.R.string.crowdnode_open_website)
                ).showAsync(requireActivity())

                if (openWebsite == true) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(de.schildbach.wallet_test.R.string.crowdnode_website)))
                    startActivity(browserIntent)
                }
            }
        }
    }

    private suspend fun isSynced(): Boolean {
        val blockChainState = blockChainDao.get()

        return blockChainState != null && blockChainState.isSynced()
    }
}
