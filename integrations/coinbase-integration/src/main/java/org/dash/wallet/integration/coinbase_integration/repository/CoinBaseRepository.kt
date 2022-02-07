/*
 * Copyright 2021 Dash Core Group.
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
package org.dash.wallet.integration.coinbase_integration.repository

import org.dash.wallet.common.Configuration
import org.dash.wallet.integration.coinbase_integration.*
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.network.safeApiCall
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseAuthApi
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseServicesApi
import java.math.BigDecimal
import javax.inject.Inject

class CoinBaseRepository @Inject constructor(
    private val servicesApi: CoinBaseServicesApi,
    private val authApi: CoinBaseAuthApi,
    private val userPreferences: Configuration,
    private val placeBuyOrderMapper: PlaceBuyOrderMapper,
    private val swapTradeMapper: SwapTradeMapper,
    private val commitBuyOrderMapper: CommitBuyOrderMapper
) : CoinBaseRepositoryInt {
    override suspend fun getUserAccount() = safeApiCall {
        val apiResponse = servicesApi.getUserAccounts()
        val userAccountData = apiResponse.body()?.data?.firstOrNull {
            it.balance?.currency?.equals(DASH_CURRENCY) ?: false
        }
        userAccountData?.also {
            saveLastCoinbaseDashAccountBalance(it.balance?.amount)
            saveUserAccountId(it.id)
        }
    }

    override suspend fun getUserAccounts(exchangeCurrencyCode: String): ResponseResource<List<CoinBaseUserAccountDataUIModel>> =
        safeApiCall {
            val userAccounts = servicesApi.getUserAccounts().body()?.data ?: emptyList()
            val exchangeRates = servicesApi.getExchangeRates(exchangeCurrencyCode)?.data
            return@safeApiCall userAccounts.map {
                val currencyToCryptoCurrencyExchangeRate = exchangeRates?.rates?.get(it.currency?.code).orEmpty()
                val currencyToDashExchangeRate = exchangeRates?.rates?.get("DASH").orEmpty()
                val cryptoCurrencyToDashExchangeRate = (BigDecimal(currencyToDashExchangeRate) / BigDecimal(currencyToCryptoCurrencyExchangeRate)).toString()
                CoinBaseUserAccountDataUIModel(
                    it,
                    currencyToCryptoCurrencyExchangeRate,
                    currencyToDashExchangeRate,
                    cryptoCurrencyToDashExchangeRate
                )
            } ?: emptyList()
        }

    override suspend fun getBaseIdForUSDModel(baseCurrency: String) = safeApiCall {
        servicesApi.getBaseIdForUSDModel(baseCurrency = baseCurrency).body()
    }

    override suspend fun getExchangeRates() = safeApiCall { servicesApi.getExchangeRates() }

    override suspend fun disconnectCoinbaseAccount() {
        userPreferences.setLastCoinBaseAccessToken(null)
        userPreferences.setLastCoinBaseRefreshToken(null)
        safeApiCall { authApi.revokeToken() }
    }

    override fun saveLastCoinbaseDashAccountBalance(amount: String?) {
        amount?.let {
            userPreferences.setLastCoinBaseBalance(it)
        }
    }

    override fun saveUserAccountId(accountId: String?) {
        accountId?.let { userPreferences.setCoinBaseUserAccountId(it) }
    }

    override suspend fun swapTrade(tradesRequest: TradesRequest) = safeApiCall {
        val apiResult = servicesApi.swapTrade(tradesRequest = tradesRequest)
        swapTradeMapper.map(apiResult?.data)
    }

    override suspend fun commitSwapTrade(buyOrderId: String) = safeApiCall {
        val apiResult = servicesApi.commitSwapTrade(tradeId = buyOrderId)
        swapTradeMapper.map(apiResult?.data)
    }

    override suspend fun getActivePaymentMethods() = safeApiCall {
        val apiResult = servicesApi.getActivePaymentMethods()
        apiResult?.data ?: emptyList()
    }

    override suspend fun placeBuyOrder(placeBuyOrderParams: PlaceBuyOrderParams) = safeApiCall {
        val apiResult = servicesApi.placeBuyOrder(accountId = userPreferences.coinbaseUserAccountId, placeBuyOrderParams = placeBuyOrderParams)
        placeBuyOrderMapper.map(apiResult?.data)
    }

    override suspend fun commitBuyOrder(buyOrderId: String) = safeApiCall {
        val commitBuyResult = servicesApi.commitBuyOrder(accountId = userPreferences.coinbaseUserAccountId, buyOrderId = buyOrderId)
        commitBuyOrderMapper.map(commitBuyResult?.data)
    }

    override suspend fun sendFundsToWallet(sendTransactionToWalletParams: SendTransactionToWalletParams) = safeApiCall {
        val apiResult = servicesApi.sendCoinsToWallet(accountId = userPreferences.coinbaseUserAccountId, sendTransactionToWalletParams = sendTransactionToWalletParams)
        apiResult.code()
    }

    override fun getUserLastCoinbaseBalance(): String = userPreferences.lastCoinbaseBalance ?: ""
    override fun isUserConnected(): Boolean = userPreferences.lastCoinbaseAccessToken.isNullOrEmpty().not()
}

interface CoinBaseRepositoryInt {
    suspend fun getUserAccount(): ResponseResource<CoinBaseUserAccountData?>
    suspend fun getUserAccounts(exchangeCurrencyCode: String): ResponseResource<List<CoinBaseUserAccountDataUIModel>>
    suspend fun getBaseIdForUSDModel(baseCurrency: String): ResponseResource<BaseIdForUSDModel?>
    suspend fun getExchangeRates(): ResponseResource<CoinBaseExchangeRates?>
    suspend fun disconnectCoinbaseAccount()
    fun saveLastCoinbaseDashAccountBalance(amount: String?)
    fun saveUserAccountId(accountId: String?)
    suspend fun getActivePaymentMethods(): ResponseResource<List<PaymentMethodsData>>
    suspend fun placeBuyOrder(placeBuyOrderParams: PlaceBuyOrderParams): ResponseResource<PlaceBuyOrderUIModel>
    suspend fun commitBuyOrder(buyOrderId: String): ResponseResource<CommitBuyOrderUIModel>
    suspend fun sendFundsToWallet(sendTransactionToWalletParams: SendTransactionToWalletParams): ResponseResource<Int>
    fun getUserLastCoinbaseBalance(): String
    fun isUserConnected(): Boolean
    suspend fun swapTrade(tradesRequest: TradesRequest): ResponseResource<SwapTradeUIModel>
    suspend fun commitSwapTrade(buyOrderId: String): ResponseResource<SwapTradeUIModel>
}
