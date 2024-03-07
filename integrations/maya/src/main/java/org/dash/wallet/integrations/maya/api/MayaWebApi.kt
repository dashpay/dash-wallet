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

import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.maya.model.Account
import org.dash.wallet.integrations.maya.model.AccountDataUIModel
import org.dash.wallet.integrations.maya.model.Balance
import org.dash.wallet.integrations.maya.model.InboundAddress
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.model.TradesRequest
import org.slf4j.LoggerFactory
import retrofit2.Response
import retrofit2.http.GET
import java.io.IOException
import java.math.BigDecimal
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
}

open class MayaWebApi @Inject constructor(
    private val endpoint: MayaEndpoint,
    private val analyticsService: AnalyticsService
) {
    companion object {
        private val log = LoggerFactory.getLogger(MayaWebApi::class.java)
    }

    suspend fun getPoolInfo(): List<PoolInfo> {
        return try {
            val response = endpoint.getPoolInfo()
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
        return listOf(
            InboundAddress(
                address = "XsPKvvrZqQB931M8EbFpB12W88QxjW9WnX",
                chain = "DASH",
                chain_lp_actions_paused = false,
                chain_trading_paused = false,
                dust_threshold = "10000",
                gas_rate = "12",
                gas_rate_units = "satsperbyte",
                global_trading_paused = false,
                halted = false,
                outbound_fee = "5412",
                outbound_tx_size = "451",
                pub_key = "mayapub1addwnpepq255x4s5ag6qu0s2hrj9kwnlqpuy5r33d5t50p9r00wgr2842zhvz74muvu"
            )
        )
//        return try {
//            val response = endpoint.getInboundAddresses()
//            log.info("maya: response: {}", response)
//
//            return if (response.isSuccessful && response.body()?.isNotEmpty() == true) {
//                response.body()!!.toList()
//            } else {
//                log.error("getInboundAddresses not successful; ${response.code()} : ${response.message()}")
//                listOf()
//            }
//        } catch (ex: Exception) {
//            log.error("Error in getInboundAddresses: $ex")
//
//            if (ex !is IOException) {
//                analyticsService.logError(ex)
//            }
//
//            listOf()
//      }
    }

    suspend fun swapTrade(tradesRequest: TradesRequest): ResponseResource<SwapTradeUIModel> {
        return ResponseResource.Success(
            SwapTradeUIModel(
                inputAmount = tradesRequest.amount,
                inputCurrency = tradesRequest.amount_asset
            )
        )
    }

    suspend fun getUserAccounts(currency: String): List<AccountDataUIModel> {
        return listOf(
            AccountDataUIModel(
                Account(UUID.randomUUID(), currency, currency, Balance("0", currency), true, true, "", true),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
            )
        )
    }
}
