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
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.util.Constants
import javax.inject.Inject
import javax.inject.Singleton

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
    }

    suspend fun getExchangeCurrencyCode(): String {
        return get(SELECTED_CURRENCY) ?: Constants.DEFAULT_EXCHANGE_CURRENCY
    }

    fun getExchangeCurrencyCodeBlocking(): String {
        return runBlocking { getExchangeCurrencyCode() }
    }
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
                sharedPreferences.edit().putString(WalletUIConfig.SELECTED_CURRENCY.name, fixedValue).apply()
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
