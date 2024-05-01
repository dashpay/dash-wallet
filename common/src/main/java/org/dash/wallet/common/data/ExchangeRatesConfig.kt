package org.dash.wallet.common.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import org.dash.wallet.common.WalletDataProvider
import javax.inject.Inject

class ExchangeRatesConfig
    @Inject
    constructor(
        context: Context,
        walletDataProvider: WalletDataProvider
    ) : BaseConfig(
            context,
            PREFERENCES_NAME,
            walletDataProvider
        ) {
        companion object {
            private const val PREFERENCES_NAME = "exchange_rates_config"
            val EXCHANGE_RATES_RETRIEVAL_TIME = longPreferencesKey("exchange_rates_retrieval_time")
            val EXCHANGE_RATES_WARNING_DISMISSED = longPreferencesKey("exchange_rates_warning_dismissed")
            val EXCHANGE_RATES_RETRIEVAL_FAILURE = booleanPreferencesKey("exchange_rates_retrieval_error")
            val EXCHANGE_RATES_VOLATILE = booleanPreferencesKey("exchange_rates_retrieval_error")
            val EXCHANGE_RATES_PREVIOUS_RETRIEVAL_TIME = longPreferencesKey("exchange_rates_previous_retrieval_time")
        }
    }
