/*
 * Copyright 2024 Dash Core Group.
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

package org.dash.wallet.integrations.maya.ui.convert_currency.model

import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.FiatValue
import org.dash.wallet.common.util.toDash
import org.dash.wallet.common.util.toFiatValue
import org.dash.wallet.integrations.maya.model.Amount

data class SwapRequest(
    val amount: Amount,
    val maximum: Boolean,
    val destinationAddress: String,
    val cryptoCurrencyCode: String,
    val cryptoCurrencyAsset: String,
    val fiatCurrencyCode: String,
    val dashToCrypto: Boolean = true
) {
    val dashAmount: Dash = amount.dash.toDash()
    val cryptoAmount: FiatValue = amount.crypto.toFiatValue(cryptoCurrencyCode)
}
