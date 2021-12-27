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

package org.dash.wallet.common.ui.payment_method_picker

import androidx.annotation.DrawableRes
import org.dash.wallet.common.R

object CardUtils {
    private val cardTypes = mapOf(
        "^4[0-9]{3}?" to R.drawable.ic_visa,
        "^5[1-5][0-9]{2}|222[1-9]|22[3-9][0-9]|2[3-6][0-9]{2}|27[01][0-9]|2720" to R.drawable.ic_mastercard,
        "^3[47][0-9]{2}" to R.drawable.ic_american_express,
        "^6(?:011|5[0-9]{2})" to R.drawable.ic_card_discover,
        "^3(?:0[0-5]|[68][0-9])[0-9]" to R.drawable.ic_diners_club,
        "^35[0-9]{2}" to R.drawable.ic_card_jcb
    )

    @DrawableRes
    fun getCardIcon(account: String?): Int? {
        if (!account.isNullOrEmpty()) {
            cardTypes.forEach { (regex, drawableRes) ->
                if (regex.toRegex().containsMatchIn(account)) {
                    return drawableRes
                }
            }
        }

        return null
    }
}