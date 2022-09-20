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

package de.schildbach.wallet.ui.explore

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.BlockchainStateDao
import de.schildbach.wallet.ui.BaseMenuActivity
import de.schildbach.wallet.ui.payments.PaymentsActivity
import de.schildbach.wallet.ui.staking.StakingActivity
import de.schildbach.wallet_test.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.features.exploredash.ui.ExploreViewModel
import org.dash.wallet.features.exploredash.ui.NavigationRequest
import javax.inject.Inject

@AndroidEntryPoint
@FlowPreview
@ExperimentalCoroutinesApi
class ExploreActivity : BaseMenuActivity() {
    private val viewModel: ExploreViewModel by viewModels()
    @Inject
    lateinit var blockChainDao: BlockchainStateDao

    override fun getLayoutId(): Int {
        return R.layout.activity_explore
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.navigationCallback.observe(this) { request ->
            when (request) {
                NavigationRequest.SendDash -> {
                    val sendCoinsIntent = PaymentsActivity.createIntent(this, PaymentsActivity.ACTIVE_TAB_PAY)
                    startActivity(sendCoinsIntent)
                }
                NavigationRequest.ReceiveDash -> {
                    val sendCoinsIntent = PaymentsActivity.createIntent(this, PaymentsActivity.ACTIVE_TAB_RECEIVE)
                    startActivity(sendCoinsIntent)
                }
                NavigationRequest.Staking -> {
                    handleStakingNavigation()
                }
                else -> { }
            }
        }
    }

    private fun handleStakingNavigation() {
        lifecycleScope.launch {
            if (isSynced()) {
                startActivity(Intent(this@ExploreActivity, StakingActivity::class.java))
            } else {
                val openWebsite = AdaptiveDialog.create(
                    null,
                    getString(R.string.chain_syncing),
                    getString(R.string.crowdnode_wait_for_sync),
                    getString(R.string.button_close),
                    getString(R.string.crowdnode_open_website)
                ).showAsync(this@ExploreActivity)

                if (openWebsite == true) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.crowdnode_website)))
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
