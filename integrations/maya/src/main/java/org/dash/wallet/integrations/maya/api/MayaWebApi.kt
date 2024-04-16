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

import org.bitcoinj.core.Transaction
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.integrations.maya.model.Account
import org.dash.wallet.integrations.maya.model.AccountDataUIModel
import org.dash.wallet.integrations.maya.model.Amount
import org.dash.wallet.integrations.maya.model.Balance
import org.dash.wallet.integrations.maya.model.CurrencyInputType
import org.dash.wallet.integrations.maya.model.InboundAddress
import org.dash.wallet.integrations.maya.model.NetworkResponse
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.model.TradesRequest
import org.slf4j.LoggerFactory
import retrofit2.Response
import retrofit2.http.GET
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject

/**
 * https://docs.mayaprotocol.com/introduction/readme
 */
interface MayaEndpoint {
    /**
     * old: https://docs.mayaprotocol.com/dev-docs/mayachain/concepts/querying-mayachain
     * new: https://docs.mayaprotocol.com/mayachain-dev-docs/concepts/querying-mayachain
     */
    @GET("pools")
    suspend fun getPoolInfo(): Response<List<PoolInfo>>
    @GET("inbound_addresses")
    suspend fun getInboundAddresses(): Response<List<InboundAddress>>
    @GET("network")
    suspend fun getNetwork(): Response<NetworkResponse>
}

open class MayaWebApi @Inject constructor(
    private val endpoint: MayaEndpoint,
    private val legacyEndpoint: MayaLegacyEndpoint,
    private val analyticsService: AnalyticsService
) {
    companion object {
        private val log = LoggerFactory.getLogger(MayaWebApi::class.java)
        private const val DEFAULT_UTXO_CHAIN_TX_SIZE = 250L
        private const val DEFAULT_ETH_TX_SIZE = 21000L
        private const val DEFAULT_ERC20_TX_SIZE = 70000L
        private const val GWEI_CONVERSION: Int = 1_000_000_000
    }

    suspend fun getPoolInfo(): List<PoolInfo> {
        return try {
            val response = legacyEndpoint.getPoolInfo()
            log.info("maya: response: {}", response)

            return if (response.isSuccessful && response.body()?.isNotEmpty() == true) {
                response.body()!!.toList()
            } else {
                log.error("getPoolInfo not successful; ${response.code()} : ${response.message()}")
                listOf()
            }
        } catch (ex: Exception) {
            log.error("Error in getPoolInfo: $ex")

            if (ex !is IOException) {
                analyticsService.logError(ex)
            }

            listOf()
        }
    }

    suspend fun getInboundAddresses(): List<InboundAddress> {
//        return listOf(
//            InboundAddress(
//                address = "XsPKvvrZqQB931M8EbFpB12W88QxjW9WnX",
//                chain = "DASH",
//                chain_lp_actions_paused = false,
//                chain_trading_paused = false,
//                dust_threshold = "10000",
//                gas_rate = "12",
//                gas_rate_units = "satsperbyte",
//                global_trading_paused = false,
//                halted = false,
//                outbound_fee = "5412",
//                outbound_tx_size = "451",
//                pub_key = "mayapub1addwnpepq255x4s5ag6qu0s2hrj9kwnlqpuy5r33d5t50p9r00wgr2842zhvz74muvu"
//            )
//        )
        return try {
            val response = endpoint.getInboundAddresses()
            log.info("maya: response: {}", response)

            return if (response.isSuccessful && response.body()?.isNotEmpty() == true) {
                response.body()!!.toList()
            } else {
                log.error("getInboundAddresses not successful; ${response.code()} : ${response.message()}")
                listOf()
            }
        } catch (ex: Exception) {
            log.error("Error in getInboundAddresses: $ex")

            if (ex !is IOException) {
                analyticsService.logError(ex)
            }

            listOf()
      }
    }

    suspend fun getNetwork(): NetworkResponse? {
        return try {
            val response = endpoint.getNetwork()
            log.info("maya: response: {}", response)

            return if (response.isSuccessful) {
                response.body()!!
            } else {
                log.error("getNetwork not successful; ${response.code()} : ${response.message()}")
                null
            }
        } catch (ex: Exception) {
            log.error("Error in getInboundAddresses: $ex")

            if (ex !is IOException) {
                analyticsService.logError(ex)
            }

            null
        }
    }

//    {
//        "address": "bc1qfufu935synv680ws076m60g60n6gxvqaqglawt",
//        "chain": "BTC",
//        "chain_lp_actions_paused": false,
//        "chain_trading_paused": false,
//        "dust_threshold": "10000",
//        "gas_rate": "36",
//        "gas_rate_units": "satsperbyte",
//        "global_trading_paused": false,
//        "halted": false,
//        "outbound_fee": "36000",
//        "outbound_tx_size": "1000",
//        "pub_key": "mayapub1addwnpepqdupyxvsy864n5wua9532ey72feplzmtsacfjc8wvy0e5sdd96w0zf0anph"
//    },

    suspend fun swapTrade(tradesRequest: TradesRequest): ResponseResource<SwapTradeUIModel> {
        // we need to calculate the fees based on getInboundAddresses

        val poolInfoList = getPoolInfo()
        val sourcePoolInfo = poolInfoList.find { it.asset == tradesRequest.source_maya_asset }
        val destinationPoolInfo = poolInfoList.find { it.asset == tradesRequest.target_maya_asset }
        val inboundAddresses = getInboundAddresses()
        val source = inboundAddresses.find { it.chain == tradesRequest.source_asset }
        val destination = inboundAddresses.find { it.chain == tradesRequest.target_asset }
        val networkResponse = getNetwork()

        // incomingFee (estimated)
        val incomingFee = Transaction.DEFAULT_TX_FEE.multiply(227).div(1000).toBigDecimal()

        // Liquidity Fee
        val swapAmount = tradesRequest.amount.crypto
        val poolDepth = destinationPoolInfo!!.assetDepth.toBigDecimal().setScale(8, RoundingMode.HALF_UP).div(BigDecimal(1_0000_000))
        val slip = swapAmount / (swapAmount + poolDepth)
        val fee = slip * swapAmount
        val feeAmount = tradesRequest.amount.copy().apply { crypto = fee }

        // Outgoing Fee
        val outgoingFee = destination!!.outboundFee.toBigDecimal().setScale(8, RoundingMode.HALF_UP).div(BigDecimal(1_0000_0000))// * txSize * outboundFeeMultiplier

        feeAmount.crypto += outgoingFee
        feeAmount.dash += incomingFee
        feeAmount.anchoredType = tradesRequest.amount.anchoredType

        return ResponseResource.Success(
            SwapTradeUIModel(
                amount = tradesRequest.amount,
                outputAsset = tradesRequest.target_maya_asset,
                feeAmount = feeAmount,
                destinationAddress = tradesRequest.targetAddress
            )
        )
    }

    suspend fun getUserAccounts(currency: String): List<AccountDataUIModel> {
        return listOf(
            AccountDataUIModel(
                Account(UUID.randomUUID(), currency, currency, currency, Balance("0", currency), true, true, "", true),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
            )
        )
    }
}
