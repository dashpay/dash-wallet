/*
 * Copyright 2026 Dash Core Group.
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

package org.dash.wallet.integrations.maya.swapkit

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.script.ScriptPattern
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFiat
import org.dash.wallet.integrations.maya.BuildConfig
import org.dash.wallet.integrations.maya.api.MayaBlockchainApi
import org.dash.wallet.integrations.maya.api.MayaException
import org.dash.wallet.integrations.maya.api.MayaWebApi
import org.dash.wallet.integrations.maya.api.SwapProvider
import org.dash.wallet.integrations.maya.model.Account
import org.dash.wallet.integrations.maya.model.AccountDataUIModel
import org.dash.wallet.integrations.maya.model.Balance
import org.dash.wallet.integrations.maya.model.InboundAddress
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.dash.wallet.integrations.maya.model.SwapFees
import org.dash.wallet.integrations.maya.model.SwapQuote
import org.dash.wallet.integrations.maya.model.SwapQuoteRequest
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitFee
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitQuoteRequest
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitRoute
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitSwapRequest
import org.dash.wallet.integrations.maya.utils.MayaConstants
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * SwapKit-backed implementation of [SwapProvider].
 *
 * Strategy: query SwapKit's MAYACHAIN aggregator for everything the wallet UI needs,
 * mapping responses onto the existing Maya-shaped DTOs ([PoolInfo], [SwapQuote],
 * [SwapTradeUIModel], [InboundAddress]) so ViewModels can switch backends without
 * code changes. CACAO-specific fields on [PoolInfo] stay blank — only the fields
 * the wallet actually reads (asset, currencyCode, assetPriceFiat, status) are
 * populated, with [PoolInfo.assetPriceFiat] computed directly from `/price` rather
 * than the stable-pool cross-product Maya uses.
 *
 * The DASH transaction itself is still built by [MayaBlockchainApi.buildAndSendSwapTx]
 * — SwapKit's `/v3/swap` response yields the same `vaultAddress` + `memo` shape the
 * existing builder needs, so no PSBT parsing is required for DASH-as-source.
 */
class SwapKitApiAggregator @Inject constructor(
    private val webApi: SwapKitWebApi,
    private val blockchainApi: MayaBlockchainApi,
    private val walletDataProvider: WalletDataProvider,
    // Source of truth for Maya halt status (mayanode /inbound_addresses). Used only
    // to enrich Maya-only assets — see markMayaInfo(). The coupling to Maya is
    // acceptable because these assets settle exclusively via Maya on SwapKit
    // (SWAPKIT_PROTOCOL.md → "Detecting Maya-only Assets").
    private val mayaWebApi: MayaWebApi
) : SwapProvider {
    companion object {
        private val log = LoggerFactory.getLogger(SwapKitApiAggregator::class.java)
        private val UPDATE_FREQ_MS = TimeUnit.SECONDS.toMillis(30)
        private val DASH_BASE_UNITS = BigDecimal("100000000") // 1e8

        // TEMP TEST FLAG — when true (debug builds only), every Maya-only asset is
        // rendered as halted so the "Halted" chip / disabled state / toast can be
        // verified without waiting for a real Maya halt on a listed Maya-only coin
        // (the only live Maya halts—XRD/ZEC—are not Maya-only here). Set back to
        // false or delete the forcedHalt branch in markMayaInfo() before merging.
        private const val DEBUG_FORCE_MAYA_ONLY_HALT = false
    }

    override val poolInfoList = MutableStateFlow<List<PoolInfo>>(emptyList())
    override val apiError = MutableStateFlow<Exception?>(null)
    override var notificationIntent: Intent? = null
    override var showNotificationOnResult: Boolean = false

    private val responseScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )
    private var poolListLastUpdated: Long = 0L

    // Asset → USD price, captured at refresh time. applyPoolPrices re-seeds from
    // this cache so it stays idempotent across re-emissions AND handles
    // selected-currency switches without re-fetching from SwapKit.
    private val usdPriceCache = mutableMapOf<String, BigDecimal>()

    // Upper-cased identifiers routable from DASH only via MAYACHAIN (no NEAR
    // fallback). Refreshed alongside the pool list. Empty until first refresh.
    private var mayaOnlyAssets: Set<String> = emptySet()

    // Upper-cased identifiers routable from DASH only via NEAR (no MAYACHAIN
    // fallback) — the mirror of [mayaOnlyAssets]. Assets in neither set are routable
    // via both providers. Refreshed alongside the pool list.
    private var nearOnlyAssets: Set<String> = emptySet()

    // Maya chain → halted (chain halted OR global trading paused). Captured at
    // refresh time from mayanode /inbound_addresses; used to stamp pool.mayaHalted.
    private var mayaHaltedChains: Set<String> = emptySet()
    private var mayaGlobalHalt: Boolean = false

    override suspend fun reset() {
        log.info("swapkit reset")
        poolInfoList.value = emptyList()
        apiError.value = null
        poolListLastUpdated = 0L
        usdPriceCache.clear()
        mayaOnlyAssets = emptySet()
        nearOnlyAssets = emptySet()
        mayaHaltedChains = emptySet()
        mayaGlobalHalt = false
    }

    override fun observePoolList(fiatExchangeRate: Fiat): Flow<List<PoolInfo>> {
        if (shouldRefresh()) {
            responseScope.launch {
                refreshPools(fiatExchangeRate)
                poolListLastUpdated = System.currentTimeMillis()
            }
        }
        return poolInfoList
    }

    private fun shouldRefresh(): Boolean {
        val now = System.currentTimeMillis()
        return poolListLastUpdated == 0L || now - poolListLastUpdated > UPDATE_FREQ_MS
    }

    private suspend fun refreshPools(fiatExchangeRate: Fiat) {
        val reachable = webApi.getSwapTo(SwapKitConstants.DASH_ASSET)
        if (reachable.isEmpty()) {
            log.info("swapkit /swapTo returned no assets — leaving pool list as is")
            return
        }
        // /swapTo returns reachable buy-assets — i.e. it never includes the source
        // asset itself. The convert screen looks up DASH's own USD price via
        // `getPoolInfo("DASH")` to compute the DASH↔fiat ratio, so we add DASH.DASH
        // explicitly. The picker excludes DASH.DASH separately, so this only
        // surfaces in price-lookup paths.
        val identifiers = (reachable + SwapKitConstants.DASH_ASSET).distinct()

        val prices = webApi.getPrices(identifiers)
            .associateBy({ it.identifier.uppercase() }, { it.priceUsd })

        // Refresh the Maya-only classification and Maya halt status before building
        // the pools so each PoolInfo can be stamped in a single pass.
        refreshMayaOnlyClassification()
        refreshMayaHaltStatus()

        // Populate `assetPriceFiat` with the raw USD price (stored as a Fiat with code "USD").
        // [applyPoolPrices] then converts USD → selected fiat in a second pass — same
        // contract Maya uses (raw price in pools, fiat conversion in the ViewModel pipeline).
        // Also seed usdPriceCache so applyPoolPrices can re-seed on subsequent
        // invocations (currency switch, repeat emissions) without re-fetching.
        usdPriceCache.clear()
        val pools = identifiers.map { identifier ->
            val priceUsd = prices[identifier.uppercase()] ?: 0.0
            val priceUsdFiat = if (priceUsd > 0.0) {
                val priceBd = BigDecimal(priceUsd)
                usdPriceCache[identifier] = priceBd
                priceBd.toFiat(MayaConstants.DEFAULT_EXCHANGE_CURRENCY)
            } else {
                Fiat.valueOf(MayaConstants.DEFAULT_EXCHANGE_CURRENCY, 0)
            }
            PoolInfo(asset = identifier, status = "Available").also {
                it.assetPriceFiat = priceUsdFiat
                markMayaInfo(it)
            }
        }
        poolInfoList.value = pools
    }

    /**
     * Refresh [mayaOnlyAssets] and [nearOnlyAssets]: which provider(s) can route each
     * identifier from DASH. Method per SWAPKIT_PROTOCOL.md → "Detecting Maya-only
     * Assets": an asset is Maya-only when it is in MAYACHAIN's token list but NOT
     * NEAR's; NEAR-only is the mirror. Assets in both lists are routable via either
     * provider and end up in neither set. Compared case-insensitively on the canonical
     * `identifier`. On failure (either list empty) the previous classification is kept
     * rather than wrongly clearing it.
     */
    private suspend fun refreshMayaOnlyClassification() {
        val mayaIds = webApi.getTokens(SwapKitConstants.MAYACHAIN_PROVIDER)
            .map { it.identifier.uppercase() }
            .toSet()
        val nearIds = webApi.getTokens(SwapKitConstants.NEAR_PROVIDER)
            .map { it.identifier.uppercase() }
            .toSet()
        if (mayaIds.isEmpty() || nearIds.isEmpty()) {
            log.info(
                "swapkit route classification: token list unavailable (maya={}, near={}); keeping previous sets",
                mayaIds.size,
                nearIds.size
            )
            return
        }
        mayaOnlyAssets = mayaIds - nearIds
        nearOnlyAssets = nearIds - mayaIds
        log.info(
            "swapkit route classification: maya-only={} {}, near-only={}",
            mayaOnlyAssets.size,
            mayaOnlyAssets,
            nearOnlyAssets.size
        )
    }

    /**
     * Refresh Maya halt status from mayanode /inbound_addresses. Captures per-chain
     * `halted`/`chainTradingPaused` and the global `globalTradingPaused` flag, which
     * together drive [PoolInfo.mayaHalted] for Maya-only assets.
     */
    private suspend fun refreshMayaHaltStatus() {
        val inbound = mayaWebApi.getInboundAddresses()
        if (inbound.isEmpty()) {
            log.info("swapkit maya halt: no inbound addresses; keeping previous halt status")
            return
        }
        mayaGlobalHalt = inbound.any { it.globalTradingPaused }
        mayaHaltedChains = inbound
            .filter { it.halted || it.chainTradingPaused || it.globalTradingPaused }
            .map { it.chain.uppercase() }
            .toSet()
        log.info("swapkit maya halt: global={} chains={}", mayaGlobalHalt, mayaHaltedChains)
    }

    /**
     * Stamp [PoolInfo.mayaOnly] and [PoolInfo.mayaHalted] from the cached
     * classification + halt status. `mayaHalted` is only meaningful for Maya-only
     * assets — others have a NEAR route and stay tradable through a Maya halt.
     */
    private fun markMayaInfo(pool: PoolInfo) {
        val isMayaOnly = mayaOnlyAssets.contains(pool.asset.uppercase())
        pool.mayaOnly = isMayaOnly
        pool.nearOnly = nearOnlyAssets.contains(pool.asset.uppercase())
        val chainHalted = mayaGlobalHalt ||
            mayaHaltedChains.contains(pool.asset.substringBefore('.').uppercase())
        // TEMP TEST: force Maya-only assets to halted in debug builds — see
        // DEBUG_FORCE_MAYA_ONLY_HALT. Remove this branch before merging.
        val forcedHalt = BuildConfig.DEBUG && DEBUG_FORCE_MAYA_ONLY_HALT
        pool.mayaHalted = isMayaOnly && (forcedHalt || chainHalted)
    }

    override suspend fun getInboundAddresses(): List<InboundAddress> {
        // SwapKit returns the deposit address inline with /v3/swap, so we don't have
        // a vault list. Synthesise one entry per chain that DASH can reach via SwapKit,
        // with halted=false — enough for the picker filter and the "any halted?" toast.
        // Prefer the cached pool list when populated; otherwise fall back to a direct
        // `/swapTo` call so the first invocation isn't blocked behind the pool refresh.
        val cached = poolInfoList.value
        val identifiers = if (cached.isNotEmpty()) {
            cached.map { it.asset }
        } else {
            webApi.getSwapTo(SwapKitConstants.DASH_ASSET)
        }
        val chains = identifiers.map { it.substringBefore('.') }.toSet()
        return chains.map { InboundAddress(chain = it, halted = false) }
    }

    override suspend fun getDefaultSwapQuote(toAsset: String, value: Long): SwapQuote? {
        val sellAmount = baseUnitsToHumanDash(value)
        val response = webApi.getQuote(
            SwapKitQuoteRequest(
                sellAsset = SwapKitConstants.DASH_ASSET,
                buyAsset = toAsset,
                sellAmount = sellAmount,
                slippage = SwapKitConstants.DEFAULT_SLIPPAGE_PERCENT
            )
        ) ?: return null
        return mapToSwapQuote(response.routes.bestRoute(), toAsset, response.error)
    }

    override suspend fun getDefaultSwapQuote(
        toAsset: String,
        destinationAddress: String,
        value: Long
    ): SwapQuote? {
        val sellAmount = baseUnitsToHumanDash(value)
        val response = webApi.getQuote(
            SwapKitQuoteRequest(
                sellAsset = SwapKitConstants.DASH_ASSET,
                buyAsset = toAsset,
                sellAmount = sellAmount,
                slippage = SwapKitConstants.DEFAULT_SLIPPAGE_PERCENT,
                destinationAddress = destinationAddress
            )
        ) ?: return null
        return mapToSwapQuote(response.routes.bestRoute(), toAsset, response.error)
    }

    override suspend fun getSwapInfo(swapRequest: SwapQuoteRequest): ResponseResource<SwapTradeUIModel> {
        val sellAmount = swapRequest.amount.dash.setScale(8, RoundingMode.HALF_UP).toPlainString()
        val sourceAddress = walletDataProvider.wallet?.currentReceiveAddress()?.toBase58()
            ?: return ResponseResource.Failure(MayaException("wallet not loaded"), false, 0, null)

        val map = hashMapOf<Address, Coin>()
        walletDataProvider.wallet!!.unspents.forEach { output ->
            when {
                ScriptPattern.isP2PKH(output.scriptPubKey) -> ScriptPattern.extractHashFromP2PKH(output.scriptPubKey)
                else -> null
            }?.let {
                val address = Address.fromPubKeyHash(walletDataProvider.networkParameters, it)
                map.computeIfPresent(address) { _, value -> output.value + value }
                map.computeIfAbsent(address) {
                    output.value
                }
            }
        }
        val maxAddressBalance = map.values.maxOf { it }
        val address = map.entries.find { maxAddressBalance == it.value }?.key

        val quote = webApi.getQuote(
            SwapKitQuoteRequest(
                sellAsset = swapRequest.source_maya_asset,
                buyAsset = swapRequest.target_maya_asset,
                sellAmount = sellAmount,
                slippage = SwapKitConstants.DEFAULT_SLIPPAGE_PERCENT,
                // sourceAddress = sourceAddress,
                destinationAddress = swapRequest.targetAddress
            )
        ) ?: return ResponseResource.Failure(MayaException("swapkit quote failed"), false, 0, null)

        if (quote.error != null) {
            return ResponseResource.Failure(MayaException(quote.error), false, 0, null)
        }
        val route = quote.routes.bestRoute()
            ?: return ResponseResource.Failure(
                MayaException(quote.providerErrors?.firstOrNull()?.message ?: "no swapkit route"),
                false,
                0,
                null
            )

        // Prefer the wallet's max-balance unspent address; fall back to the current
        // receive address if the wallet has no P2PKH unspents (e.g. brand-new wallet).
        // SwapKit still validates the address format even with disableBalanceCheck=true,
        // so the value must be a real DASH address — but it must belong to *this* wallet
        // so any refund SwapKit issues lands back here.
        //
        // disableBuildTx=true is required on UTXO chains: per SwapKit docs,
        // disableBalanceCheck alone is ignored on UTXO chains because the tx-building
        // step itself fetches per-address balance. We don't need SwapKit to build the
        // DASH tx (MayaBlockchainApi.buildAndSendSwapTx does it locally from
        // vaultAddress+memo), so skipping the build also skips the per-address check
        // that would otherwise fail for HD wallets with balance spread across UTXOs.
        val swap = webApi.postSwap(
            SwapKitSwapRequest(
                routeId = route.routeId,
                sourceAddress = address?.toBase58() ?: sourceAddress,
                destinationAddress = swapRequest.targetAddress,
                disableBalanceCheck = true,
                disableBuildTx = true
            )
        ) ?: return ResponseResource.Failure(MayaException("swapkit /v3/swap failed"), false, 0, null)

        if (swap.error != null) {
            val errorMessage = StringBuilder().apply {
                append(swap.error)
                if (swap.message != null) {
                    append(": ")
                    append(swap.message)
                }
            }
            return ResponseResource.Failure(MayaException(errorMessage.toString()), false, 0, null)
        }

        val vault = swap.targetAddress ?: swap.inboundAddress
            ?: return ResponseResource.Failure(MayaException("swapkit returned no vault address"), false, 0, null)
        val memo = swap.memo
        // ?: return ResponseResource.Failure(MayaException("swapkit returned no memo"), false, 0, null)

        val feeAmount = swapRequest.amount.copy().apply {
            // Sum the SwapKit fee breakdown, converting each leg to DASH via the
            // user's market rate. Captures inbound (DASH) + outbound (target) +
            // anything denominated in either. Routing-asset fees (CACAO/RUNE for
            // Maya/Thor) are skipped — we don't have a rate to convert them.
            dash = totalSwapCostInDash(swapRequest, route, swap.fees)
            anchoredType = swapRequest.amount.anchoredType
        }

        // Pass swapRequest.amount through unchanged. Mirrors MayaWebApi (which also
        // passes `amount = swapRequest.amount` to SwapTradeUIModel). DO NOT set
        // .crypto from swap.expectedBuyAmount here — Amount.crypto's setter flips
        // the anchor to Crypto and recomputes _dash from crypto/rate, which silently
        // destroys the user's actual sell amount. The preview shows the pool-price
        // crypto estimate; what actually arrives is the on-chain payout.

        val result = SwapTradeUIModel(
            amount = swapRequest.amount,
            outputAsset = swapRequest.target_maya_asset,
            feeAmount = feeAmount,
            vaultAddress = vault,
            destinationAddress = swapRequest.targetAddress,
            memo = memo,
            maximum = swapRequest.maximum,
            routeName = route.providers.joinToString(","),
            availableRoutes = quote.routes.map { "${it.providers.joinToString(",")}: ${it.meta?.tags ?: listOf() }" }
        )
        return ResponseResource.Success(result)
    }

    override suspend fun commitSwapTransaction(
        tradeId: String,
        swapTradeUIModel: SwapTradeUIModel
    ): ResponseResource<SwapTradeUIModel> {
        // Refresh the route via SwapKit (routeId expires after 60s) and then hand off
        // to the existing DASH-tx builder.
        val refreshed = getSwapInfo(
            SwapQuoteRequest(
                amount = swapTradeUIModel.amount,
                source_maya_asset = SwapKitConstants.DASH_ASSET,
                target_maya_asset = swapTradeUIModel.outputAsset,
                fiatCurrency = swapTradeUIModel.amount.fiatCode,
                targetAddress = swapTradeUIModel.destinationAddress,
                maximum = swapTradeUIModel.maximum
            )
        )
        return if (refreshed is ResponseResource.Success) {
            blockchainApi.buildAndSendSwapTx(refreshed.value)
        } else {
            refreshed
        }
    }

    override suspend fun getUserAccounts(currency: String): List<AccountDataUIModel> {
        return listOf(
            AccountDataUIModel(
                Account(UUID.randomUUID(), currency, currency, currency, Balance("0", currency), true, true, "", true),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
            )
        )
    }

    private fun baseUnitsToHumanDash(value: Long): String {
        return BigDecimal.valueOf(value)
            .divide(DASH_BASE_UNITS, 8, RoundingMode.HALF_UP)
            .toPlainString()
    }

    private fun mapToSwapQuote(route: SwapKitRoute?, toAsset: String, topLevelError: String?): SwapQuote? {
        if (route == null) {
            return SwapQuote(
                dustThreshold = "0",
                expectedAmountOut = "0",
                expiry = 0L,
                fees = SwapFees("0", toAsset, "0", "0", 0, "0", 0),
                inboundAddress = "",
                inboundConfirmationBlocks = 0,
                inboundConfirmationSeconds = 0.0,
                memo = "",
                notes = "",
                outboundDelayBlocks = 0,
                outboundDelaySeconds = 0.0,
                recommendedMinAmountIn = "0",
                slippageBps = 0,
                warning = "",
                error = topLevelError ?: "no route"
            )
        }
        val expectedBaseUnits = humanToBuyAssetBaseUnits(route.expectedBuyAmount)
        val outboundBaseUnits = outboundFeeBaseUnits(route)
        // Maya's SwapQuote/SwapFees expose slippage as Int; SwapKit returns it as
        // a fractional Double. Round at the boundary.
        val slippageBpsInt = route.totalSlippageBps.toInt()
        return SwapQuote(
            dustThreshold = "0",
            expectedAmountOut = expectedBaseUnits,
            expiry = (System.currentTimeMillis() / 1000) + 60,
            fees = SwapFees(
                affiliate = "0",
                asset = toAsset,
                liquidity = "0",
                outbound = outboundBaseUnits,
                slippageBps = slippageBpsInt,
                total = outboundBaseUnits,
                totalBps = slippageBpsInt
            ),
            inboundAddress = "",
            inboundConfirmationBlocks = 0,
            inboundConfirmationSeconds = route.estimatedTime?.inbound ?: 0.0,
            memo = "",
            notes = "",
            outboundDelayBlocks = 0,
            outboundDelaySeconds = route.estimatedTime?.outbound ?: 0.0,
            recommendedMinAmountIn = "0",
            slippageBps = slippageBpsInt,
            warning = route.warnings?.joinToString().orEmpty(),
            error = topLevelError
        )
    }

    private fun humanToBuyAssetBaseUnits(human: String): String {
        // Maya consumers downstream divide by 1e8 to get back to whole units; convert
        // the SwapKit human decimal into matching base units.
        return runCatching {
            BigDecimal(human).multiply(DASH_BASE_UNITS).setScale(0, RoundingMode.HALF_UP).toPlainString()
        }.getOrDefault("0")
    }

    private fun outboundFeeBaseUnits(route: SwapKitRoute): String {
        val outbound = route.fees.firstOrNull { it.type.equals("outbound", ignoreCase = true) }
            ?: route.fees.firstOrNull { it.type.equals("network", ignoreCase = true) }
        val human = outbound?.amount ?: return "0"
        return runCatching {
            BigDecimal(human).multiply(DASH_BASE_UNITS).setScale(0, RoundingMode.HALF_UP).toPlainString()
        }.getOrDefault("0")
    }

    private fun inboundFeeInDash(fees: List<SwapKitFee>): BigDecimal {
        val inbound = fees.firstOrNull {
            it.type.equals("inbound", ignoreCase = true) &&
                (it.chain.equals("DASH", ignoreCase = true) || it.asset?.contains("DASH") == true)
        }
        val amt = inbound?.amount ?: return BigDecimal.ZERO
        return runCatching { BigDecimal(amt) }.getOrDefault(BigDecimal.ZERO)
    }

    private fun totalSwapCostInDash(
        swapRequest: SwapQuoteRequest,
        route: SwapKitRoute,
        swapFees: List<SwapKitFee>?
    ): BigDecimal {
        val fees = swapFees ?: route.fees
        if (fees.isEmpty()) return BigDecimal.ZERO

        // cryptoDashExchangeRate is "target per DASH"; 1 target = 1/rate DASH.
        val targetPerDash = swapRequest.amount.cryptoDashExchangeRate
        // target_maya_asset is "CHAIN.SYMBOL", e.g. "THOR.RUNE" or "BTC.BTC".
        val targetChain = swapRequest.target_maya_asset.substringBefore(".").uppercase()
        val targetAsset = swapRequest.target_maya_asset.uppercase()

        // Sum fees from the SwapKit breakdown, converting non-DASH legs to DASH
        // via the user's market rate. Using the input/output spread underreports
        // for streaming routes because streaming nearly eliminates slippage —
        // the network/liquidity/affiliate fees are still there, they just don't
        // show up as a spread against market rate.
        var total = BigDecimal.ZERO
        fees.forEach { fee ->
            val amt = runCatching { BigDecimal(fee.amount ?: "0") }
                .getOrDefault(BigDecimal.ZERO)
            if (amt.signum() <= 0) return@forEach

            val chain = fee.chain?.uppercase()
            val asset = fee.asset?.uppercase()
            val inDash = when {
                chain == "DASH" || asset?.contains("DASH") == true -> amt
                targetPerDash.signum() > 0 &&
                    (chain == targetChain || asset == targetAsset) ->
                    amt.divide(targetPerDash, 16, RoundingMode.HALF_UP)
                else -> {
                    // Routing-asset fees (CACAO for Maya, RUNE for Thor liquidity,
                    // etc.) need their own DASH rate to convert. Log and skip;
                    // undercounting is preferable to guessing.
                    log.info(
                        "swapkit fee skipped: type={} amount={} asset={} chain={}",
                        fee.type,
                        fee.amount,
                        fee.asset,
                        fee.chain
                    )
                    BigDecimal.ZERO
                }
            }
            total = total.add(inDash)
        }
        return total
    }

    private fun List<SwapKitRoute>.bestRoute(): SwapKitRoute? {
        if (isEmpty()) return null
        return firstOrNull { it.meta?.tags?.contains("RECOMMENDED") == true }
            ?: firstOrNull { it.meta?.tags?.contains("CHEAPEST") == true }
            ?: first()
    }

    override fun applyPoolPrices(pools: List<PoolInfo>, usdToFiat: Fiat) {
        // usdToFiat is the wallet's "1 USD in SELECTED_CURRENCY" rate. Unlike Maya
        // (which recomputes USD from balance_cacao/balance_asset each pass), the
        // SwapKit aggregator caches USD prices in usdPriceCache at refresh time
        // and re-seeds from there on every call. This keeps the function
        // idempotent across re-emissions (the original bug: 45.53 USD → 124.97
        // BYN → 346.06 → 949.92 → ... compounding every cycle) AND lets a
        // selected-currency switch convert from the cached USD baseline without
        // a network refetch.
        val fiatPerUsd = usdToFiat.toBigDecimal()

        pools.forEach { pool ->
            val priceUsd = usdPriceCache[pool.asset]
            if (priceUsd == null || priceUsd.signum() <= 0) {
                log.info("no USD price for {}", pool.asset)
                return@forEach
            }
            pool.assetPriceFiat = priceUsd.multiply(fiatPerUsd).toFiat(usdToFiat.currencyCode)
            log.info("$priceUsd, ${pool.assetPriceFiat} -> ${pool.asset}")
        }
    }
}
