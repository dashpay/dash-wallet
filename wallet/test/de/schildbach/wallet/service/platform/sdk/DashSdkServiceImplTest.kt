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
import org.junit.Assert.assertArrayEquals
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
        mnemonicProvider = SecurityGuardMnemonicProvider(mockk(relaxed = true))
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
    fun toSdkNetwork_mapsFlavorNetworksToSdkNetworks() {
        assertEquals(Network.MAINNET, toSdkNetwork(MainNetParams.get()))
        assertEquals(Network.TESTNET, toSdkNetwork(TestNet3Params.get()))
        assertEquals(Network.DEVNET, toSdkNetwork(OuzoDevNetParams.get()))
        assertEquals(Network.REGTEST, toSdkNetwork(RegTestParams.get()))
    }

    // ── bindAppWallet helpers (the native-free logic of Phase 3b) ─────

    @Test
    fun joinMnemonicWords_joinsAndTrims() {
        assertEquals(
            "weapon elder job",
            joinMnemonicWords(listOf(" weapon", "elder ", "job"))
        )
    }

    @Test
    fun joinMnemonicWords_rejectsEmptyAndMalformedInput() {
        assertThrows(IllegalArgumentException::class.java) {
            joinMnemonicWords(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            joinMnemonicWords(listOf("weapon", ""))
        }
        // A whole phrase smuggled in as one "word".
        val exception = assertThrows(IllegalArgumentException::class.java) {
            joinMnemonicWords(listOf("weapon elder job"))
        }
        // Error messages must never leak seed material.
        assertFalse(exception.message!!.contains("weapon"))
    }

    @Test
    fun normalizeMnemonic_collapsesWhitespaceOnly() {
        assertEquals("weapon elder job", normalizeMnemonic("  weapon\telder  job \n"))
        assertEquals("weapon", normalizeMnemonic("weapon"))
    }

    @Test
    fun sdkBirthHeightFor_isConservativeFullScanUntilPhase5() {
        // The time→height mapping lands with the Phase 5 migration flow;
        // until then every import scans from genesis (0u) — a too-high
        // guess would silently hide funds.
        assertEquals(0u, sdkBirthHeightFor(null))
        assertEquals(0u, sdkBirthHeightFor(1_231_006_505L))
        assertEquals(0u, sdkBirthHeightFor(System.currentTimeMillis() / 1000))
    }

    @Test
    fun walletIdFromHex_roundTripsAndRejectsMalformed() {
        val hex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        val bytes = walletIdFromHex(hex)!!
        assertEquals(32, bytes.size)
        assertArrayEquals(
            byteArrayOf(0x00, 0x11, 0x22, 0x33),
            bytes.copyOfRange(0, 4)
        )

        assertNull(walletIdFromHex(""))
        assertNull(walletIdFromHex(hex.dropLast(2))) // 31 bytes
        assertNull(walletIdFromHex(hex.dropLast(1) + "zz".drop(1))) // non-hex
    }

    @Test
    fun findBoundWalletId_matchesStoredMnemonicIgnoringWhitespace() = runBlocking {
        val stored = mapOf(
            "aa".repeat(32) to null, // watch-only: no stored phrase
            "bb".repeat(32) to "weapon  elder\tjob ", // ragged whitespace
            "cc".repeat(32) to "other seed phrase"
        )

        val match = findBoundWalletId(stored.keys, "weapon elder job") { stored[it] }

        assertEquals("bb".repeat(32), match)
    }

    @Test
    fun findBoundWalletId_returnsNullWhenNothingMatches() = runBlocking {
        assertNull(
            findBoundWalletId(listOf("aa".repeat(32)), "weapon elder job") { null }
        )
        assertNull(
            findBoundWalletId(emptyList(), "weapon elder job") { "weapon elder job" }
        )
        assertNull(
            findBoundWalletId(listOf("aa".repeat(32)), "weapon elder job") { "different phrase" }
        )
    }
}
