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

import androidx.room.ColumnInfo
import androidx.room.Ignore
import androidx.room.PrimaryKey
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.ui.extensions.Const

open class SearchResult(
    @PrimaryKey(autoGenerate = true) var id: Int = 0,
    @ColumnInfo(name = "active", defaultValue = "1") var active: Boolean? = true,
    var name: String? = "",
    var address1: String? = "",
    var address2: String? = "",
    var address3: String? = "",
    var address4: String? = "",
    var latitude: Double? = 0.0,
    var longitude: Double? = 0.0,
    var website: String? = "",
    var phone: String? = "",
    var territory: String? = "",
    var city: String? = "",
    var source: String? = "",
    var sourceId: String? = "",
    var logoLocation: String? = "",
    var googleMaps: String? = "",
    var coverImage: String? = "",
    var type: String? = "",
    @Ignore var distance: Double = Double.NaN
) {
    fun getDisplayAddress(separator: String = "\n"): String {
        val addressBuilder = StringBuilder()
        addressBuilder.append(address1)

        if (!address2.isNullOrBlank()) {
            addressBuilder.append("${separator}$address2")
        }

        if (!address3.isNullOrBlank()) {
            addressBuilder.append("${separator}$address3")
        }

        if (!address4.isNullOrBlank()) {
            addressBuilder.append("${separator}$address4")
        }

        // CTX records do not use address2, address3, address4
        if (source?.lowercase() == GiftCardProviderType.CTX.name.lowercase() ||
            source?.lowercase() == GiftCardProviderType.PiggyCards.name.lowercase()) {
            addressBuilder.append("${separator}$city")
            territory?.let {
                addressBuilder.append(", ")
                addressBuilder.append(territory)
            }
        }

        return addressBuilder.toString()
    }

    fun getDistanceStr(isMetric: Boolean): String {
        return if (distance.isNaN()) {
            ""
        } else {
            val distance =
                if (isMetric) {
                    distance / Const.METERS_IN_KILOMETER
                } else {
                    distance / Const.METERS_IN_MILE
                }

            if (distance < 10) {
                String.format("%.1f", distance)
            } else {
                String.format("%.0f", distance)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        val second = other as SearchResult
        return id == second.id && name == second.name && active == second.active
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (active?.hashCode() ?: 0)
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}
