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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.safeApiCall
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.*
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseAuthApi
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseClientConstants
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseServicesApi
import org.dash.wallet.integration.coinbase_integration.utils.CoinbaseConfig
import org.dash.wallet.integration.coinbase_integration.viewmodels.toDoubleOrZero
import java.math.BigDecimal
import javax.inject.Inject

class CoinBaseRepository @Inject constructor(
    private val servicesApi: CoinBaseServicesApi,
    private val authApi: CoinBaseAuthApi,
    private val userPreferences: Configuration,
    private val config: CoinbaseConfig,
    private val walletUIConfig: WalletUIConfig,
    private val placeBuyOrderMapper: PlaceBuyOrderMapper,
    private val swapTradeMapper: SwapTradeMapper,
    private val commitBuyOrderMapper: CommitBuyOrderMapper,
    private val coinbaseAddressMapper: CoinbaseAddressMapper
) : CoinBaseRepositoryInt {
    private val configScope = CoroutineScope(Dispatchers.IO)
    private var userAccountInfo: List<CoinBaseUserAccountData> = listOf()

    override val hasValidCredentials: Boolean
        get() = CoinBaseClientConstants.CLIENT_ID.isNotEmpty() &&
            CoinBaseClientConstants.CLIENT_SECRET.isNotEmpty()

    override var isAuthenticated: Boolean = false
        private set

    init {
        config.observe(CoinbaseConfig.LAST_ACCESS_TOKEN)
            .onEach { isAuthenticated = !it.isNullOrEmpty() }
            .launchIn(configScope)
    }

    override suspend fun getUserAccount(): ResponseResource<CoinBaseUserAccountData?> = safeApiCall {
        val apiResponse = servicesApi.getUserAccounts()
        userAccountInfo = apiResponse?.data ?: listOf()
        val userAccountData = userAccountInfo.firstOrNull {
            it.balance?.currency?.equals(Constants.DASH_CURRENCY) ?: false
        }
        userAccountData?.also {
            config.set(CoinbaseConfig.USER_ACCOUNT_ID, it.id ?: "")
            config.set(CoinbaseConfig.LAST_BALANCE, Coin.parseCoin(it.balance?.amount ?: "0.0").value)
        }
    }

    override suspend fun getUserAccounts(exchangeCurrencyCode: String) = safeApiCall {
        if (userAccountInfo.isEmpty()) {
            getUserAccount()
        }

        val exchangeRates = servicesApi.getExchangeRates(exchangeCurrencyCode)?.data
        val currencyToDashExchangeRate = exchangeRates?.rates?.get(Constants.DASH_CURRENCY).orEmpty()
        val currencyToUSDExchangeRate = exchangeRates?.rates?.get(Constants.USD_CURRENCY).orEmpty()

        return@safeApiCall userAccountInfo.map {
            val currencyToCryptoCurrencyExchangeRate = exchangeRates?.rates?.get(it.currency?.code).orEmpty()
            val cryptoCurrencyToDashExchangeRate = (
                BigDecimal(currencyToDashExchangeRate) / BigDecimal(
                    currencyToCryptoCurrencyExchangeRate
                )
                ).toString()

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
        val accessToken = config.get(CoinbaseConfig.LAST_ACCESS_TOKEN)
        config.clearAll()
        accessToken?.let { safeApiCall { authApi.revokeToken(it) } }
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
        val userAccountId = config.get(CoinbaseConfig.USER_ACCOUNT_ID) ?: ""
        val apiResult = servicesApi.placeBuyOrder(
            accountId = userAccountId,
            placeBuyOrderParams = placeBuyOrderParams
        )
        placeBuyOrderMapper.map(apiResult?.data)
    }

    override suspend fun getUserAccountAddress(): ResponseResource<String> = safeApiCall {
        val userAccountId = config.get(CoinbaseConfig.USER_ACCOUNT_ID) ?: ""
        val apiResult = servicesApi.getUserAccountAddress(accountId = userAccountId)
        coinbaseAddressMapper.map(apiResult)
    }

    override suspend fun commitBuyOrder(buyOrderId: String) = safeApiCall {
        val userAccountId = config.get(CoinbaseConfig.USER_ACCOUNT_ID) ?: ""
        val commitBuyResult = servicesApi.commitBuyOrder(
            accountId = userAccountId,
            buyOrderId = buyOrderId
        )
        commitBuyOrderMapper.map(commitBuyResult?.data)
    }

    override suspend fun sendFundsToWallet(
        sendTransactionToWalletParams: SendTransactionToWalletParams,
        api2FATokenVersion: String?
    ) = safeApiCall {
        val userAccountId = config.get(CoinbaseConfig.USER_ACCOUNT_ID) ?: ""
        servicesApi.sendCoinsToWallet(
            accountId = userAccountId,
            sendTransactionToWalletParams = sendTransactionToWalletParams,
            api2FATokenVersion = api2FATokenVersion
        )
    }

    override suspend fun completeCoinbaseAuthentication(authorizationCode: String) = safeApiCall {
        authApi.getToken(code = authorizationCode).also {
            it?.let { tokenResponse ->
                config.set(CoinbaseConfig.LAST_ACCESS_TOKEN, tokenResponse.accessToken)
                config.set(CoinbaseConfig.LAST_REFRESH_TOKEN, tokenResponse.refreshToken)
                config.set(CoinbaseConfig.LOGOUT_COINBASE, false)
                getUserAccount()
            }
        }
        !config.get(CoinbaseConfig.LAST_ACCESS_TOKEN).isNullOrEmpty()
    }

    override suspend fun getWithdrawalLimit() = safeApiCall {
        val apiResponse = servicesApi.getAuthorizationInformation()
        apiResponse?.data?.oauthMeta?.let { metadata ->
            config.set(CoinbaseConfig.USER_WITHDRAWAL_LIMIT, metadata.sendLimitAmount)
            config.set(CoinbaseConfig.SEND_LIMIT_CURRENCY, metadata.sendLimitCurrency)
        }
        WithdrawalLimitUIModel(
            apiResponse?.data?.oauthMeta?.sendLimitAmount,
            apiResponse?.data?.oauthMeta?.sendLimitCurrency ?: ""
        )
    }

    override suspend fun getExchangeRateFromCoinbase() = safeApiCall {
        if (userAccountInfo.isEmpty()) {
            getUserAccount()
        }

        val userAccountData = userAccountInfo.firstOrNull {
            it.balance?.currency?.equals(Constants.DASH_CURRENCY) ?: false
        }

        val currencyCode = walletUIConfig.getExchangeCurrencyCode()
        val exchangeRates = servicesApi.getExchangeRates(currencyCode)?.data

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
        val userAccountId = config.get(CoinbaseConfig.USER_ACCOUNT_ID) ?: ""
        servicesApi.createAddress(accountId = userAccountId)?.addresses?.address ?: ""
    }

    override suspend fun getWithdrawalLimitInDash(exchangeRate: ExchangeRate): Double {
        val withdrawalLimit = config.get(CoinbaseConfig.USER_WITHDRAWAL_LIMIT)
        return if (withdrawalLimit.isNullOrEmpty()) {
            0.0
        } else {
            val formattedAmount = GenericUtils.formatFiatWithoutComma(withdrawalLimit)
            val currency = config.get(CoinbaseConfig.SEND_LIMIT_CURRENCY) ?: CoinbaseConstants.DEFAULT_CURRENCY_USD
            val fiatAmount = try {
                Fiat.parseFiat(currency, formattedAmount)
            } catch (x: Exception) {
                Fiat.valueOf(currency, 0)
            }
            val amountInDash = exchangeRate.fiatToCoin(fiatAmount)
            return amountInDash.toPlainString().toDoubleOrZero
        }
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
    suspend fun createAddress(): ResponseResource<String>
    suspend fun getUserAccountAddress(): ResponseResource<String>
    suspend fun getActivePaymentMethods(): ResponseResource<List<PaymentMethodsData>>
    suspend fun placeBuyOrder(placeBuyOrderParams: PlaceBuyOrderParams): ResponseResource<PlaceBuyOrderUIModel>
    suspend fun commitBuyOrder(buyOrderId: String): ResponseResource<CommitBuyOrderUIModel>
    suspend fun sendFundsToWallet(
        sendTransactionToWalletParams: SendTransactionToWalletParams,
        api2FATokenVersion: String?
    ): ResponseResource<SendTransactionToWalletResponse?>
    suspend fun swapTrade(tradesRequest: TradesRequest): ResponseResource<SwapTradeUIModel>
    suspend fun commitSwapTrade(buyOrderId: String): ResponseResource<SwapTradeUIModel>
    suspend fun completeCoinbaseAuthentication(authorizationCode: String): ResponseResource<Boolean>
    suspend fun getWithdrawalLimit(): ResponseResource<WithdrawalLimitUIModel>
    suspend fun getExchangeRateFromCoinbase(): ResponseResource<CoinbaseToDashExchangeRateUIModel>
    suspend fun getWithdrawalLimitInDash(exchangeRate: ExchangeRate): Double
}

data class WithdrawalLimitUIModel(
    val amount: String?,
    val currency: String
)
