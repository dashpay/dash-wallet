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

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Review on #1632 (PastaClaw): the blockchain service's NETWORK impediment was
 * only ever set by its network callback, and a passive registration reports
 * nothing while no network matches. A service created while already offline
 * therefore read as online, and a persisted replay held the no-timeout wake
 * lock in airplane mode. The service now seeds the impediment from
 * [hasUsableNetworkNow] before it registers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class NetworkAvailabilityTest {
    private val connectivityManager =
        RuntimeEnvironment.getApplication().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun defaultNetworkWith(vararg capabilities: Int) {
        shadowOf(connectivityManager).setDefaultNetworkActive(true)
        val network = connectivityManager.activeNetwork!!
        val nc = ShadowNetworkCapabilities.newInstance()
        capabilities.forEach { shadowOf(nc).addCapability(it) }
        shadowOf(connectivityManager).setNetworkCapabilities(network, nc)
    }

    @Test
    fun noDefaultNetwork_isNotUsable() {
        shadowOf(connectivityManager).setDefaultNetworkActive(false)
        assertFalse(hasUsableNetworkNow(connectivityManager))
    }

    @Test
    fun aValidatedInternetNetwork_isUsable() {
        defaultNetworkWith(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        assertTrue(hasUsableNetworkNow(connectivityManager))
    }

    @Test
    fun aNetworkThatIsNotValidated_isNotUsable() {
        // Captive portal / no upstream: the same rule the network callback applies.
        defaultNetworkWith(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        assertFalse(hasUsableNetworkNow(connectivityManager))
    }

    // ── The seed after registration (CodeRabbit on #1632) ──────────────────

    private val impediments = java.util.EnumSet.noneOf(
        org.dash.wallet.common.data.entity.BlockchainState.Impediment::class.java
    )
    private val network = org.dash.wallet.common.data.entity.BlockchainState.Impediment.NETWORK
    private val gate = ImpedimentGate()

    @Test
    fun aNetworkLostBeforeRegistration_isSeededOffline() {
        // Usable when the old code read it, gone by the time the callback was
        // registered: nothing matches, so the callback never says a word.
        val token = gate.seedToken()
        // registerNetworkCallback(...) — no callback update follows
        assertTrue(gate.seedOffline(token, usableNow = false) { impediments.add(network) })
        assertTrue(network in impediments)
    }

    @Test
    fun aCallbackUpdateBeforeTheSeed_wins_overAStaleRead() {
        val token = gate.seedToken()
        // A network appears right after registration; its onAvailable lands first.
        gate.networkCallbackUpdate { impediments.remove(network) }
        // The seed's read was taken just before that network came up.
        assertFalse(gate.seedOffline(token, usableNow = false) { impediments.add(network) })
        assertFalse("the callback's online verdict stands", network in impediments)
    }

    @Test
    fun aCallbackUpdateAfterTheSeed_overridesIt() {
        val token = gate.seedToken()
        assertTrue(gate.seedOffline(token, usableNow = false) { impediments.add(network) })
        gate.networkCallbackUpdate { impediments.remove(network) } // onAvailable
        assertFalse(network in impediments)
    }

    @Test
    fun aUsableNetwork_isNeverSeededOffline() {
        val token = gate.seedToken()
        assertFalse(gate.seedOffline(token, usableNow = true) { impediments.add(network) })
        assertFalse(network in impediments)
    }

    @Test
    fun aServiceCreatedOffline_neverTakesTheReplayLock_untilANetworkAppears() {
        shadowOf(connectivityManager).setDefaultNetworkActive(false)

        // What onCreate does before registering the network callback.
        val networkImpeded = !hasUsableNetworkNow(connectivityManager)
        assertTrue("seeded offline", networkImpeded)

        var impeded = networkImpeded
        var acquisitions = 0
        var count = 0
        val lock = object : ReplayWakeLockHandle {
            override val isHeld: Boolean get() = count > 0
            override fun acquire() {
                count++
                acquisitions++
            }
            override fun release() {
                count--
            }
        }
        // A replay persisted from the previous session is still in progress.
        val replayWakeLock = ReplayWakeLock(
            lock = { lock },
            sdkPath = { true },
            replayDue = { replayHoldsWakeLock(replaying = true, networkImpeded = impeded, filterScanWedged = false) }
        )
        replayWakeLock.hold(replayHoldsWakeLock(replaying = true, networkImpeded = impeded, filterScanWedged = false))
        assertFalse("offline from the start: no wake lock for a replay that cannot move", lock.isHeld)
        assertEquals(0, acquisitions)

        // A network appears: the callback clears the impediment and re-checks.
        impeded = false
        replayWakeLock.reacquireIfDue()
        assertTrue(lock.isHeld)
    }
}
