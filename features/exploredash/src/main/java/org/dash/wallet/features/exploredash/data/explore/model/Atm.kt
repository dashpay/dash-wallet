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

package org.dash.wallet.features.exploredash.data.explore.model

import androidx.room.Entity
import androidx.room.Index

object AtmType {
    const val BUY = "Buy Only"
    const val SELL = "Sell Only"
    const val BOTH = "Buy and Sell"
}

@Entity(
    tableName = "atm",
    indices =
    [
        Index("latitude"),
        Index("longitude")
    ]
)
data class Atm(
    var postcode: String? = "",
    var manufacturer: String? = ""
) : SearchResult()
