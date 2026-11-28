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

import org.dash.wallet.common.money.Coin
import org.dash.wallet.common.money.ExchangeRate
import org.dash.wallet.common.money.Fiat
import org.dash.wallet.features.exploredash.utils.GiftCardPaymentGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class GiftCardPaymentGuardTest {
    private val confirmed = Coin.COIN

    @Test
    fun upToHalfAPercentMore_isNotHigher() {
        assertFalse(GiftCardPaymentGuard.isHigher(confirmed, Coin.valueOf(99_000_000)))
        assertFalse(GiftCardPaymentGuard.isHigher(confirmed, confirmed))
        assertFalse(GiftCardPaymentGuard.isHigher(confirmed, Coin.valueOf(100_500_000)))
        assertTrue(GiftCardPaymentGuard.isHigher(confirmed, Coin.valueOf(100_500_001)))
    }

    @Test
    fun theSilentShare_isRoundedDown() {
        // 0.5% of 19,999 duffs is 99.995
        assertFalse(GiftCardPaymentGuard.isHigher(Coin.valueOf(19_999), Coin.valueOf(20_098)))
        assertTrue(GiftCardPaymentGuard.isHigher(Coin.valueOf(19_999), Coin.valueOf(20_099)))
    }

    @Test
    fun approvedAmount_isTheTotalAtTheRateRoundedDown() {
        val rate = ExchangeRate(Fiat.parseFiat("GBP", "30"))

        assertEquals(Coin.valueOf(316_666_666), GiftCardPaymentGuard.approvedAmount(BigDecimal("95.00"), "GBP", rate))
        assertNull(GiftCardPaymentGuard.approvedAmount(BigDecimal("95.00"), "USD", rate))
        assertNull(GiftCardPaymentGuard.approvedAmount(BigDecimal.ZERO, "GBP", rate))
    }

    @Test
    fun quotedDash_mustBeAPositiveNumberOfDuffs() {
        assertEquals(Coin.valueOf(316_666_666), GiftCardPaymentGuard.dashAmount("3.16666666"))
        assertEquals(Coin.COIN, GiftCardPaymentGuard.dashAmount(" 1 "))
        listOf(null, "", "0", "-1", "1e3", "1,5", "0.123456789", "abc").forEach {
            assertNull("$it", GiftCardPaymentGuard.dashAmount(it))
        }
    }
}
