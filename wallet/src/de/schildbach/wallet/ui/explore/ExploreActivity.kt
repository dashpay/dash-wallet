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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.BaseMenuActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.features.exploredash.ui.ExploreTopic
import org.dash.wallet.features.exploredash.ui.ExploreViewModel
import org.dash.wallet.features.exploredash.ui.NavigationRequest

@AndroidEntryPoint
class ExploreActivity : BaseMenuActivity() {
    companion object {
        private const val TOPIC_KEY = "type"

        fun createIntent(context: Context, topic: ExploreTopic): Intent {
            val intent = Intent(context, ExploreActivity::class.java)
            intent.putExtra(TOPIC_KEY, topic)
            return intent
        }
    }

    private val viewModel: ExploreViewModel by viewModels()

    override fun getLayoutId(): Int {
        return R.layout.activity_explore
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setNavigationGraph()

        viewModel.navigationCallback.observe(this) { request ->
            when (request) {
                NavigationRequest.SendDash -> {
//                    val sendCoinsIntent = PaymentsActivity.createIntent(this, 0)
//                    startActivity(sendCoinsIntent)
                }
                NavigationRequest.ReceiveDash -> {
//                    val sendCoinsIntent = PaymentsActivity.createIntent(this, 1)
//                    startActivity(sendCoinsIntent)
                }
                else -> {}
            }
        }
    }

    private fun setNavigationGraph() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val navGraph = navController.navInflater.inflate(R.navigation.explore_dash)
        navController.setGraph(navGraph, intent.extras)
    }
}