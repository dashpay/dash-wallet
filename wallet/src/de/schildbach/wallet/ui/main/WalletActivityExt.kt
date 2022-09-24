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

package de.schildbach.wallet.ui.main

import android.view.MenuItem
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import de.schildbach.wallet_test.R

object WalletActivityExt {
    fun FragmentActivity.setupBottomNavigation() {
        val navController = (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
        val navView: BottomNavigationView = findViewById(R.id.bottom_navigation)
        setupWithNavController(navView, navController)
        navView.itemIconTintList = null
        navView.setOnItemReselectedListener { item: MenuItem ->
            if (item.itemId == R.id.paymentsFragment) {
                navController.navigateUp()
            }
        }
        navController.addOnDestinationChangedListener { _, _, arguments ->
            navView.isVisible = arguments?.getBoolean("ShowNavBar", false) == true
        }
    }
}