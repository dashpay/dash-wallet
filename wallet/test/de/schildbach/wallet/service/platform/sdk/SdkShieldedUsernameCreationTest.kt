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

    /** 0.25 DASH in credits — Constants.DASH_PAY_FEE_CONTESTED without loading Constants. */
    private val contestedFeeCredits = 25_000_000_000L

    /** The smallest covering denomination: 0.1 DASH. */
    private val denominationCredits = 10_000_000_000L
    private val denomination = creditsToDash(denominationCredits)

    /** The smallest denomination covering the contested fee: 0.3 DASH. */
    private val contestedDenominationCredits = 30_000_000_000L
    private val contestedDenomination = creditsToDash(contestedDenominationCredits)

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

    private fun balanceService(ready: Boolean = true) = mockk<ShieldedBalanceService> {
        coEvery { ensureShieldedReady() } returns ready
        every { shieldedSyncStatus } returns statusFlow
        every { observeShieldedBalance() } returns balanceFlow
    }

    private fun happySource() = mockk<ShieldedUsernameSource> {
        coEvery { boundWalletIdOrNull() } returns walletIdHex
        coEvery { managedIdentityCount(walletIdHex) } returns 0
        coEvery { previewRegistrationKeySet(walletIdHex, 0) } returns registrationKeys
        coEvery { persistRegistrationKey(walletIdHex, any(), 0, any()) } just Runs
        coEvery { fallbackPlatformAddressOrNull(walletIdHex) } returns fallbackAddress
        coEvery {
            createIdentityFromPool(walletIdHex, 0, registrationKeys, denominationCredits, any())
        } returns identityId
        coEvery { registerDpnsName(walletIdHex, identityId, any()) } returns "alice2.dash"
    }

    private fun config(flag: Boolean? = true) = mockk<DashPayConfig> {
        coEvery { get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } returns flag
    }

    private class HandoffRecorder {
        val identities = mutableListOf<String>()
        var throwOnCall = false
        fun invoke(identityId: String) {
            if (throwOnCall) error("workmanager down")
            identities.add(identityId)
        }
    }

    private fun service(
        source: ShieldedUsernameSource = happySource(),
        flag: Boolean? = true,
        balance: ShieldedBalanceService = balanceService(),
        handoff: HandoffRecorder = HandoffRecorder(),
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
        executorScope = scope
    )

    // ── Fee → denomination mapping ────────────────────────────────────────

    @Test
    fun denominationMapping_smallestCoveringDenomination() {
        // 0.03 DASH (the non-contested fee) → 0.1 DASH.
        assertEquals(10_000_000_000L, chooseShieldedIdentityDenominationCredits(3_000_000_000L))
        // Exactly a denomination maps to itself.
        assertEquals(10_000_000_000L, chooseShieldedIdentityDenominationCredits(10_000_000_000L))
        assertEquals(30_000_000_000L, chooseShieldedIdentityDenominationCredits(30_000_000_000L))
        assertEquals(50_000_000_000L, chooseShieldedIdentityDenominationCredits(50_000_000_000L))
        assertEquals(100_000_000_000L, chooseShieldedIdentityDenominationCredits(100_000_000_000L))
        // One credit over a denomination steps up to the next.
        assertEquals(30_000_000_000L, chooseShieldedIdentityDenominationCredits(10_000_000_001L))
        assertEquals(50_000_000_000L, chooseShieldedIdentityDenominationCredits(30_000_000_001L))
        assertEquals(100_000_000_000L, chooseShieldedIdentityDenominationCredits(50_000_000_001L))
        // A contested-scale fee (0.25 DASH) would map to 0.3.
        assertEquals(30_000_000_000L, chooseShieldedIdentityDenominationCredits(25_000_000_000L))
    }

    @Test
    fun denominationMapping_unmappableFees() {
        assertNull(chooseShieldedIdentityDenominationCredits(0L))
        assertNull(chooseShieldedIdentityDenominationCredits(-1L))
        // Above the largest denomination there is nothing to spend.
        assertNull(chooseShieldedIdentityDenominationCredits(100_000_000_001L))
    }

    @Test
    fun fundingRequirement_isTheDenominationInDash() {
        // 0.03 DASH fee → 0.1 DASH pool requirement.
        assertEquals(Dash(10_000_000L), shieldedIdentityFundingRequirement(Dash(3_000_000L)))
        assertNull(shieldedIdentityFundingRequirement(Dash.ZERO))
        // Above 1.0 DASH no denomination covers.
        assertNull(shieldedIdentityFundingRequirement(Dash(200_000_000L)))
    }

    // ── Fallback platform address decoding ────────────────────────────────

    @Test
    fun decodePlatformAddress_roundTripsTypeByteAndHash() {
        val raw = byteArrayOf(0xb0.toByte()) + ByteArray(20) { 3 }
        val decoded = decodePlatformAddressRaw21(Bech32m.encode("tdash", raw)!!, "tdash")
        assertTrue(raw.contentEquals(decoded!!))

        val p2sh = byteArrayOf(0x80.toByte()) + ByteArray(20) { 9 }
        assertTrue(p2sh.contentEquals(decodePlatformAddressRaw21(Bech32m.encode("dash", p2sh)!!, "dash")!!))
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
        }
        statusFlow.value = ShieldedSyncStatus.READY
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
            source.persistRegistrationKey(walletIdHex, any(), 0, 2)
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
            source.persistRegistrationKey(walletIdHex, registrationKeys[0].publicKey, 0, 0)
            source.persistRegistrationKey(walletIdHex, registrationKeys[1].publicKey, 0, 1)
            source.persistRegistrationKey(walletIdHex, registrationKeys[2].publicKey, 0, 2)
            source.persistRegistrationKey(walletIdHex, registrationKeys[3].publicKey, 0, 3)
            source.createIdentityFromPool(walletIdHex, 0, registrationKeys, denominationCredits, any())
            source.registerDpnsName(walletIdHex, identityId, "alice2")
        }
        // The decoded 21-byte fallback address was passed through.
        coVerify {
            source.createIdentityFromPool(
                walletIdHex, 0, registrationKeys, denominationCredits,
                match { it.size == 21 && (it[0].toInt() and 0xFF) == 0xb0 }
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
}
