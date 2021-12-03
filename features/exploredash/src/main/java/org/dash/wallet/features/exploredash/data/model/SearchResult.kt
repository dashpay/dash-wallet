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

package org.dash.wallet.features.exploredash.data.model

import androidx.room.ColumnInfo
import androidx.room.PrimaryKey
import com.google.firebase.database.PropertyName

open class SearchResult(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,
    @ColumnInfo(name = "active", defaultValue = "1")
    var active: Boolean? = true,
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
    @get:PropertyName("source_id") @set:PropertyName("source_id")
    var sourceId: Int? = -1,
    @get:PropertyName("logo_location") @set:PropertyName("logo_location")
    var logoLocation: String? = "",
    @get:PropertyName("google_maps") @set:PropertyName("google_maps")
    var googleMaps: String? = "",
    @get:PropertyName("cover_image") @set:PropertyName("cover_image")
    var coverImage: String? = "",
    open var type: String? = ""
) {
    fun getDisplayAddress(separator: String): String {
        val addressBuilder = StringBuilder()
        addressBuilder.append(address1)

        if (!address2.isNullOrBlank()) {
            addressBuilder.append("${separator}${address2}")
        }

        if (!address3.isNullOrBlank()) {
            addressBuilder.append("${separator}${address3}")
        }

        if (!address4.isNullOrBlank()) {
            addressBuilder.append("${separator}${address4}")
        }

        return addressBuilder.toString()
    }

    override fun equals(other: Any?): Boolean {
        val second = other as SearchResult
        return id == second.id &&
                name == second.name &&
                active == second.active
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (active?.hashCode() ?: 0)
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}