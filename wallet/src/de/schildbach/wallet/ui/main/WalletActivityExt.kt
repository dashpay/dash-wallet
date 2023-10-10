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

import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.MenuItem
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI.onNavDestinationSelected
import androidx.navigation.ui.NavigationUI.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import de.schildbach.wallet.WalletBalanceWidgetProvider
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.openCustomTab

object WalletActivityExt {
    private const val TIME_SKEW_TOLERANCE = 60 // minutes
    private const val STORAGE_TOLERANCE = 500 // MB
    private var timeSkewDialogShown = false
    private var lowStorageDialogShown = false

    const val NOTIFICATION_ACTION_KEY = "action"
    private const val BROWSER_ACTION_KEY = "browser"
    private const val ACTION_URL_KEY = "url"

    fun MainActivity.setupBottomNavigation(viewModel: MainViewModel) {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val navView: BottomNavigationView = findViewById(R.id.bottom_navigation)
        setupWithNavController(navView, navController)
        navView.itemIconTintList = null
        navView.setOnItemSelectedListener { item: MenuItem ->
            when (item.itemId) {
                R.id.walletFragment -> {
                    viewModel.logEvent(AnalyticsConstants.Home.NAV_HOME)
                    navController.navigate(
                        R.id.walletFragment,
                        null,
                        NavOptions.Builder()
                            .setPopUpTo(navController.graph.startDestinationId, true)
                            .build()
                    )
                }
                R.id.paymentsFragment -> viewModel.logEvent(AnalyticsConstants.Home.SEND_RECEIVE_BUTTON)
                R.id.moreFragment -> viewModel.logEvent(AnalyticsConstants.Home.NAV_MORE)
                else -> { }
            }
            onNavDestinationSelected(item, navController)
            true
        }
        navView.setOnItemReselectedListener { item: MenuItem ->
            if (item.itemId == R.id.paymentsFragment) {
                navController.navigateUp()
            } else if (item.itemId == R.id.walletFragment) {
                navHostFragment.childFragmentManager.fragments.firstOrNull { it is WalletFragment }?.let {
                    (it as WalletFragment).scrollToTop()
                }
            }
        }
        navController.addOnDestinationChangedListener { _, _, arguments ->
            navView.isVisible = arguments?.getBoolean("ShowNavBar", false) == true
        }
    }

    fun MainActivity.checkTimeSkew(viewModel: MainViewModel) {
        lifecycleScope.launch {
            val timeSkew = viewModel.getDeviceTimeSkew()
            val inMinutes = timeSkew / 1000 / 60

            if (inMinutes > TIME_SKEW_TOLERANCE && !timeSkewDialogShown) {
                timeSkewDialogShown = true
                showTimeSkewAlertDialog(inMinutes)
            }
        }
    }

    fun MainActivity.checkLowStorageAlert() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val storageManager = applicationContext.getSystemService<StorageManager>()!!
            val storageUUID = storageManager.getUuidForPath(filesDir)
            val availableBytes = storageManager.getAllocatableBytes(storageUUID)
            val toleranceInBytes = 1024L * 1024 * STORAGE_TOLERANCE

            if (availableBytes <= toleranceInBytes && !lowStorageDialogShown) {
                lowStorageDialogShown = true
                showLowStorageAlertDialog()
            }
        } else {
            val stickyIntent = registerReceiver(null, IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW))

            if (stickyIntent != null && !lowStorageDialogShown) {
                lowStorageDialogShown = true
                showLowStorageAlertDialog()
            }
        }
    }

    fun MainActivity.handleFirebaseAction(extras: Bundle) {
        when (extras.getString(NOTIFICATION_ACTION_KEY)) {
            BROWSER_ACTION_KEY -> {
                extras.getString(ACTION_URL_KEY)?.let { url ->
                    openCustomTab(url)
                }
            }
            // Other actions, e.g. Dialog action
            else -> {}
        }
    }

    /**
     * Show a Dialog and if user confirms it, set the default fiat currency exchange rate using
     * the country code to generate a Locale and get the currency code from it.
     *
     * @param newCurrencyCode currency code.
     */
    fun MainActivity.showFiatCurrencyChangeDetectedDialog(
        viewModel: MainViewModel,
        currentCurrencyCode: String,
        newCurrencyCode: String
    ) {
        AdaptiveDialog.create(
            R.drawable.ic_warning,
            getString(R.string.menu_local_currency),
            getString(
                R.string.change_exchange_currency_code_message,
                newCurrencyCode,
                currentCurrencyCode
            ),
            getString(R.string.leave_as, currentCurrencyCode),
            getString(R.string.change_to, newCurrencyCode)
        ).show(this) { result: Boolean? ->
            if (result != null && result) {
                viewModel.setExchangeCurrencyCodeDetected(newCurrencyCode)
                val balance = walletData.getWalletBalance()
                WalletBalanceWidgetProvider.updateWidgets(this, balance)
            } else {
                viewModel.setExchangeCurrencyCodeDetected(null)
            }
        }
    }

    private fun MainActivity.showTimeSkewAlertDialog(diffMinutes: Long) {
        val settingsIntent = Intent(Settings.ACTION_DATE_SETTINGS)
        val hasSettings = packageManager.resolveActivity(settingsIntent, 0) != null

        AdaptiveDialog.create(
            R.drawable.ic_warning,
            getString(R.string.wallet_timeskew_dialog_title),
            getString(R.string.wallet_timeskew_dialog_msg, diffMinutes),
            getString(R.string.button_dismiss),
            if (hasSettings) getString(R.string.button_settings) else null
        ).show(this) { openSettings ->
            if (openSettings == true && hasSettings) {
                startActivity(settingsIntent)
            }
        }
    }

    private fun MainActivity.showLowStorageAlertDialog() {
        val storageManagerIntent = Intent(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                StorageManager.ACTION_MANAGE_STORAGE
            } else {
                Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
            }
        )
        val hasStorageManager = packageManager.resolveActivity(storageManagerIntent, 0) != null

        AdaptiveDialog.create(
            R.drawable.ic_warning,
            getString(R.string.wallet_low_storage_dialog_title),
            getString(R.string.wallet_low_storage_dialog_msg),
            getString(R.string.button_dismiss),
            if (hasStorageManager) getString(R.string.wallet_low_storage_dialog_button_apps) else null
        ).show(this) { openSettings ->
            if (openSettings == true && hasStorageManager) {
                startActivity(storageManagerIntent)
            }
        }
    }
}
