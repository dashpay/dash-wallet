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
import org.junit.Assert.assertTrue
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

    // ── healIdentityKeys (the native-free logic of Phase 3f-b) ─────────

    private fun key(id: Int, byte: Byte = id.toByte()) =
        IdentityKeyCandidate(keyId = id, publicKeyData = ByteArray(33) { byte })

    @Test
    fun healIdentityKeys_alreadyStoredKeys_areHealthy_neverRederived() = runBlocking {
        var derives = 0
        val report = healIdentityKeys(
            candidates = listOf(key(0), key(1)),
            hasPrivateKey = { true },
            deriveKeyPair = { derives++; error("must not derive") },
            storePrivateKey = { _, _ -> error("must not store") }
        )

        assertEquals(0, derives)
        assertEquals(
            IdentityKeyHealReport(2, healthy = 2, repaired = 0, watchOnly = 0, failed = 0),
            report
        )
        assertTrue(report.allSignable)
        assertTrue(report.settled)
    }

    @Test
    fun healIdentityKeys_missingKey_derivedVerifiedAndStored_scalarScrubbed() = runBlocking {
        val candidate = key(2)
        val scalar = ByteArray(32) { 9 }
        var storedHex: String? = null
        var storedKey: ByteArray? = null

        val report = healIdentityKeys(
            candidates = listOf(candidate),
            hasPrivateKey = { false },
            deriveKeyPair = { keyIndex ->
                assertEquals(2, keyIndex) // slot = the on-chain key id
                scalar to candidate.publicKeyData.copyOf()
            },
            storePrivateKey = { hex, priv ->
                storedHex = hex
                storedKey = priv.copyOf() // snapshot before the scrub
            }
        )

        assertEquals(candidate.publicKeyHex, storedHex)
        assertArrayEquals(ByteArray(32) { 9 }, storedKey)
        // The only scalar copy that escaped the derive is zero-filled.
        assertArrayEquals(ByteArray(32), scalar)
        assertEquals(
            IdentityKeyHealReport(1, healthy = 0, repaired = 1, watchOnly = 0, failed = 0),
            report
        )
        assertTrue(report.allSignable)
    }

    @Test
    fun healIdentityKeys_deriveMismatch_staysWatchOnly_nothingStored_scalarScrubbed() = runBlocking {
        val scalar = ByteArray(32) { 9 }
        var stored = false

        val report = healIdentityKeys(
            candidates = listOf(key(0)),
            hasPrivateKey = { false },
            // Derived public key differs from the on-chain key: foreign key.
            deriveKeyPair = { scalar to ByteArray(33) { 0x7F } },
            storePrivateKey = { _, _ -> stored = true }
        )

        assertFalse(stored)
        assertArrayEquals(ByteArray(32), scalar)
        assertEquals(
            IdentityKeyHealReport(1, healthy = 0, repaired = 0, watchOnly = 1, failed = 0),
            report
        )
        assertFalse(report.allSignable)
        assertTrue(report.settled) // deterministic — no retry can fix it
    }

    @Test
    fun healIdentityKeys_readOnlyAndDisabledRows_watchOnly_neverDerived() = runBlocking {
        val report = healIdentityKeys(
            candidates = listOf(
                key(0).copy(readOnly = true),
                key(1).copy(disabled = true)
            ),
            hasPrivateKey = { false },
            deriveKeyPair = { error("must not derive") },
            storePrivateKey = { _, _ -> error("must not store") }
        )

        assertEquals(
            IdentityKeyHealReport(2, healthy = 0, repaired = 0, watchOnly = 2, failed = 0),
            report
        )
        assertTrue(report.settled)
    }

    @Test
    fun healIdentityKeys_transientFailure_isContained_otherKeysStillHealed() = runBlocking {
        // Key 1's store dies (e.g. Keystore auth window expired); keys 0/2
        // must still be processed and the report must stay unsettled so the
        // binder retries.
        val report = healIdentityKeys(
            candidates = listOf(key(0), key(1), key(2)),
            hasPrivateKey = { false },
            deriveKeyPair = { keyIndex -> ByteArray(32) to ByteArray(33) { keyIndex.toByte() } },
            storePrivateKey = { hex, _ ->
                if (hex == key(1).publicKeyHex) throw RuntimeException("UserNotAuthenticated")
            }
        )

        assertEquals(
            IdentityKeyHealReport(3, healthy = 0, repaired = 2, watchOnly = 0, failed = 1),
            report
        )
        assertFalse(report.allSignable)
        assertFalse(report.settled)
    }

    @Test
    fun healIdentityKeys_emptyCandidateList_isNotSettled() = runBlocking {
        val report = healIdentityKeys(
            candidates = emptyList(),
            hasPrivateKey = { false },
            deriveKeyPair = { error("must not derive") },
            storePrivateKey = { _, _ -> error("must not store") }
        )

        assertEquals(IdentityKeyHealReport(0, 0, 0, 0, 0), report)
        assertFalse(report.allSignable)
        assertFalse(report.settled)
    }
}
