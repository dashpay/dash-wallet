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

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.maya.model.InboundAddress
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.dash.wallet.integrations.maya.utils.MayaConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

interface MayaApi {
    val poolInfoList: StateFlow<List<PoolInfo>>
    val apiError: StateFlow<Exception?>
    var notificationIntent: Intent?
    var showNotificationOnResult: Boolean

    suspend fun swap()
    suspend fun reset()

    fun observePoolList(fiatExchangeRate: Fiat): Flow<List<PoolInfo>>
    suspend fun getInboundAddresses(): List<InboundAddress>
}

class MayaApiAggregator @Inject constructor(
    private val webApi: MayaWebApi,
    private val blockchainApi: MayaBlockchainApi,
    private val walletDataProvider: WalletDataProvider,
    private val notificationService: NotificationService,
    private val analyticsService: AnalyticsService,
    private val config: MayaConfig,
    private val securityFunctions: AuthenticationManager,
    private val transactionMetadataProvider: TransactionMetadataProvider
) : MayaApi {
    companion object {
        private val log = LoggerFactory.getLogger(MayaApiAggregator::class.java)
        private val UPDATE_FREQ_MS = TimeUnit.SECONDS.toMillis(30)
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

    override suspend fun getInboundAddresses(): List<InboundAddress> {
        return webApi.getInboundAddresses()
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
