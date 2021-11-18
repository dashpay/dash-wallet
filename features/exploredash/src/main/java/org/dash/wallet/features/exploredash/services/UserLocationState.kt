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

package org.dash.wallet.features.exploredash.services

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.SphericalUtil
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.features.exploredash.data.model.GeoBounds
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.*

// TODO: it would be better if the users of this class don't depend on the concrete
// implementation, but on an interface instead. We might have a different
// implementation which does not use google play services.
@ExperimentalCoroutinesApi
class UserLocationState @Inject constructor(private val context: Context, private val client: FusedLocationProviderClient): UserLocationStateInt  {
    companion object {
        private const val UPDATE_INTERVAL_SECS = 10L
        private const val FASTEST_UPDATE_INTERVAL_SECS = 2L
        private const val EARTH_RADIUS = 6371009 // in meters
    }

    private var previousLocation: Pair<Double, Double> = Pair(0.0, 0.0)

    override fun calculateBounds(center: LatLng, radius: Double): LatLngBounds {
        return LatLngBounds.builder()
            .include(SphericalUtil.computeOffset(center, radius, 0.0))
            .include(SphericalUtil.computeOffset(center, radius, 90.0))
            .include(SphericalUtil.computeOffset(center, radius, 180.0))
            .include(SphericalUtil.computeOffset(center, radius, 270.0)).build()
    }

    @SuppressLint("MissingPermission")
    override fun observeUpdates(): Flow<UserLocation> = callbackFlow {
        val locationRequest: LocationRequest = LocationRequest.create()
            .apply {
                interval = TimeUnit.SECONDS.toMillis(UPDATE_INTERVAL_SECS)
                fastestInterval = TimeUnit.SECONDS.toMillis(FASTEST_UPDATE_INTERVAL_SECS)
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }

        val callback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                val location = locationResult.lastLocation
                val newLocation = Pair(location.latitude, location.longitude)

                val distance = distanceBetween(previousLocation.first, previousLocation.second, newLocation.first, newLocation.second)
                if (distance < 100) {
                    Log.i(this@UserLocationState::class.java.simpleName, "previous and latest locations are equal")
                } else {
                    previousLocation = Pair(location.latitude, location.longitude)
                    trySend(UserLocation(previousLocation.first, previousLocation.second, location.accuracy.toDouble()))
                }
            }
        }
        client.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

   override fun getCurrentLocationAddress(lat: Double, lng: Double): Address? {
        return try {
            val geocoder = Geocoder(context, GenericUtils.getDeviceLocale())
            val addresses = geocoder.getFromLocation(lat, lng, 1)

            if (!addresses.isNullOrEmpty()) {
                val locality = addresses[0].locality
                val cityName = if (locality.isNullOrEmpty()) addresses[0].adminArea else locality
                Log.e(this::class.java.simpleName, "City name: $cityName")
                Address(addresses[0].countryName, cityName)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.i("GeocoderException" ,"${e.message}")
            null
        }
    }

    override fun distanceBetweenCenters(bounds1: GeoBounds, bounds2: GeoBounds): Double {
        return distanceBetween(bounds1.centerLat, bounds1.centerLng, bounds2.centerLat, bounds2.centerLng)
    }

    private fun distanceBetween(location1: UserLocation, location2: UserLocation): Double {
        return distanceBetween(location1.latitude, location1.longitude, location2.latitude, location2.longitude)
    }

    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)

        val sindLat = sin(dLat / 2)
        val sindLng = sin(dLng / 2)

        val a = sindLat.pow(2.0) +
                (sindLng.pow(2.0) * cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)))

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS * c // output distance, in METERS
    }

    override fun getRadiusBounds(centerLat: Double, centerLng: Double, radius: Double): GeoBounds {
        val latLng = LatLng(centerLat, centerLng)
        val latLngBounds = calculateBounds(latLng, radius)

        return GeoBounds(
            latLngBounds.northeast.latitude,
            latLngBounds.northeast.longitude,
            latLngBounds.southwest.latitude,
            latLngBounds.southwest.longitude,
            latLngBounds.center.latitude,
            latLngBounds.center.longitude
        )
    }
}


data class UserLocation(var latitude: Double,
                        var longitude: Double,
                        var accuracy: Double)


interface UserLocationStateInt {
    fun calculateBounds(center: LatLng, radius: Double): LatLngBounds
    fun observeUpdates(): Flow<UserLocation>
    fun getCurrentLocationAddress(lat: Double, lng: Double): Address?
    fun distanceBetweenCenters(bounds1: GeoBounds, bounds2: GeoBounds): Double
    fun getRadiusBounds(centerLat: Double, centerLng: Double, radius: Double): GeoBounds
}