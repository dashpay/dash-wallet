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

package org.dash.wallet.features.exploredash.utils

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/** Amounts as a gift card provider's API reads them. */
object GiftCardCurrency {
    fun fractionDigits(currencyCode: String): Int = Currency.getInstance(currencyCode).defaultFractionDigits

    /** Locale-independent amount for the provider's API. Throws rather than round a user's amount. */
    fun orderAmount(amount: Double, currencyCode: String): String =
        BigDecimal.valueOf(amount)
            .setScale(fractionDigits(currencyCode), RoundingMode.UNNECESSARY)
            .toPlainString()
}
