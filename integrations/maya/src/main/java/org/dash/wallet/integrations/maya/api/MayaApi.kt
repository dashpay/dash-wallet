package org.dash.wallet.integrations.maya.api

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.maya.MayaWebApi
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.dash.wallet.integrations.maya.utils.MayaConfig
import org.dash.wallet.integrations.maya.utils.MayaConstants
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

interface MayaApi {
    val poolInfoList: MutableStateFlow<List<PoolInfo>>
    val apiError: MutableStateFlow<Exception?>
    var notificationIntent: Intent?
    var showNotificationOnResult: Boolean

    suspend fun swap()
    suspend fun reset()
    //fun observePoolList(): Flow<List<PoolInfo>>
    fun observePoolList(fiatExchangeRate: Fiat): Flow<List<PoolInfo>>
}

class MayaApiAggregator @Inject constructor(
    private val webApi: MayaWebApi,
    private val blockchainApi: MayaBlockchainApi,
    private val walletDataProvider: WalletDataProvider,
    private val notificationService: NotificationService,
    private val analyticsService: AnalyticsService,
    private val config: MayaConfig,
    private val globalConfig: Configuration,
    private val securityFunctions: AuthenticationManager,
    private val transactionMetadataProvider: TransactionMetadataProvider,
    @ApplicationContext private val appContext: Context
): MayaApi {
    companion object {
        private val log = LoggerFactory.getLogger(MayaApiAggregator::class.java)
        private val UPDATE_FREQ_MS = TimeUnit.SECONDS.toMillis(30)
        private const val CONFIRMED_STATUS = "confirmed"
        private const val VALID_STATUS = "valid"
        private const val MESSAGE_RECEIVED_STATUS = "received"
        private const val MESSAGE_FAILED_STATUS = "failed"
    }

    private val params = walletDataProvider.networkParameters
    private var tickerJob: Job? = null
    private val configScope = CoroutineScope(Dispatchers.IO)
    private val responseScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )
    private val statusScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )
    private var poolListLastUpdated: Long = 0
    override val poolInfoList = MutableStateFlow<List<PoolInfo>>(listOf())

    override val apiError = MutableStateFlow<Exception?>(null)
    override var notificationIntent: Intent? = null
    override var showNotificationOnResult = false

    override suspend fun swap() {
        TODO("Not yet implemented")
    }

    init {
        walletDataProvider.attachOnWalletWipedListener {
            configScope.launch { reset() }
        }

        config.observe(MayaConfig.BACKGROUND_ERROR)
            .filterNot { it.isNullOrEmpty() }
            .onEach {
                if (apiError.value == null) {
                    apiError.value = MayaException(it ?: "")
                    config.set(MayaConfig.BACKGROUND_ERROR, "")
                }
            }
            .launchIn(configScope)
    }

    private suspend fun updatePoolList(fiatExchangeRate: Fiat) {
        val resultWithUSDRates = webApi.getPoolInfo()
        val resultWithFiatRates = resultWithUSDRates.map { pool ->
            log.info("adjusting fiat value {}: {}", pool.asset, pool)
            log.info("  {}", fiatExchangeRate.toFriendlyString())
            pool.setAssetPrice(fiatExchangeRate)
            log.info("  {}", pool.assetPriceFiat.toFriendlyString())
            pool
        }
        log.info("USD: {}", resultWithUSDRates.map { it.assetPriceFiat })
        log.info("Fiat: {}", resultWithFiatRates.map { it.assetPriceFiat })
        poolInfoList.value = resultWithFiatRates
    }

    override suspend fun reset() {
        log.info("reset is triggered")
        poolInfoList.value = listOf()
        apiError.value = null
    }

    private fun isError(): Boolean {
        val savedError = runBlocking { config.get(MayaConfig.BACKGROUND_ERROR) ?: "" }

        if (savedError.isNotEmpty()) {
            apiError.value = MayaException(savedError)
            configScope.launch { config.set(MayaConfig.BACKGROUND_ERROR, "") }
            log.info("found an error: $savedError")
            return true
        }

        return false
    }

    private fun cancelTrackingJob() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun handleError(ex: Exception, error: String) {
        apiError.value = ex
        notifyIfNeeded(error, "maya_error")
        log.error("$error: $ex")
        analyticsService.logError(ex)
    }

    private fun notifyIfNeeded(message: String, tag: String) {
        if (showNotificationOnResult) {
            notificationService.showNotification(
                tag,
                message,
                intent = notificationIntent
            )
        }
    }

//    override fun observePoolList(): Flow<List<PoolInfo>> {
//        if (shouldRefresh()) {
//            refreshRates(Fiat.valueOf(MayaConstants.DEFAULT_EXCHANGE_CURRENCY, 100000000))
//        }
//        return poolInfoList
//    }

    override fun observePoolList(fiatExchangeRate: Fiat): Flow<List<PoolInfo>> {
       log.info("observePoolList(${fiatExchangeRate.toFriendlyString()})")
        if (shouldRefresh()) {
            refreshRates(fiatExchangeRate)
        }
        return poolInfoList
    }

    private fun refreshRates(fiatExchangeRate: Fiat) {
        log.info("refreshRates(${fiatExchangeRate.toFriendlyString()})")
        if (!shouldRefresh()) {
            return
        }

        responseScope.launch {
            updatePoolList(fiatExchangeRate)
            poolListLastUpdated = System.currentTimeMillis()
        }
    }

    private fun shouldRefresh(): Boolean {
        val now = System.currentTimeMillis()
        return poolListLastUpdated == 0L || now - poolListLastUpdated > UPDATE_FREQ_MS
    }
}
