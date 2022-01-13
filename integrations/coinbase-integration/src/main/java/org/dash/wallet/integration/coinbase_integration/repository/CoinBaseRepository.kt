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
import org.dash.wallet.integration.coinbase_integration.CommitBuyOrderMapper
import org.dash.wallet.integration.coinbase_integration.DASH_CURRENCY
import org.dash.wallet.integration.coinbase_integration.PlaceBuyOrderMapper
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.network.safeApiCall
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseAuthApi
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseServicesApi
import retrofit2.Response
import javax.inject.Inject

class CoinBaseRepository @Inject constructor(
    private val servicesApi: CoinBaseServicesApi,
    private val authApi: CoinBaseAuthApi,
    private val userPreferences: Configuration,
    private val placeBuyOrderMapper: PlaceBuyOrderMapper,
    private val commitBuyOrderMapper: CommitBuyOrderMapper
): CoinBaseRepositoryInt {
    override suspend fun getUserAccount() = safeApiCall {
        val apiResponse = servicesApi.getUserAccount()
        val userAccountData = apiResponse.body()?.data?.firstOrNull {
            it.balance?.currency?.equals(DASH_CURRENCY) ?: false
        }
        userAccountData?.also {
            saveLastCoinbaseDashAccountBalance(it.balance?.amount)
            saveUserAccountId(it.id)
        }
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

    override suspend fun authenticateOnCoinbase(requestCode: String): ResponseResource<Boolean> = safeApiCall {
        authApi.getToken(code = requestCode).also {
            it.body()?.let { tokenResponse ->
                userPreferences.setLastCoinBaseAccessToken(tokenResponse.accessToken)
                userPreferences.setLastCoinBaseRefreshToken(tokenResponse.refreshToken)
                //saveAccessTokens(TEMP_ACCESS_TOKEN, TEMP_REFRESH_TOKEN)   // Temp values for refresh & access tokens
            }
        }
        userPreferences.lastCoinbaseAccessToken.isNullOrEmpty().not()
    }

    override fun getUserLastCoinbaseBalance(): String = userPreferences.lastCoinbaseBalance ?: ""
    override fun isUserConnected(): Boolean = userPreferences.lastCoinbaseAccessToken.isNullOrEmpty().not()
}

interface CoinBaseRepositoryInt {
    suspend fun getUserAccount(): ResponseResource<CoinBaseUserAccountData?>
    suspend fun getExchangeRates(): ResponseResource<Response<CoinBaseUserAccountInfo>>
    suspend fun disconnectCoinbaseAccount()
    fun saveLastCoinbaseDashAccountBalance(amount: String?)
    fun saveUserAccountId(accountId: String?)
    suspend fun getActivePaymentMethods(): ResponseResource<List<PaymentMethodsData>>
    suspend fun placeBuyOrder(placeBuyOrderParams: PlaceBuyOrderParams): ResponseResource<PlaceBuyOrderUIModel>
    suspend fun commitBuyOrder(buyOrderId: String): ResponseResource<CommitBuyOrderUIModel>
    suspend fun sendFundsToWallet(sendTransactionToWalletParams: SendTransactionToWalletParams): ResponseResource<Int>
    suspend fun authenticateOnCoinbase(requestCode: String): ResponseResource<Boolean>
    fun getUserLastCoinbaseBalance(): String
    fun isUserConnected(): Boolean
}