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

package de.schildbach.wallet.ui.coinbase

import android.os.Bundle
import androidx.activity.viewModels
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.BaseMenuActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.integration.coinbase_integration.service.CloseCoinbasePortalBroadcaster
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseActivityViewModel
import javax.inject.Inject

@AndroidEntryPoint
class CoinbaseActivity : BaseMenuActivity() {
    private val viewModel: CoinbaseActivityViewModel by viewModels()
    @Inject
    lateinit var broadcaster: CloseCoinbasePortalBroadcaster
    private lateinit var navController: NavController

    override fun getLayoutId(): Int {
        return R.layout.activity_coinbase
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        navController = setNavigationGraph()

        broadcaster.closeCoinbasePortal.observe(this){
            finish()
        }

        viewModel.getPaymentMethods()
        viewModel.getBaseIdForFaitModel()
    }

    private fun setNavigationGraph(): NavController {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_coinbase_fragment) as NavHostFragment
        return navHostFragment.navController
    }

    override fun onLockScreenActivated() {
        if (navController.currentDestination?.id == R.id.enterTwoFaCodeFragment) {
            navController.popBackStack(org.dash.wallet.integration.coinbase_integration.R.id.coinbaseServicesFragment, false)
        }
    }
}
