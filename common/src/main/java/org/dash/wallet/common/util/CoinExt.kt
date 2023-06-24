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
import java.math.BigDecimal

// the purpose of these methods is to directly convert Coin and Fiat to BigDecimal
// without first converting to a string.  If the strings are localized, BitDecimal
// may throw a NumberFormatException (e.g. "0,43")

fun Coin.toBigDecimal() : BigDecimal {
    return BigDecimal(this.value).scaleByPowerOfTen(-Coin.SMALLEST_UNIT_EXPONENT)
}

fun Fiat.toBigDecimal() : BigDecimal {
    return BigDecimal(this.value).scaleByPowerOfTen(-Fiat.SMALLEST_UNIT_EXPONENT)
}

fun BigDecimal.toCoin() : Coin {
    return Coin.valueOf(this.scaleByPowerOfTen(Coin.SMALLEST_UNIT_EXPONENT).toLong())
}

fun BigDecimal.toFiat(currency: String) : Fiat {
    return Fiat.valueOf(currency, this.scaleByPowerOfTen(Fiat.SMALLEST_UNIT_EXPONENT).toLong())
}
