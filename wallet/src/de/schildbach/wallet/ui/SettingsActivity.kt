/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletBalanceWidgetProvider
import de.schildbach.wallet.ui.main.WalletActivity
import de.schildbach.wallet.ui.more.AboutActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivitySettingsBinding
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.SystemActionsService
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.exchange_rates.ExchangeRatesDialog
import org.slf4j.LoggerFactory
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : LockScreenActivity() {
    private val log = LoggerFactory.getLogger(SettingsActivity::class.java)
    private lateinit var binding: ActivitySettingsBinding
    @Inject
    lateinit var analytics: AnalyticsService
    @Inject
    lateinit var systemActions: SystemActionsService
    @Inject
    lateinit var walletUIConfig: WalletUIConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appBar.toolbar.title = getString(R.string.settings_title)
        binding.appBar.toolbar.setNavigationOnClickListener { finish() }

        binding.about.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.Settings.ABOUT, mapOf())
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.localCurrency.setOnClickListener {
            lifecycleScope.launch {
                analytics.logEvent(AnalyticsConstants.Settings.LOCAL_CURRENCY, mapOf())
                val currentOption = walletUIConfig.getExchangeCurrencyCode()
                ExchangeRatesDialog(currentOption) { rate, _, dialog ->
                    setSelectedCurrency(rate.currencyCode)
                    dialog.dismiss()
                }.show(this@SettingsActivity)
            }
        }

        binding.rescanBlockchain.setOnClickListener { resetBlockchain() }
        binding.notifications.setOnClickListener { systemActions.openNotificationSettings() }
        binding.batteryOptimization.setOnClickListener {
            val powerManager = getSystemService(PowerManager::class.java)

            if (ContextCompat.checkSelfPermission(
                    walletApplication,
                    Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ) == PackageManager.PERMISSION_GRANTED &&
                !powerManager.isIgnoringBatteryOptimizations(walletApplication.packageName)
            ) {
                AdaptiveDialog.create(
                    null,
                    getString(R.string.alert_dialogs_fragment_battery_optimization_dialog_title),
                    getString(R.string.alert_dialogs_fragment_battery_optimization_dialog_message),
                    getString(R.string.permission_deny),
                    getString(R.string.permission_allow)
                ).show(this) { allow ->
                    if (allow == true) {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
            } else {
                // TODO: There will be designs for this dialog
                AdaptiveDialog.create(
                    null,
                    getString(R.string.alert_dialogs_fragment_battery_optimization_dialog_title),
                    getString(R.string.alert_dialogs_fragment_battery_optimization_off_dialog_message),
                    getString(R.string.close),
                    getString(R.string.alert_dialogs_fragment_battery_optimization_dialog_button_change)
                ).show(this) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    } else {
                        // Handle case where the intent is not resolvable (e.g., device doesn't have this setting).
                    }
                }
            }
        }

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { binding.localCurrencySymbol.text = it }
            .launchIn(lifecycleScope)
    }

    private fun resetBlockchain() {
        AdaptiveDialog.create(
            null,
            getString(R.string.preferences_initiate_reset_title),
            getString(R.string.preferences_initiate_reset_dialog_message),
            getString(R.string.button_cancel),
            getString(R.string.preferences_initiate_reset_dialog_positive)
        ).show(this) {
            if (it == true) {
                log.info("manually initiated blockchain reset")
                analytics.logEvent(AnalyticsConstants.Settings.RESCAN_BLOCKCHAIN_RESET, mapOf())

                walletApplication.resetBlockchain()
                configuration.updateLastBlockchainResetTime()
                startActivity(WalletActivity.createIntent(this@SettingsActivity))
            } else {
                analytics.logEvent(AnalyticsConstants.Settings.RESCAN_BLOCKCHAIN_DISMISS, mapOf())
            }
        }
    }

    private fun setSelectedCurrency(code: String) {
        lifecycleScope.launch {
            walletUIConfig.set(WalletUIConfig.SELECTED_CURRENCY, code)
            val balance = walletData.getWalletBalance()
            WalletBalanceWidgetProvider.updateWidgets(this@SettingsActivity, balance)
        }
    }
}
