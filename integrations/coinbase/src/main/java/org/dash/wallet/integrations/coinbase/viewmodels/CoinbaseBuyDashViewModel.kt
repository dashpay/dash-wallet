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
package org.dash.wallet.integrations.coinbase.viewmodels

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethod
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethodType
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toCoin
import org.dash.wallet.common.util.toFiat
import org.dash.wallet.integrations.coinbase.CoinbaseConstants
import org.dash.wallet.integrations.coinbase.model.CoinbaseErrorType
import org.dash.wallet.integrations.coinbase.model.MarketMarketIoc
import org.dash.wallet.integrations.coinbase.model.OrderConfiguration
import org.dash.wallet.integrations.coinbase.model.PlaceOrderParams
import org.dash.wallet.integrations.coinbase.model.SendTransactionToWalletParams
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject

data class CoinbaseBuyUIState(
    val dashAmount: Coin = Coin.ZERO,
    val order: Fiat? = null,
    val fee: Fiat? = null,
    val paymentMethod: PaymentMethod? = null
)

@HiltViewModel
class CoinbaseBuyDashViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    var exchangeRates: ExchangeRatesProvider,
    private val analyticsService: AnalyticsService,
    private val walletDataProvider: WalletDataProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoinbaseBuyUIState())
    val uiState: StateFlow<CoinbaseBuyUIState> = _uiState.asStateFlow()

    suspend fun validateBuyDash(amount: Coin, retryWithDeposit: Boolean): CoinbaseErrorType {
        previewBuyOrder(amount)

        val fiatAmount = uiState.value.order ?: return CoinbaseErrorType.NO_EXCHANGE_RATE
        val fiatAccount = coinBaseRepository.getFiatAccount()
        val balance = Fiat.parseFiatInexact(
            CoinbaseConstants.DEFAULT_CURRENCY_USD,
            fiatAccount.availableBalance.value
        )

        val paymentMethod: PaymentMethod

        if (balance >= fiatAmount) {
            paymentMethod = PaymentMethod(
                fiatAccount.uuid.toString(),
                fiatAccount.name,
                account = fiatAccount.currency,
                accountType = fiatAccount.type,
                paymentMethodType = PaymentMethodType.Fiat,
                isValid = true
            )
        } else if (retryWithDeposit) {
            val bankAccount = coinBaseRepository.getActivePaymentMethods().firstOrNull {
                paymentMethodTypeFromCoinbaseType(it.type) == PaymentMethodType.BankAccount
            } ?: return CoinbaseErrorType.NO_BANK_ACCOUNT

            paymentMethod = PaymentMethod(
                bankAccount.id,
                bankAccount.name,
                account = "",
                accountType = bankAccount.type,
                paymentMethodType = PaymentMethodType.BankAccount,
                isValid = true
            )
        } else {
            return CoinbaseErrorType.INSUFFICIENT_BALANCE
        }

        _uiState.update { it.copy(paymentMethod = paymentMethod) }
        return CoinbaseErrorType.NONE
    }

    suspend fun buyDash() {
        val amount = uiState.value.order ?: return

        analyticsService.logEvent(AnalyticsConstants.Coinbase.QUOTE_CONFIRM, mapOf())
        val format = Constants.SEND_PAYMENT_LOCAL_FORMAT.noCode().roundingMode(RoundingMode.UP)
        val amountStr = format.format(amount).toString()

        if (uiState.value.paymentMethod?.paymentMethodType == PaymentMethodType.BankAccount) {
            coinBaseRepository.depositToFiatAccount(
                uiState.value.paymentMethod!!.paymentMethodId,
                amountStr
            )
        }

        val params = PlaceOrderParams(
            UUID.randomUUID(),
            productId = CoinbaseConstants.DASH_USD_PAIR,
            side = CoinbaseConstants.TRANSACTION_TYPE_BUY,
            OrderConfiguration(
                MarketMarketIoc(amountStr)
            )
        )

        coinBaseRepository.placeBuyOrder(params)
    }

    fun getTransferDashParams(): SendTransactionToWalletParams {
        return SendTransactionToWalletParams(
            amount = uiState.value.dashAmount.toPlainString(),
            currency = Constants.DASH_CURRENCY,
            idem = UUID.randomUUID().toString(),
            to = walletDataProvider.freshReceiveAddress().toBase58(),
            type = CoinbaseConstants.TRANSACTION_TYPE_SEND
        )
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, mapOf())
    }

    fun logContinue(dashToFiat: Boolean) {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.BUY_CONTINUE, mapOf())
        analyticsService.logEvent(
            if (dashToFiat) {
                AnalyticsConstants.Coinbase.BUY_ENTER_DASH
            } else {
                AnalyticsConstants.Coinbase.BUY_ENTER_FIAT
            },
            mapOf()
        )
    }

    private suspend fun previewBuyOrder(dashAmount: Coin) {
        _uiState.update { it.copy(dashAmount = dashAmount) }

        val coinbaseFee = dashAmount.toBigDecimal().multiply(CoinbaseConstants.BUY_FEE.toBigDecimal()).toCoin()
        val rates = coinBaseRepository.getExchangeRates(CoinbaseConstants.DEFAULT_CURRENCY_USD)
        var order: Fiat? = null
        var feeInFiat: Fiat? = null

        rates[Constants.DASH_CURRENCY]?.let { rate ->
            val dashRate = 1.toBigDecimal().divide(rate.toBigDecimal(), 8, RoundingMode.HALF_UP)
            val exchangeRate = dashRate?.let {
                ExchangeRate(Coin.COIN, it.toFiat(CoinbaseConstants.DEFAULT_CURRENCY_USD))
            }
            order = exchangeRate?.coinToFiat(dashAmount)
            feeInFiat = exchangeRate?.coinToFiat(coinbaseFee)
        }

        _uiState.update { it.copy(dashAmount = dashAmount, order = order, fee = feeInFiat) }
    }

    private fun paymentMethodTypeFromCoinbaseType(type: String?): PaymentMethodType {
        return when (type) {
            "COINBASE_FIAT_ACCOUNT" -> PaymentMethodType.Fiat
            "SECURE3D_CARD", "WORLDPAY_CARD", "CREDIT_CARD", "DEBIT_CARD" -> PaymentMethodType.Card
            "ACH", "SEPA",
            "IDEAL", "EFT", "INTERAC" -> PaymentMethodType.BankAccount
            "BANK_WIRE" -> PaymentMethodType.WireTransfer
            "PAYPAL", "PAYPAL_ACCOUNT" -> PaymentMethodType.PayPal
            "APPLE_PAY" -> PaymentMethodType.ApplePay
            "GOOGLE_PAY" -> PaymentMethodType.GooglePay
            else -> PaymentMethodType.Unknown
        }
    }
}

val String.toDoubleOrZero: Double
    get() = try {
        this.toDouble()
    } catch (e: NumberFormatException) {
        0.0
    }
