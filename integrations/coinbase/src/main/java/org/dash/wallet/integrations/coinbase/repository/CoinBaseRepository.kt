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
package org.dash.wallet.integrations.coinbase.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.safeApiCall
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integrations.coinbase.*
import org.dash.wallet.integrations.coinbase.model.*
import org.dash.wallet.integrations.coinbase.service.CoinBaseAuthApi
import org.dash.wallet.integrations.coinbase.service.CoinBaseClientConstants
import org.dash.wallet.integrations.coinbase.service.CoinBaseServicesApi
import org.dash.wallet.integrations.coinbase.utils.CoinbaseConfig
import org.dash.wallet.integrations.coinbase.viewmodels.toDoubleOrZero
import java.math.BigDecimal
import javax.inject.Inject

interface CoinBaseRepositoryInt {
    val hasValidCredentials: Boolean
    val isAuthenticated: Boolean

    suspend fun getUserAccount(): CoinbaseAccount
    suspend fun getUserAccounts(exchangeCurrencyCode: String): List<CoinBaseUserAccountDataUIModel>
    suspend fun getFiatAccount(): CoinbaseAccount
    suspend fun getBaseIdForUSDModel(baseCurrency: String): ResponseResource<BaseIdForUSDModel?>
    suspend fun getExchangeRates(currencyCode: String): Map<String, String>
    suspend fun disconnectCoinbaseAccount()
    suspend fun createAddress(): ResponseResource<String>
    suspend fun getUserAccountAddress(): ResponseResource<String>
    suspend fun getActivePaymentMethods(): List<PaymentMethodsData>
    suspend fun depositToFiatAccount(paymentMethodId: String, amountUSD: String)
    suspend fun placeBuyOrder(placeBuyOrderParams: PlaceOrderParams): PlaceOrderResponse
    suspend fun sendFundsToWallet(
        sendTransactionToWalletParams: SendTransactionToWalletParams,
        api2FATokenVersion: String?
    ): ResponseResource<SendTransactionToWalletResponse?>
    suspend fun swapTrade(tradesRequest: TradesRequest): ResponseResource<SwapTradeUIModel>
    suspend fun commitSwapTrade(buyOrderId: String): ResponseResource<SwapTradeUIModel>
    suspend fun completeCoinbaseAuthentication(authorizationCode: String): ResponseResource<Boolean>
    suspend fun refreshWithdrawalLimit()
    suspend fun getExchangeRateFromCoinbase(): ResponseResource<CoinbaseToDashExchangeRateUIModel>
    suspend fun getWithdrawalLimitInDash(): Double
}

class CoinBaseRepository @Inject constructor(
    private val servicesApi: CoinBaseServicesApi,
    private val authApi: CoinBaseAuthApi,
    private val config: CoinbaseConfig,
    private val walletUIConfig: WalletUIConfig,
    private val swapTradeMapper: SwapTradeMapper,
    private val coinbaseAddressMapper: CoinbaseAddressMapper,
    private val exchangeRates: ExchangeRatesProvider
) : CoinBaseRepositoryInt {
    private val configScope = CoroutineScope(Dispatchers.IO)
    private var userAccountInfo: List<CoinbaseAccount> = listOf()

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

    override suspend fun getUserAccount(): CoinbaseAccount {
        val accountsResponse = servicesApi.getAccounts()
        userAccountInfo = accountsResponse.accounts
        val userAccountData = userAccountInfo.firstOrNull {
            it.currency == Constants.DASH_CURRENCY
        } ?: throw IllegalStateException("No DASH account found")

        return userAccountData.also {
            config.set(CoinbaseConfig.USER_ACCOUNT_ID, it.uuid.toString())
            config.set(CoinbaseConfig.LAST_BALANCE, Coin.parseCoin(it.availableBalance.value).value)
        }
    }

    override suspend fun getUserAccounts(exchangeCurrencyCode: String): List<CoinBaseUserAccountDataUIModel> {
        if (userAccountInfo.isEmpty()) {
            getUserAccount()
        }

        val exchangeRates = servicesApi.getExchangeRates(exchangeCurrencyCode)?.data // TODO: cache?
        val currencyToDashExchangeRate = exchangeRates?.rates?.get(Constants.DASH_CURRENCY).orEmpty()
        val currencyToUSDExchangeRate = exchangeRates?.rates?.get(Constants.USD_CURRENCY).orEmpty()

        return userAccountInfo.map {
            val currencyToCryptoCurrencyExchangeRate = exchangeRates?.rates?.get(it.currency).orEmpty()
            CoinBaseUserAccountDataUIModel(
                it,
                BigDecimal(currencyToCryptoCurrencyExchangeRate),
                // TODO: below values don't depend on a specific account. Refactor out
                BigDecimal(currencyToDashExchangeRate),
                BigDecimal(currencyToUSDExchangeRate)
            )
        }
    }

    override suspend fun getFiatAccount(): CoinbaseAccount {
        return userAccountInfo.first {
            it.type == CoinbaseConstants.FIAT_ACCOUNT_TYPE &&
                it.currency == CoinbaseConstants.DEFAULT_CURRENCY_USD
        }
    }

    override suspend fun getBaseIdForUSDModel(baseCurrency: String) = safeApiCall {
        servicesApi.getBaseIdForUSDModel(baseCurrency = baseCurrency)
    }

    override suspend fun getExchangeRates(currencyCode: String): Map<String, String> {
        return servicesApi.getExchangeRates(currencyCode)?.data?.rates ?: mapOf()
    }

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

    override suspend fun getActivePaymentMethods(): List<PaymentMethodsData> {
        val apiResult = servicesApi.getActivePaymentMethods()
        return apiResult?.data ?: emptyList()
    }

    override suspend fun depositToFiatAccount(paymentMethodId: String, amountUSD: String) {
        val result = servicesApi.depositTo(
            accountId = getFiatAccount().uuid.toString(),
            request = DepositRequest(
                amount = amountUSD,
                currency = CoinbaseConstants.DEFAULT_CURRENCY_USD,
                paymentMethod = paymentMethodId
            )
        )

        if (!result.isSuccessful) {
            throw CoinbaseException(
                CoinbaseErrorType.DEPOSIT_FAILED,
                result.errorBody()?.string()?.let {
                    CoinbaseErrorResponse.getErrorMessage(it)
                }?.message ?: ""
            )
        } else if (result.body()?.data?.status != "created") {
            throw CoinbaseException(
                CoinbaseErrorType.DEPOSIT_FAILED,
                result.body()?.data?.status
            )
        }
    }

    override suspend fun placeBuyOrder(placeBuyOrderParams: PlaceOrderParams): PlaceOrderResponse {
        val result = servicesApi.placeBuyOrder(placeBuyOrderParams)

        if (!result.success) {
            throw CoinbaseException(
                CoinbaseErrorType.BUY_FAILED,
                result.errorResponse?.message ?: result.failureReason
            )
        }

        return result
    }

    override suspend fun getUserAccountAddress(): ResponseResource<String> = safeApiCall {
        val userAccountId = config.get(CoinbaseConfig.USER_ACCOUNT_ID) ?: ""
        val apiResult = servicesApi.getUserAccountAddress(accountId = userAccountId)
        coinbaseAddressMapper.map(apiResult)
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
                getUserAccount()
            }
        }
        !config.get(CoinbaseConfig.LAST_ACCESS_TOKEN).isNullOrEmpty()
    }

    override suspend fun refreshWithdrawalLimit() {
        val apiResponse = servicesApi.getAuthorizationInformation()
        apiResponse?.data?.oauthMeta?.let { metadata ->
            config.set(CoinbaseConfig.USER_WITHDRAWAL_LIMIT, metadata.sendLimitAmount)
            config.set(CoinbaseConfig.SEND_LIMIT_CURRENCY, metadata.sendLimitCurrency)
        }
    }

    override suspend fun getExchangeRateFromCoinbase() = safeApiCall {
        if (userAccountInfo.isEmpty()) {
            getUserAccount()
        }

        val userAccountData = userAccountInfo.firstOrNull {
            it.currency == Constants.DASH_CURRENCY
        }

        val currencyCode = walletUIConfig.getExchangeCurrencyCode()
        val exchangeRates = servicesApi.getExchangeRates(currencyCode)?.data

        return@safeApiCall userAccountData?.let {
            val currencyToDashExchangeRate = exchangeRates?.rates?.get(Constants.DASH_CURRENCY).orEmpty()
            val currencyToUSDExchangeRate = exchangeRates?.rates?.get(Constants.USD_CURRENCY).orEmpty()
            CoinbaseToDashExchangeRateUIModel(
                it,
                BigDecimal(currencyToDashExchangeRate),
                BigDecimal(currencyToUSDExchangeRate)
            )
        } ?: CoinbaseToDashExchangeRateUIModel.EMPTY
    }

    override suspend fun createAddress() = safeApiCall {
        val userAccountId = config.get(CoinbaseConfig.USER_ACCOUNT_ID) ?: ""
        servicesApi.createAddress(accountId = userAccountId)?.addresses?.address ?: ""
    }

    override suspend fun getWithdrawalLimitInDash(): Double {
        val withdrawalLimit = config.get(CoinbaseConfig.USER_WITHDRAWAL_LIMIT)
        val withdrawalLimitCurrency = config.get(CoinbaseConfig.SEND_LIMIT_CURRENCY)
            ?: CoinbaseConstants.DEFAULT_CURRENCY_USD
        val exchangeRate = exchangeRates.getExchangeRate(withdrawalLimitCurrency)?.let {
            ExchangeRate(Coin.COIN, it.fiat)
        }

        return if (withdrawalLimit.isNullOrEmpty() || exchangeRate == null) {
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

