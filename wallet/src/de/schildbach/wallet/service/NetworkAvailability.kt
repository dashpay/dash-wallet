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
 * The service seeds its NETWORK impediment from this once that callback is
 * registered ([ImpedimentGate.seedOffline]). A passive `registerNetworkCallback`
 * reports nothing while no network matches (no `onAvailable`, `onLost` or
 * `onCapabilitiesChanged`; `onUnavailable` is for `requestNetwork`), so a
 * service created while already offline used to read as online until some
 * network came and went. Never throws: an unreadable state answers "usable",
 * the old assumption, so this can only ever add an impediment the platform
 * confirmed.
 */
fun hasUsableNetworkNow(connectivityManager: ConnectivityManager): Boolean = try {
    val capabilities = connectivityManager.activeNetwork?.let(connectivityManager::getNetworkCapabilities)
    capabilities != null &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
} catch (t: Throwable) {
    true
}

/**
 * Serializes every change to the blockchain service's impediment set, and
 * decides the one-off connectivity seed at service start.
 *
 * The set is a plain `EnumSet` changed from the network callback's coroutines
 * (IO) and the keystore health observer (main); [update] and
 * [networkCallbackUpdate] make each change atomic.
 *
 * The seed: reading connectivity BEFORE registering the callback left a gap —
 * a network lost between the read and the registration was never reported
 * (nothing matched any more), and the service read as online. So the read
 * comes AFTER registration, where it must not overwrite what the callback has
 * already said: every callback update bumps a counter, the token is taken
 * just before registering, and [seedOffline] marks the service offline only if
 * no callback update landed since. Any network that is present after the
 * registration is reported by the callback (`onAvailable`, later `onLost`),
 * so the seed only ever decides the "nothing matched" case the callback stays
 * silent on.
 */
internal class ImpedimentGate {
    private val lock = Any()
    private var networkCallbackUpdates = 0L

    /** A change that is not a network-callback update (the keystore health observer). */
    fun <T> update(block: () -> T): T = synchronized(lock, block)

    /** A network-callback update; it supersedes the start-up seed. */
    fun <T> networkCallbackUpdate(block: () -> T): T = synchronized(lock) {
        networkCallbackUpdates++
        block()
    }

    /** Taken just BEFORE `registerNetworkCallback`. */
    fun seedToken(): Long = synchronized(lock) { networkCallbackUpdates }

    /**
     * After registration, with [usableNow] read after it too: mark the service
     * offline ([markOffline], under the lock) unless a network is usable or a
     * callback update has landed since [token]. A stale "not usable" read is
     * harmless: a network that appeared before it is reported by the callback,
     * and its update either landed first (the seed is skipped) or lands after
     * (and clears the impediment).
     */
    fun seedOffline(token: Long, usableNow: Boolean, markOffline: () -> Unit): Boolean = synchronized(lock) {
        if (usableNow || networkCallbackUpdates != token) return false
        markOffline()
        true
    }
}
