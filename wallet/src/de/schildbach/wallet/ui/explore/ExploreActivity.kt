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
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.core.os.bundleOf
import androidx.navigation.NavOptions
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.BaseMenuActivity
import de.schildbach.wallet.ui.PaymentsFragment
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

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        setNavigationGraph(navHostFragment)

        viewModel.navigationCallback.observe(this) { request ->
            val animationOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.slide_in_right)
                .setExitAnim(R.anim.activity_stay)
                .setPopExitAnim(R.anim.slide_out_left)
                .build()

            when (request) {
                NavigationRequest.SendDash -> {
                    navHostFragment.navController.navigate(R.id.payments, bundleOf(
                        PaymentsFragment.ARGS_ACTIVE_TAB to PaymentsFragment.ACTIVE_TAB_PAY
                    ), animationOptions)
                }
                NavigationRequest.ReceiveDash -> {
                    navHostFragment.navController.navigate(R.id.payments, bundleOf(
                        PaymentsFragment.ARGS_ACTIVE_TAB to PaymentsFragment.ACTIVE_TAB_RECEIVE
                    ), animationOptions)
                }
                else -> {}
            }
        }
    }

    private fun setNavigationGraph(navHostFragment: NavHostFragment) {
        val navController = navHostFragment.navController
        val navGraph = navController.navInflater.inflate(R.navigation.explore_dash)
        navController.setGraph(navGraph, intent.extras)

        // Injecting PaymentsFragment into the explore nav graph manually since PaymentsFragment is
        // in the wallet module. This can be done more gracefully in the future when we use
        // navigation controller in the wallet and deep links for navigating between modules.
        val paymentsDestination = navController.navigatorProvider.getNavigator(FragmentNavigator::class.java)
            .createDestination().apply {
                id = R.id.payments
                className = PaymentsFragment::class.java.name

            }
        navController.graph.addDestination(paymentsDestination)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // PaymentsFragment has a close button that needs to pop the stack
            R.id.option_close -> {
                val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                navHostFragment.navController.popBackStack()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}