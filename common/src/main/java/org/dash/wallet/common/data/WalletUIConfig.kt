/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.common.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.readRetryDelayMs
import org.dash.wallet.common.util.retryReadErrors
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

@Singleton
// Intended for the UI settings which affect what the user sees on the screen.
// Should be used from views and viewModels only.
// For other settings, use Configuration or another datastore class.
open class WalletUIConfig @Inject constructor(
    context: Context,
    walletDataProvider: WalletDataProvider,
    exchangeRates: ExchangeRatesProvider
): BaseConfig(
    context,
    PREFERENCES_NAME,
    walletDataProvider,
    migrations = listOf(
        // Migrating relevant keys from default prefs
        SharedPreferencesMigration(
            context = context,
            sharedPreferencesName = context.packageName + "_preferences",
            keysToMigrate = setOf(
                AUTO_HIDE_BALANCE.name,
                EXCHANGE_CURRENCY_DETECTED.name
            )
        ),
        ExchangeCurrencyMigration(
            context = context,
            sharedPreferencesName = context.packageName + "_preferences",
            exchangeRates = exchangeRates
        )
    )
) {
    companion object {
        private const val PREFERENCES_NAME = "wallet_ui"
        val AUTO_HIDE_BALANCE = booleanPreferencesKey("hide_balance")
        val SHOW_TAP_TO_HIDE_HINT = booleanPreferencesKey("show_tap_to_hide_balance_hint")
        val SELECTED_CURRENCY = stringPreferencesKey("exchange_currency")
        val EXCHANGE_CURRENCY_DETECTED = booleanPreferencesKey("exchange_currency_detected")
        val LAST_TOTAL_BALANCE = longPreferencesKey("last_total_balance")
        val CUSTOMIZED_SHORTCUTS = stringPreferencesKey("customized_shortcuts")
        val IS_SHORTCUT_INFO_HIDDEN = booleanPreferencesKey("is_shortcut_info_hidden")

        /** The Settings "Advanced mode" switch. Off unless the user turns it on. */
        val ADVANCED_MODE = booleanPreferencesKey("advanced_mode")
    }

    /**
     * Emits the "Advanced mode" setting; false until the user turns it on.
     *
     * observePreservingErrors, not observe: observe() turns a failed read into one
     * empty emission and then completes, so a collector held for a screen's
     * lifetime would stop seeing later saves. See [advancedModeFlow].
     */
    fun observeAdvancedMode(): Flow<Boolean> =
        advancedModeFlow(observePreservingErrors(ADVANCED_MODE))

    suspend fun setAdvancedMode(enabled: Boolean) = set(ADVANCED_MODE, enabled)

    suspend fun getExchangeCurrencyCode(): String {
        return get(SELECTED_CURRENCY) ?: Constants.DEFAULT_EXCHANGE_CURRENCY
    }

    fun getExchangeCurrencyCodeBlocking(): String {
        return runBlocking { getExchangeCurrencyCode() }
    }
}

private val advancedModeLog = org.slf4j.LoggerFactory.getLogger("WalletUIConfig.advancedMode")

/**
 * The saved Advanced mode value as a flow that never completes on a failed read:
 * an [Exception] emits false (basic mode) and re-subscribes to [source] after
 * [retryDelayMs], so a save made once storage recovers still reaches collectors.
 * Cancellation and [Error]s are not retried; they propagate. See [retryReadErrors].
 */
fun advancedModeFlow(
    source: Flow<Boolean?>,
    retryDelayMs: (Long) -> Long = ::readRetryDelayMs
): Flow<Boolean> = source
    .map { it ?: false }
    .retryReadErrors(false, retryDelayMs) { e, attempt ->
        advancedModeLog.warn("advanced mode read failed (attempt {})", attempt, e)
    }

class ExchangeCurrencyMigration(
    private val context: Context,
    private val sharedPreferencesName: String,
    private val exchangeRates: ExchangeRatesProvider
) : DataMigration<Preferences> {

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(WalletUIConfig::class.java)
    }

    private val migration = SharedPreferencesMigration(
        context = context,
        sharedPreferencesName = sharedPreferencesName,
        keysToMigrate = setOf(
            WalletUIConfig.SELECTED_CURRENCY.name
        )
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        return migration.shouldMigrate(currentData)
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        try {
            // previous versions of the app (prior to 7.3.3) may have stored an obsolete
            // currency code in the preferences.  Let's change to the most up to date.
            val sharedPreferences = context.getSharedPreferences(sharedPreferencesName, Context.MODE_PRIVATE)
            val currentValue = sharedPreferences.getString(WalletUIConfig.SELECTED_CURRENCY.name, null)
            currentValue?.let {
                val otherName = CurrencyInfo.getOtherName(currentValue)
                val fixedValue = if (CurrencyInfo.hasObsoleteCurrency(otherName)) {
                    CurrencyInfo.getUpdatedCurrency(otherName)
                } else {
                    otherName
                }
                sharedPreferences.edit { putString(WalletUIConfig.SELECTED_CURRENCY.name, fixedValue) }
            }
            // The database might have obsolete currencies as well
            exchangeRates.cleanupObsoleteCurrencies()
        } catch (ex: Exception) {
            log.error("Error migrating obsolete currencies", ex)
        }

        // Continue migration normally
        return migration.migrate(currentData)
    }

    override suspend fun cleanUp() {
        migration.cleanUp()
    }
}
