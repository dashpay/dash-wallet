package de.schildbach.wallet.rates

import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.database.dao.ExchangeRatesDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.CurrencyInfo
import org.dash.wallet.common.data.ExchangeRatesConfig
import org.dash.wallet.common.data.ExchangeRatesConfig.Companion.EXCHANGE_RATES_PREVIOUS_RETRIEVAL_TIME
import org.dash.wallet.common.data.ExchangeRatesConfig.Companion.EXCHANGE_RATES_RETRIEVAL_FAILURE
import org.dash.wallet.common.data.ExchangeRatesConfig.Companion.EXCHANGE_RATES_RETRIEVAL_TIME
import org.dash.wallet.common.data.ExchangeRatesConfig.Companion.EXCHANGE_RATES_VOLATILE
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.RateRetrievalState
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * @author Samuel Barbosa
 */
class ExchangeRatesRepository @Inject constructor(
    private val exchangeRatesDao: ExchangeRatesDao,
    private val config: ExchangeRatesConfig
): ExchangeRatesProvider {
    companion object {
        private val log = LoggerFactory.getLogger(ExchangeRatesRepository::class.java)
        private val UPDATE_FREQ_MS = TimeUnit.SECONDS.toMillis(30)
        private val STALE_DURATION_MS = TimeUnit.MINUTES.toMillis(30)
    }

    private val exchangeRatesClients: Deque<ExchangeRatesClient> = ArrayDeque()

    private var lastUpdated: Long = 0
    @JvmField
    var isLoading = MutableLiveData<Boolean>()
    @JvmField
    var hasError = MutableLiveData<Boolean>()
    private var isRefreshing = false
    private val refreshScope = CoroutineScope(Dispatchers.IO)
    private val updateTrigger = MutableSharedFlow<Unit>()
    private val previousRates = arrayListOf<ExchangeRate>()

    init {
        populateExchangeRatesStack()
    }

    private fun populateExchangeRatesStack() {
        if (!exchangeRatesClients.isEmpty()) {
            exchangeRatesClients.clear()
        }
        exchangeRatesClients.push(DashRatesSecondFallback.getInstance())
        exchangeRatesClients.push(DashRetailClient.getInstance())
    }

    private fun refreshRates(forceRefresh: Boolean = false) {
        if (!shouldRefresh()) {
            return
        }

        if (exchangeRatesClients.isEmpty()) {
            populateExchangeRatesStack()
        }

        if (!forceRefresh && isRefreshing) {
            return
        }

        isRefreshing = true
        val exchangeRatesClient = exchangeRatesClients.pop()
        isLoading.postValue(true)

        refreshScope.launch {
            try {
                val rates = exchangeRatesClient.rates.filterNot {
                    CurrencyInfo.hasObsoleteCurrency(it.currencyCode)
                }

                if (rates.isNotEmpty()) {
                    previousRates.clear()
                    previousRates.addAll(exchangeRatesDao.getAll())
                    exchangeRatesDao.insertAll(rates)
                    lastUpdated = System.currentTimeMillis()
                    populateExchangeRatesStack()
                    hasError.postValue(false)
                    config.set(EXCHANGE_RATES_RETRIEVAL_FAILURE, true)
                    val prevRetrievalTime = config.get(EXCHANGE_RATES_RETRIEVAL_TIME) ?: 0
                    config.set(EXCHANGE_RATES_PREVIOUS_RETRIEVAL_TIME, prevRetrievalTime)
                    config.set(EXCHANGE_RATES_RETRIEVAL_TIME, System.currentTimeMillis())
                    isRefreshing = false
                    log.info("exchange rates updated successfully with {}", exchangeRatesClient)
                } else if (!exchangeRatesClients.isEmpty()) {
                    refreshRates(true)
                } else {
                    handleRefreshError()
                }
            } catch (e: Exception) {
                log.error("failed to fetch exchange rates with {}", exchangeRatesClient, e)
                if (!exchangeRatesClients.isEmpty()) {
                    refreshRates(true)
                } else {
                    handleRefreshError()
                }
            } finally {
                isLoading.postValue(false)
                updateTrigger.tryEmit(Unit)
            }
        }
    }

    private suspend fun handleRefreshError() {
        isRefreshing = false
        config.set(EXCHANGE_RATES_RETRIEVAL_FAILURE, true)
        if (exchangeRatesDao.count() == 0) {
            hasError.postValue(true)
        }
    }

    private fun shouldRefresh(): Boolean {
        val now = System.currentTimeMillis()
        return lastUpdated == 0L || now - lastUpdated > UPDATE_FREQ_MS
    }

    // This will return null if not found
    override suspend fun getExchangeRate(currencyCode: String): ExchangeRate? =
        exchangeRatesDao.getExchangeRateForCurrency(currencyCode)

    override suspend fun cleanupObsoleteCurrencies() {
        exchangeRatesDao.delete(CurrencyInfo.obsoleteCurrencyMap.keys)
    }

    override fun observeExchangeRates(): Flow<List<ExchangeRate>> {
        if (shouldRefresh()) {
            refreshRates()
        }
        return exchangeRatesDao.observeAll()
    }

    override fun observeExchangeRate(currencyCode: String): Flow<ExchangeRate> {
        if (shouldRefresh()) {
            refreshRates()
        }
        return exchangeRatesDao.observeRate(currencyCode)
    }

    suspend fun isRateStale(currencyCode: String): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastRetrievalTime = config.get(EXCHANGE_RATES_RETRIEVAL_TIME) ?: 0L

        return if ((currentTime - lastRetrievalTime) > STALE_DURATION_MS) {
            true
        } else {
            val lastRate = exchangeRatesDao.getExchangeRateForCurrency(currencyCode)
            lastRate != null && lastRate.retrievalTime != -1L &&
                    (currentTime - lastRate.retrievalTime) > STALE_DURATION_MS
        }
    }

    override fun observeStaleRates(currencyCode: String): Flow<RateRetrievalState> = updateTrigger
        .mapLatest {
            val currentTime = System.currentTimeMillis()
            val lastRetrievalTime = config.get(EXCHANGE_RATES_RETRIEVAL_TIME) ?: 0L

            val staleRate = if ((currentTime - lastRetrievalTime) > STALE_DURATION_MS) {
                true
            } else {
                val lastRate = exchangeRatesDao.getExchangeRateForCurrency(currencyCode)
                lastRate != null && lastRate.retrievalTime != -1L &&
                        (currentTime - lastRate.retrievalTime) > STALE_DURATION_MS
            }
            val previousRetrievalTime = config.get(EXCHANGE_RATES_PREVIOUS_RETRIEVAL_TIME) ?: 0
            val volatile = if (lastRetrievalTime - previousRetrievalTime < TimeUnit.DAYS.toMillis(7)) {
                val previousRate = previousRates.find { it.currencyCode == currencyCode }
                previousRate?.let { prev ->
                    prev.rate?.let { prevRate ->
                        val oldRate = prevRate.toBigDecimal()
                        exchangeRatesDao.getExchangeRateForCurrency(currencyCode)?.let { new ->
                            new.rate?.let { newRate ->
                                (newRate.toBigDecimal() - oldRate) / oldRate > BigDecimal(0.50)
                            }
                    }}
                } ?: false
            } else {
                false
            }
            RateRetrievalState(
                config.get(EXCHANGE_RATES_RETRIEVAL_FAILURE) ?: false,
                staleRate,
                volatile
            )
        }
        .distinctUntilChanged()  // Only emit when the value changes
}
