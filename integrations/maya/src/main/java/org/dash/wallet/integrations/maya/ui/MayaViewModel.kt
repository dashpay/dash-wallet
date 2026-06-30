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

package org.dash.wallet.integrations.maya.ui

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.isCurrencyFirst
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFiat
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.api.DispatchingSwapProvider
import org.dash.wallet.integrations.maya.api.FiatExchangeRateProvider
import org.dash.wallet.integrations.maya.api.MayaApiAggregator
import org.dash.wallet.integrations.maya.api.RouteProvider
import org.dash.wallet.integrations.maya.api.SwapProvider
import org.dash.wallet.integrations.maya.model.InboundAddress
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.dash.wallet.integrations.maya.payments.MayaCurrencyList
import org.dash.wallet.integrations.maya.swapkit.SwapKitApiAggregator
import org.dash.wallet.integrations.maya.utils.MayaConfig
import org.dash.wallet.integrations.maya.utils.SwapBackend
import org.dash.wallet.integrations.maya.utils.SwapDirection
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import javax.inject.Inject

data class MayaPortalUIState(
    val errorCode: Int? = null
)

/**
 * Row model for the "Select coin" picker. Context-free: name/code are kept as
 * string resource IDs ([nameId]/[codeId]) and resolved with stringResource in the
 * row composable so the ViewModel stays free of Android Context.
 */
data class CoinPickerItem(
    val asset: String,
    val currencyCode: String,
    @androidx.annotation.StringRes val nameId: Int,
    @androidx.annotation.StringRes val codeId: Int,
    // Ordered icon URLs to try in sequence until one loads (see GenericUtils.getCoinIconUrls).
    val iconUrls: List<String>,
    val price: String?,
    // Route-provider label string res: maya / near (single provider), or
    // "Multiple networks" while a both-provider asset's preferred network is still
    // being resolved.
    @androidx.annotation.StringRes val routeLabelId: Int?,
    // True when [routeLabelId] is the asynchronously-calculated preferred network for
    // a both-provider asset (rendered with a trailing "*"); false for statically-known
    // single-provider labels and the "Multiple networks" placeholder.
    val routeCalculated: Boolean,
    val isHalted: Boolean,
    val isEnabled: Boolean
)

data class CurrencyPickerUIState(
    val coins: List<CoinPickerItem> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    // False when the device has no internet. While offline the coin list is emptied
    // (see currencyPickerUIState), so the picker hides the search bar and shows the
    // "No available coins" empty state plus the "no connection" toast.
    val isOnline: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MayaViewModel @Inject constructor(
    private val globalConfig: Configuration,
    private val config: MayaConfig,
    private val swapProvider: SwapProvider,
    private val fiatExchangeRateProvider: FiatExchangeRateProvider,
    exchangeRatesProvider: ExchangeRatesProvider,
    val analytics: AnalyticsService,
    walletUIConfig: WalletUIConfig,
    networkState: NetworkStateInt
) : ViewModel() {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(MayaViewModel::class.java)
    }

    private var fiatFormat: MonetaryFormat = MonetaryFormat()
        .minDecimals(GenericUtils.getCurrencyDigits())
        .withLocale(Locale.getDefault())
        .noCode()

    val networkError = SingleLiveEvent<Unit>()

    // private var dashExchangeRate: org.bitcoinj.utils.ExchangeRate? = null
    private var fiatExchangeRate: Fiat? = null

    private val _uiState = MutableStateFlow(MayaPortalUIState())
    val uiState: StateFlow<MayaPortalUIState> = _uiState.asStateFlow()

    val dashFormat: MonetaryFormat
        get() = globalConfig.format.noCode()

    /**
     * The currently-active swap backend. Resolves through [DispatchingSwapProvider] so
     * the portal screen can show the correct provider name + logo. Falls back to MAYA
     * if the swap provider isn't the dispatcher (defensive — shouldn't happen in prod).
     */
    val activeSwapBackend: SwapBackend
        get() = (swapProvider as? DispatchingSwapProvider)?.currentBackend() ?: SwapBackend.MAYA

    val poolList = MutableStateFlow<List<PoolInfo>>(listOf())
    private val _inboundAddresses = MutableStateFlow<List<InboundAddress>>(emptyList())
    val inboundAddresses: StateFlow<List<InboundAddress>> = _inboundAddresses.asStateFlow()
    private val _exchangeRates = MutableStateFlow<List<ExchangeRate>>(listOf())
    val exchangeRates = _exchangeRates.asStateFlow()

    // Halted when either the per-chain inbound list reports a halt (native Maya
    // backend) OR any Maya-only pool is flagged halted (SwapKit backend, where the
    // signal is carried per-asset on PoolInfo — see SwapKitApiAggregator.markMayaInfo).
    val hasHaltedCoins: StateFlow<Boolean> = combine(inboundAddresses, poolList) { addresses, pools ->
        addresses.any { it.halted } || pools.any { it.mayaHalted }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    // Lazy: building the parsers iterates the full currency list, which is only needed
    // once the user reaches the address-input screen. Computing it eagerly ran on the
    // main thread during the nav-graph-scoped construction (triggered by the portal),
    // stalling the portal's enter slide.
    val paymentParsers by lazy { MayaCurrencyList.getPaymentProcessors() }

    private val _searchQuery = MutableStateFlow("")

    // The operation the user picked on the portal (BUY/SELL). Drives which coins the
    // picker offers: BUY (crypto -> Dash) is SwapKit-only and cannot route Maya-only
    // assets, so those are hidden when buying. Defaults to SELL (Dash -> crypto), the
    // direction supported by both backends.
    private val _swapDirection = MutableStateFlow(SwapDirection.SELL)
    val swapDirection: StateFlow<SwapDirection> = _swapDirection.asStateFlow()

    fun setSwapDirection(direction: SwapDirection) {
        _swapDirection.value = direction
        // Let the backend skip direction-irrelevant work (e.g. SwapKit skips the Maya halt query
        // and preferred-route quotes on BUY, which excludes Maya-only assets).
        swapProvider.setSwapDirection(direction)
    }

    // Membership map: which assets are part of the curated MayaCurrencyList, with
    // their translatable name/code resource IDs. Replaces the old defaultItemMap.
    private val currencyResIds: Map<String, Pair<Int, Int>> =
        MayaCurrencyList.all.associateBy({ it.asset }, { it.nameId to it.codeId })

    /**
     * Single UIState for the "Select coin" picker. Builds the row list from the
     * pool list + inbound addresses (same rules as the legacy fragment), then
     * applies the search filter. Context-free — name/code stay as resource IDs.
     */
    val currencyPickerUIState: StateFlow<CurrencyPickerUIState> =
        combine(
            poolList,
            inboundAddresses,
            _searchQuery,
            swapProvider.preferredRouteProviders,
            // Pair connectivity with the chosen direction so the coin list recomputes
            // when either changes (combine tops out at 5 typed flows).
            networkState.isConnected.combine(_swapDirection) { isOnline, direction ->
                isOnline to direction
            }
        ) { pools, addresses, query, preferredRoutes, (isOnline, direction) ->
            // Offline: show no coins at all (the screen renders the "No available coins"
            // empty state + the no-connection toast). We don't surface the cached pool
            // list, since it can't be traded without a live connection.
            val coins = if (!isOnline) {
                emptyList()
            } else {
                pools.filter { pool -> pool.asset != "DASH.DASH" }
                    .filter { pool ->
                        currencyResIds.containsKey(pool.asset) &&
                            pool.status.equals("available", ignoreCase = true)
                    }
                    .filter { pool -> addresses.any { pool.asset.startsWith(it.chain) } }
                    // BUY (crypto -> Dash) routes only through SwapKit/NEAR; Maya-only
                    // assets have no buy path, so hide them. SELL keeps the full list.
                    .filter { pool -> direction == SwapDirection.SELL || !pool.mayaOnly }
                    .map { pool ->
                        val chain = pool.asset.substringBefore('.')
                        val inbound = addresses.find { it.chain == chain }
                        // Maya-only assets carry halt status per-asset (pool.mayaHalted),
                        // OR-ed with the per-chain inbound halt used by the native Maya backend.
                        val isHalted = inbound?.halted == true || pool.mayaHalted
                        val isEnabled = inbound != null && !isHalted
                        val price = if (isEnabled) {
                            GenericUtils.formatFiatWithoutComma(formatFiat(pool.assetPriceFiat))
                        } else {
                            null
                        }
                        val resIds = currencyResIds[pool.asset]
                        // Single-provider assets are labelled statically from the token-list
                        // classification. Both-provider assets show "Multiple networks" until
                        // the background quote resolves a preferred network, then show it with
                        // a trailing "*" (routeCalculated) to flag it as calculated.
                        val preferred = preferredRoutes[pool.asset]
                        val routeLabelId: Int
                        val routeCalculated: Boolean
                        when {
                            pool.mayaOnly -> {
                                routeLabelId = R.string.maya_route_label_maya
                                routeCalculated = false
                            }
                            pool.nearOnly -> {
                                routeLabelId = R.string.maya_route_label_near
                                routeCalculated = false
                            }
                            direction == SwapDirection.BUY -> {
                                // BUY routes via NEAR (Maya can't buy DASH), so a both-provider
                                // asset is bought via NEAR. Checked BEFORE the preferred-route map:
                                // that map can still hold a stale MAYA entry from a prior SELL
                                // session or the persisted snapshot, which would otherwise show as
                                // "Maya*" here. No preferred-route quote is run for BUY.
                                routeLabelId = R.string.maya_route_label_near
                                routeCalculated = false
                            }
                            preferred == RouteProvider.MAYA -> {
                                routeLabelId = R.string.maya_route_label_maya
                                routeCalculated = true
                            }
                            preferred == RouteProvider.NEAR -> {
                                routeLabelId = R.string.maya_route_label_near
                                routeCalculated = true
                            }
                            else -> {
                                routeLabelId = R.string.maya_route_label_multiple
                                routeCalculated = false
                            }
                        }
                        CoinPickerItem(
                            asset = pool.asset,
                            currencyCode = pool.currencyCode,
                            nameId = resIds?.first ?: 0,
                            codeId = resIds?.second ?: 0,
                            iconUrls = GenericUtils.getCoinIconUrls(pool.currencyCode, pool.asset),
                            price = price,
                            routeLabelId = routeLabelId,
                            routeCalculated = routeCalculated,
                            isHalted = isHalted,
                            isEnabled = isEnabled
                        )
                    }
                    .sortedBy { it.currencyCode }
            }

            // The list is emitted unfiltered; the search filter is applied in the
            // composable layer so it can match the localized coin name (resolved via
            // stringResource from nameId), preserving the legacy fragment's behavior
            // of matching both code and translated name. The ViewModel stays
            // Context-free and cannot resolve those localized strings here.
            CurrencyPickerUIState(
                coins = coins,
                searchQuery = query,
                // Only spin while we're online and still waiting for the first pool list.
                // Offline shows the "No available coins" empty state instead of spinning forever.
                isLoading = pools.isEmpty() && isOnline,
                isOnline = isOnline
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CurrencyPickerUIState())

    fun onSearchQuery(text: String) {
        _searchQuery.value = text
    }

    init {
        // TODO: is this really needed? we don't support DASH swaps
        exchangeRatesProvider.observeExchangeRates()
            .onEach {
                _exchangeRates.value = it
            }.launchIn(viewModelScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest(exchangeRatesProvider::observeExchangeRate)
            .filterNotNull()
            .onEach { exchangeRate ->
                val usdPrice = exchangeRatesProvider.getExchangeRate(Constants.USD_CURRENCY)
                if (usdPrice != null) {
                    val rate = exchangeRate.rate!!.toDouble() / usdPrice.rate!!.toDouble()
                    log.info("exchange rate from CTX: {}", rate)
                }
            }
            .launchIn(viewModelScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { log.info("exchange rate selected currency: {}", it) }
            .flatMapLatest(fiatExchangeRateProvider::observeFiatRate)
            .filterNotNull()
            .onEach { fiatRate ->
                fiatFormat = fiatFormat.minDecimals(GenericUtils.getCurrencyDigits(fiatRate.currencyCode))
                fiatExchangeRate = fiatRate.fiat
                log.info("exchange rate: {}", fiatRate)
            }
            .flatMapLatest { fiatRate ->
                swapProvider.observePoolList(fiatRate.fiat).mapLatest { pools ->
                    pools to fiatRate.fiat
                }
            }
            .onEach { (newPoolList, usdToFiat) ->
                swapProvider.applyPoolPrices(newPoolList, usdToFiat)
                log.info(
                    "exchange rate Pool List: {}",
                    newPoolList.map { pool -> "${pool.asset}=${pool.assetPriceFiat.toFriendlyString()}" }
                )
                poolList.value = newPoolList
            }
            .launchIn(viewModelScope)

        // Re-fetch inbound addresses whenever the pool list transitions to non-empty.
        // SwapKit's getInboundAddresses() can return an empty set on the very first
        // call if the pool refresh is still in flight; this catches up once the
        // pools land. Maya is unaffected (its addresses come from a separate
        // endpoint and don't depend on pool state).
        poolList
            .filter { it.isNotEmpty() && _inboundAddresses.value.isEmpty() }
            .onEach { refreshInboundAddresses() }
            .launchIn(viewModelScope)

        updateInboundAddresses()
    }

    private fun applyPoolPrices(pools: List<PoolInfo>, usdToFiat: Fiat) {
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

    fun formatFiat(fiatAmount: Fiat): String {
        val localCurrencySymbol = GenericUtils.getLocalCurrencySymbol(fiatAmount.currencyCode)

        val fiatBalance = fiatFormat.format(fiatAmount).toString()

        return if (fiatAmount.isCurrencyFirst()) {
            "$localCurrencySymbol $fiatBalance"
        } else {
            "$fiatBalance $localCurrencySymbol"
        }
    }
    fun errorHandled() {
        _uiState.update { it.copy(errorCode = null) }
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }

    fun getPoolInfo(currency: String): PoolInfo? {
        if (poolList.value.isNotEmpty()) {
            return poolList.value.find { it.currencyCode == currency }
        }
        return null
    }

    /**
     * Route-provider label string res for [asset] (`maya` / `near`) when it routes through
     * a single, known provider, or null when undetermined or routable via both. Mirrors the
     * currency picker's classification (pool [PoolInfo.mayaOnly]/[PoolInfo.nearOnly] + the
     * asynchronously-resolved [SwapProvider.preferredRouteProviders]).
     */
    @androidx.annotation.StringRes
    fun getRouteLabelResId(asset: String): Int? {
        val pool = poolList.value.find { it.asset == asset }
        val preferred = swapProvider.preferredRouteProviders.value[asset]
        return when {
            pool?.mayaOnly == true -> R.string.maya_route_label_maya
            pool?.nearOnly == true -> R.string.maya_route_label_near
            preferred == RouteProvider.MAYA -> R.string.maya_route_label_maya
            preferred == RouteProvider.NEAR -> R.string.maya_route_label_near
            else -> null
        }
    }

    private fun updateInboundAddresses() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshInboundAddresses()
        }
    }

    suspend fun refreshInboundAddresses() {
        _inboundAddresses.value = withContext(Dispatchers.IO) { swapProvider.getInboundAddresses() }
    }

    fun getInboundAddress(asset: String): InboundAddress? {
        return if (inboundAddresses.value.isNotEmpty()) {
            val chain = asset.let { it.substring(0, it.indexOf('.')) }
            inboundAddresses.value.find { it.chain == chain }
        } else { null }
    }

    fun isTradingActive(): Boolean {
        return when (swapProvider) {
            is MayaApiAggregator -> {
                val dashInbound = _inboundAddresses.value.find { it.chain == "DASH" }
                if (dashInbound == null) {
                    false
                } else {
                    dashInbound.halted != true
                }
            }

            is SwapKitApiAggregator -> {
                inboundAddresses.value.isNotEmpty()
            }
            is DispatchingSwapProvider -> {
                when (swapProvider.active) {
                    is MayaApiAggregator -> {
                        val dashInbound = _inboundAddresses.value.find { it.chain == "DASH" }
                        if (dashInbound == null) {
                            false
                        } else {
                            dashInbound.halted != true
                        }
                    }

                    is SwapKitApiAggregator -> {
                        inboundAddresses.value.isNotEmpty()
                    }

                    else -> false
                }
            }
            else -> false
        }
    }
}
