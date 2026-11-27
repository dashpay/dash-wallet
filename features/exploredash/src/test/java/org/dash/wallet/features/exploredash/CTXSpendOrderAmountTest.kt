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
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.features.exploredash

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GiftCardResponse
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.PurchaseGiftCardRequest
import org.dash.wallet.features.exploredash.network.authenticator.TokenAuthenticator
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendApi
import org.dash.wallet.features.exploredash.repository.CTXSpendRepository
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardShoppingCart
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import org.dash.wallet.features.exploredash.utils.GiftCardCurrency
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking
import java.util.Locale

/** The amount in a gift card order is read by a server, so the device locale must not shape it. */
class CTXSpendOrderAmountTest {
    private val originalLocale = Locale.getDefault()

    private val locales = listOf(
        Locale.US,
        Locale.GERMANY,
        Locale.forLanguageTag("ar-EG-u-nu-arab"),
        Locale.forLanguageTag("hi-IN-u-nu-deva")
    )

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun orderAmount_isPlainAsciiInEveryLocale() {
        val cases = listOf(
            Triple(25.0, "USD", "25.00"),
            Triple(19.99, "USD", "19.99"),
            Triple(0.5, "USD", "0.50"),
            Triple(1000.0, "USD", "1000.00"),
            Triple(2500.5, "GBP", "2500.50"),
            Triple(1234567.89, "EUR", "1234567.89"),
            Triple(1000.0, "JPY", "1000"),
            Triple(12345678.0, "JPY", "12345678"),
            Triple(1.234, "KWD", "1.234"),
            Triple(1500.0, "KWD", "1500.000")
        )

        locales.forEach { locale ->
            Locale.setDefault(locale)
            cases.forEach { (amount, currency, expected) ->
                assertEquals("$amount $currency in $locale", expected, GiftCardCurrency.orderAmount(amount, currency))
            }
        }
    }

    @Test
    fun orderAmount_refusesAnAmountTheCurrencyCannotHold() {
        val cases = listOf(
            1000.5 to "JPY",
            19.999 to "USD",
            1.2345 to "KWD",
            // float noise is not an amount anyone entered, so it is refused rather than tidied up
            0.1 + 0.7 to "USD"
        )

        cases.forEach { (amount, currency) ->
            assertThrows("$amount $currency", ArithmeticException::class.java) {
                GiftCardCurrency.orderAmount(amount, currency)
            }
        }
    }

    @Test
    fun orderGiftcard_sendsThePlainAmountWhateverTheLocale() {
        val cases = listOf(25.0 to "25.00", 10.5 to "10.50", 1000.0 to "1000.00", 2499.99 to "2499.99")

        locales.forEach { locale ->
            cases.forEach { (amount, expected) ->
                Locale.setDefault(locale)
                val api = mock<CTXSpendApi> {
                    onBlocking { purchaseGiftCard(any()) } doReturn GiftCardResponse(id = "order-1", status = "unpaid")
                }
                val config = mock<CTXSpendConfig> { on { observeSecureData(any()) } doReturn emptyFlow() }
                val repository = CTXSpendRepository(api, config, TokenAuthenticator(mock(), config))

                runBlocking {
                    repository.orderGiftcard("DASH", "USD", GiftCardShoppingCart(amount, 1), "merchant")
                }

                val request = argumentCaptor<PurchaseGiftCardRequest>()
                verifyBlocking(api) { purchaseGiftCard(request.capture()) }
                assertEquals("$amount in $locale", expected, request.firstValue.fiatAmount)
            }
        }
    }
}
