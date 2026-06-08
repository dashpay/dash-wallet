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
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFiat
import org.dash.wallet.integrations.maya.model.AccountDataUIModel
import org.dash.wallet.integrations.maya.model.InboundAddress
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.dash.wallet.integrations.maya.model.SwapQuote
import org.dash.wallet.integrations.maya.model.SwapQuoteRequest
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.utils.MayaConfig
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Legacy Maya-specific surface kept for backwards compatibility.
 *
 * New consumers should depend on [SwapProvider] instead — [MayaApiAggregator] implements
 * both. Once every call site is migrated this interface can be removed.
 */
interface MayaApi {
    val poolInfoList: StateFlow<List<PoolInfo>>
    val apiError: StateFlow<Exception?>
    var notificationIntent: Intent?
    var showNotificationOnResult: Boolean

    suspend fun swap()
    suspend fun reset()

    fun observePoolList(fiatExchangeRate: Fiat): Flow<List<PoolInfo>>
    suspend fun getInboundAddresses(): List<InboundAddress>

    // Default lives on [SwapProvider.getDefaultSwapQuote] — Kotlin refuses defaults
    // declared on more than one super interface, so [MayaApiAggregator] gets the
    // default solely from [SwapProvider].
    suspend fun getDefaultSwapQuote(toAsset: String, value: Long): SwapQuote?
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
) : MayaApi, SwapProvider {
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
        poolInfoList.value = webApi.getPoolInfo()
    }

    override suspend fun getInboundAddresses(): List<InboundAddress> {
        return webApi.getInboundAddresses()
    }

    override suspend fun getDefaultSwapQuote(toAsset: String, value: Long): SwapQuote? {
        return webApi.getDefaultSwapQuote(toAsset, value)
    }

    override suspend fun getDefaultSwapQuote(
        toAsset: String,
        destinationAddress: String,
        value: Long
    ): SwapQuote? {
        return webApi.getDefaultSwapQuote(toAsset, destinationAddress, value)
    }

    override suspend fun getSwapInfo(swapRequest: SwapQuoteRequest): ResponseResource<SwapTradeUIModel> {
        return webApi.getSwapInfo(swapRequest)
    }

    override suspend fun commitSwapTransaction(
        tradeId: String,
        swapTradeUIModel: SwapTradeUIModel
    ): ResponseResource<SwapTradeUIModel> {
        return blockchainApi.commitSwapTransaction(tradeId, swapTradeUIModel)
    }

    override suspend fun getUserAccounts(currency: String): List<AccountDataUIModel> {
        return webApi.getUserAccounts(currency)
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

    override fun applyPoolPrices(pools: List<PoolInfo>, usdToFiat: Fiat) {
        // Liquidity-weighted USD price of CACAO from all available USD-stable pools.
        // Sum of asset balances / sum of cacao balances naturally weights by depth.
        val stablePools = pools.filter {
            (it.currencyCode == "USDT" || it.currencyCode == "USDC") &&
                it.status.equals("available", ignoreCase = true)
        }
        val sumStableCacao = stablePools.fold(BigDecimal.ZERO) { acc, p ->
            acc + (p.balanceCacao.toBigDecimalOrNull() ?: BigDecimal.ZERO)
        }
        val sumStableAsset = stablePools.fold(BigDecimal.ZERO) { acc, p ->
            acc + (p.balanceAsset.toBigDecimalOrNull() ?: BigDecimal.ZERO)
        }
        if (sumStableCacao.signum() <= 0 || sumStableAsset.signum() <= 0) {
            log.warn("no stablecoin pool data; skipping price update")
            return
        }
        log.info("stable pools: {} ({} pools)", stablePools.map { it.asset }, stablePools.size)

        // usdToFiat is the wallet's "1 USD in SELECTED_CURRENCY" rate. Each pool's
        // USD price is computed via the (balance_cacao * Σstable_asset) /
        // (balance_asset * Σstable_cacao) cross-product (CACAO's decimals cancel,
        // and pool assets share 8 decimals so the result is USD per whole asset).
        // Multiply by usdToFiat to land in the selected fiat.
        val fiatPerUsd = usdToFiat.toBigDecimal()

        pools.forEach { pool ->
            val priceUsd = priceInUsd(pool, sumStableCacao, sumStableAsset)
            if (priceUsd == null || priceUsd.signum() <= 0) {
                log.info("no USD price for {}", pool.asset)
                return@forEach
            }
            pool.assetPriceFiat = priceUsd.multiply(fiatPerUsd).toFiat(usdToFiat.currencyCode)
            log.info("$priceUsd, ${pool.assetPriceFiat} -> ${pool.asset}")
        }
    }

    private fun priceInUsd(
        pool: PoolInfo,
        sumStableCacao: BigDecimal,
        sumStableAsset: BigDecimal
    ): BigDecimal? {
        val cacao = pool.balanceCacao.toBigDecimalOrNull() ?: return null
        val asset = pool.balanceAsset.toBigDecimalOrNull() ?: return null
        if (asset.signum() == 0 || sumStableCacao.signum() == 0) return null
        return cacao.multiply(sumStableAsset)
            .divide(asset.multiply(sumStableCacao), 10, RoundingMode.HALF_UP)
    }
}
