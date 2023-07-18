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

package de.schildbach.wallet.service

import android.net.*
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import org.dash.wallet.common.services.NetworkStateInt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkState @Inject constructor(connectivityManager: ConnectivityManager): NetworkStateInt {
    override var isConnected: MutableStateFlow<Boolean> = MutableStateFlow(false)
        private set

    private var networkRequestBuilder: NetworkRequest.Builder = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)

    init {
        val connectivityManagerCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                isConnected.value = true
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                isConnected.value = false
            }

            override fun onUnavailable() {
                super.onUnavailable()
                isConnected.value = false
            }
        }

        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        isConnected.value = capabilities?.hasCapability(NET_CAPABILITY_INTERNET) == true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(
                connectivityManagerCallback
            )
        } else {
            connectivityManager.registerNetworkCallback(
                networkRequestBuilder.build(),
                connectivityManagerCallback
            )
        }
    }
}
