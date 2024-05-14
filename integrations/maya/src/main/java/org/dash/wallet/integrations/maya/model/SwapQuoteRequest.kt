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
package org.dash.wallet.integrations.maya.model

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
data class SwapQuoteRequest(
    val amount: Amount,
    val amount_from: String = "input",
    val source_maya_asset: String,
    val target_maya_asset: String,
    val fiatCurrency: String,
    val targetAddress: String,
    val maximum: Boolean
) : Parcelable {
    @IgnoredOnParcel
    val source_asset = amount.dashCode
    @IgnoredOnParcel
    val target_asset = amount.cryptoCode
}