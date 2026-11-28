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
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.freshReceiveAddressStringOffMain
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.FiatValue
import org.dash.wallet.common.money.dashToFiat
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethod
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethodType
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.toDash
import org.dash.wallet.common.util.toFiatValue
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
    val dashAmount: Dash = Dash.ZERO,
    val order: FiatValue? = null,
    val fee: FiatValue? = null,
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

    private val confirmLock = Any()

    /**
     * The confirm attempt that currently OWNS the single-flight, or null.
     *
     * The single-flight covers the whole confirm sequence: the destination read,
     * the fiat deposit, [buyDash]'s `placeBuyOrder`, and the handoff to 2FA. The
     * confirm button launches a coroutine, and the first thing that coroutine
     * does — [getTransferDashParams] — can block for seconds: post cutover it
     * waits for the SDK engine to bind and then takes the engine's wallet-manager
     * lock. Nothing is on screen for that wait, so repeated taps each started
     * their own coroutine, and every one that got an address went on to place its
     * OWN order under a fresh idempotency UUID (and, on the bank path, to repeat
     * the deposit). Coinbase cannot collapse those: different UUIDs are different
     * orders by definition, so the user is charged twice.
     *
     * This is an attempt id rather than a flag because this view model outlives
     * the fragment that drives it. Rotate while an attempt is parked in that
     * blocking read and the attempt is cancelled, but cancellation cannot finish
     * it until the read returns; meanwhile the recreated fragment can legitimately
     * start a new one. A plain flag released by whoever finished last would let
     * the stale attempt's unwinding clear the new attempt's ownership, and the new
     * attempt — which HAS prepared its destination — would then fail [buyDash]'s
     * check and show a purchase error. Releases therefore name the attempt they
     * are releasing and are a no-op for anyone who no longer owns the flight.
     */
    private var activeConfirmAttempt: Long? = null

    /**
     * Set once an attempt has actually placed its order and handed off to 2FA.
     *
     * Deliberately NOT the same state as [activeConfirmAttempt]: nothing is
     * running any more, but the review screen is still attached and still
     * clickable while the navigation transaction executes, so a queued tap must
     * not start a second purchase. Only the review screen coming back to the
     * front — `onResume` with no confirm of its own running — drops this, and
     * dropping it must never disturb an attempt that IS running.
     */
    private var confirmNavigationLatched = false

    private var lastConfirmAttempt = 0L

    /**
     * Take the confirm single-flight, returning the new attempt's id, or null if
     * an attempt already owns it or the navigation latch is still set. Call this
     * on the tap itself, not inside the coroutine it starts: that is the only
     * point at which two taps are ordered against each other.
     */
    fun tryBeginConfirm(): Long? = synchronized(confirmLock) {
        if (activeConfirmAttempt != null || confirmNavigationLatched) {
            return null
        }

        return (++lastConfirmAttempt).also { activeConfirmAttempt = it }
    }

    /**
     * Give the single-flight back for [attemptId] — a RETRYABLE failure, one
     * where nothing was bought, or an attempt abandoned because the fragment that
     * owned it was destroyed. A no-op unless [attemptId] still owns the flight,
     * so a cancelled attempt unwinding late cannot release someone else's.
     */
    fun endConfirm(attemptId: Long) = synchronized(confirmLock) {
        if (activeConfirmAttempt == attemptId) {
            activeConfirmAttempt = null
        }
    }

    /**
     * [attemptId] bought: hand its ownership over to the navigation latch. The
     * operation is over — so a stale sibling can no longer be confused with it —
     * while the button stays guarded until the review screen is resumed.
     */
    fun latchConfirmForNavigation(attemptId: Long) = synchronized(confirmLock) {
        if (activeConfirmAttempt == attemptId) {
            activeConfirmAttempt = null
            confirmNavigationLatched = true
        }
    }

    /**
     * Drop the completed-navigation latch, and ONLY that: an attempt still
     * running keeps the flight it owns.
     */
    fun releaseConfirmNavigationLatch() = synchronized(confirmLock) {
        confirmNavigationLatched = false
    }

    /** Whether anything — a running attempt or the navigation latch — holds the flight. */
    fun isConfirmBusy(): Boolean = synchronized(confirmLock) {
        activeConfirmAttempt != null || confirmNavigationLatched
    }

    suspend fun validateBuyDash(amount: Dash, retryWithDeposit: Boolean): CoinbaseErrorType {
        previewBuyOrder(amount)

        val fiatAmount = uiState.value.order ?: return CoinbaseErrorType.NO_EXCHANGE_RATE
        val fiatAccount = try {
            coinBaseRepository.getFiatAccount()
        } catch (_: NoSuchElementException) {
            return CoinbaseErrorType.NO_USD_ACCOUNT
        }
        val balance = FiatValue.parseFiatInexact(
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

    suspend fun buyDash(attemptId: Long) {
        // The tripwire for the guard above: this method deposits and places an
        // order with a fresh UUID, so reaching it twice IS the double purchase.
        // Failing here turns a wiring mistake into a caught error on the review
        // screen instead of a second charge. The caller must still OWN the flight,
        // not merely find somebody holding it.
        check(synchronized(confirmLock) { activeConfirmAttempt == attemptId }) {
            "buyDash() outside its own confirm single-flight — a second order under a new " +
                "idempotency UUID is a second purchase"
        }
        val amount = uiState.value.order ?: return

        analyticsService.logEvent(AnalyticsConstants.Coinbase.QUOTE_CONFIRM, mapOf())
        val format = Constants.SEND_PAYMENT_LOCAL_MONEY_FORMAT.noCode().roundingMode(RoundingMode.UP)
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

    suspend fun getTransferDashParams(): SendTransactionToWalletParams {
        return SendTransactionToWalletParams(
            amount = uiState.value.dashAmount.toPlainString(),
            currency = Constants.DASH_CURRENCY,
            idem = UUID.randomUUID().toString(),
            // Off-main: the caller launches this from lifecycleScope (Main), and the
            // underlying freshReceiveAddress() forces a synchronous full-wallet save.
            to = walletDataProvider.freshReceiveAddressStringOffMain(),
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

    private suspend fun previewBuyOrder(dashAmount: Dash) {
        _uiState.update { it.copy(dashAmount = dashAmount) }

        val coinbaseFee = dashAmount.toBigDecimal().multiply(CoinbaseConstants.BUY_FEE.toBigDecimal()).toDash()
        val rates = coinBaseRepository.getExchangeRates(CoinbaseConstants.DEFAULT_CURRENCY_USD)
        var order: FiatValue? = null
        var feeInFiat: FiatValue? = null

        rates[Constants.DASH_CURRENCY]?.let { rate ->
            val dashRate = 1.toBigDecimal().divide(rate.toBigDecimal(), 8, RoundingMode.HALF_UP)
            val dashPrice = dashRate?.toFiatValue(CoinbaseConstants.DEFAULT_CURRENCY_USD)
            order = dashPrice?.dashToFiat(dashAmount)
            feeInFiat = dashPrice?.dashToFiat(coinbaseFee)
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
