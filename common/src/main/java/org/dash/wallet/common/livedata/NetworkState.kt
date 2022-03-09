/*
 * Copyright 2020 Dash Core Group.
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

package org.dash.wallet.common.livedata

import android.net.*
import android.os.Build
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * @author Kebab Krabby
 * https://stackoverflow.com/questions/36421930/connectivitymanager-connectivity-action-deprecated
 */

interface NetworkStateInt {
    fun observeNetworkChangeState(): Flow<Boolean>
}

@ExperimentalCoroutinesApi
class NetworkState @Inject constructor (private val connectivityManager: ConnectivityManager): NetworkStateInt {

    private var networkRequestBuilder: NetworkRequest.Builder = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)

    override fun observeNetworkChangeState(): Flow<Boolean> = callbackFlow {
        val connectivityManagerCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(false)
                super.onLost(network)
            }
        }

        val activeNetwork: NetworkInfo? = connectivityManager.activeNetworkInfo
        trySend(activeNetwork?.isConnected == true)

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> connectivityManager.registerDefaultNetworkCallback(
                connectivityManagerCallback
            )
            else -> connectivityManager.registerNetworkCallback(
                networkRequestBuilder.build(),
                connectivityManagerCallback
            )
        }
        awaitClose {
            connectivityManager.unregisterNetworkCallback(connectivityManagerCallback)
        }
    }
}