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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.dash.wallet.common.util.GenericUtils
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.*

// TODO: it would be better if the users of this class don't depend on the concrete
// implementation, but on an interface instead. See dependency inversion principle.
@ExperimentalCoroutinesApi
class UserLocationState @Inject constructor(private val context: Context, private val client: FusedLocationProviderClient) {
    companion object {
        private const val UPDATE_INTERVAL_SECS = 10L
        private const val FASTEST_UPDATE_INTERVAL_SECS = 2L
    }

    private var previousLocation: Pair<Double, Double> = Pair(0.0, 0.0)

    @SuppressLint("MissingPermission")
    fun observeUpdates(): Flow<UserLocation> = callbackFlow {
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

                if (distanceBetween(previousLocation.first, previousLocation.second, newLocation.first, newLocation.second) < 0.1){
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


    fun distanceBetween(location1: UserLocation, location2: UserLocation): Double {
        return distanceBetween(location1.latitude, location1.longitude, location2.latitude, location2.longitude)
    }

    fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 3958.75 // in miles, change to 6371 for kilometer output

        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)

        val sindLat = sin(dLat / 2)
        val sindLng = sin(dLng / 2)

        val a = sindLat.pow(2.0) +
                (sindLng.pow(2.0) * cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)))

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c // output distance, in MILES
    }

    fun getCurrentLocationName(lat: Double, lng: Double): String {
        return try {
            val geocoder = Geocoder(context, GenericUtils.getDeviceLocale())
            val addresses = geocoder.getFromLocation(lat, lng, 1)

            if (!addresses.isNullOrEmpty()){
                val locality = addresses[0].locality
                val cityName = if (locality.isNullOrEmpty()) addresses[0].adminArea else locality
                Log.e(this::class.java.simpleName, "City name: $cityName")
                cityName
            } else {
                ""
            }
        } catch (e: Exception){
            Log.i("GeocoderException" ,"${e.message}")
            ""
        }
    }
}


data class UserLocation(var latitude: Double,
                        var longitude: Double,
                        var accuracy: Double)