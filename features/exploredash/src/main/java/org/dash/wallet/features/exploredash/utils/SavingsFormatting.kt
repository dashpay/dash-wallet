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

import android.content.Context
import org.dash.wallet.features.exploredash.R
import java.math.RoundingMode
import java.text.DecimalFormat
import kotlin.math.abs

object SavingsFormatting {
    /**
     * Formats a signed savings percentage. Positive values render as a discount
     * (e.g. "-2.5%" or "~3%" depending on [discountPrefix]); negative values
     * render as "fee X%" using the absolute value; zero returns an empty string.
     *
     * @param percent Signed percentage where 2.5 means 2.5%.
     * @param decimals Maximum decimal places shown.
     * @param discountPrefix Prefix written before the value in the discount case.
     */
    fun format(
        context: Context,
        percent: Double,
        decimals: Int = 2,
        discountPrefix: String = ""
    ): String {
        if (percent == 0.0) return ""
        val numberFormat = DecimalFormat().apply {
            maximumFractionDigits = decimals
            minimumFractionDigits = 0
            roundingMode = RoundingMode.HALF_UP
        }
        val absValue = numberFormat.format(abs(percent))
        return if (percent < 0.0) {
            context.getString(R.string.explore_fee, absValue)
        } else {
            "$discountPrefix$absValue%"
        }
    }
}