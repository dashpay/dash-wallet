package org.dash.wallet.features.exploredash.ui

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

@ExperimentalCoroutinesApi
class UserLocationState @Inject constructor(private val context: Context, private val client: FusedLocationProviderClient) {
    private var previousLocation: Pair<Double, Double> = Pair(0.0, 0.0)

    @SuppressLint("MissingPermission")
    fun fetchUpdates(): Flow<UserLocation> = callbackFlow {
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

                if (distance(previousLocation.first, previousLocation.second, newLocation.first, newLocation.second) < 0.1){
                    Log.i(this@UserLocationState::class.java.simpleName, "previous and latest locations are equal")
                } else {
                    previousLocation = Pair(location.latitude, location.longitude)

                    val cityName = getCurrentLocationName(location.latitude, location.longitude)
                    trySend(UserLocation(previousLocation.first, previousLocation.second, cityName))
                }

            }
        }
        client.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    companion object {
        private const val UPDATE_INTERVAL_SECS = 10L
        private const val FASTEST_UPDATE_INTERVAL_SECS = 2L
    }

    private fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
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

    private fun getCurrentLocationName(lat: Double, lng: Double): String {
        return try {
            val geocoder = Geocoder(context, GenericUtils.getDeviceLocale())
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()){
                Log.e(this::class.java.simpleName, "City name: ${addresses[0].locality}")
                addresses[0].locality
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
                        var name: String = "")