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

import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.util.Constants
import org.dash.wallet.integration.coinbase_integration.*
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.safeApiCall
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseAuthApi
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseClientConstants
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseServicesApi
import org.dash.wallet.integration.coinbase_integration.utils.CoinbaseConfig
import java.math.BigDecimal
import javax.inject.Inject

class CoinBaseRepository @Inject constructor(
    private val servicesApi: CoinBaseServicesApi,
    private val authApi: CoinBaseAuthApi,
    private val userPreferences: Configuration,
    private val config: CoinbaseConfig,
    private val placeBuyOrderMapper: PlaceBuyOrderMapper,
    private val swapTradeMapper: SwapTradeMapper,
    private val commitBuyOrderMapper: CommitBuyOrderMapper,
    private val coinbaseAddressMapper: CoinbaseAddressMapper
) : CoinBaseRepositoryInt {
    private var userAccountInfo: List<CoinBaseUserAccountData> = listOf()

    override val hasValidCredentials: Boolean
        get() = CoinBaseClientConstants.CLIENT_ID.isNotEmpty() &&
                CoinBaseClientConstants.CLIENT_SECRET.isNotEmpty()

    override val isAuthenticated: Boolean
        get() = userPreferences.lastCoinbaseAccessToken.isNotEmpty()

    override suspend fun getUserAccount(): ResponseResource<CoinBaseUserAccountData?> = safeApiCall {
        val apiResponse = servicesApi.getUserAccounts()
        userAccountInfo = apiResponse?.data ?: listOf()
        val userAccountData = userAccountInfo.firstOrNull {
            it.balance?.currency?.equals(Constants.DASH_CURRENCY) ?: false
        }
        userAccountData?.also {
            userPreferences.setCoinBaseUserAccountId(it.id)
            config.setPreference(CoinbaseConfig.LAST_BALANCE, Coin.parseCoin(it.balance?.amount ?: "0.0").value)
        }
    }

    override suspend fun getUserAccounts(exchangeCurrencyCode: String): ResponseResource<List<CoinBaseUserAccountDataUIModel>> =
        safeApiCall {
            if (userAccountInfo.isEmpty()) {
                getUserAccount()
            }

            val exchangeRates = servicesApi.getExchangeRates(exchangeCurrencyCode)?.data
            val currencyToDashExchangeRate = exchangeRates?.rates?.get(Constants.DASH_CURRENCY).orEmpty()
            val currencyToUSDExchangeRate = exchangeRates?.rates?.get(Constants.USD_CURRENCY).orEmpty()

            return@safeApiCall userAccountInfo.map {
                val currencyToCryptoCurrencyExchangeRate = exchangeRates?.rates?.get(it.currency?.code).orEmpty()
                val cryptoCurrencyToDashExchangeRate = (BigDecimal(currencyToDashExchangeRate) / BigDecimal(currencyToCryptoCurrencyExchangeRate)).toString()

                CoinBaseUserAccountDataUIModel(
                    it,
                    currencyToCryptoCurrencyExchangeRate,
                    currencyToDashExchangeRate,
                    cryptoCurrencyToDashExchangeRate,
                    currencyToUSDExchangeRate
                )
            }
        }

    override suspend fun getBaseIdForUSDModel(baseCurrency: String) = safeApiCall {
        servicesApi.getBaseIdForUSDModel(baseCurrency = baseCurrency)
    }

    override suspend fun getExchangeRates() = safeApiCall { servicesApi.getExchangeRates() }

    override suspend fun disconnectCoinbaseAccount() {
        userPreferences.setLastCoinBaseAccessToken(null)
        userPreferences.setLastCoinBaseRefreshToken(null)
        userPreferences.setCoinBaseUserAccountId(null)
        config.clearAll()
        safeApiCall { authApi.revokeToken() }
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


    override suspend fun getUserAccountAddress(): ResponseResource<String> = safeApiCall {
        val apiResult = servicesApi.getUserAccountAddress(accountId = userPreferences.coinbaseUserAccountId)
        coinbaseAddressMapper.map(apiResult)
    }

    override suspend fun commitBuyOrder(buyOrderId: String) = safeApiCall {
        val commitBuyResult = servicesApi.commitBuyOrder(accountId = userPreferences.coinbaseUserAccountId, buyOrderId = buyOrderId)
        commitBuyOrderMapper.map(commitBuyResult?.data)
    }

    override suspend fun sendFundsToWallet(sendTransactionToWalletParams: SendTransactionToWalletParams, api2FATokenVersion: String?) = safeApiCall {
        servicesApi.sendCoinsToWallet(accountId = userPreferences.coinbaseUserAccountId, sendTransactionToWalletParams = sendTransactionToWalletParams, api2FATokenVersion = api2FATokenVersion)
    }

    override fun isUserConnected(): Boolean = userPreferences.lastCoinbaseAccessToken.isNotEmpty()

    override suspend fun completeCoinbaseAuthentication(authorizationCode: String): ResponseResource<Boolean> = safeApiCall {
        authApi.getToken(code = authorizationCode).also {
            it?.let { tokenResponse ->
                userPreferences.setLastCoinBaseAccessToken(tokenResponse.accessToken)
                userPreferences.setLastCoinBaseRefreshToken(tokenResponse.refreshToken)

                getUserAccount()
            }
        }
        userPreferences.lastCoinbaseAccessToken.isNotEmpty()
    }

    override suspend fun getWithdrawalLimit() = safeApiCall {
        val apiResponse = servicesApi.getAuthorizationInformation()
        apiResponse?.data?.oauth_meta?.let { meta_data ->
            userPreferences.coinbaseUserWithdrawalLimitAmount = meta_data.send_limit_amount
            userPreferences.coinbaseSendLimitCurrency = meta_data.send_limit_currency
        }
        WithdrawalLimitUIModel(userPreferences.coinbaseUserWithdrawalLimitAmount, userPreferences.coinbaseSendLimitCurrency)
    }

    override suspend fun getExchangeRateFromCoinbase(): ResponseResource<CoinbaseToDashExchangeRateUIModel> = safeApiCall {
        if (userAccountInfo.isEmpty()) {
            getUserAccount()
        }

        val userAccountData = userAccountInfo.firstOrNull {
            it.balance?.currency?.equals(Constants.DASH_CURRENCY) ?: false
        }
        val exchangeRates = userPreferences.exchangeCurrencyCode?.let { servicesApi.getExchangeRates(it)?.data }

        return@safeApiCall userAccountData?.let {
            val currencyToDashExchangeRate = exchangeRates?.rates?.get(Constants.DASH_CURRENCY).orEmpty()
            val currencyToUSDExchangeRate = exchangeRates?.rates?.get(Constants.USD_CURRENCY).orEmpty()
            CoinbaseToDashExchangeRateUIModel(
                it,
                currencyToDashExchangeRate,
                currencyToUSDExchangeRate
            )
        } ?: CoinbaseToDashExchangeRateUIModel.EMPTY
    }

    override suspend fun createAddress() = safeApiCall {
        servicesApi.createAddress(accountId = userPreferences.coinbaseUserAccountId)?.addresses?.address ?: ""
    }
}

interface CoinBaseRepositoryInt {
    val hasValidCredentials: Boolean
    val isAuthenticated: Boolean

    suspend fun getUserAccount(): ResponseResource<CoinBaseUserAccountData?>
    suspend fun getUserAccounts(exchangeCurrencyCode: String): ResponseResource<List<CoinBaseUserAccountDataUIModel>>
    suspend fun getBaseIdForUSDModel(baseCurrency: String): ResponseResource<BaseIdForUSDModel?>
    suspend fun getExchangeRates(): ResponseResource<CoinBaseExchangeRates?>
    suspend fun disconnectCoinbaseAccount()
    fun saveUserAccountId(accountId: String?)
    suspend fun createAddress(): ResponseResource<String>
    suspend fun getUserAccountAddress(): ResponseResource<String>
    suspend fun getActivePaymentMethods(): ResponseResource<List<PaymentMethodsData>>
    suspend fun placeBuyOrder(placeBuyOrderParams: PlaceBuyOrderParams): ResponseResource<PlaceBuyOrderUIModel>
    suspend fun commitBuyOrder(buyOrderId: String): ResponseResource<CommitBuyOrderUIModel>
    suspend fun sendFundsToWallet(sendTransactionToWalletParams: SendTransactionToWalletParams, api2FATokenVersion: String?): ResponseResource<SendTransactionToWalletResponse?>
    fun isUserConnected(): Boolean
    suspend fun swapTrade(tradesRequest: TradesRequest): ResponseResource<SwapTradeUIModel>
    suspend fun commitSwapTrade(buyOrderId: String): ResponseResource<SwapTradeUIModel>
    suspend fun completeCoinbaseAuthentication(authorizationCode: String): ResponseResource<Boolean>
    suspend fun getWithdrawalLimit(): ResponseResource<WithdrawalLimitUIModel>
    suspend fun getExchangeRateFromCoinbase(): ResponseResource<CoinbaseToDashExchangeRateUIModel>
}

data class WithdrawalLimitUIModel(
    val amount: String?,
    val currency: String
)
