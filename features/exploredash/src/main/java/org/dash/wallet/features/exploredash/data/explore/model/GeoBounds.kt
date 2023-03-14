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

data class GeoBounds(
    val northLat: Double,
    val eastLng: Double,
    val southLat: Double,
    val westLng: Double,
    val centerLat: Double,
    val centerLng: Double,
    var zoomLevel: Float = 0f
) {
    companion object {
        val noBounds: GeoBounds
            get() = GeoBounds(90.0, 180.0, -90.0, -180.0, 0.0, 0.0)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is GeoBounds) {
            return false
        }

        return northLat == other.northLat &&
            eastLng == other.eastLng &&
            southLat == other.southLat &&
            westLng == other.westLng &&
            centerLat == other.centerLat &&
            centerLng == other.centerLng &&
            zoomLevel == other.zoomLevel
    }

    override fun hashCode(): Int {
        var result = northLat.hashCode()
        result = 31 * result + eastLng.hashCode()
        result = 31 * result + southLat.hashCode()
        result = 31 * result + westLng.hashCode()
        result = 31 * result + centerLat.hashCode()
        result = 31 * result + centerLng.hashCode()
        result = 31 * result + zoomLevel.hashCode()
        return result
    }
}
