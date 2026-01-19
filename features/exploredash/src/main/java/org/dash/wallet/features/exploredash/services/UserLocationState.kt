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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.SphericalUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.features.exploredash.data.explore.model.GeoBounds
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.math.*

data class UserLocation(var latitude: Double, var longitude: Double, var accuracy: Double)

interface UserLocationStateInt {
    fun calculateBounds(center: LatLng, radius: Double): LatLngBounds
    fun observeUpdates(): Flow<UserLocation>
    fun getCurrentLocationAddress(lat: Double, lng: Double): Address?
    fun distanceBetweenCenters(bounds1: GeoBounds, bounds2: GeoBounds): Double
    fun distanceBetween(location1: UserLocation, location2: UserLocation): Double
    fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double
    fun getRadiusBounds(centerLat: Double, centerLng: Double, radius: Double): GeoBounds
    suspend fun getCountryCodeFromLocation(): String
}

class UserLocationState
@Inject
constructor(private val context: Context, private val client: FusedLocationProviderClient) : UserLocationStateInt {
    companion object {
        private const val UPDATE_INTERVAL_SECS = 10L
        private const val FASTEST_UPDATE_INTERVAL_SECS = 2L
        private const val EARTH_RADIUS = 6371009 // in meters
        private val log = LoggerFactory.getLogger(UserLocationState::class.java)
    }

    private var previousLocation: Pair<Double, Double> = Pair(0.0, 0.0)

    override fun calculateBounds(center: LatLng, radius: Double): LatLngBounds {
        return LatLngBounds.builder()
            .include(SphericalUtil.computeOffset(center, radius, 0.0))
            .include(SphericalUtil.computeOffset(center, radius, 90.0))
            .include(SphericalUtil.computeOffset(center, radius, 180.0))
            .include(SphericalUtil.computeOffset(center, radius, 270.0))
            .build()
    }

    @SuppressLint("MissingPermission")
    override fun observeUpdates(): Flow<UserLocation> = callbackFlow {
        val locationRequest: LocationRequest =
            LocationRequest.create().apply {
                interval = TimeUnit.SECONDS.toMillis(UPDATE_INTERVAL_SECS)
                fastestInterval = TimeUnit.SECONDS.toMillis(FASTEST_UPDATE_INTERVAL_SECS)
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }

        val settingsClient: SettingsClient = LocationServices.getSettingsClient(context)
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)

        val task = settingsClient.checkLocationSettings(builder.build())
        task.addOnSuccessListener { locationSettingsResponse ->
            locationSettingsResponse.locationSettingsStates?.let { states ->
                log.info(
                    "checkLocationSettings, isGpsPresent: ${states.isGpsPresent}, " +
                        "isGpsUsable: ${states.isGpsUsable}, " +
                        "isNetworkLocationPresent: ${states.isNetworkLocationPresent}, " +
                        "isNetworkLocationUsable: ${states.isNetworkLocationUsable}"
                )
            }
        }

        task.addOnFailureListener { exception -> log.info("checkLocationSettings failure: $exception") }

        val callback =
            object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    super.onLocationResult(locationResult)
                    val location = locationResult.lastLocation
                    val newLocation = Pair(location!!.latitude, location.longitude)
                    val distance =
                        distanceBetween(
                            previousLocation.first,
                            previousLocation.second,
                            newLocation.first,
                            newLocation.second
                        )

                    if (distance > 50) {
                        previousLocation = Pair(location.latitude, location.longitude)
                        trySend(
                            UserLocation(previousLocation.first, previousLocation.second, location.accuracy.toDouble())
                        )
                    }
                }
            }

        client.lastLocation
            .addOnSuccessListener { location ->
                log.info("getLastLocation() success, location available: ${location != null}")
                location?.let {
                    trySend(UserLocation(location.latitude, location.longitude, location.accuracy.toDouble()))
                }
            }
            .addOnFailureListener { log.info("getLastLocation() failure") }
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
                Address(addresses[0].countryName, cityName)
            } else {
                null
            }
        } catch (e: Exception) {
            log.info("GeocoderException ${e.message}")
            null
        }
    }

    override fun distanceBetweenCenters(bounds1: GeoBounds, bounds2: GeoBounds): Double {
        return distanceBetween(bounds1.centerLat, bounds1.centerLng, bounds2.centerLat, bounds2.centerLng)
    }

    override fun distanceBetween(location1: UserLocation, location2: UserLocation): Double {
        return distanceBetween(location1.latitude, location1.longitude, location2.latitude, location2.longitude)
    }

    override fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)

        val sindLat = sin(dLat / 2)
        val sindLng = sin(dLng / 2)

        val a = sindLat.pow(2.0) + (sindLng.pow(2.0) * cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)))

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

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override suspend fun getCountryCodeFromLocation(): String {
        return suspendCancellableCoroutine { continuation ->
            client.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val geocoder = Geocoder(context, GenericUtils.getDeviceLocale())
                    val results = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    continuation.resume(results?.firstOrNull()?.countryCode ?: "")
                } else {
                    continuation.resume("")
                }
            }
            client.lastLocation.addOnFailureListener {
                continuation.resume("")
            }
        }
    }

    // TODO: this doesn't give the country name, though it doesn't use callbacks
//    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
//    override suspend fun getCountryCodeFromLocation(): String = withContext(Dispatchers.IO) {
//        try {
//            val location = client.lastLocation.await() ?: return@withContext ""
//            val geocoder = Geocoder(context, GenericUtils.getDeviceLocale())
//            geocoder.getFromLocation(location.latitude, location.longitude, 1)
//                ?.firstOrNull()
//                ?.countryCode
//                        .orEmpty()
//        } catch (e: Exception) {
//            log.info("GeocoderException ${e.message}")
//            ""
//        }
//    }
}
