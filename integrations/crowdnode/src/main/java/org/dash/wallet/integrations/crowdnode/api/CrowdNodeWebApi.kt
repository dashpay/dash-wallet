/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.integrations.crowdnode.api

import kotlinx.coroutines.delay
import org.bitcoinj.core.Address
import org.bitcoinj.core.AddressFormatException
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.crowdnode.model.*
import org.slf4j.LoggerFactory
import retrofit2.Response
import retrofit2.http.*
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

interface CrowdNodeEndpoint {
    @GET("odata/apifundings/GetFunds(address='{address}')")
    suspend fun getTransactions(
        @Path("address") address: String
    ): Response<List<CrowdNodeTx>>

    @GET("odata/apifundings/GetBalance(address='{address}')")
    suspend fun getBalance(
        @Path("address") address: String
    ): Response<CrowdNodeBalance>

    @GET("odata/apifundings/GetWithdrawalLimits(address='{address}')")
    suspend fun getWithdrawalLimits(
        @Path("address") address: String
    ): Response<List<WithdrawalLimit>>

    @GET("odata/apiaddresses/IsApiAddressInUse(address='{address}')")
    suspend fun isAddressInUse(
        @Path("address") address: String
    ): Response<IsAddressInUse>

    @GET("odata/apiaddresses/AddressStatus(address='{address}')")
    suspend fun addressStatus(
        @Path("address") address: String
    ): Response<AddressStatus>

    @GET("odata/apiaddresses/UsingDefaultApiEmail(address='{address}')")
    suspend fun hasDefaultEmail(
        @Path("address") address: String
    ): Response<IsDefaultEmail>

    @GET("odata/apimessages/SendMessage(address='{address}',message='{message}',signature='{signature}',messagetype=1)")
    suspend fun sendSignedMessage(
        @Path("address") address: String,
        @Path("message") message: String,
        @Path("signature") signature: String
    ): Response<MessageStatus>

    @GET("odata/apimessages/GetMessages(address='{address}')")
    suspend fun getMessages(
        @Path("address") address: String
    ): Response<List<MessageStatus>>
}

open class CrowdNodeWebApi @Inject constructor(
    private val endpoint: CrowdNodeEndpoint,
    private val analyticsService: AnalyticsService
) {
    companion object {
        private val log = LoggerFactory.getLogger(CrowdNodeWebApi::class.java)
        private const val MAX_PER_TX_KEY = "AmountApiWithdrawalMax"
        private const val MAX_PER_1H_KEY = "AmountApiWithdrawal1hMax"
        private const val MAX_PER_24H_KEY = "AmountApiWithdrawal24hMax"
    }

    // TODO: these methods are just mappers right now. Move more logic in here from the aggregator class
    suspend fun sendSignedMessage(address: String, email: String, encodedSignature: String): Response<MessageStatus> {
        return endpoint.sendSignedMessage(address, email, encodedSignature)
    }

    suspend fun getMessages(address: String): Response<List<MessageStatus>> {
        return endpoint.getMessages(address)
    }
    // end TODO

    suspend fun fromCrowdNode(address: Address, tx: Transaction): Boolean? {
        var fromCrowdNode: Boolean? = false

        for (i in 0..3) {
            if (i != 0) {
                delay(2.0.pow(i).seconds)
            }

            try {
                val response = endpoint.getTransactions(address.toBase58())

                if (response.isSuccessful && response.body() != null) {
                    if (response.body()!!.all { it.txId != tx.txId.toString() }) {
                        fromCrowdNode = false
                        continue
                    } else {
                        fromCrowdNode = true
                        break
                    }
                }
            } catch (ex: Exception) {
                log.error("Error in getTransactions: $ex")

                if (ex !is IOException) {
                    analyticsService.logError(ex)
                }
            }

            // Fallback to simple detection if a network or other error
            fromCrowdNode = null
        }

        return fromCrowdNode
    }

    open suspend fun resolveBalance(address: Address?): Resource<Coin> {
        return if (address != null) {
            try {
                val balance = Coin.parseCoin(fetchBalance(address.toBase58()))
                Resource.success(balance)
            } catch (ex: IOException) {
                Resource.error(ex)
            } catch (ex: Exception) {
                log.error("Error while resolving balance: $ex")
                analyticsService.logError(ex)
                Resource.error(ex)
            }
        } else {
            Resource.success(Coin.ZERO)
        }
    }

    private suspend fun fetchBalance(address: String): String {
        val response = endpoint.getBalance(address)
        val balance = BigDecimal.valueOf(response.body()?.totalBalance ?: 0.0)

        return balance.setScale(8, RoundingMode.HALF_UP).toString()
    }

    suspend fun addressStatus(address: Address): String? {
        return try {
            val response = endpoint.addressStatus(address.toBase58())
            response.body()?.status
        } catch (ex: Exception) {
            log.error("Error in resolveAddressStatus: $ex")

            if (ex !is IOException) {
                analyticsService.logError(ex)
            }

            null
        }
    }

    suspend fun isDefaultEmail(address: Address): Boolean {
        return try {
            val response = endpoint.hasDefaultEmail(address.toBase58())
            response.isSuccessful && response.body()?.isDefault != false
        } catch (ex: Exception) {
            log.error("Error in resolveIsDefaultEmail: $ex")

            if (ex !is IOException) {
                analyticsService.logError(ex)
            }

            true
        }
    }

    open suspend fun getWithdrawalLimits(address: Address?): Map<WithdrawalLimitPeriod, Coin> {
        return try {
            val response = endpoint.getWithdrawalLimits(address?.toBase58() ?: "")

            return if (response.isSuccessful && response.body()?.isNotEmpty() == true) {
                response.body()!!.mapNotNull {
                    when (it.key.lowercase()) {
                        MAX_PER_TX_KEY.lowercase() -> WithdrawalLimitPeriod.PerTransaction to Coin.parseCoin(it.value)
                        MAX_PER_1H_KEY.lowercase() -> WithdrawalLimitPeriod.PerHour to Coin.parseCoin(it.value)
                        MAX_PER_24H_KEY.lowercase() -> WithdrawalLimitPeriod.PerDay to Coin.parseCoin(it.value)
                        else -> null
                    }
                }.toMap()
            } else {
                log.error("getWithdrawalLimits not successful; ${response.code()} : ${response.message()}")
                mapOf()
            }
        } catch (ex: Exception) {
            log.error("Error in getWithdrawalLimits: $ex")

            if (ex !is IOException) {
                analyticsService.logError(ex)
            }

            mapOf()
        }
    }

    open suspend fun isApiAddressInUse(address: Address): Pair<Boolean, Address?> {
        return try {
            val result = endpoint.isAddressInUse(address.toBase58())
            val isSuccess = result.isSuccessful && result.body()?.isInUse == true
            var primaryAddress: Address? = null

            if (isSuccess) {
                val primary = result.body()!!.primaryAddress
                requireNotNull(primary) { "isAddressInUse returns true but missing primary address" }

                primaryAddress = try {
                    Address.fromBase58(address.parameters, primary)
                } catch (ex: AddressFormatException) {
                    analyticsService.logError(ex, primary)
                    null
                }
            }

            Pair(isSuccess, primaryAddress)
        } catch (ex: Exception) {
            log.error("Error in resolveIsAddressInUse: $ex")

            if (ex !is IOException) {
                analyticsService.logError(ex)
            }

            Pair(false, null)
        }
    }
}
