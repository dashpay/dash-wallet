package de.schildbach.wallet.rates

import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.database.dao.ExchangeRatesDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * @author Samuel Barbosa
 */
class ExchangeRatesRepository @Inject constructor(
    private val exchangeRatesDao: ExchangeRatesDao
): ExchangeRatesProvider {
    companion object {
        private val log = LoggerFactory.getLogger(ExchangeRatesRepository::class.java)
        private val UPDATE_FREQ_MS = TimeUnit.SECONDS.toMillis(30)
    }

    private val executor: Executor
    private val exchangeRatesClients: Deque<ExchangeRatesClient> = ArrayDeque()

    private var lastUpdated: Long = 0
    @JvmField
    var isLoading = MutableLiveData<Boolean>()
    @JvmField
    var hasError = MutableLiveData<Boolean>()
    private var isRefreshing = false

    init {
        executor = Executors.newSingleThreadExecutor()
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
        executor.execute {
            val rates: List<ExchangeRate>?
            try {
                rates = exchangeRatesClient.rates
                if (rates != null && rates.isNotEmpty()) {
                    exchangeRatesDao.insertAll(rates)
                    lastUpdated = System.currentTimeMillis()
                    populateExchangeRatesStack()
                    hasError.postValue(false)
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
            }
        }
    }

    private fun handleRefreshError() {
        isRefreshing = false
        if (exchangeRatesDao.count() == 0) {
            hasError.postValue(true)
        }
    }

    private fun shouldRefresh(): Boolean {
        val now = System.currentTimeMillis()
        return lastUpdated == 0L || now - lastUpdated > UPDATE_FREQ_MS
    }

    // This will return null if not found
    override suspend fun getExchangeRate(currencyCode: String): ExchangeRate? {
        return withContext(Dispatchers.IO) {
            exchangeRatesDao.getExchangeRateForCurrency(currencyCode)
        }
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
}