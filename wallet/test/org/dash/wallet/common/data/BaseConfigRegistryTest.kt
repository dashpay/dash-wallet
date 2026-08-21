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

package org.dash.wallet.common.data

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.WalletDataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the live-instance registry on [BaseConfig].
 *
 * The registry exists so a wallet wipe can clear every LIVE config through
 * its DataStore API (memory + disk atomically) instead of deleting the
 * backing files out-of-band, which desynchronizes the in-memory caches
 * from disk. These tests exercise the registry mechanics — registration on
 * construction, iteration, file-name derivation, failure containment, and
 * tolerance of GC'd instances — with `clearAll()` overridden so no real
 * DataStore IO happens.
 *
 * Note: the registry is process-global, so assertions use `contains`
 * rather than exact set equality (instances from other tests may still be
 * weakly reachable).
 */
class BaseConfigRegistryTest {

    private class TestConfig(
        context: Context,
        name: String,
        walletDataProvider: WalletDataProvider
    ) : BaseConfig(context, name, walletDataProvider) {
        var clearCount = 0

        override suspend fun clearAll() {
            clearCount++
        }
    }

    private class FailingConfig(
        context: Context,
        name: String,
        walletDataProvider: WalletDataProvider
    ) : BaseConfig(context, name, walletDataProvider) {
        override suspend fun clearAll() {
            throw RuntimeException("simulated clear failure")
        }
    }

    private fun mockContext(): Context = mockk(relaxed = true)

    private fun mockWalletDataProvider(): WalletDataProvider = mockk(relaxed = true)

    @Test
    fun clearAllLiveInstances_clearsEveryLiveConfig_andReturnsTheirFileNames() = runBlocking {
        val configA = TestConfig(mockContext(), "registry_test_a", mockWalletDataProvider())
        val configB = TestConfig(mockContext(), "registry_test_b", mockWalletDataProvider())

        val cleared = BaseConfig.clearAllLiveInstances()

        assertEquals(1, configA.clearCount)
        assertEquals(1, configB.clearCount)
        assertTrue(cleared.contains("registry_test_a.preferences_pb"))
        assertTrue(cleared.contains("registry_test_b.preferences_pb"))
    }

    @Test
    fun preferencesFileName_matchesDataStoreFileNaming() {
        val config = TestConfig(mockContext(), "registry_test_naming", mockWalletDataProvider())
        // must match androidx preferencesDataStoreFile(name): "<name>.preferences_pb"
        assertEquals("registry_test_naming.preferences_pb", config.preferencesFileName)
    }

    @Test
    fun clearAllLiveInstances_aFailingClearIsContained_andExcludedFromResult() = runBlocking {
        val healthy = TestConfig(mockContext(), "registry_test_healthy", mockWalletDataProvider())
        val failing = FailingConfig(mockContext(), "registry_test_failing", mockWalletDataProvider())

        val cleared = BaseConfig.clearAllLiveInstances()

        // the failing instance must not abort the others and must not be
        // reported as cleared (so the caller can fall back to file deletion)
        assertEquals(1, healthy.clearCount)
        assertTrue(cleared.contains("registry_test_healthy.preferences_pb"))
        assertFalse(cleared.contains(failing.preferencesFileName))
    }

    @Test
    fun clearAllLiveInstances_toleratesUnreferencedInstances() {
        // create an instance with no strong reference so it may be collected
        createUnreferencedConfig()
        System.gc()

        val kept = TestConfig(mockContext(), "registry_test_kept", mockWalletDataProvider())
        val cleared = runBlocking { BaseConfig.clearAllLiveInstances() }

        // best-effort: whether or not the unreferenced instance was collected,
        // iteration must complete and clear the live one
        assertEquals(1, kept.clearCount)
        assertTrue(cleared.contains("registry_test_kept.preferences_pb"))
    }

    private fun createUnreferencedConfig() {
        TestConfig(mockContext(), "registry_test_gc", mockWalletDataProvider())
    }
}
