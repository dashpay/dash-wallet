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
    val exchangeRateApi: ExchangeRateApi
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
    fun observeFiatRates(): Flow<List<ExchangeRate>>
    fun observeFiatRate(currencyCode: String): Flow<ExchangeRate?>
}

class FiatExchangeRateAggregatedProvider @Inject constructor(
    val fiatExchangeRateApi: FiatExchangeRateApiAggregator
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
    override fun observeFiatRates(): Flow<List<ExchangeRate>> {
        TODO("Not yet implemented")
    }

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

    suspend fun updateExchangeRates(currencyCode: String) {
        fiatExchangeRateApi.getRate(currencyCode)?.let { rate ->
            fiatExchangeRate.value = rate
        }
    }
}
