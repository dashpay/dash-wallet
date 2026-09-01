/*
 * Copyright 2026 Dash Core Group.
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
 */

package org.dash.wallet.integrations.coinbase

import org.dash.wallet.integrations.coinbase.model.Balance
import org.dash.wallet.integrations.coinbase.model.CoinbaseAccount
import org.dash.wallet.integrations.coinbase.model.CoinBaseUserAccountDataUIModel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Pins what each rate on [CoinBaseUserAccountDataUIModel] means, because `toDashValue`
 * picks between them and picking wrong is a wrong amount rather than a visible failure.
 *
 * Coinbase's /exchange-rates?currency=LOCAL returns rates[X] = how many X one unit of the
 * local currency buys. So `currencyToDashExchangeRate` is DASH per local currency and
 * `currencyToCryptoCurrencyExchangeRate` is crypto per local currency.
 */
class ExchangeRateSemanticsTest {
    companion object {
        // 1 EUR buys 0.02 DASH (DASH = 50 EUR) and 0.00002 BTC (BTC = 50,000 EUR).
        private val DASH_PER_LOCAL = BigDecimal("0.02")
        private val CRYPTO_PER_LOCAL = BigDecimal("0.00002")
    }

    private val account = CoinBaseUserAccountDataUIModel(
        CoinbaseAccount(
            uuid = UUID.randomUUID(),
            name = "BTC Wallet",
            currency = "BTC",
            availableBalance = Balance("1.0", "BTC"),
            default = true,
            active = true,
            type = "ACCOUNT_TYPE_CRYPTO",
            ready = true
        ),
        CRYPTO_PER_LOCAL,
        DASH_PER_LOCAL,
        BigDecimal("1.1")
    )

    private fun scaled(value: BigDecimal) = value.setScale(8, RoundingMode.HALF_UP)

    @Test
    fun `crypto to dash is the ratio of the two rates`() {
        // 1 BTC = 50,000 EUR = 1000 DASH.
        assertEquals(scaled(BigDecimal("1000")), scaled(account.getCryptoToDashExchangeRate()))
    }

    @Test
    fun `a crypto amount converts with the crypto rate`() {
        // 0.5 BTC -> 500 DASH.
        val dash = BigDecimal("0.5") * account.getCryptoToDashExchangeRate()
        assertEquals(scaled(BigDecimal("500")), scaled(dash))
    }

    @Test
    fun `a local-currency amount converts with the local rate`() {
        // 100 EUR -> 2 DASH.
        val dash = BigDecimal("100") * account.currencyToDashExchangeRate
        assertEquals(scaled(BigDecimal("2")), scaled(dash))
    }

    @Test
    fun `using the crypto rate on a local-currency amount is wrong by the price ratio`() {
        // The defect toDashValue carried: its `if` and `else` were the same expression, so
        // a fiat entry took the crypto rate. 100 EUR became 100,000 DASH instead of 2.
        val correct = BigDecimal("100") * account.currencyToDashExchangeRate
        val asItWas = BigDecimal("100") * account.getCryptoToDashExchangeRate()

        assertEquals(scaled(BigDecimal("2")), scaled(correct))
        assertEquals(scaled(BigDecimal("100000")), scaled(asItWas))
    }
}
