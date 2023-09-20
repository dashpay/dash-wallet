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

package org.dash.wallet.integrations.maya.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.integrations.maya.utils.MayaConstants
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class FiatExchangeRateApiAggregator @Inject constructor(
    private val exchangeRateApi: ExchangeRateApi
) {
    companion object {
        val log: Logger = LoggerFactory.getLogger(FiatExchangeRateApiAggregator::class.java)
    }
    suspend fun getRate(currencyCode: String): ExchangeRate? {
        val response = exchangeRateApi.getRates(MayaConstants.DEFAULT_EXCHANGE_CURRENCY).body()
        val exchangeRate = response?.rates?.get(currencyCode) ?: 0.0
        log.info("exchange rate: {} {}", exchangeRate, currencyCode)
        return if (exchangeRate != 0.0) {
            ExchangeRate(currencyCode, exchangeRate.toString())
        } else {
            null
        }
    }
}

interface FiatExchangeRateProvider {
    val fiatExchangeRate: Flow<ExchangeRate>
    fun observeFiatRate(currencyCode: String): Flow<ExchangeRate?>
}

class FiatExchangeRateAggregatedProvider @Inject constructor(
    private val fiatExchangeRateApi: FiatExchangeRateApiAggregator
) : FiatExchangeRateProvider {
    companion object {
        private val log = LoggerFactory.getLogger(FiatExchangeRateApiAggregator::class.java)
        private val UPDATE_FREQ_MS = TimeUnit.SECONDS.toMillis(30)
    }

    private val responseScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )
    private var poolListLastUpdated: Long = 0
    override val fiatExchangeRate = MutableStateFlow(ExchangeRate(MayaConstants.DEFAULT_EXCHANGE_CURRENCY, "1.0"))

    override fun observeFiatRate(currencyCode: String): Flow<ExchangeRate?> {
        if (shouldRefresh()) {
            refreshRates(currencyCode)
        }
        return fiatExchangeRate
    }

    private fun refreshRates(currencyCode: String) {
        if (!shouldRefresh()) {
            return
        }

        responseScope.launch {
            updateExchangeRates(currencyCode)
            poolListLastUpdated = System.currentTimeMillis()
        }
    }

    private fun shouldRefresh(): Boolean {
        val now = System.currentTimeMillis()
        return poolListLastUpdated == 0L || now - poolListLastUpdated > UPDATE_FREQ_MS
    }

    private suspend fun updateExchangeRates(currencyCode: String) {
        fiatExchangeRateApi.getRate(currencyCode)?.let { rate ->
            fiatExchangeRate.value = rate
        }
    }
}
