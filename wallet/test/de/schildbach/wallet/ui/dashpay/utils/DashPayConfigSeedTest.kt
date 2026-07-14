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

package de.schildbach.wallet.ui.dashpay.utils

import androidx.datastore.preferences.core.Preferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host-JVM tests for [DashPayConfig.seedDebugDefaultsIfUnset] — the
 * idempotent debug-build seeding of the `USE_KOTLIN_SDK_*` flags that a
 * Reset Wallet re-runs after clearing the DataStore mid-process.
 *
 * `get`/`set` are stubbed against an in-memory map (mockk, no real
 * DataStore); the seeding method itself runs its real implementation via
 * `callOriginal()`. These tests run under the debug variant, so
 * `BuildConfig.DEBUG` is true and the seeding body executes.
 */
class DashPayConfigSeedTest {

    private val store = mutableMapOf<Preferences.Key<*>, Any?>()

    private fun configBackedByStore(): DashPayConfig {
        val config = mockk<DashPayConfig>()
        coEvery { config.get(any<Preferences.Key<Boolean>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            store[firstArg()] as Boolean?
        }
        coEvery { config.set(any<Preferences.Key<Boolean>>(), any<Boolean>()) } answers {
            store[firstArg<Preferences.Key<Boolean>>()] = secondArg<Boolean>()
        }
        coEvery { config.seedDebugDefaultsIfUnset() } coAnswers { callOriginal() }
        return config
    }

    @Test
    fun seedsAllDebugFlags_whenUnset() = runBlocking {
        val config = configBackedByStore()

        config.seedDebugDefaultsIfUnset()

        assertEquals(true, store[DashPayConfig.USE_KOTLIN_SDK_DPNS_READS])
        assertEquals(true, store[DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES])
        assertEquals(true, store[DashPayConfig.USE_KOTLIN_SDK_SHIELDED])
        assertEquals(true, store[DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW])
        // L1 send moves real funds through the SDK and is deliberately NOT seeded
        assertNull(store[DashPayConfig.USE_KOTLIN_SDK_L1_SEND])
    }

    @Test
    fun skipsSeeding_whenFlagsAlreadySet() = runBlocking {
        store[DashPayConfig.USE_KOTLIN_SDK_DPNS_READS] = false
        store[DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES] = false
        store[DashPayConfig.USE_KOTLIN_SDK_SHIELDED] = false
        store[DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW] = false
        val config = configBackedByStore()

        config.seedDebugDefaultsIfUnset()

        // explicitly-set values (even OFF) must never be overwritten
        assertEquals(false, store[DashPayConfig.USE_KOTLIN_SDK_DPNS_READS])
        assertEquals(false, store[DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES])
        assertEquals(false, store[DashPayConfig.USE_KOTLIN_SDK_SHIELDED])
        assertEquals(false, store[DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW])
        coVerify(exactly = 0) { config.set(any<Preferences.Key<Boolean>>(), any<Boolean>()) }
    }

    @Test
    fun seedsOnlyUnsetFlags_whenMixed() = runBlocking {
        store[DashPayConfig.USE_KOTLIN_SDK_SHIELDED] = false
        val config = configBackedByStore()

        config.seedDebugDefaultsIfUnset()

        assertEquals(true, store[DashPayConfig.USE_KOTLIN_SDK_DPNS_READS])
        assertEquals(true, store[DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES])
        assertEquals(false, store[DashPayConfig.USE_KOTLIN_SDK_SHIELDED])
        assertEquals(true, store[DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW])
    }

    @Test
    fun aFailingRead_doesNotThrow() = runBlocking {
        val config = mockk<DashPayConfig>()
        coEvery { config.get(any<Preferences.Key<Boolean>>()) } throws RuntimeException("simulated read failure")
        coEvery { config.seedDebugDefaultsIfUnset() } coAnswers { callOriginal() }

        // best-effort seeding: failures are logged (loudly), never thrown
        config.seedDebugDefaultsIfUnset()

        assertFalse(store.containsKey(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS))
    }

    @Test
    fun mainnetSeedList_isOnlyTheReadOnlyL1Shadow() {
        // prodDebug (the external mainnet validation build) must never seed
        // the shielded pool or SDK write paths against real funds.
        assertEquals(
            listOf(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW),
            DashPayConfig.debugSeedFlags(isMainnet = true)
        )
    }

    @Test
    fun testnetSeedList_isAllFourFlags_neverL1Send() {
        val flags = DashPayConfig.debugSeedFlags(isMainnet = false)
        assertEquals(
            listOf(
                DashPayConfig.USE_KOTLIN_SDK_DPNS_READS,
                DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES,
                DashPayConfig.USE_KOTLIN_SDK_SHIELDED,
                DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW
            ),
            flags
        )
        assertFalse(DashPayConfig.USE_KOTLIN_SDK_L1_SEND in DashPayConfig.debugSeedFlags(isMainnet = true))
        assertFalse(DashPayConfig.USE_KOTLIN_SDK_L1_SEND in flags)
    }
}
