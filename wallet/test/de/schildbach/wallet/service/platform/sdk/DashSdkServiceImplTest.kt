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

package de.schildbach.wallet.service.platform.sdk

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.OuzoDevNetParams
import org.bitcoinj.params.RegTestParams
import org.bitcoinj.params.TestNet3Params
import org.dashfoundation.dashsdk.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Phase 3 scaffold guarantees (`docs/kotlin-sdk-migration-plan.md`):
 *
 * These tests run on the host JVM, where the SDK's native library
 * (`libdash_sdk`) cannot load. That is the point: they prove the
 * construction path of [DashSdkServiceImpl] performs no native/SDK/Room
 * initialization — if construction (or any of the passive accessors)
 * touched `Sdk.initialize()`, `DashDatabase.create` or `WalletStorage`,
 * these tests would die with `UnsatisfiedLinkError` or an Android runtime
 * error.
 *
 * Deliberately absent: any test calling [DashSdkService.ensureStarted] or
 * [DashSdkService.resolveUsername] — those require the native library and
 * belong in instrumented tests once Phase 3b wires real flows.
 */
class DashSdkServiceImplTest {

    private fun newService() = DashSdkServiceImpl(
        context = mockk<Context>(relaxed = true),
        mnemonicProvider = Phase3bPlaceholderMnemonicProvider()
    )

    @Test
    fun construction_isJvmSafe_andDoesNotStartSdk() {
        // Must not throw (no native lib load, no Room, no Keystore).
        val service = newService()

        assertFalse(service.isStarted)
        assertNull(service.sdkOrNull())
        assertNull(service.walletManagerOrNull())
    }

    @Test
    fun stop_beforeStart_isSafeNoOp() {
        val service = newService()

        runBlocking { service.stop() }

        assertFalse(service.isStarted)
    }

    @Test
    fun placeholderMnemonicProvider_failsUntilPhase3b() {
        val exception = assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { Phase3bPlaceholderMnemonicProvider().getMnemonic() }
        }
        assertEquals("wallet binding lands in Phase 3b", exception.message)
    }

    @Test
    fun toSdkNetwork_mapsFlavorNetworksToSdkNetworks() {
        assertEquals(Network.MAINNET, toSdkNetwork(MainNetParams.get()))
        assertEquals(Network.TESTNET, toSdkNetwork(TestNet3Params.get()))
        assertEquals(Network.DEVNET, toSdkNetwork(OuzoDevNetParams.get()))
        assertEquals(Network.REGTEST, toSdkNetwork(RegTestParams.get()))
    }
}
