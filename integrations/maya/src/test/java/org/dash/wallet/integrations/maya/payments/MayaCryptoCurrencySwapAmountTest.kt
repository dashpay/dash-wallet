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

package org.dash.wallet.integrations.maya.payments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Guards the swap sell-amount quantization ([MayaCryptoCurrency.formatSwapAmount]).
 *
 * Regression: a DEX buy of 5.70167264 USDC (an 8-decimal amount for a 6-decimal token) was
 * registered with the NEAR Intents quote as 5.701673 (rounded up) while the deposit URI
 * transferred 5.701672 (truncated) — one base unit short of the quote, so the whole deposit
 * was refunded. Sell amounts must be quantized DOWN to the asset's on-chain decimals so the
 * quoted amount and the deposited amount are always the same exact value.
 */
class MayaCryptoCurrencySwapAmountTest {

    private val arbUsdc = MayaCurrencyList["ARB.USDC-0XAF88D065E77C8CC2239327C5EDB3A432268E5831"]!!

    @Test
    fun usdcSellAmountIsQuantizedDownToSixDecimals() {
        // The exact amount from the refunded field swap.
        assertEquals("5.701672", arbUsdc.formatSwapAmount(BigDecimal("5.70167264")))
    }

    @Test
    fun quantizedUsdcAmountRoundTripsExactlyThroughThePaymentUri() {
        // The URI encodes base units via movePointRight(decimals).toBigInteger(), which
        // truncates. A formatSwapAmount value must survive that conversion without loss —
        // i.e. the amount the paying wallet sends equals the amount quoted.
        val amount = arbUsdc.formatSwapAmount(BigDecimal("5.70167264"))
        val uri = arbUsdc.getPaymentRequestURI("0x4A870Cc5F1275bf21551aC07e85861e638DE2EFf", amount)
        assertTrue("URI should carry the exact base-unit amount: $uri", uri.contains("uint256=5701672"))
    }

    @Test
    fun sixDecimalEntryIsNotChanged() {
        assertEquals("5.701672", arbUsdc.formatSwapAmount(BigDecimal("5.701672")))
    }

    @Test
    fun stablecoinDecimalsAreSixOnEveryChainExceptBsc() {
        val bscUsdc = MayaCurrencyList["BSC.USDC-0X8AC76A51CC950D9822D68B83FE1AD97B32CD580D"]
        val solUsdc = MayaCurrencyList["SOL.USDC-EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"]
        val tronUsdt = MayaCurrencyList["TRON.USDT-TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"]
        assertNotNull(bscUsdc)
        assertNotNull(solUsdc)
        assertNotNull(tronUsdt)
        assertEquals(6, arbUsdc.decimals)
        assertEquals(18, bscUsdc!!.decimals) // Binance-Peg USDC is an 18-decimal token
        assertEquals(6, solUsdc!!.decimals)
        assertEquals(6, tronUsdt!!.decimals)
    }

    @Test
    fun nativeCoinDecimalsMatchTheirChains() {
        assertEquals(8, MayaCurrencyList["BTC.BTC"]!!.decimals)
        assertEquals(18, MayaCurrencyList["ETH.ETH"]!!.decimals)
        assertEquals(6, MayaCurrencyList["XRP.XRP"]!!.decimals)
        assertEquals(6, MayaCurrencyList["TRON.TRX"]!!.decimals)
        assertEquals(6, MayaCurrencyList["ADA.ADA"]!!.decimals)
        assertEquals(9, MayaCurrencyList["SOL.SOL"]!!.decimals)
    }

    @Test
    fun everyCurrencyQuantizesToItsRepresentablePrecision() {
        // For every registered currency, a high-precision value must come back with no more
        // decimals than the asset can represent on chain (capped at the UI's 8), and never
        // rounded up past the original value.
        val input = BigDecimal("0.1234567891234")
        val failures = MayaCurrencyList.all.mapNotNull { currency ->
            val formatted = BigDecimal(currency.formatSwapAmount(input))
            val scaleOk = formatted.scale() <= minOf(currency.decimals, MayaCryptoCurrency.MAX_SWAP_AMOUNT_DECIMALS)
            val notRoundedUp = formatted <= input
            if (scaleOk && notRoundedUp) null else "${currency.asset}: $input -> $formatted (decimals=${currency.decimals})"
        }
        assertTrue("Currencies with unrepresentable swap amounts:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
