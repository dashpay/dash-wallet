package de.schildbach.wallet.rates

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.AppDatabase
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * @author Samuel Barbosa
 */
class ExchangeRatesRepository private constructor(): ExchangeRatesProvider {
    private val appDatabase: AppDatabase = AppDatabase.getAppDatabase()
    private val executor: Executor
    private val exchangeRatesClients: Deque<ExchangeRatesClient> = ArrayDeque()
    private val exchangeRatesDao: ExchangeRatesDao
    private var lastUpdated: Long = 0
    @JvmField
    var isLoading = MutableLiveData<Boolean>()
    @JvmField
    var hasError = MutableLiveData<Boolean>()
    private var isRefreshing = false
    private fun populateExchangeRatesStack() {
        if (!exchangeRatesClients.isEmpty()) {
            exchangeRatesClients.clear()
        }
        exchangeRatesClients.push(DashRatesSecondFallback.getInstance())
        //These sources do not return valid data (TODO: Remove these or replace these?)
        //exchangeRatesClients.push(DashRatesFirstFallback.getInstance());
        //exchangeRatesClients.push(DashRatesClient.getInstance());
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
        if (appDatabase.exchangeRatesDao().count() == 0) {
            hasError.postValue(true)
        }
    }

    private fun shouldRefresh(): Boolean {
        val now = System.currentTimeMillis()
        return lastUpdated == 0L || now - lastUpdated > UPDATE_FREQ_MS
    }

    val rates: LiveData<List<ExchangeRate>>
        get() {
            if (shouldRefresh()) {
                refreshRates()
            }
            return exchangeRatesDao.all
        }

    // it is possible that the currencyCode could be null
    fun getRate(currencyCode: String?): LiveData<ExchangeRate> {
        if (shouldRefresh()) {
            refreshRates()
        }
        return exchangeRatesDao.getRate(currencyCode)
    }

    fun searchRates(query: String): LiveData<List<ExchangeRate>> {
        return exchangeRatesDao.searchRates(query)
    }

    // This will return null if not found
    fun getExchangeRate(currencyCode: String): ExchangeRate? {
        return exchangeRatesDao.getExchangeRateForCurrency(currencyCode)
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

    companion object {
        private val log = LoggerFactory.getLogger(
            ExchangeRatesRepository::class.java
        )
        @JvmStatic
        val instance =  ExchangeRatesRepository()
        private val UPDATE_FREQ_MS = TimeUnit.SECONDS.toMillis(30)
    }

    init {
        executor = Executors.newSingleThreadExecutor()
        exchangeRatesDao = appDatabase.exchangeRatesDao()
        populateExchangeRatesStack()
    }
}