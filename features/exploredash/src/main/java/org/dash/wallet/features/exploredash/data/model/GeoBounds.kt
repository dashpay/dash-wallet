/*
 *
 *  * Copyright 2021 Dash Core Group
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.dash.wallet.features.exploredash.data.model

data class GeoBounds(val northLat: Double,
                     val eastLng: Double,
                     val southLat: Double,
                     val westLng: Double,
                     val centerLat: Double,
                     val centerLng: Double,
                     val zoomLevel: Float = 0f,
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
                centerLat == other.centerLng &&
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