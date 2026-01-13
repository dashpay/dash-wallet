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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.util.getCountryCodeFromIP
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkState @Inject constructor(
    private val connectivityManager: ConnectivityManager
): NetworkStateInt {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override var isConnected: MutableStateFlow<Boolean> = MutableStateFlow(false)
        private set

    private val country = MutableStateFlow<String>(Locale.getDefault().country)

    private val connectivityManagerCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            isConnected.value = true

            scope.launch {
                country.value = getCountryCodeFromIP()
            }
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

    init {
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        isConnected.value = capabilities?.hasCapability(NET_CAPABILITY_INTERNET) == true
        connectivityManager.registerDefaultNetworkCallback(
            connectivityManagerCallback
        )
        scope.launch {
            country.value = getCountryCodeFromIP()
        }
    }

    override fun isWifiConnected(): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
    }

    override fun getCountryFromIP() = country.value

    override fun observeCountryFromIP(): Flow<String> = country

    fun cleanup() {
        scope.cancel()
        connectivityManager.unregisterNetworkCallback(connectivityManagerCallback)
    }
}
