/*
 * Copyright 2026 Dash Core Group.
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

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether connectivity AS IT STANDS NOW offers a network the blockchain
 * service can sync over: the default network, with internet and validated —
 * the same capabilities the service's network callback requires.
 *
 * The service seeds its NETWORK impediment from this before it registers that
 * callback. A passive `registerNetworkCallback` reports nothing while no
 * network matches (no `onAvailable`, `onLost` or `onCapabilitiesChanged`;
 * `onUnavailable` is for `requestNetwork`), so a service created while already
 * offline used to read as online until some network came and went. Never
 * throws: an unreadable state answers "usable", the old assumption, so this can
 * only ever add an impediment the platform confirmed.
 */
fun hasUsableNetworkNow(connectivityManager: ConnectivityManager): Boolean = try {
    val capabilities = connectivityManager.activeNetwork?.let(connectivityManager::getNetworkCapabilities)
    capabilities != null &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
} catch (t: Throwable) {
    true
}
