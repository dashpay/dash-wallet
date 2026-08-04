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

import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.dash.wallet.common.money.Dash
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashfoundation.dashsdk.identity.IdentityKeyPreview
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the shielded-funded username creation
 * ([SdkShieldedUsernameCreation]): the fee → denomination mapping, the
 * preflight gates, the [SdkWriteResult] three-valued classification of the
 * Type-20 spend (Ambiguous NEVER retried), the best-effort DPNS name
 * demotion, the legacy handoff, and the single-flight [submit] executor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SdkShieldedUsernameCreationTest {

    // ── Fixtures ──────────────────────────────────────────────────────────

    private val walletIdHex = "aa".repeat(32)
    private val identityId = ByteArray(32) { 7 }
    private val identityIdBase58 = Identifier.from(identityId).toString()

    /** 0.03 DASH in credits — Constants.DASH_PAY_FEE without loading Constants. */
    private val feeCredits = 3_000_000_000L

    /**
     * VERBATIM from the on-device claim of a 0.3-DASH-funded shielded invite
     * that exposed the invalid-denomination defect. The refusal is the only
     * runtime channel that reports the protocol's exit-denomination set, so
     * this fixture is what pins the app's mirror to it.
     */
    private val liveSdkDenominationRefusal =
        "shielded identity-create-from-one-time-key failed: Shielded build error: " +
            "denomination 30000000000 is not a member of the allowed exit-denomination set " +
            "[3000000000, 10000000000, 25000000000, 50000000000, 100000000000]"

    /** 0.25 DASH in credits — Constants.DASH_PAY_FEE_CONTESTED without loading Constants. */
    private val contestedFeeCredits = 25_000_000_000L

    /** The smallest covering denomination: 0.03 DASH (an exact member since v13). */
    private val denominationCredits = 3_000_000_000L
    private val denomination = creditsToDash(denominationCredits)

    /** The smallest denomination covering the contested fee: 0.25 DASH. */
    private val contestedDenominationCredits = 25_000_000_000L
    private val contestedDenomination = creditsToDash(contestedDenominationCredits)

    /**
     * The value a PRE-V13 contested invite note carries: 0.3 DASH. A real note
     * value, but NOT a member of the exit-denomination set — the regression was
     * requesting it verbatim as the claim denomination.
     */
    private val legacyContestedNoteCredits = 30_000_000_000L

    private val registrationKeys = List(4) { index ->
        IdentityKeyPreview(
            identityIndex = 0,
            derivationPath = "m/9'/1'/5'/0'/0'/0'/$index'",
            publicKey = ByteArray(33) { index.toByte() },
            privateKey = ByteArray(32) { (index + 1).toByte() }
        )
    }

    /** A valid bech32m-encoded testnet Platform address (0xb0 P2PKH type byte). */
    private val fallbackAddress =
        Bech32m.encode("tdash", byteArrayOf(0xb0.toByte()) + ByteArray(20) { 3 })!!

    private val statusFlow = MutableStateFlow(ShieldedSyncStatus.READY)
    private val balanceFlow = MutableStateFlow(denomination)

    private fun balanceService(ready: Boolean = true, fundingNoteAnchored: Boolean = true) =
        mockk<ShieldedBalanceService> {
            coEvery { ensureShieldedReady() } returns ready
            every { shieldedSyncStatus } returns statusFlow
            every { observeShieldedBalance() } returns balanceFlow
            coEvery { isFundingNoteAnchoredForDenomination(any()) } returns fundingNoteAnchored
        }

    private fun happySource() = mockk<ShieldedUsernameSource> {
        coEvery { boundWalletIdOrNull() } returns walletIdHex
        coEvery { managedIdentityCount(walletIdHex) } returns 0
        coEvery { previewRegistrationKeySet(walletIdHex, 0) } returns registrationKeys
        coEvery { storeIdentityPrivateKey(walletIdHex, any(), any()) } just Runs
        coEvery { fallbackPlatformAddressOrNull(walletIdHex) } returns fallbackAddress
        coEvery {
            createIdentityFromPool(walletIdHex, 0, registrationKeys, denominationCredits, any())
        } returns identityId
        coEvery { registerDpnsName(walletIdHex, identityId, any()) } returns "alice2.dash"
    }

    private fun config(flag: Boolean? = true) = mockk<DashPayConfig> {
        coEvery { get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } returns flag
    }

    // ── Invitation-claim fixtures ────────────────────────────────────────
    private val oneTimeKeyHex = "0011223344556677889900aabbccddeeff00112233445566778899aabbccddee"
    private val changeAddressRaw43 = ByteArray(43) { 5 }
    private val fundingHeight = 123_456

    /** A source primed for the L2 invitation-claim path. */
    private fun claimSource() = mockk<ShieldedUsernameSource> {
        coEvery { boundWalletIdOrNull() } returns walletIdHex
        coEvery { managedIdentityCount(walletIdHex) } returns 0
        coEvery { previewRegistrationKeySet(walletIdHex, 0) } returns registrationKeys
        coEvery { storeIdentityPrivateKey(walletIdHex, any(), any()) } just Runs
        coEvery { fallbackPlatformAddressOrNull(walletIdHex) } returns fallbackAddress
        coEvery { ownDefaultOrchardAddressRaw43(walletIdHex) } returns changeAddressRaw43
        coEvery {
            createIdentityFromOneTimeKey(
                walletIdHex, any(), changeAddressRaw43, 0, registrationKeys, any(), any(), any()
            )
        } returns identityId
    }

    private class HandoffRecorder {
        val identities = mutableListOf<String>()
        var throwOnCall = false
        fun invoke(identityId: String) {
            if (throwOnCall) error("workmanager down")
            identities.add(identityId)
        }
    }

    /** Captures the invite-overage records the claim path persists. */
    private class OverageRecorder {
        val records = mutableListOf<Pair<String, Long>>()
        suspend fun invoke(identityIdBase58: String, overageCredits: Long) {
            records.add(identityIdBase58 to overageCredits)
        }
    }

    private fun service(
        source: ShieldedUsernameSource = happySource(),
        flag: Boolean? = true,
        balance: ShieldedBalanceService = balanceService(),
        handoff: HandoffRecorder = HandoffRecorder(),
        overages: OverageRecorder = OverageRecorder(),
        scope: TestScope? = null
    ) = SdkShieldedUsernameCreation(
        source = source,
        dashPayConfig = config(flag),
        shieldedBalanceService = balance,
        // Same contested split as the production lambda ("alice2" has a
        // digit 2-9 → non-contested; "alice" normalizes to a11ce → contested).
        feeCredits = { contested -> if (contested) contestedFeeCredits else feeCredits },
        displayHrp = { "tdash" },
        handOffToLegacy = handoff::invoke,
        recordInviteOverage = overages::invoke,
        executorScope = scope
    )

    // ── Fee → denomination mapping ────────────────────────────────────────

    @Test
    fun denominationMapping_smallestCoveringDenomination() {
        // 0.03 DASH (the non-contested fee) → 0.03 DASH, an exact member of
        // the v13 set (before v13 added 0.03 this had to round up to 0.1).
        assertEquals(3_000_000_000L, chooseShieldedIdentityDenominationCredits(3_000_000_000L))
        // Exactly a denomination maps to itself.
        assertEquals(10_000_000_000L, chooseShieldedIdentityDenominationCredits(10_000_000_000L))
        assertEquals(25_000_000_000L, chooseShieldedIdentityDenominationCredits(25_000_000_000L))
        assertEquals(50_000_000_000L, chooseShieldedIdentityDenominationCredits(50_000_000_000L))
        assertEquals(100_000_000_000L, chooseShieldedIdentityDenominationCredits(100_000_000_000L))
        // One credit over a denomination steps up to the next.
        assertEquals(10_000_000_000L, chooseShieldedIdentityDenominationCredits(3_000_000_001L))
        assertEquals(25_000_000_000L, chooseShieldedIdentityDenominationCredits(10_000_000_001L))
        assertEquals(50_000_000_000L, chooseShieldedIdentityDenominationCredits(25_000_000_001L))
        assertEquals(100_000_000_000L, chooseShieldedIdentityDenominationCredits(50_000_000_001L))
        // The RETIRED 0.3 value is not a denomination — a fee of exactly 0.3
        // steps UP to 0.5 rather than resolving to the non-member 0.3.
        assertEquals(50_000_000_000L, chooseShieldedIdentityDenominationCredits(30_000_000_000L))
    }

    @Test
    fun denominationMapping_unmappableFees() {
        assertNull(chooseShieldedIdentityDenominationCredits(0L))
        assertNull(chooseShieldedIdentityDenominationCredits(-1L))
        // Above the largest denomination there is nothing to spend.
        assertNull(chooseShieldedIdentityDenominationCredits(100_000_000_001L))
    }

    /**
     * The app MIRRORS the protocol's `shielded_identity_create_denominations`
     * (there is no SDK accessor for it), so the mirror is pinned to the set the
     * SDK itself quotes in its refusal — the one runtime channel that reports
     * it. [liveSdkDenominationRefusal] is the verbatim message from the
     * on-device claim that exposed this defect; when the SDK's set changes,
     * this test is what fails.
     */
    @Test
    fun exitDenominationSet_matchesTheSetTheSdkQuotes() {
        assertEquals(
            parseAllowedExitDenominations(liveSdkDenominationRefusal),
            SHIELDED_IDENTITY_DENOMINATIONS_CREDITS.toList()
        )
        // Ascending, and no value the ladder could emit sits outside it.
        assertEquals(
            SHIELDED_IDENTITY_DENOMINATIONS_CREDITS.toList().sorted(),
            SHIELDED_IDENTITY_DENOMINATIONS_CREDITS.toList()
        )
    }

    @Test
    fun parseAllowedExitDenominations_readsTheSetOrNothing() {
        assertEquals(
            listOf(3_000_000_000L, 10_000_000_000L, 25_000_000_000L, 50_000_000_000L, 100_000_000_000L),
            parseAllowedExitDenominations(liveSdkDenominationRefusal)
        )
        assertNull(parseAllowedExitDenominations(null))
        assertNull(parseAllowedExitDenominations("Insufficient shielded balance: available 1, required 2"))
        // A malformed list is not half-believed.
        assertNull(parseAllowedExitDenominations("allowed exit-denomination set [1000, oops]"))
        assertNull(parseAllowedExitDenominations("allowed exit-denomination set 1000"))
    }

    @Test
    fun largestExitDenominationAtOrBelow_mapsANoteValueIntoTheSet() {
        // The defect in one line: a 0.3 legacy invite note exits at 0.25.
        assertEquals(25_000_000_000L, largestExitDenominationAtOrBelow(30_000_000_000L))
        assertEquals(25_000_000_000L, largestExitDenominationAtOrBelow(25_000_000_000L))
        assertEquals(10_000_000_000L, largestExitDenominationAtOrBelow(24_999_999_999L))
        assertEquals(3_000_000_000L, largestExitDenominationAtOrBelow(3_000_000_000L))
        assertNull(largestExitDenominationAtOrBelow(2_999_999_999L))
    }

    @Test
    fun fundingRequirement_isTheDenominationInDash() {
        // 0.03 DASH fee → 0.03 DASH pool requirement (its own denomination).
        assertEquals(Dash(3_000_000L), shieldedIdentityFundingRequirement(Dash(3_000_000L)))
        assertNull(shieldedIdentityFundingRequirement(Dash.ZERO))
        // Above 1.0 DASH no denomination covers.
        assertNull(shieldedIdentityFundingRequirement(Dash(200_000_000L)))
    }

    // ── Fallback platform address decoding ────────────────────────────────

    @Test
    fun decodePlatformAddress_mapsDisplayTypeByteToStorageTag() {
        // The FFI deserializes the bincode VARIANT TAG (0x00/0x01), not the
        // bech32m display type byte (0xb0/0x80) — passing the display byte
        // through failed FFI validation live (UnexpectedVariant found: 176).
        val p2pkhHash = ByteArray(20) { 3 }
        val decoded = decodePlatformAddressRaw21(
            Bech32m.encode("tdash", byteArrayOf(0xb0.toByte()) + p2pkhHash)!!,
            "tdash"
        )
        assertTrue((byteArrayOf(0x00) + p2pkhHash).contentEquals(decoded!!))

        val p2shHash = ByteArray(20) { 9 }
        val p2sh = decodePlatformAddressRaw21(
            Bech32m.encode("dash", byteArrayOf(0x80.toByte()) + p2shHash)!!,
            "dash"
        )
        assertTrue((byteArrayOf(0x01) + p2shHash).contentEquals(p2sh!!))
    }

    @Test
    fun decodePlatformAddress_rejectsWrongNetworkLengthAndType() {
        val raw = byteArrayOf(0xb0.toByte()) + ByteArray(20) { 3 }
        // Wrong HRP = wrong network.
        assertNull(decodePlatformAddressRaw21(Bech32m.encode("dash", raw)!!, "tdash"))
        // Wrong payload length.
        assertNull(decodePlatformAddressRaw21(Bech32m.encode("tdash", ByteArray(20))!!, "tdash"))
        // Unknown type byte.
        assertNull(
            decodePlatformAddressRaw21(
                Bech32m.encode("tdash", byteArrayOf(0x10) + ByteArray(20))!!,
                "tdash"
            )
        )
        // Not bech32m at all.
        assertNull(decodePlatformAddressRaw21("not an address", "tdash"))
    }

    // ── Preflight gates ───────────────────────────────────────────────────

    @Test
    fun flagOff_isInert_neverTouchesTheSourceOrRuntime() = runTest {
        val source = mockk<ShieldedUsernameSource>()
        val balance = mockk<ShieldedBalanceService>()
        val handoff = HandoffRecorder()

        val result = service(source = source, flag = false, balance = balance, handoff = handoff)
            .createUsernameFromShielded("alice2")

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(handoff.identities.isEmpty())
        // No stubs configured: any call on the mocks would have thrown.
    }

    @Test
    fun flagUnset_treatedAsOff() = runTest {
        val result = service(source = mockk(), flag = null, balance = mockk())
            .createUsernameFromShielded("alice2")
        assertTrue(result is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun emptyUsername_notBroadcast() = runTest {
        val result = service().createUsernameFromShielded("   ")
        assertTrue(result is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun runtimeNotReady_notBroadcast() = runTest {
        val source = mockk<ShieldedUsernameSource>()
        val result = service(source = source, balance = balanceService(ready = false))
            .createUsernameFromShielded("alice2")
        assertTrue(result is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun poolStillSyncing_notBroadcast_placeholderBalanceNeverSpent() = runTest {
        for (status in listOf(ShieldedSyncStatus.NOT_READY, ShieldedSyncStatus.SYNCING)) {
            statusFlow.value = status
            val result = service(source = mockk()).createUsernameFromShielded("alice2")
            assertTrue("status=$status", result is SdkWriteResult.NotBroadcast)
            // The refusal reason must classify as pool-not-ready so the UI
            // shows the calm "still preparing" surface, not a hard error.
            val reason = (result as SdkWriteResult.NotBroadcast).reason
            assertTrue(
                "status=$status reason=$reason",
                SdkShieldedUsernameCreation.isPoolNotReadyReason(reason)
            )
        }
        statusFlow.value = ShieldedSyncStatus.READY
    }

    @Test
    fun isPoolNotReadyReason_classifiesOnlyTheTransientPoolReasons() {
        assertTrue(
            SdkShieldedUsernameCreation.isPoolNotReadyReason(
                SdkShieldedUsernameCreation.REASON_POOL_STILL_SYNCING
            )
        )
        assertTrue(
            SdkShieldedUsernameCreation.isPoolNotReadyReason(
                SdkShieldedUsernameCreation.REASON_RUNTIME_NOT_READY
            )
        )
        // Genuine errors must NOT be softened into the "still preparing" surface.
        assertFalse(SdkShieldedUsernameCreation.isPoolNotReadyReason("flag off"))
        assertFalse(SdkShieldedUsernameCreation.isPoolNotReadyReason("app wallet not bound to the SDK"))
        assertFalse(SdkShieldedUsernameCreation.isPoolNotReadyReason("malformed fallback platform address"))
    }

    @Test
    fun balanceCoversFeeButNotDenomination_notBroadcast() = runTest {
        // Denomination affordability: 0.05 DASH ≥ the 0.03 fee but below
        // the 0.1 DASH note the Type-20 spend needs.
        balanceFlow.value = Dash(5_000_000L)
        try {
            val source = mockk<ShieldedUsernameSource>()
            val result = service(source = source).createUsernameFromShielded("alice2")
            assertTrue(result is SdkWriteResult.NotBroadcast)
        } finally {
            balanceFlow.value = denomination
        }
    }

    @Test
    fun contestedUsername_spendsTheContestedDenomination() = runTest {
        // "alice" normalizes to the contested charset → the 0.25 fee maps
        // to the 0.3 denomination (the identity's credits must cover the
        // ~0.2 prefunded voting balance the contested DPNS doc debits).
        balanceFlow.value = contestedDenomination
        try {
            val source = happySource()
            coEvery {
                source.createIdentityFromPool(
                    walletIdHex, 0, registrationKeys, contestedDenominationCredits, any()
                )
            } returns identityId

            val result = service(source = source).createUsernameFromShielded("alice")

            assertTrue(result is SdkWriteResult.Broadcast)
            coVerify {
                source.createIdentityFromPool(
                    walletIdHex, 0, registrationKeys, contestedDenominationCredits, any()
                )
            }
            coVerify(exactly = 0) {
                source.createIdentityFromPool(walletIdHex, 0, registrationKeys, denominationCredits, any())
            }
        } finally {
            balanceFlow.value = denomination
        }
    }

    @Test
    fun contestedUsername_poolCoversNonContestedButNotContestedDenomination_notBroadcast() = runTest {
        // Pool holds 0.1 (enough for a non-contested name) but the
        // contested name needs the 0.3 note — nothing may be spent.
        val source = mockk<ShieldedUsernameSource>()
        val result = service(source = source).createUsernameFromShielded("alice")
        assertTrue(result is SdkWriteResult.NotBroadcast)
        coVerify(exactly = 0) { source.createIdentityFromPool(any(), any(), any(), any(), any()) }
    }

    @Test
    fun dualUsernames_registersBoth_contestedFundingFromThePrimary() = runTest {
        balanceFlow.value = contestedDenomination
        try {
            val source = happySource()
            coEvery {
                source.createIdentityFromPool(
                    walletIdHex, 0, registrationKeys, contestedDenominationCredits, any()
                )
            } returns identityId

            val result = service(source = source)
                .createUsernameFromShielded("alice", "alice2")

            val outcome = (result as SdkWriteResult.Broadcast).value
            assertEquals(ShieldedUsernameNameStatus.REGISTERED, outcome.nameStatus)
            assertEquals(ShieldedUsernameNameStatus.REGISTERED, outcome.secondaryNameStatus)
            coVerifyOrder {
                source.registerDpnsName(walletIdHex, identityId, "alice")
                source.registerDpnsName(walletIdHex, identityId, "alice2")
            }
        } finally {
            balanceFlow.value = denomination
        }
    }

    @Test
    fun dualUsernames_primaryNameFailure_secondaryStillAttempted() = runTest {
        balanceFlow.value = contestedDenomination
        try {
            val source = happySource()
            coEvery {
                source.createIdentityFromPool(
                    walletIdHex, 0, registrationKeys, contestedDenominationCredits, any()
                )
            } returns identityId
            coEvery {
                source.registerDpnsName(walletIdHex, identityId, "alice")
            } throws DashSdkError.InvalidParameter("contest already locked")

            val result = service(source = source)
                .createUsernameFromShielded("alice", "alice2")

            // The identity is on chain either way; each name lands (or not)
            // independently.
            val outcome = (result as SdkWriteResult.Broadcast).value
            assertEquals(ShieldedUsernameNameStatus.NOT_REGISTERED, outcome.nameStatus)
            assertEquals(ShieldedUsernameNameStatus.REGISTERED, outcome.secondaryNameStatus)
        } finally {
            balanceFlow.value = denomination
        }
    }

    @Test
    fun walletNotBound_notBroadcast() = runTest {
        val source = mockk<ShieldedUsernameSource> {
            coEvery { boundWalletIdOrNull() } returns null
        }
        val result = service(source = source).createUsernameFromShielded("alice2")
        assertTrue(result is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun keyPersistFailure_notBroadcast_createNeverAttempted() = runTest {
        val source = happySource()
        coEvery {
            source.storeIdentityPrivateKey(walletIdHex, registrationKeys[2].publicKeyHex, any())
        } throws IllegalStateException("keystore auth window expired")

        val result = service(source = source).createUsernameFromShielded("alice2")

        assertTrue(result is SdkWriteResult.NotBroadcast)
        coVerify(exactly = 0) { source.createIdentityFromPool(any(), any(), any(), any(), any()) }
    }

    @Test
    fun missingOrMalformedFallbackAddress_notBroadcast_createNeverAttempted() = runTest {
        val missing = happySource()
        coEvery { missing.fallbackPlatformAddressOrNull(walletIdHex) } returns null
        assertTrue(
            service(source = missing).createUsernameFromShielded("alice2")
                is SdkWriteResult.NotBroadcast
        )
        coVerify(exactly = 0) { missing.createIdentityFromPool(any(), any(), any(), any(), any()) }

        val malformed = happySource()
        coEvery { malformed.fallbackPlatformAddressOrNull(walletIdHex) } returns "garbage"
        assertTrue(
            service(source = malformed).createUsernameFromShielded("alice2")
                is SdkWriteResult.NotBroadcast
        )
        coVerify(exactly = 0) { malformed.createIdentityFromPool(any(), any(), any(), any(), any()) }
    }

    // ── The Type-20 spend classification ──────────────────────────────────

    @Test
    fun happyPath_broadcast_nameRegistered_keysPersisted_handoffEnqueued() = runTest {
        val source = happySource()
        val handoff = HandoffRecorder()

        val result = service(source = source, handoff = handoff)
            .createUsernameFromShielded("alice2")

        val outcome = (result as SdkWriteResult.Broadcast).value
        assertEquals(identityIdBase58, outcome.identityIdBase58)
        assertEquals(ShieldedUsernameNameStatus.REGISTERED, outcome.nameStatus)
        assertNull(outcome.nameFailureReason)
        assertEquals(listOf(identityIdBase58), handoff.identities)

        // Every canonical key is persisted (keyId == position) BEFORE the
        // spend, and the pipeline runs in order: derive → persist → create
        // → DPNS name.
        coVerifyOrder {
            source.previewRegistrationKeySet(walletIdHex, 0)
            source.storeIdentityPrivateKey(walletIdHex, registrationKeys[0].publicKeyHex, any())
            source.storeIdentityPrivateKey(walletIdHex, registrationKeys[1].publicKeyHex, any())
            source.storeIdentityPrivateKey(walletIdHex, registrationKeys[2].publicKeyHex, any())
            source.storeIdentityPrivateKey(walletIdHex, registrationKeys[3].publicKeyHex, any())
            source.createIdentityFromPool(walletIdHex, 0, registrationKeys, denominationCredits, any())
            source.registerDpnsName(walletIdHex, identityId, "alice2")
        }
        // The decoded 21-byte fallback address was passed through.
        coVerify {
            source.createIdentityFromPool(
                walletIdHex, 0, registrationKeys, denominationCredits,
                match { it.size == 21 && it[0].toInt() == 0x00 } // storage tag, NOT the 0xb0 display byte
            )
        }
    }

    @Test
    fun createRejectedPreBroadcast_notBroadcast_noHandoff_noName() = runTest {
        val source = happySource()
        coEvery {
            source.createIdentityFromPool(any(), any(), any(), any(), any())
        } throws DashSdkError.InvalidParameter("bad keys blob")
        val handoff = HandoffRecorder()

        val result = service(source = source, handoff = handoff).createUsernameFromShielded("alice2")

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(handoff.identities.isEmpty())
        coVerify(exactly = 0) { source.registerDpnsName(any(), any(), any()) }
        // The single-attempt contract: exactly one spend attempt.
        coVerify(exactly = 1) { source.createIdentityFromPool(any(), any(), any(), any(), any()) }
    }

    @Test
    fun createUnprovableFailure_ambiguous_neverRetried_noHandoff() = runTest {
        val source = happySource()
        coEvery {
            source.createIdentityFromPool(any(), any(), any(), any(), any())
        } throws DashSdkError.Timeout("dapi timeout")
        val handoff = HandoffRecorder()

        val result = service(source = source, handoff = handoff).createUsernameFromShielded("alice2")

        assertTrue(result is SdkWriteResult.Ambiguous)
        assertTrue(handoff.identities.isEmpty())
        coVerify(exactly = 1) { source.createIdentityFromPool(any(), any(), any(), any(), any()) }
    }

    // ── Best-effort DPNS name / handoff resilience ────────────────────────

    @Test
    fun nameRejectedPreBroadcast_outcomeDemoted_identityStillBroadcastAndHandedOff() = runTest {
        val source = happySource()
        coEvery {
            source.registerDpnsName(any(), any(), any())
        } throws DashSdkError.InvalidState("name taken")
        val handoff = HandoffRecorder()

        val result = service(source = source, handoff = handoff).createUsernameFromShielded("alice2")

        val outcome = (result as SdkWriteResult.Broadcast).value
        assertEquals(ShieldedUsernameNameStatus.NOT_REGISTERED, outcome.nameStatus)
        assertEquals(listOf(identityIdBase58), handoff.identities)
    }

    @Test
    fun nameUnprovableFailure_outcomeAmbiguousName_identityStillBroadcast() = runTest {
        val source = happySource()
        coEvery {
            source.registerDpnsName(any(), any(), any())
        } throws DashSdkError.NetworkError("connection reset")

        val result = service(source = source).createUsernameFromShielded("alice2")

        val outcome = (result as SdkWriteResult.Broadcast).value
        assertEquals(ShieldedUsernameNameStatus.AMBIGUOUS, outcome.nameStatus)
    }

    @Test
    fun handoffFailure_neverDemotesTheBroadcastResult() = runTest {
        val handoff = HandoffRecorder().apply { throwOnCall = true }

        val result = service(handoff = handoff).createUsernameFromShielded("alice2")

        assertTrue(result is SdkWriteResult.Broadcast)
    }

    @Test
    fun usernameIsTrimmedBeforeRegistration() = runTest {
        val source = happySource()

        service(source = source).createUsernameFromShielded("  alice2  ")

        coVerify { source.registerDpnsName(walletIdHex, identityId, "alice2") }
    }

    // ── The app-scope submit executor ─────────────────────────────────────

    @Test
    fun submit_singleFlight_provingRefusesResubmission() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val executor = service(scope = scope)
        executor.ioDispatcher = StandardTestDispatcher(testScheduler)

        assertTrue(executor.submit("alice2"))
        assertEquals(ShieldedUsernameSubmitState.Proving, executor.submitState.value)
        // A second confirmation while proving must never double-spend.
        assertFalse(executor.submit("alice2"))

        scope.testScheduler.advanceUntilIdle()
        val state = executor.submitState.value
        assertTrue(state is ShieldedUsernameSubmitState.Created)
        assertEquals(
            ShieldedUsernameNameStatus.REGISTERED,
            (state as ShieldedUsernameSubmitState.Created).outcome.nameStatus
        )

        // Terminal Created refuses re-submission until acknowledged.
        assertFalse(executor.submit("alice2"))
        executor.acknowledge()
        assertEquals(ShieldedUsernameSubmitState.Idle, executor.submitState.value)
        assertTrue(executor.submit("alice2"))
    }

    @Test
    fun submit_notSent_isRetrySafe() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val executor = service(flag = false, source = mockk(), balance = mockk(), scope = scope)
        executor.ioDispatcher = StandardTestDispatcher(testScheduler)

        assertTrue(executor.submit("alice2"))
        scope.testScheduler.advanceUntilIdle()
        assertTrue(executor.submitState.value is ShieldedUsernameSubmitState.NotSent)

        // NotSent is provably pre-broadcast — a retry is allowed directly.
        assertTrue(executor.submit("alice2"))
    }

    @Test
    fun submit_ambiguous_staysStickyAndUnretryable() = runTest {
        val source = happySource()
        coEvery {
            source.createIdentityFromPool(any(), any(), any(), any(), any())
        } throws DashSdkError.Timeout("dapi timeout")
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val executor = service(source = source, scope = scope)
        executor.ioDispatcher = StandardTestDispatcher(testScheduler)

        assertTrue(executor.submit("alice2"))
        scope.testScheduler.advanceUntilIdle()
        assertEquals(ShieldedUsernameSubmitState.MayHaveGoneThrough, executor.submitState.value)

        // Never re-submittable, even after acknowledge (funds safety).
        assertFalse(executor.submit("alice2"))
        executor.acknowledge()
        assertEquals(ShieldedUsernameSubmitState.MayHaveGoneThrough, executor.submitState.value)
        assertFalse(executor.submit("alice2"))
    }

    @Test
    fun submit_withoutExecutorScope_refusesAndSubmitsNothing() {
        val source = mockk<ShieldedUsernameSource>()
        val executor = service(source = source, scope = null)

        assertFalse(executor.submit("alice2"))
        assertEquals(ShieldedUsernameSubmitState.Idle, executor.submitState.value)
    }

    // ── Invitation claim (createIdentityFromInvitation) ───────────────────

    @Test
    fun claim_flagOff_isInert() = runTest {
        val result = service(source = mockk(), flag = false, balance = mockk())
            .createIdentityFromInvitation(oneTimeKeyHex, fundingHeight, "alice2")
        assertTrue(result is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun claim_knownNonContestedNoteValue_spendsExactlyThatValue() = runTest {
        val source = claimSource()

        // The link says the note is the 0.1 non-contested denomination — the
        // claim requests exactly that, in ONE attempt.
        val result = service(source = source)
            .createIdentityFromInvitation(
                oneTimeKeyHex, fundingHeight, "alice2", fundingCredits = denominationCredits
            )

        assertEquals(identityIdBase58, (result as SdkWriteResult.Broadcast).value)
        // The one-time key (decoded to 32 bytes), the claimer's own change
        // address, the note's 0.1 denomination and the funding-height hint
        // are all threaded through.
        coVerify(exactly = 1) {
            source.createIdentityFromOneTimeKey(
                walletIdHex,
                match { it.size == 32 && it[0].toInt() == 0x00 && (it[1].toInt() and 0xFF) == 0x11 },
                changeAddressRaw43,
                0,
                registrationKeys,
                denominationCredits,
                match { it.size == 21 && it[0].toInt() == 0x00 },
                fundingHeight
            )
        }
    }

    @Test
    fun claim_contestedNoteValue_nonContestedUsername_identityGetsTheFullNote() = runTest {
        val source = claimSource()

        // THE product decision: a 0.3 contested invite claimed with a
        // NON-contested username ("alice2") funds the identity with the full
        // 0.3 — not the 0.1 username minimum with the 0.2 difference routed
        // to the claimer's own Orchard change address.
        val result = service(source = source)
            .createIdentityFromInvitation(
                oneTimeKeyHex, fundingHeight, "alice2", fundingCredits = contestedDenominationCredits
            )

        assertTrue(result is SdkWriteResult.Broadcast)
        coVerify(exactly = 1) {
            source.createIdentityFromOneTimeKey(
                walletIdHex, any(), changeAddressRaw43, 0, registrationKeys,
                contestedDenominationCredits, any(), any()
            )
        }
    }

    @Test
    fun claim_legacyLinkWithoutNoteValue_attemptsTheContestedDenominationFirst() = runTest {
        val source = claimSource()

        // Legacy links carry no `amt`: the note value is UNKNOWN, so the
        // claim starts at the largest denomination any invite note could
        // cover (0.25) — a legacy contested invite still funds the identity
        // as fully as the exit-denomination set allows.
        val result = service(source = source)
            .createIdentityFromInvitation(oneTimeKeyHex, fundingHeight, "alice2")

        assertTrue(result is SdkWriteResult.Broadcast)
        coVerify(exactly = 1) {
            source.createIdentityFromOneTimeKey(
                walletIdHex, any(), changeAddressRaw43, 0, registrationKeys,
                contestedDenominationCredits, any(), any()
            )
        }
        coVerify(exactly = 1) {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    /** The FFI's fail-closed refusal when the key's note(s) don't cover the denomination. */
    private fun noteBelowDenominationError() = DashSdkError.PlatformWallet.WalletOperation(
        "shielded identity-create-from-one-time-key failed: " +
            "Insufficient shielded balance: available 10000000000, required 25000000000"
    )

    @Test
    fun claim_legacyLink_noteTooSmallForContested_fallsBackDownTheLadder() = runTest {
        val source = claimSource()
        // The real note is 0.1: the 0.25 attempt is refused pre-broadcast
        // (nothing spent), and the claim falls back down the ladder to the
        // next allowed denomination, 0.1.
        coEvery {
            source.createIdentityFromOneTimeKey(
                walletIdHex, any(), changeAddressRaw43, 0, registrationKeys,
                contestedDenominationCredits, any(), any()
            )
        } throws noteBelowDenominationError()

        val result = service(source = source)
            .createIdentityFromInvitation(oneTimeKeyHex, fundingHeight, "alice2")

        assertEquals(identityIdBase58, (result as SdkWriteResult.Broadcast).value)
        coVerifyOrder {
            source.createIdentityFromOneTimeKey(
                walletIdHex, any(), changeAddressRaw43, 0, registrationKeys,
                contestedDenominationCredits, any(), any()
            )
            source.createIdentityFromOneTimeKey(
                walletIdHex, any(), changeAddressRaw43, 0, registrationKeys,
                10_000_000_000L, any(), any()
            )
        }
    }

    @Test
    fun claim_tamperedOversizedNoteValue_failsClosedThenFallsBack() = runTest {
        val source = claimSource()
        // A tampered link claims 0.25 but the note is really 0.1: the
        // oversized attempt finds no covering note and is refused
        // pre-broadcast, then the ladder meets the real note. The tampered
        // `amt` cost one refused attempt — it moved no funds.
        coEvery {
            source.createIdentityFromOneTimeKey(
                walletIdHex, any(), changeAddressRaw43, 0, registrationKeys,
                contestedDenominationCredits, any(), any()
            )
        } throws noteBelowDenominationError()

        val result = service(source = source)
            .createIdentityFromInvitation(
                oneTimeKeyHex, fundingHeight, "alice2", fundingCredits = contestedDenominationCredits
            )

        assertTrue(result is SdkWriteResult.Broadcast)
        coVerify(exactly = 1) {
            source.createIdentityFromOneTimeKey(
                walletIdHex, any(), changeAddressRaw43, 0, registrationKeys,
                10_000_000_000L, any(), any()
            )
        }
    }

    /**
     * THE ON-DEVICE CASE: a 0.3-funded (pre-v13 contested) invite. The claim
     * must request 0.25 — the largest exit denomination that note covers — and
     * NEVER the 0.3 note value itself, which the FFI refuses as a non-member of
     * the allowed exit-denomination set.
     */
    @Test
    fun claim_legacy0Point3Note_requests0Point25_neverTheNoteValue() = runTest {
        val source = claimSource()

        val result = service(source = source)
            .createIdentityFromInvitation(
                oneTimeKeyHex, fundingHeight, "alice2", fundingCredits = legacyContestedNoteCredits
            )

        assertTrue(result is SdkWriteResult.Broadcast)
        coVerify(exactly = 1) {
            source.createIdentityFromOneTimeKey(
                walletIdHex, any(), changeAddressRaw43, 0, registrationKeys,
                contestedDenominationCredits, any(), any()
            )
        }
        coVerify(exactly = 0) {
            source.createIdentityFromOneTimeKey(
                any(), any(), any(), any(), any(), legacyContestedNoteCredits, any(), any()
            )
        }
    }

    // ── Invite-claim overage recording (all remaining value → identity) ──

    @Test
    fun claim_legacy0Point3Note_recordsThe0Point05Overage_beforeReturning() = runTest {
        val source = claimSource()
        val overages = OverageRecorder()

        val result = service(source = source, overages = overages)
            .createIdentityFromInvitation(
                oneTimeKeyHex, fundingHeight, "alice2", fundingCredits = legacyContestedNoteCredits
            )

        assertTrue(result is SdkWriteResult.Broadcast)
        // 0.3 note − 0.25 exit denomination = 0.05 pending for the identity.
        assertEquals(
            listOf(identityIdBase58 to 5_000_000_000L),
            overages.records
        )
    }

    /**
     * ORDERING GUARD (the S22 link-clear lesson): the overage record must be
     * PERSISTED before the claim call returns — i.e., before ANY caller-side
     * completion step can possibly run. CreateIdentityService clears the
     * invite-link copy (topUpRepository.clearInvitation) only after
     * claimShieldedInvitation returned, and the caller is suspended on this
     * call for its whole duration, so "record persisted before return"
     * IS "record persisted before any link clear" under structured
     * concurrency. The sequenced events pin exactly that: the first moment a
     * caller-side clear can execute is after the call returns, and by then
     * the record event has already been appended — this test fails if the
     * recording is ever moved after the return, made fire-and-forget
     * (launched instead of awaited), or dropped from the success tail.
     */
    @Test
    fun claim_overageRecordPersists_beforeAnyCallerSideLinkClear() = runTest {
        val source = claimSource()
        val events = mutableListOf<String>()
        val overages = object {
            @Suppress("UNUSED_PARAMETER")
            suspend fun invoke(identityIdBase58: String, overageCredits: Long) {
                events.add("overage-record-persisted")
            }
        }

        val result = SdkShieldedUsernameCreation(
            source = source,
            dashPayConfig = config(true),
            shieldedBalanceService = balanceService(),
            feeCredits = { contested -> if (contested) contestedFeeCredits else feeCredits },
            displayHrp = { "tdash" },
            handOffToLegacy = {},
            recordInviteOverage = overages::invoke,
            executorScope = null
        ).createIdentityFromInvitation(
            oneTimeKeyHex, fundingHeight, "alice2", fundingCredits = legacyContestedNoteCredits
        )
        // The earliest instant ANY caller-side completion step (the invite
        // link clear) can run — strictly after the suspend call returned.
        events.add("caller-side-link-clear")

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(
            listOf("overage-record-persisted", "caller-side-link-clear"),
            events
        )
    }

    @Test
    fun claim_exactDenominationInvite_recordsNoOverage() = runTest {
        val source = claimSource()
        val overages = OverageRecorder()

        val result = service(source = source, overages = overages)
            .createIdentityFromInvitation(
                oneTimeKeyHex, fundingHeight, "alice2", fundingCredits = contestedDenominationCredits
            )

        assertTrue(result is SdkWriteResult.Broadcast)
        assertTrue(overages.records.isEmpty())
    }

    @Test
    fun claim_descendedLadder_recordsNoOverage() = runTest {
        val source = claimSource()
        val overages = OverageRecorder()
        // amt lies high (0.3): the 0.25 attempt is refused because the real
        // note is smaller, and the ladder descends. The claimed difference is
        // fiction — nothing may be recorded.
        coEvery {
            source.createIdentityFromOneTimeKey(
                walletIdHex, any(), changeAddressRaw43, 0, registrationKeys,
                contestedDenominationCredits, any(), any()
            )
        } throws noteBelowDenominationError()

        val result = service(source = source, overages = overages)
            .createIdentityFromInvitation(
                oneTimeKeyHex, fundingHeight, "alice2", fundingCredits = legacyContestedNoteCredits
            )

        assertTrue(result is SdkWriteResult.Broadcast)
        assertTrue(overages.records.isEmpty())
    }

    @Test
    fun claim_junkNoteValue_treatedAsUnknown_startsAtContested() = runTest {
        val source = claimSource()

        // A value that is not a real invite denomination (tampered/garbage)
        // is never believed — the claim behaves exactly like a legacy link.
        val result = service(source = source)
            .createIdentityFromInvitation(
                oneTimeKeyHex, fundingHeight, "alice2", fundingCredits = 12_345L
            )

        assertTrue(result is SdkWriteResult.Broadcast)
        coVerify(exactly = 1) {
            source.createIdentityFromOneTimeKey(
                walletIdHex, any(), changeAddressRaw43, 0, registrationKeys,
                contestedDenominationCredits, any(), any()
            )
        }
    }

    @Test
    fun claim_contestedUsername_neverDescendsBelowItsFloor() = runTest {
        val source = claimSource()
        // A contested username needs the 0.25 denomination; when the note
        // cannot cover it there is nothing smaller that could fund the name,
        // so the refusal is terminal — never a doomed 0.1 attempt.
        coEvery {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        } throws noteBelowDenominationError()

        val result = service(source = source)
            .createIdentityFromInvitation(oneTimeKeyHex, fundingHeight, "alice")

        assertTrue(result is SdkWriteResult.NotBroadcast)
        coVerify(exactly = 1) {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun claim_noNotesAtAll_terminalRefusal_neverDescends() = runTest {
        val source = claimSource()
        // ShieldedNoUnspentNotes means the key owns nothing the scan can see
        // — a smaller denomination re-scans the same tree and finds the same
        // nothing, so the ladder must not burn two more full scans on it.
        coEvery {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        } throws DashSdkError.PlatformWallet.WalletOperation(
            "shielded identity-create-from-one-time-key failed: No unspent shielded notes available"
        )

        val result = service(source = source)
            .createIdentityFromInvitation(oneTimeKeyHex, fundingHeight, "alice2")

        assertTrue(result is SdkWriteResult.NotBroadcast)
        coVerify(exactly = 1) {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun claim_alreadyUsedFirstAttempt_neverDescends() = runTest {
        val source = claimSource()
        coEvery {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        } throws DashSdkError.InvalidState("orchard nullifier already spent on chain")

        val result = service(source = source)
            .createIdentityFromInvitation(oneTimeKeyHex, fundingHeight, "alice2")

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(
            SdkShieldedUsernameCreation.isInviteAlreadyUsedReason(
                (result as SdkWriteResult.NotBroadcast).reason
            )
        )
        coVerify(exactly = 1) {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ── Already-claimed detection (typed code 37 + string fallback) ──────

    /**
     * rs-platform-wallet's `PlatformWalletError::ShieldedInviteAlreadyClaimed`
     * Display, rendered with one of its four raise sites' `reason` literals.
     * The prefix is reason-independent; only the parenthetical varies.
     */
    private fun alreadyClaimedDisplay(reason: String) =
        "Shielded invitation already claimed: its note is spent on chain but this wallet " +
            "cannot prove that this claim created an identity ($reason); the invitation " +
            "cannot be claimed again"

    /**
     * The raise site (`operations.rs`, "broadcast accepted but result
     * confirmation failed" arm) whose reason quotes the RESULT-WAIT error, not
     * a nullifier — the one the old per-reason substring list missed, which
     * made a terminal already-claimed surface as an AMBIGUOUS "outcome
     * unconfirmed".
     */
    private val waitErrorReason =
        "identity 5xK was created from this invitation's notes but does not carry the " +
            "submitted master authentication key, so it belongs to another holder of the " +
            "one-time key: timed out waiting for the state transition result"

    @Test
    fun claim_typedAlreadyClaimed_code37_isTerminalAndNeverDescends() = runTest {
        val source = claimSource()
        // Native code 37 → DashSdkError.PlatformWallet.ShieldedInviteAlreadyClaimed.
        // The message deliberately carries NONE of the legacy substrings, so
        // only the typed arm can classify it. The note is consumed on chain:
        // the ladder must stop at the first rung, not descend to 0.1.
        coEvery {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        } throws DashSdkError.PlatformWallet.ShieldedInviteAlreadyClaimed(
            "this invitation cannot be claimed again"
        )

        // A legacy link (no `amt`) with a non-contested name has a THREE-rung
        // ladder, so a descend would show up as a second attempt.
        assertEquals(3, inviteClaimDenominationLadder(denominationCredits, null).size)

        val result = service(source = source)
            .createIdentityFromInvitation(oneTimeKeyHex, fundingHeight, "alice2")

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(
            SdkShieldedUsernameCreation.isInviteAlreadyUsedReason(
                (result as SdkWriteResult.NotBroadcast).reason
            )
        )
        assertTrue(SdkShieldedUsernameCreation.isInviteAlreadyUsedOutcome(result))
        coVerify(exactly = 1) {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun claim_untypedAlreadyClaimedDisplay_withoutNullifierInReason_isTerminal() = runTest {
        val source = claimSource()
        // Pre-v41int13 AAR shape: the same outcome arrives as an untyped
        // wallet-operation error carrying only the Display text. The
        // reason-independent prefix must still classify it as already-used
        // rather than letting it fall through to the Ambiguous bucket.
        coEvery {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        } throws DashSdkError.PlatformWallet.WalletOperation(alreadyClaimedDisplay(waitErrorReason))

        val result = service(source = source)
            .createIdentityFromInvitation(oneTimeKeyHex, fundingHeight, "alice2")

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(
            SdkShieldedUsernameCreation.isInviteAlreadyUsedReason(
                (result as SdkWriteResult.NotBroadcast).reason
            )
        )
        coVerify(exactly = 1) {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun alreadyClaimed_allFourRaiseSites_classifyAsInviteAlreadyUsed() {
        // The four `ShieldedInviteAlreadyClaimed` raise sites in
        // rs-platform-wallet's operations.rs, by their reason literal. Only
        // the second and (via their `{evidence}` tail) the third and fourth
        // ever quoted a nullifier — the first did not, so it used to escape
        // detection entirely.
        val reasons = listOf(
            waitErrorReason,
            "the note was spent by an earlier transition whose identity id cannot be " +
                "re-derived (single-spend bundles are padded with a randomly generated dummy " +
                "nullifier that participates in the id derivation): the selected note's " +
                "nullifier is already spent on chain (pre-broadcast preflight)",
            "identity 5xK owns the submitted master auth key hash but was not created by this " +
                "claim (expected id 9tQ); the shielded spend was finalized as a chargeable " +
                "failure and its value went to the creation-failure address: broadcast " +
                "returned NullifierAlreadySpent",
            "identity 9tQ was created from this invitation's notes but does not carry the " +
                "submitted master authentication key, so it belongs to another holder of the " +
                "one-time key: result wait returned NullifierAlreadySpent"
        )
        reasons.forEach { reason ->
            assertTrue(
                "already-claimed Display must classify as invite-already-used: $reason",
                SdkShieldedUsernameCreation.isInviteAlreadyUsedFailure(
                    DashSdkError.PlatformWallet.WalletOperation(alreadyClaimedDisplay(reason))
                )
            )
        }
        // And typed, regardless of message.
        assertTrue(
            SdkShieldedUsernameCreation.isInviteAlreadyUsedFailure(
                DashSdkError.PlatformWallet.ShieldedInviteAlreadyClaimed("")
            )
        )
        // The fail-closed denomination refusal is NOT already-used — it is the
        // only refusal the ladder may descend on.
        assertFalse(
            SdkShieldedUsernameCreation.isInviteAlreadyUsedFailure(noteBelowDenominationError())
        )
        assertFalse(
            SdkShieldedUsernameCreation.isNoteBelowDenominationFailure(
                DashSdkError.PlatformWallet.ShieldedInviteAlreadyClaimed(
                    alreadyClaimedDisplay(waitErrorReason)
                )
            )
        )
    }

    @Test
    fun alreadyUsedOutcome_typedCauseIsRecognisedOnEveryResultShape() {
        val typed = DashSdkError.PlatformWallet.ShieldedInviteAlreadyClaimed("consumed")
        // The app-owned reason (the normal path).
        assertTrue(
            SdkShieldedUsernameCreation.isInviteAlreadyUsedOutcome(
                SdkWriteResult.NotBroadcast(SdkShieldedUsernameCreation.REASON_INVITE_ALREADY_USED)
            )
        )
        // A typed cause carried under any other reason, and under Ambiguous —
        // belt and braces so code 37 can never reach the "outcome unconfirmed"
        // surface.
        assertTrue(
            SdkShieldedUsernameCreation.isInviteAlreadyUsedOutcome(
                SdkWriteResult.NotBroadcast("some other reason", typed)
            )
        )
        assertTrue(
            SdkShieldedUsernameCreation.isInviteAlreadyUsedOutcome(SdkWriteResult.Ambiguous(typed))
        )
        // An unrelated failure must NOT be promoted to "invite already used" —
        // the loose string arm is scoped to the claim-spend catch only.
        assertFalse(
            SdkShieldedUsernameCreation.isInviteAlreadyUsedOutcome(
                SdkWriteResult.Ambiguous(DashSdkError.PlatformWallet.WalletOperation("Wallet already exists: ab"))
            )
        )
        assertFalse(
            SdkShieldedUsernameCreation.isInviteAlreadyUsedOutcome(SdkWriteResult.Broadcast("id"))
        )
    }

    // ── Invite-claim denomination ladder (pure) ──────────────────────────

    /**
     * THE REGRESSION, pinned: whatever the inputs, the ladder may only ever
     * emit members of the allowed exit-denomination set. A funded note value is
     * mapped THROUGH the set, never passed through — the 0.3 legacy note value
     * that the FFI refused must never appear.
     */
    @Test
    fun claimLadder_onlyEverEmitsAllowedExitDenominations() {
        val allowed = SHIELDED_IDENTITY_DENOMINATIONS_CREDITS.toSet()
        val floors = listOf(denominationCredits, contestedDenominationCredits, 50_000_000_000L)
        val funding = listOf(
            null, 0L, -1L, 12_345L, 3_000_000_000L, 10_000_000_000L,
            25_000_000_000L, legacyContestedNoteCredits, 99_999_999_999_999L
        )
        for (floor in floors) {
            for (value in funding) {
                val ladder = inviteClaimDenominationLadder(floor, value)
                assertTrue(
                    "ladder($floor, $value) = $ladder escaped the allowed set",
                    ladder.all { it in allowed }
                )
                assertTrue(
                    "ladder($floor, $value) = $ladder descended below the floor",
                    ladder.all { it >= floor }
                )
                assertEquals(ladder.sortedDescending(), ladder)
            }
        }
    }

    @Test
    fun claimLadder_legacy0Point3Note_startsAtTheLargestCoveredDenomination() {
        // The on-device case: a 0.3-funded invite claimed with a non-contested
        // name. 0.3 is NOT exitable → 0.25 is, and the 0.05 remainder returns
        // to the claimer's own change address.
        assertEquals(
            listOf(contestedDenominationCredits, 10_000_000_000L, denominationCredits),
            inviteClaimDenominationLadder(denominationCredits, legacyContestedNoteCredits)
        )
        // Same note, contested name: the floor collapses it to one attempt.
        assertEquals(
            listOf(contestedDenominationCredits),
            inviteClaimDenominationLadder(contestedDenominationCredits, legacyContestedNoteCredits)
        )
    }

    @Test
    fun claimLadder_knownValueIsTheSingleStart_withTamperFallback() {
        // Known 0.25 (today's contested invite), non-contested floor.
        assertEquals(
            listOf(contestedDenominationCredits, 10_000_000_000L, denominationCredits),
            inviteClaimDenominationLadder(denominationCredits, contestedDenominationCredits)
        )
        // Known 0.03 (today's non-contested invite) at its own floor: one attempt.
        assertEquals(
            listOf(denominationCredits),
            inviteClaimDenominationLadder(denominationCredits, denominationCredits)
        )
    }

    @Test
    fun claimLadder_unknownValueDescendsFromTheLargestClaimableDenomination() {
        val fromTheTop = listOf(contestedDenominationCredits, 10_000_000_000L, denominationCredits)
        assertEquals(fromTheTop, inviteClaimDenominationLadder(denominationCredits, null))
        // Junk / non-member values are never believed → same ladder as unknown.
        assertEquals(fromTheTop, inviteClaimDenominationLadder(denominationCredits, 12_345L))
        // 0.5 is an exit denomination but NOT an invite note value → not believed.
        assertEquals(fromTheTop, inviteClaimDenominationLadder(denominationCredits, 50_000_000_000L))
    }

    @Test
    fun claimLadder_contestedFloorNeverDescendsBelowIt() {
        assertEquals(
            listOf(contestedDenominationCredits),
            inviteClaimDenominationLadder(contestedDenominationCredits, null)
        )
        // A believed 0.03 note cannot fund a contested name: refuse outright
        // rather than burn a ~30 s attempt that must fail.
        assertTrue(
            inviteClaimDenominationLadder(contestedDenominationCredits, denominationCredits).isEmpty()
        )
    }

    @Test
    fun claimLadder_floorAboveEveryClaimableDenomination_isEmpty() {
        assertTrue(inviteClaimDenominationLadder(50_000_000_000L, null).isEmpty())
    }

    /**
     * The divergence escape hatch: fed the set the SDK quotes, the ladder is
     * derived from THAT set rather than the app's mirror — so a protocol
     * revision costs one refused (nothing-spent) attempt, not every claim.
     */
    @Test
    fun claimLadder_honoursAnSdkReportedDenominationSet() {
        val sdkAllowed = listOf(10_000_000_000L, 30_000_000_000L, 50_000_000_000L)
        assertEquals(
            listOf(30_000_000_000L, 10_000_000_000L),
            inviteClaimDenominationLadder(10_000_000_000L, legacyContestedNoteCredits, sdkAllowed)
        )
        assertEquals(
            10_000_000_000L,
            chooseShieldedIdentityDenominationCredits(feeCredits, sdkAllowed)
        )
    }

    @Test
    fun isNoteBelowDenominationFailure_matchesOnlyTheInsufficientRefusal() {
        assertTrue(
            SdkShieldedUsernameCreation.isNoteBelowDenominationFailure(noteBelowDenominationError())
        )
        // No notes at all is NOT the descend case…
        assertFalse(
            SdkShieldedUsernameCreation.isNoteBelowDenominationFailure(
                DashSdkError.PlatformWallet.WalletOperation("No unspent shielded notes available")
            )
        )
        // …and neither is anything else.
        assertFalse(
            SdkShieldedUsernameCreation.isNoteBelowDenominationFailure(
                DashSdkError.Timeout("dapi timeout")
            )
        )
        assertFalse(SdkShieldedUsernameCreation.isNoteBelowDenominationFailure(RuntimeException()))
    }

    @Test
    fun claim_contestedUsername_spendsTheContestedDenomination() = runTest {
        val source = claimSource()
        coEvery {
            source.createIdentityFromOneTimeKey(
                walletIdHex, any(), changeAddressRaw43, 0, registrationKeys,
                contestedDenominationCredits, any(), any()
            )
        } returns identityId

        val result = service(source = source)
            .createIdentityFromInvitation(oneTimeKeyHex, fundingHeight, "alice")

        assertTrue(result is SdkWriteResult.Broadcast)
        coVerify {
            source.createIdentityFromOneTimeKey(
                walletIdHex, any(), changeAddressRaw43, 0, registrationKeys,
                contestedDenominationCredits, any(), any()
            )
        }
    }

    @Test
    fun claim_doubleClaim_nullifierAlreadySpent_mapsToInviteAlreadyUsed() = runTest {
        val source = claimSource()
        coEvery {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        } throws DashSdkError.InvalidState("orchard nullifier already spent on chain")

        val result = service(source = source)
            .createIdentityFromInvitation(oneTimeKeyHex, fundingHeight, "alice2")

        assertTrue(result is SdkWriteResult.NotBroadcast)
        val reason = (result as SdkWriteResult.NotBroadcast).reason
        assertTrue(SdkShieldedUsernameCreation.isInviteAlreadyUsedReason(reason))
    }

    @Test
    fun claim_malformedOneTimeKey_notBroadcast_neverAttempted() = runTest {
        val source = claimSource()
        val result = service(source = source)
            .createIdentityFromInvitation("not-hex", fundingHeight, "alice2")
        assertTrue(result is SdkWriteResult.NotBroadcast)
        coVerify(exactly = 0) {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun claim_noBoundShieldedSubWallet_notBroadcast() = runTest {
        val source = claimSource()
        coEvery { source.ownDefaultOrchardAddressRaw43(walletIdHex) } returns null
        val result = service(source = source)
            .createIdentityFromInvitation(oneTimeKeyHex, fundingHeight, "alice2")
        assertTrue(result is SdkWriteResult.NotBroadcast)
        coVerify(exactly = 0) {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun claim_unprovableFailure_isAmbiguous_neverRetried() = runTest {
        val source = claimSource()
        coEvery {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        } throws DashSdkError.Timeout("dapi timeout")
        val result = service(source = source)
            .createIdentityFromInvitation(oneTimeKeyHex, fundingHeight, "alice2")
        assertTrue(result is SdkWriteResult.Ambiguous)
        coVerify(exactly = 1) {
            source.createIdentityFromOneTimeKey(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun hexToBytes32_decodesLowercaseHex() {
        val bytes = SdkShieldedUsernameCreation.hexToBytes32(oneTimeKeyHex)
        assertEquals(32, bytes.size)
        assertEquals(0x00, bytes[0].toInt() and 0xFF)
        assertEquals(0x11, bytes[1].toInt() and 0xFF)
        assertEquals(0xee, bytes[31].toInt() and 0xFF)
    }
}
