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

import de.schildbach.wallet.database.entity.IdentityCreationState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashfoundation.dashsdk.identity.IdentityKeyPreview
import org.dashfoundation.dashsdk.wallet.TrackedAssetLock
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the transparent (post-cutover) username creation's
 * funding step — specifically the MO-1023 false-locked Keystore handling: a
 * signing refusal that lands AFTER the SDK built (and broadcast) the asset
 * lock must resume that lock, never report "nothing spent" while the funds
 * are committed, and never spin on a genuinely locked device.
 */
class SdkTransparentUsernameCreationTest {

    private val walletIdHex = "aa".repeat(32)
    private val identityId = ByteArray(32) { 7 }
    private val identityIdBase58 = Identifier.from(identityId).toString()

    /** 0.03 DASH in duffs — Constants.DASH_PAY_FEE without loading Constants. */
    private val feeDuffs = 3_000_000L

    /** Non-contestable label (contains a digit other than 0/1). */
    private val label = "alice2"

    private val falseLocked = DashSdkError.PlatformWallet.Generic(
        5000,
        "SDK error: Protocol error: Generic Error: identity create failed: Keystore denied 'decrypt' on " +
            "lock-bound alias 'org.dashfoundation.wallet.keys.devicebound' as device-locked; " +
            "KeyguardManager at throw time: isDeviceLocked=false isKeyguardLocked=false " +
            "(FALSE-LOCKED: device reports unlocked but Keystore denied — Keystore2 lock-state " +
            "misreporting; retryable immediately)"
    )

    private val genuinelyLocked = DashSdkError.PlatformWallet.Generic(
        5000,
        "SDK error: Protocol error: Generic Error: Keystore denied 'decrypt' on lock-bound alias " +
            "'org.dashfoundation.wallet.keys.devicebound' as device-locked; KeyguardManager at throw " +
            "time: isDeviceLocked=true isKeyguardLocked=true (genuinely locked — retry after unlock)"
    )

    private val lock = TrackedAssetLock(
        outpointTxid = ByteArray(32) { if (it == 0) 9 else 0 },
        outpointVout = 0,
        fundingType = TrackedAssetLock.FundingType.IDENTITY_REGISTRATION,
        status = TrackedAssetLock.Status.INSTANT_SEND_LOCKED,
        registrationIndex = 0,
        instantLockPresent = true,
        chainLockHeight = 0
    )

    /** A FRESH key list per call: the pipeline zeroes each scalar after storing it. */
    private fun keys(count: Int) = List(count) { index ->
        IdentityKeyPreview(
            identityIndex = 0,
            derivationPath = "m/9'/1'/5'/0'/0'/0'/$index'",
            publicKey = ByteArray(33) { (index + 2).toByte() },
            privateKey = ByteArray(32) { (index + 1).toByte() }
        )
    }

    private val fourKeyPubHexes = keys(4).map { it.publicKeyHex }
    private val sixKeyPubHexes = keys(6).map { it.publicKeyHex }

    private val pauses = mutableListOf<Long>()
    private val creationStates = mutableListOf<IdentityCreationState>()

    /**
     * A source whose FIRST recovery query (the resume gate) finds nothing —
     * the fresh build runs — and whose later queries return [lockAfterFailure]
     * (the lock the fresh build committed before it failed to sign).
     */
    private fun source(lockAfterFailure: TrackedAssetLock?) = mockk<TransparentUsernameSource> {
        coEvery { boundWalletIdOrNull() } returns walletIdHex
        coEvery { managedIdentityCount(walletIdHex) } returns 0
        coEvery { unresolvedRegistrationAssetLock(walletIdHex, 0) } returnsMany listOf(null, lockAfterFailure)
        coEvery { previewRegistrationKeySet(walletIdHex, 0, true) } answers { keys(6) }
        coEvery { previewRegistrationKeySet(walletIdHex, 0, false) } answers { keys(4) }
        coEvery { storeIdentityPrivateKey(walletIdHex, any(), any()) } just Runs
        coEvery { registerWithWalletFunding(walletIdHex, feeDuffs, 0, 0, any()) } throws falseLocked
        coEvery { registrationAssetLockTxidDisplayHex(walletIdHex, 0) } returns null
        coEvery { registerDpnsName(walletIdHex, identityId, label) } returns "$label.dash"
    }

    private fun creation(source: TransparentUsernameSource) = SdkTransparentUsernameCreation(
        source = source,
        cutoverCommitted = { true },
        feeDuffs = { feeDuffs },
        handOffToLegacy = {},
        driveCreationState = { state, _ -> creationStates += state },
        pause = { pauses += it }
    )

    @Test
    fun `false-locked after the lock is tracked auto-resumes with the four-key set`() = runTest {
        val source = source(lockAfterFailure = lock)
        coEvery {
            source.resumeRegistrationFromAssetLock(walletIdHex, lock, 0, any())
        } returns identityId

        val result = creation(source).createUsernameTransparent(label)

        assertTrue("must be Broadcast, got $result", result is SdkWriteResult.Broadcast)
        val outcome = (result as SdkWriteResult.Broadcast).value
        assertEquals(identityIdBase58, outcome.identityIdBase58)
        assertEquals(ShieldedUsernameNameStatus.REGISTERED, outcome.nameStatus)
        // Exactly ONE fresh build — the recovery only ever resumes.
        coVerify(exactly = 1) { source.registerWithWalletFunding(any(), any(), any(), any(), any()) }
        // The resume re-persisted and signed with the base FOUR keys.
        coVerify(exactly = 1) {
            source.resumeRegistrationFromAssetLock(
                walletIdHex,
                lock,
                0,
                match { k -> k.map { it.publicKeyHex } == fourKeyPubHexes }
            )
        }
        coVerify(exactly = 1) { source.previewRegistrationKeySet(walletIdHex, 0, false) }
        fourKeyPubHexes.forEach { hex ->
            // Once for the fresh six-key persist, once for the resume persist.
            coVerify(exactly = 2) { source.storeIdentityPrivateKey(walletIdHex, hex, any()) }
        }
        (sixKeyPubHexes - fourKeyPubHexes.toSet()).forEach { hex ->
            coVerify(exactly = 1) { source.storeIdentityPrivateKey(walletIdHex, hex, any()) }
        }
        assertEquals(listOf(500L), pauses)
    }

    @Test
    fun `a second false-locked refusal is retried once more and then succeeds`() = runTest {
        val source = source(lockAfterFailure = lock)
        coEvery {
            source.resumeRegistrationFromAssetLock(walletIdHex, lock, 0, any())
        } throws falseLocked andThen identityId

        val result = creation(source).createUsernameTransparent(label)

        assertTrue("must be Broadcast, got $result", result is SdkWriteResult.Broadcast)
        coVerify(exactly = 2) { source.resumeRegistrationFromAssetLock(walletIdHex, lock, 0, any()) }
        coVerify(exactly = 1) { source.registerWithWalletFunding(any(), any(), any(), any(), any()) }
        assertEquals(listOf(500L, 1_500L), pauses)
    }

    @Test
    fun `false-locked with no tracked lock stays NotBroadcast as before`() = runTest {
        val source = source(lockAfterFailure = null)

        val result = creation(source).createUsernameTransparent(label)

        assertTrue("must be NotBroadcast, got $result", result is SdkWriteResult.NotBroadcast)
        val reason = (result as SdkWriteResult.NotBroadcast).reason
        assertTrue("classifier reason expected, got: $reason", reason.contains("considers the device locked"))
        coVerify(exactly = 0) { source.resumeRegistrationFromAssetLock(any(), any(), any(), any()) }
        coVerify(exactly = 2) { source.unresolvedRegistrationAssetLock(walletIdHex, 0) }
        assertTrue("no auto-resume back-off", pauses.isEmpty())
    }

    @Test
    fun `a genuinely locked device is not auto-retried`() = runTest {
        val source = source(lockAfterFailure = lock)
        coEvery { source.registerWithWalletFunding(walletIdHex, feeDuffs, 0, 0, any()) } throws genuinelyLocked

        val result = creation(source).createUsernameTransparent(label)

        coVerify(exactly = 0) { source.resumeRegistrationFromAssetLock(any(), any(), any(), any()) }
        coVerify(exactly = 0) { source.previewRegistrationKeySet(walletIdHex, 0, false) }
        assertTrue("no auto-resume back-off", pauses.isEmpty())
        // The lock is committed, so the result says so (resumable on the
        // next submit after unlock) instead of the classifier's "nothing spent".
        assertEquals(
            SdkTransparentUsernameCreation.REASON_FUNDS_LOCKED_RESUMABLE,
            (result as SdkWriteResult.NotBroadcast).reason
        )
    }

    @Test
    fun `a bare User-not-authenticated refusal is not auto-retried`() = runTest {
        val source = source(lockAfterFailure = lock)
        coEvery {
            source.registerWithWalletFunding(walletIdHex, feeDuffs, 0, 0, any())
        } throws RuntimeException("Generic Error: User not authenticated")

        val result = creation(source).createUsernameTransparent(label)

        coVerify(exactly = 0) { source.resumeRegistrationFromAssetLock(any(), any(), any(), any()) }
        assertTrue(pauses.isEmpty())
        assertEquals(
            SdkTransparentUsernameCreation.REASON_FUNDS_LOCKED_RESUMABLE,
            (result as SdkWriteResult.NotBroadcast).reason
        )
    }

    @Test
    fun `exhausted auto-resume with a tracked lock is resumable and the next submit resumes it`() = runTest {
        val source = source(lockAfterFailure = lock)
        coEvery { source.resumeRegistrationFromAssetLock(walletIdHex, lock, 0, any()) } throws falseLocked
        val creation = creation(source)

        val result = creation.createUsernameTransparent(label)

        // Bounded: exactly two automatic resumes, never a second fresh build.
        coVerify(exactly = 2) { source.resumeRegistrationFromAssetLock(walletIdHex, lock, 0, any()) }
        coVerify(exactly = 1) { source.registerWithWalletFunding(any(), any(), any(), any(), any()) }
        assertEquals(listOf(500L, 1_500L), pauses)
        assertTrue("re-submittable outcome expected, got $result", result is SdkWriteResult.NotBroadcast)
        assertEquals(
            SdkTransparentUsernameCreation.REASON_FUNDS_LOCKED_RESUMABLE,
            (result as SdkWriteResult.NotBroadcast).reason
        )
        assertTrue("the executor never resets the creation state itself", creationStates.isEmpty())
        assertFalse(IdentityCreationState.NONE in creationStates)

        // The next submit: the resume gate finds the same lock and resumes it.
        coEvery { source.unresolvedRegistrationAssetLock(walletIdHex, 0) } returns lock
        coEvery { source.resumeRegistrationFromAssetLock(walletIdHex, lock, 0, any()) } returns identityId

        val retry = creation.createUsernameTransparent(label)

        assertTrue("retry must be Broadcast, got $retry", retry is SdkWriteResult.Broadcast)
        coVerify(exactly = 1) { source.registerWithWalletFunding(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an ambiguous failure during auto-resume is Ambiguous`() = runTest {
        val source = source(lockAfterFailure = lock)
        coEvery {
            source.resumeRegistrationFromAssetLock(walletIdHex, lock, 0, any())
        } throws DashSdkError.NetworkError("connection reset")

        val result = creation(source).createUsernameTransparent(label)

        assertTrue("must be Ambiguous, got $result", result is SdkWriteResult.Ambiguous)
        coVerify(exactly = 1) { source.resumeRegistrationFromAssetLock(walletIdHex, lock, 0, any()) }
    }

    @Test
    fun `a false-locked refusal on the resume-gate path also auto-resumes`() = runTest {
        val source = source(lockAfterFailure = lock)
        coEvery { source.unresolvedRegistrationAssetLock(walletIdHex, 0) } returns lock
        coEvery {
            source.resumeRegistrationFromAssetLock(walletIdHex, lock, 0, any())
        } throws falseLocked andThen identityId

        val result = creation(source).createUsernameTransparent(label)

        assertTrue("must be Broadcast, got $result", result is SdkWriteResult.Broadcast)
        coVerify(exactly = 0) { source.registerWithWalletFunding(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { source.previewRegistrationKeySet(walletIdHex, 0, true) }
        coVerify(exactly = 2) { source.resumeRegistrationFromAssetLock(walletIdHex, lock, 0, any()) }
        assertEquals(listOf(500L), pauses)
    }

    // ── Fee ledger (log-only credit snapshots) ───────────────────────────

    /** A source whose fresh build succeeds outright (no Keystore refusal). */
    private fun happySource() = source(lockAfterFailure = null).also {
        coEvery { it.registerWithWalletFunding(walletIdHex, feeDuffs, 0, 0, any()) } returns identityId
    }

    @Test
    fun `fee ledger arithmetic matches the verified mainnet example`() {
        // 0.03 DASH asset lock → 3,000,000,000 credits; balance 2,693,390,540.
        val credited = IdentityFeeLedger.creditedCredits(3_000_000L)
        assertEquals(3_000_000_000L, credited)
        assertEquals(306_609_460L, IdentityFeeLedger.feeCredits(credited, 2_693_390_540L))
        assertEquals("0.0030660946", IdentityFeeLedger.creditsToDashString(306_609_460L))
        assertNull(IdentityFeeLedger.feeCredits(null, 2_693_390_540L))
        assertNull(IdentityFeeLedger.feeCredits(credited, null))

        val line = IdentityFeeLedger.format(
            step = IdentityFeeStep.IDENTITY_CREATE,
            identityIdBase58 = "5zZxxQEwZ7awvRTNzi9dwh4DRkCFY9fVVxbRhuaqwbjm",
            start = credited,
            after = 2_693_390_540L,
            startSource = "assetLock"
        )
        assertEquals(
            "identity fee ledger: step=IDENTITY_CREATE identity=5zZxxQEw… credited=3000000000 (assetLock) " +
                "balanceAfter=2693390540 fee=306609460 credits (≈0.0030660946 DASH)",
            line
        )
    }

    @Test
    fun `fee ledger line marks unknown and possibly-stale figures`() {
        val unknown = IdentityFeeLedger.format(IdentityFeeStep.DPNS, identityIdBase58, 100L, null, label = label)
        assertTrue(unknown, unknown.contains("step=DPNS"))
        assertTrue(unknown, unknown.contains("label=$label"))
        assertTrue(unknown, unknown.contains("balanceBefore=100 balanceAfter=unknown fee=unknown"))

        // A read that still shows the pre-write balance (lagging node).
        val stale = IdentityFeeLedger.format(IdentityFeeStep.CONTACT_REQUEST, identityIdBase58, 100L, 100L)
        assertTrue(stale, stale.contains("fee=0 credits (≈0 DASH) possiblyStale"))
    }

    @Test
    fun `fee ledger balance read swallows failures, nulls and timeouts`() = runBlocking {
        val step = IdentityFeeStep.DPNS
        assertEquals(42L, IdentityFeeLedger.readBalanceOrNull(step, identityIdBase58) { 42L })
        assertNull(IdentityFeeLedger.readBalanceOrNull(step, identityIdBase58) { null })
        assertNull(IdentityFeeLedger.readBalanceOrNull(step, identityIdBase58) { error("DAPI down") })
        assertNull(
            IdentityFeeLedger.readBalanceOrNull(step, identityIdBase58, timeoutMs = 50L) {
                CompletableDeferred<Long?>().await()
            }
        )
    }

    @Test
    fun `fee ledger balance read rethrows the caller's cancellation`() = runBlocking {
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            IdentityFeeLedger.readBalanceOrNull(IdentityFeeStep.DPNS, identityIdBase58, timeoutMs = 60_000L) {
                CompletableDeferred<Long?>().await()
            }
        }
        read.cancel()
        read.join()
        assertTrue("cancellation must propagate, not become a null read", read.isCancelled)
    }

    @Test
    fun `balance snapshots are read after identity create and after each DPNS step`() = runBlocking {
        val source = happySource()
        val secondary = "bob3"
        coEvery { source.registerDpnsName(walletIdHex, identityId, secondary) } returns "$secondary.dash"
        coEvery { source.registrationAssetLockAmountDuffs(walletIdHex, 0) } returns feeDuffs
        coEvery { source.identityCreditBalance(identityIdBase58) } returnsMany
            listOf(2_700_000_000L, 2_690_000_000L, 2_680_000_000L)

        val result = creation(source).createUsernameTransparent(label, secondary)

        val outcome = (result as SdkWriteResult.Broadcast).value
        assertEquals(ShieldedUsernameNameStatus.REGISTERED, outcome.nameStatus)
        assertEquals(ShieldedUsernameNameStatus.REGISTERED, outcome.secondaryNameStatus)
        // One post-create read, then one post-step read per DPNS name — the
        // previous step's "after" is the next step's "before".
        coVerify(exactly = 3) { source.identityCreditBalance(identityIdBase58) }
        coVerify(exactly = 1) { source.registrationAssetLockAmountDuffs(walletIdHex, 0) }
    }

    @Test
    fun `failing fee ledger reads never change the creation outcome`() = runBlocking {
        val source = happySource()
        coEvery { source.registrationAssetLockAmountDuffs(walletIdHex, 0) } throws IllegalStateException("no db")
        coEvery { source.identityCreditBalance(identityIdBase58) } throws DashSdkError.NetworkError("DAPI down")

        val result = creation(source).createUsernameTransparent(label)

        assertEquals(
            SdkWriteResult.Broadcast(
                ShieldedUsernameCreationOutcome(
                    identityIdBase58 = identityIdBase58,
                    nameStatus = ShieldedUsernameNameStatus.REGISTERED,
                    nameFailureReason = null,
                    secondaryNameStatus = null,
                    secondaryNameFailureReason = null
                )
            ),
            result
        )
        assertEquals(
            listOf(
                IdentityCreationState.PREORDER_REGISTERING,
                IdentityCreationState.USERNAME_REGISTERING,
                IdentityCreationState.USERNAME_REGISTERED,
                IdentityCreationState.DONE
            ),
            creationStates
        )
        coVerify(exactly = 1) { source.registerDpnsName(walletIdHex, identityId, label) }
    }

    @Test
    fun `a hanging fee ledger read is abandoned and does not change the outcome`() = runBlocking {
        val source = happySource()
        coEvery { source.registrationAssetLockAmountDuffs(walletIdHex, 0) } returns feeDuffs
        coEvery { source.identityCreditBalance(identityIdBase58) } coAnswers { CompletableDeferred<Long?>().await() }
        val creation = creation(source).apply { feeLedgerReadTimeoutMs = 50L }

        val result = creation.createUsernameTransparent(label)

        val outcome = (result as SdkWriteResult.Broadcast).value
        assertEquals(ShieldedUsernameNameStatus.REGISTERED, outcome.nameStatus)
        assertEquals(IdentityCreationState.DONE, creationStates.last())
    }

    @Test
    fun `a pre-broadcast DPNS rejection skips the post-step balance read`() = runBlocking {
        val source = happySource()
        coEvery { source.registrationAssetLockAmountDuffs(walletIdHex, 0) } returns feeDuffs
        coEvery { source.identityCreditBalance(identityIdBase58) } returns 2_700_000_000L
        coEvery { source.registerDpnsName(walletIdHex, identityId, label) } throws
            DashSdkError.InvalidParameter("label taken")

        val result = creation(source).createUsernameTransparent(label)

        val outcome = (result as SdkWriteResult.Broadcast).value
        assertEquals(ShieldedUsernameNameStatus.NOT_REGISTERED, outcome.nameStatus)
        // Only the post-create snapshot — nothing was spent on the name.
        coVerify(exactly = 1) { source.identityCreditBalance(identityIdBase58) }
    }

    @Test
    fun `existing-identity DPNS re-drive snapshots around the registration without changing the result`() =
        runBlocking {
            val source = happySource()
            coEvery { source.identityCreditBalance(identityIdBase58) } returnsMany
                listOf(2_700_000_000L, 2_690_000_000L)

            val result = creation(source).registerDpnsNameForExistingIdentity(identityIdBase58, label)

            assertEquals(SdkWriteResult.Broadcast("$label.dash"), result)
            coVerify(exactly = 2) { source.identityCreditBalance(identityIdBase58) }

            // Failing reads: identical result.
            coEvery { source.identityCreditBalance(identityIdBase58) } throws IllegalStateException("boom")
            assertEquals(
                SdkWriteResult.Broadcast("$label.dash"),
                creation(source).registerDpnsNameForExistingIdentity(identityIdBase58, label)
            )
        }
}
