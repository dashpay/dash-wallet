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
import org.dash.wallet.integrations.maya.model.NetworkResponse
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.dash.wallet.integrations.maya.model.SwapQuote
import org.dash.wallet.integrations.maya.model.SwapQuoteRequest
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.model.SwapTransactionInfo
import org.dash.wallet.integrations.maya.payments.MayaCurrencyList
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SendTransactionToWalletParams
import org.slf4j.LoggerFactory
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject

/**
 * https://docs.mayaprotocol.com/introduction/readme
 * https://mayanode.mayachain.info/mayachain/doc (swagger)
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
    @GET("quote/swap")
    suspend fun getSwapQuote(
        @Query("from_asset") fromAsset: String,
        @Query("to_asset") toAsset: String,
        @Query("amount") amount: Long,
        @Query("destination") destination: String
    ): Response<SwapQuote>

    @GET("tx/{txid}")
    suspend fun getSwapTransactionInfo(
        @Path("txid") txid: String
    ): Response<SwapTransactionInfo>
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

    suspend fun getSwapQuote(from: String, to: String, value: Long, destination: String): SwapQuote? {
        return try {
            val response = endpoint.getSwapQuote(from, to, value, destination)
            log.info("maya: response: {}", response)

            return if (response.isSuccessful) {
                response.body()!!
            } else {
                log.error("getSwapQuote not successful; ${response.code()} : ${response.message()}")
                null
            }
        } catch (ex: Exception) {
            log.error("Error in getSwapQuote: $ex")

            if (ex !is IOException) {
                analyticsService.logError(ex)
            }

            null
        }
    }

    suspend fun getDefaultSwapQuote(to: String, value: Long = 1_0000_0000): SwapQuote? {
        return MayaCurrencyList[to]?.exampleAddress?.let { destination ->
            return getSwapQuote("DASH.DASH", to, value, destination)
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

    suspend fun getSwapInfo(tradesRequest: SwapQuoteRequest): ResponseResource<SwapTradeUIModel> {
        // we need to calculate the fees based on getSwapQuote
        val inboundAddresses = getInboundAddresses()
        if (inboundAddresses.isNotEmpty()) {
            val source = inboundAddresses.find { it.chain == tradesRequest.source_asset }
            val destinationChain = tradesRequest.target_maya_asset.let { it.substring(0, it.indexOf('.')) }
            val destination = inboundAddresses.find { it.chain == destinationChain }
            if (source == null || destination == null) {
                return ResponseResource.Failure(
                    MayaException(
                        "inbound_addresses did not return ${
                            tradesRequest.source_asset
                        } or ${tradesRequest.target_asset}"
                    ),
                    false,
                    0,
                    null
                )
            }
            if (source.halted) {
                return ResponseResource.Failure(
                    MayaException(
                        "source vault has been halted ${tradesRequest.source_asset}"
                    ),
                    false,
                    0,
                    null
                )
            }
            if (destination.halted) {
                return ResponseResource.Failure(
                    MayaException(
                        "destination vault has been halted ${tradesRequest.target_asset}"
                    ),
                    false,
                    0,
                    null
                )
            }

//            // val networkResponse = getNetwork()
//
//            // incomingFee (estimated)
//            val incomingFee = Transaction.DEFAULT_TX_FEE.multiply(227).div(1000).toBigDecimal()
//
//            // Liquidity Fee
//            val swapAmount = tradesRequest.amount.crypto
//            val poolDepth = destinationPoolInfo!!.assetDepth.toBigDecimal().setScale(8, RoundingMode.HALF_UP).div(
//                BigDecimal(1_0000_0000)
//            )
//            val slip = swapAmount / (swapAmount + poolDepth)
//            val fee = slip * swapAmount
//            val feeAmount = tradesRequest.amount.copy().apply { crypto = fee }
//
//            // Outgoing Fee
//            val outgoingFee = if (tradesRequest.target_maya_asset.contains("ETH.")) {
//                destination.outboundFee.toBigDecimal().setScale(8, RoundingMode.HALF_UP).div(
//                    BigDecimal(1_000_000_000)
//                )
//            } else {
//                // DASH/BITCOIN
//                destination.outboundFee.toBigDecimal().setScale(8, RoundingMode.HALF_UP).div(
//                    BigDecimal(1_0000_0000)
//                )
//            }
            var quote = getSwapQuote(
                tradesRequest.source_maya_asset,
                tradesRequest.target_maya_asset,
                tradesRequest.amount.dash.multiply(BigDecimal.valueOf(1_0000_0000)).toLong(),
                tradesRequest.targetAddress
            )
                ?: return ResponseResource.Failure(
                    MayaException("swap-quote failure"),
                    false,
                    0,
                    null
                )

            if (quote.error == null) {
                val newAmount = tradesRequest.amount.copy()
                val feeAmount = tradesRequest.amount.copy()

                if (!tradesRequest.maximum) {
                    val outgoingFee2 = if (tradesRequest.target_maya_asset.contains("ETH.")) {
                        quote.fees.outbound.toBigDecimal().setScale(8, RoundingMode.HALF_UP).div(
                            BigDecimal(1_00_000_000)
                        )
                    } else {
                        // DASH/BITCOIN
                        quote.fees.outbound.toBigDecimal().setScale(8, RoundingMode.HALF_UP).div(
                            BigDecimal(1_0000_0000)
                        )
                    }
                    log.info("quote: {}", quote)
                    log.info("quote.amount {} vs {}", quote.expectedAmountOut, tradesRequest.amount.crypto)

                    newAmount.crypto += outgoingFee2

                    quote = getSwapQuote(
                        tradesRequest.source_maya_asset,
                        tradesRequest.target_maya_asset,
                        newAmount.dash.multiply(BigDecimal.valueOf(1_0000_0000)).toLong(),
                        tradesRequest.targetAddress
                    )
                        ?: return ResponseResource.Failure(
                            MayaException("swap-quote failure"),
                            false,
                            0,
                            null
                        )

                    log.info("quote: {}", quote)
                    log.info("quote.amount {} vs {}", quote.expectedAmountOut, tradesRequest.amount.crypto)
                    feeAmount.dash = newAmount.dash - tradesRequest.amount.dash
                } else {
                    newAmount.crypto = quote.expectedAmountOut.toBigDecimal().setScale(8, RoundingMode.HALF_UP).div(
                        BigDecimal(1_0000_0000)
                    )
                    feeAmount.dash = tradesRequest.amount.dash - newAmount.dash
                }
                feeAmount.anchoredType = tradesRequest.amount.anchoredType

                val result = SwapTradeUIModel(
                    amount = tradesRequest.amount,
                    outputAsset = tradesRequest.target_maya_asset,
                    feeAmount = feeAmount,
                    vaultAddress = source.address,
                    destinationAddress = tradesRequest.targetAddress,
                    memo = quote.memo,
                    maximum = tradesRequest.maximum
                )
                return ResponseResource.Success(result)
            }
            return ResponseResource.Failure(MayaException(quote.error ?: "Unknown error"), false, 0, null)
        } else {
            return ResponseResource.Failure(MayaException("inbound address api failure"), false, 0, null)
        }
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

    fun sendFundsToWallet(params: SendTransactionToWalletParams, nothing: String?): ResponseResource<Boolean> {
        log.info("sendFundsToWallet($params, $nothing")
        return ResponseResource.Success(true)
    }

    fun commitSwapTransaction(tradeId: String, swapTradeUIModel: SwapTradeUIModel): ResponseResource<SwapTradeUIModel> {
        log.info("commitSwapTransaction($tradeId, $swapTradeUIModel")
        return ResponseResource.Success(swapTradeUIModel)
    }
}
