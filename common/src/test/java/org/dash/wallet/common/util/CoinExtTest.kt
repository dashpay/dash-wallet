/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.common.util

import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.util.toBigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class CoinExtTest {
    @Test
    fun convertCoinToBigDecimal() {
        val format = MonetaryFormat().minDecimals(8).noCode()
        val value = Coin.valueOf(0, 43)
        val valueString = "0.43000000"
        val valueBDFromString = BigDecimal(valueString)
        assertEquals(valueString, format.format(value).toString())
        assertEquals(valueBDFromString, value.toBigDecimal())
    }

    @Test
    fun convertFiatToBigDecimal() {
        val format = MonetaryFormat().minDecimals(8).noCode()
        val value = Fiat.parseFiat("USD", "0.43")
        val valueString = "0.43000000"
        val valueBDFromString = BigDecimal(valueString)
        assertEquals(valueString, format.format(value).toString())
        assertEquals(valueBDFromString, value.toBigDecimal())
    }
}
