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

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI.onNavDestinationSelected
import androidx.navigation.ui.NavigationUI.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletBalanceWidgetProvider
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet_test.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.components.ComposeHostFrameLayout
import org.dash.wallet.common.ui.components.Toast
import org.dash.wallet.common.ui.components.ToastDuration
import org.dash.wallet.common.ui.components.ToastImageResource
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog.Companion.create
import org.dash.wallet.common.util.openCustomTab
import kotlin.math.abs

object WalletActivityExt {
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
        if (Constants.DASHPAY_DISABLED) {
            navView.menu.findItem(R.id.contactsFragment).isEnabled = false
        }
    }

    fun MainActivity.checkTimeSkew(viewModel: MainViewModel, force: Boolean = false) {
        lifecycleScope.launch {
            val (isTimeSkewed, timeSkew) = viewModel.getDeviceTimeSkew(force)
            val coinJoinOn = viewModel.getCoinJoinMode() != CoinJoinMode.NONE
            if (isTimeSkewed && (!timeSkewDialogShown || force)) {
                timeSkewDialogShown = true
                // add 1 to round up so 2.2 seconds appears as 3
                showTimeSkewAlertDialog((if (timeSkew > 0) 1 else -1) + timeSkew / 1000L, coinJoinOn)
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

    private fun MainActivity.showTimeSkewAlertDialog(diffSeconds: Long, coinJoin: Boolean) {
        val settingsIntent = Intent(Settings.ACTION_DATE_SETTINGS)
        val hasSettings = packageManager.resolveActivity(settingsIntent, 0) != null

        AdaptiveDialog.create(
            R.drawable.ic_warning,
            getString(R.string.wallet_timeskew_dialog_title),
            if (coinJoin) {
                val position = getString(if (diffSeconds > 0) R.string.timeskew_ahead else R.string.timeskew_behind)
                getString(R.string.wallet_coinjoin_timeskew_dialog_msg, position, abs(diffSeconds))
            } else {
                getString(R.string.wallet_timeskew_dialog_msg, diffSeconds / 1000)
            },
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

    /**
     * Android 13 - Show system dialog to get notification permission from user, if not granted
     * ask again with each app upgrade if not granted.  This logic is handled by
     * [.onLockScreenDeactivated] and [.onStart].
     * Android 12 and below - show a explainer dialog once only.
     */
    fun MainActivity.explainPushNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (configuration.showNotificationsExplainer) {
            val dialog = create(
                R.drawable.ic_info_blue,
                getString(R.string.notification_explainer_title),
                getString(R.string.notification_explainer_message),
                "",
                getString(R.string.button_okay)
            )
            dialog.show(this) {
                requestDisableBatteryOptimisation()
            }
        }
        // only show either the permissions dialog (Android >= 13) or the explainer (Android <= 12) once
        configuration.showNotificationsExplainer = false
    }

    fun MainActivity.requestDisableBatteryOptimisation() {
        val powerManager: PowerManager = getSystemService(PowerManager::class.java)
        if (ContextCompat.checkSelfPermission(
                walletApplication,
                Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ) == PackageManager.PERMISSION_GRANTED &&
            !powerManager.isIgnoringBatteryOptimizations(walletApplication.packageName)
        ) {
            create(
                R.drawable.ic_bolt_border,
                getString(R.string.battery_optimization_dialog_optimized_title),
                getString(R.string.battery_optimization_dialog_message_optimized),
                getString(R.string.permission_deny),
                getString(R.string.permission_allow)
            ).show(this) { allow: Boolean? ->
                if (allow == true) {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        }
    }

    /**
     * show a single toast that is created with Compose
     */
    private fun MainActivity.showToast(
        visible: Boolean,
        imageResource: ToastImageResource? = null,
        messageText: String? = null,
        duration: ToastDuration = ToastDuration.INDEFINITE,
        actionText: String? = null,
        onActionClick: (() -> Unit)? = null,
    ) {
        if (composeHostFrameLayout == null) {
            composeHostFrameLayout = ComposeHostFrameLayout(this)
            composeHostFrameLayout?.layoutParams =
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT // Convert dp to pixels for height
                ).apply {
                    gravity = android.view.Gravity.BOTTOM // Align to bottom of FrameLayout
                }
            val rootView = findViewById<ViewGroup>(android.R.id.content)
            rootView.addView(composeHostFrameLayout)
        }
        composeHostFrameLayout?.setContent {
            if (visible && messageText != null) {
                var showToast by remember { mutableStateOf(true) }
                if (showToast) {
                    if (duration != ToastDuration.INDEFINITE) {
                        LaunchedEffect(key1 = true) {
                            delay(if (duration == ToastDuration.SHORT) 3000L else 10000L)
                            showToast = false
                        }
                    }
                    MaterialTheme {
                        val density = LocalDensity.current
                        val bottom = WindowInsets.systemBars.getBottom(density)
                        val top = WindowInsets.systemBars.getTop(density)
                        Box(
                            modifier =
                                Modifier
                                    .padding(
                                        top = top.dp,
                                        bottom = bottom.dp
                                    )
                        ) {
                            // rh
                            Toast(
                                text = messageText,
                                actionText = actionText ?: getString(R.string.button_ok),
                                imageResource = imageResource?.resourceId
                            ) {
                                showToast = false
                                onActionClick?.invoke()
                            }
                        }
                    }
                }
            }
        }
    }

    fun MainActivity.showStaleRatesToast() {
        val staleRateState = viewModel.currentStaleRateState
        val message = when {
            staleRateState.volatile -> getString(R.string.stale_exchange_rates_volatile)
            staleRateState.staleRates -> getString(R.string.stale_exchange_rates_stale)
            staleRateState.lastAttemptFailed -> getString(R.string.stale_exchange_rates_error)
            else -> null
        }
        showToast(
            !lockScreenDisplayed && staleRateState.isStale && !viewModel.rateStaleDismissed,
            imageResource = ToastImageResource.Warning,
            messageText = message,
            actionText = getString(R.string.button_ok)
        ) {
            // never show a stale rate message until app is restarted
            viewModel.rateStaleDismissed = true
        }
    }
}
