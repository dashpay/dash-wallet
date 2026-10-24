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

import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.entity.IdentityCreationState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashfoundation.dashsdk.identity.IdentityKeyPreview
import org.dashfoundation.dashsdk.wallet.TrackedAssetLock
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the transparent (post-cutover) username creation's
 * funding step:
 *
 * - the MO-1023 false-locked Keystore handling: a signing refusal that lands
 *   AFTER the SDK built (and broadcast) the asset lock must resume that lock,
 *   never report "nothing spent" while the funds are committed, and never
 *   spin on a genuinely locked device;
 * - [transparentUsernameFeeDuffs] — the exact `feeDuffs` lambda
 *   [SdkTransparentUsernameCreation]'s `@Inject` constructor wires — pinning
 *   the MO-1069 protocol-version gate: a wallet funded with the Platform v4.2
 *   (0.15 DASH) contested fee while the network still runs protocol 13 fails
 *   DPNS registration with "Insufficient identity balance" (the network still
 *   needs ~0.2 DASH of prefunded voting balance, i.e. the 0.25 DASH total
 *   LEGACY fee) — reproduced live before this fix routed the funding amount
 *   through [ContestedUsernameFees] instead of the raw, un-gated
 *   `Constants.DASH_PAY_FEE_CONTESTED` constant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    private fun creation(
        source: TransparentUsernameSource,
        fence: WalletSecretWriteFence = WalletSecretWriteFence(),
        executorScope: CoroutineScope? = null,
        pause: suspend (Long) -> Unit = { pauses += it }
    ) = SdkTransparentUsernameCreation(
        source = source,
        cutoverCommitted = { true },
        feeDuffs = { feeDuffs },
        handOffToLegacy = {},
        driveCreationState = { state, _ -> creationStates += state },
        executorScope = executorScope,
        pause = pause,
        secretWriteFence = fence
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
    fun `a throwing tracked-lock recheck after a signing refusal reports funding unresolved, not pre-broadcast`() =
        runTest {
            val source = source(lockAfterFailure = null)
            // Resume gate SUCCEEDS (no lock → fresh build), the fresh build's
            // signing is refused, then the recovery re-check THROWS.
            var lookups = 0
            coEvery { source.unresolvedRegistrationAssetLock(walletIdHex, 0) } answers {
                if (++lookups == 1) null else throw IllegalStateException("asset-lock store unavailable")
            }
            coEvery { source.registerWithWalletFunding(walletIdHex, feeDuffs, 0, 0, any()) } throws genuinelyLocked
            val creation = creation(source)

            val result = creation.createUsernameTransparent(label)

            assertTrue("re-submittable outcome expected, got $result", result is SdkWriteResult.NotBroadcast)
            val reason = (result as SdkWriteResult.NotBroadcast).reason
            assertEquals(SdkTransparentUsernameCreation.REASON_FUNDING_UNRESOLVED_RESUMABLE, reason)
            assertFalse("never the pre-broadcast classifier", reason.contains("considers the device locked"))
            assertTrue(SdkTransparentUsernameCreation.isFundsPossiblyCommittedReason(reason))
            coVerify(exactly = 2) { source.unresolvedRegistrationAssetLock(walletIdHex, 0) }
            coVerify(exactly = 0) { source.resumeRegistrationFromAssetLock(any(), any(), any(), any()) }
            assertTrue("no auto-resume back-off", pauses.isEmpty())

            // The next submit: the resume gate finds the lock the failed
            // attempt had committed and resumes it — no second fresh payment.
            coEvery { source.unresolvedRegistrationAssetLock(walletIdHex, 0) } returns lock
            coEvery { source.resumeRegistrationFromAssetLock(walletIdHex, lock, 0, any()) } returns identityId

            val retry = creation.createUsernameTransparent(label)

            assertTrue("retry must be Broadcast, got $retry", retry is SdkWriteResult.Broadcast)
            coVerify(exactly = 1) { source.registerWithWalletFunding(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a throwing recheck after a refused resume reports the funds locked`() = runTest {
        val source = source(lockAfterFailure = null)
        var lookups = 0
        coEvery { source.unresolvedRegistrationAssetLock(walletIdHex, 0) } answers {
            if (++lookups == 1) lock else throw IllegalStateException("asset-lock store unavailable")
        }
        coEvery { source.resumeRegistrationFromAssetLock(walletIdHex, lock, 0, any()) } throws genuinelyLocked

        val result = creation(source).createUsernameTransparent(label)

        assertEquals(
            SdkTransparentUsernameCreation.REASON_FUNDS_LOCKED_RESUMABLE,
            (result as SdkWriteResult.NotBroadcast).reason
        )
        coVerify(exactly = 0) { source.registerWithWalletFunding(any(), any(), any(), any(), any()) }
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

    // ── Reset Wallet fence ───────────────────────────────────────────────

    private fun assertWipeRefusal(result: SdkWriteResult<*>) {
        assertTrue("must be NotBroadcast, got $result", result is SdkWriteResult.NotBroadcast)
        assertEquals(REASON_WALLET_WIPE_IN_PROGRESS, (result as SdkWriteResult.NotBroadcast).reason)
    }

    @Test
    fun `a raised fence refuses before any key is persisted or funded`() = runTest {
        val source = source(lockAfterFailure = null)
        val fence = WalletSecretWriteFence()
        // The wipe starts while the key set is being derived.
        coEvery { source.previewRegistrationKeySet(walletIdHex, 0, true) } answers {
            fence.raise()
            keys(6)
        }

        val result = creation(source, fence).createUsernameTransparent(label)

        assertWipeRefusal(result)
        coVerify(exactly = 0) { source.storeIdentityPrivateKey(any(), any(), any()) }
        coVerify(exactly = 0) { source.registerWithWalletFunding(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { source.resumeRegistrationFromAssetLock(any(), any(), any(), any()) }
    }

    @Test
    fun `a fence raised during the key persist stops before the funding call`() = runTest {
        val source = source(lockAfterFailure = null)
        val fence = WalletSecretWriteFence()
        coEvery { source.storeIdentityPrivateKey(walletIdHex, any(), any()) } answers { fence.raise() }

        val result = creation(source, fence).createUsernameTransparent(label)

        assertWipeRefusal(result)
        coVerify(exactly = 0) { source.registerWithWalletFunding(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { source.resumeRegistrationFromAssetLock(any(), any(), any(), any()) }
    }

    @Test
    fun `a fence raised during the auto-resume back-off stops the resume and leaves the lock tracked`() = runTest {
        val source = source(lockAfterFailure = lock)
        val fence = WalletSecretWriteFence()

        val result = creation(source, fence, pause = { fence.raise() }).createUsernameTransparent(label)

        // The lock is built: the result is the existing "resumable" one, not a
        // claim that nothing was spent.
        assertEquals(
            SdkTransparentUsernameCreation.REASON_FUNDS_LOCKED_RESUMABLE,
            (result as SdkWriteResult.NotBroadcast).reason
        )
        coVerify(exactly = 0) { source.resumeRegistrationFromAssetLock(any(), any(), any(), any()) }
        coVerify(exactly = 0) { source.previewRegistrationKeySet(walletIdHex, 0, false) }
        // Only the original six-key persist — no re-persist for the resume.
        coVerify(exactly = 6) { source.storeIdentityPrivateKey(walletIdHex, any(), any()) }
    }

    @Test
    fun `a wipe cancels and joins a submit suspended in the auto-resume back-off`() = runBlocking {
        val source = source(lockAfterFailure = lock)
        val fence = WalletSecretWriteFence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inBackOff = CompletableDeferred<Unit>()
        val creation = creation(source, fence, executorScope = scope, pause = {
            inBackOff.complete(Unit)
            awaitCancellation()
        }).also { it.ioDispatcher = Dispatchers.Default }

        assertTrue(creation.submit(label))
        inBackOff.await()

        assertTrue("the fence must hold", fence.raiseAndQuiesce(5_000L))

        coVerify(exactly = 0) { source.resumeRegistrationFromAssetLock(any(), any(), any(), any()) }
        coVerify(exactly = 6) { source.storeIdentityPrivateKey(walletIdHex, any(), any()) }
        // The single-flight slot is released (in memory) for the next wallet…
        assertEquals(
            ShieldedUsernameSubmitState.Idle,
            creation.submitState.first { it == ShieldedUsernameSubmitState.Idle }
        )
        // …but nothing new can start until the wipe completes.
        assertFalse(creation.submit(label))
        fence.lower()
        scope.cancel()
    }

    // ── name-only retry for an existing identity ─────────────────────────

    private class RetryRun {
        var accepted = false
        val states = mutableListOf<Pair<IdentityCreationState, String?>>()
        val handedOff = mutableListOf<String>()
        val nameContexts = mutableListOf<List<Any?>>()
        val uncaught = mutableListOf<Throwable>()
    }

    /**
     * A name-only retry for the existing identity, run on an app-like scope (no exception handler
     * of its own: anything escaping is collected in [RetryRun.uncaught]).
     */
    private suspend fun TestScope.retry(
        source: TransparentUsernameSource,
        username: String,
        secondary: String?,
        nameAlreadyTaken: suspend (String, String) -> Boolean = { _, _ -> false },
        persistNameContext: (suspend (String, String, String?, Boolean) -> Unit)? = null,
        run: RetryRun = RetryRun()
    ): RetryRun {
        val appScope = CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) +
                kotlinx.coroutines.CoroutineExceptionHandler { _, e -> run.uncaught += e }
        )
        val creation = SdkTransparentUsernameCreation(
            source = source,
            cutoverCommitted = { true },
            feeDuffs = { feeDuffs },
            handOffToLegacy = { run.handedOff += it },
            driveCreationState = { state, error -> run.states += state to error },
            persistNameContext = persistNameContext ?: { id, name, second, restoring ->
                run.nameContexts += listOf(id, name, second, restoring)
            },
            executorScope = appScope,
            nameAlreadyTaken = nameAlreadyTaken
        ).also { it.ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) }
        run.accepted = creation.retryNameForExistingIdentity(identityIdBase58, username, secondary)
        advanceUntilIdle()
        return run
    }

    private fun neverFunded(source: TransparentUsernameSource) {
        coVerify(exactly = 0) { source.registerWithWalletFunding(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { source.resumeRegistrationFromAssetLock(any(), any(), any(), any()) }
        coVerify(exactly = 0) { source.previewRegistrationKeySet(any(), any(), any()) }
    }

    @Test
    fun `a name-only retry registers the replacement primary for the existing identity`() = runTest {
        // the identity owns a registered secondary; the lost name is replaced by "alice3", which
        // becomes the primary while the secondary stays as it is (no secondary in this request)
        val source = source(lockAfterFailure = null)
        coEvery { source.registerDpnsName(walletIdHex, identityId, "alice3") } returns "alice3.dash"

        val run = retry(source, "alice3", null)

        assertTrue("nothing escapes: ${run.uncaught}", run.uncaught.isEmpty())
        coVerify(exactly = 1) { source.registerDpnsName(walletIdHex, identityId, "alice3") }
        assertEquals(
            "registered like the create path's primary, then DONE for a non-contested name",
            listOf(
                IdentityCreationState.PREORDER_REGISTERING,
                IdentityCreationState.USERNAME_REGISTERING,
                IdentityCreationState.USERNAME_REGISTERED,
                IdentityCreationState.DONE
            ),
            run.states.map { it.first }
        )
        assertTrue("no error left behind: ${run.states}", run.states.all { it.second == null })
        assertEquals("the record's secondary is left alone", listOf(identityIdBase58, "alice3", null, false), run.nameContexts.first())
        assertEquals("handed to recovery, like the create path", listOf(identityIdBase58), run.handedOff)
        neverFunded(source)
    }

    @Test
    fun `a newly requested secondary is registered once the replacement primary is confirmed`() = runTest {
        val source = source(lockAfterFailure = null)
        coEvery { source.registerDpnsName(walletIdHex, identityId, "alice3") } returns "alice3.dash"
        coEvery { source.registerDpnsName(walletIdHex, identityId, "alice4") } returns "alice4.dash"

        val run = retry(source, "alice3", "alice4")

        coVerifyOrder {
            source.registerDpnsName(walletIdHex, identityId, "alice3")
            source.registerDpnsName(walletIdHex, identityId, "alice4")
        }
        assertTrue(
            "the secondary's own states: ${run.states}",
            run.states.map { it.first }.containsAll(
                listOf(
                    IdentityCreationState.PREORDER_SECONDARY_REGISTERING,
                    IdentityCreationState.USERNAME_SECONDARY_REGISTERING,
                    IdentityCreationState.USERNAME_SECONDARY_REGISTERED
                )
            )
        )
        assertEquals(listOf(identityIdBase58), run.handedOff)
        neverFunded(source)
    }

    @Test
    fun `a label the identity already owns is not submitted again`() = runTest {
        // an earlier Ambiguous attempt landed: the name is on chain already
        val source = source(lockAfterFailure = null)

        val run = retry(source, "alice3", null, nameAlreadyTaken = { _, label -> label == "alice3" })

        coVerify(exactly = 0) { source.registerDpnsName(any(), any(), "alice3") }
        assertEquals(IdentityCreationState.DONE, run.states.last().first)
        assertEquals(listOf(identityIdBase58), run.handedOff)
    }

    @Test
    fun `an unreadable owned-names lookup refuses the retry before accepting it, touching nothing`() = runTest {
        // fail closed, and synchronously: the screen shows its error; the record is not touched,
        // so no stamped error can send a later "Try again" down the legacy path
        val source = source(lockAfterFailure = null)

        val run = retry(
            source,
            "alice3",
            null,
            nameAlreadyTaken = { _, _ -> throw java.io.IOException("DAPI unreachable") }
        )

        assertFalse("refused", run.accepted)
        assertTrue("nothing escapes: ${run.uncaught}", run.uncaught.isEmpty())
        assertTrue("no state touched: ${run.states}", run.states.isEmpty())
        assertTrue("no name context written: ${run.nameContexts}", run.nameContexts.isEmpty())
        coVerify(exactly = 0) { source.registerDpnsName(any(), any(), any()) }
        neverFunded(source)
    }

    @Test
    fun `the wallet-id read and the name-ownership checks run on the io dispatcher, not the caller's`() = runTest {
        // the caller is a viewModelScope (Main): the SDK cold start and the platform queries
        // must not run there
        val source = source(lockAfterFailure = null)
        coEvery { source.registerDpnsName(walletIdHex, identityId, any()) } returns "alice3.dash"
        val io = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "test-io") }
        val ioDispatcher = io.asCoroutineDispatcher()
        val checkedOn = mutableListOf<String>()
        val walletIdReadOn = mutableListOf<String>()
        coEvery { source.boundWalletIdOrNull() } answers {
            synchronized(walletIdReadOn) { walletIdReadOn += Thread.currentThread().name }
            walletIdHex
        }
        val appScope = CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        )
        val creation = SdkTransparentUsernameCreation(
            source = source,
            cutoverCommitted = { true },
            feeDuffs = { feeDuffs },
            handOffToLegacy = {},
            driveCreationState = { _, _ -> },
            persistNameContext = { _, _, _, _ -> },
            executorScope = appScope,
            nameAlreadyTaken = { _, _ ->
                synchronized(checkedOn) { checkedOn += Thread.currentThread().name }
                false
            }
        ).also { it.ioDispatcher = ioDispatcher }

        try {
            assertTrue(creation.retryNameForExistingIdentity(identityIdBase58, "alice3", "alice4"))
            assertEquals(listOf("test-io", "test-io"), synchronized(checkedOn) { checkedOn.toList() })
            // the launched retry may read it again on its own scope; the pre-accept read is first
            assertEquals("test-io", synchronized(walletIdReadOn) { walletIdReadOn.firstOrNull() })
        } finally {
            appScope.cancel()
            io.shutdown()
        }
    }

    @Test
    fun `a second retry, or a creation, while a name-only retry runs is refused`() = runTest {
        // B2: a double tap must not register the same label twice (a contested prefund twice)
        val source = source(lockAfterFailure = null)
        coEvery { source.registerDpnsName(walletIdHex, identityId, "alice3") } returns "alice3.dash"
        val gate = CompletableDeferred<Unit>()
        val appScope = CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        )
        val creation = SdkTransparentUsernameCreation(
            source = source,
            cutoverCommitted = { true },
            feeDuffs = { feeDuffs },
            handOffToLegacy = {},
            driveCreationState = { _, _ -> },
            // the first run is still writing its name context
            persistNameContext = { _, _, _, _ -> gate.await() },
            executorScope = appScope
        ).also { it.ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) }

        assertTrue("the first retry is accepted", creation.retryNameForExistingIdentity(identityIdBase58, "alice3", null))
        advanceUntilIdle()
        assertFalse("a second retry is refused", creation.retryNameForExistingIdentity(identityIdBase58, "alice3", null))
        assertFalse("a creation is refused", creation.submit("bob5"))

        gate.complete(Unit)
        advanceUntilIdle()
        coVerify(exactly = 1) { source.registerDpnsName(walletIdHex, identityId, "alice3") }
        assertTrue(
            "free again once the run is over",
            creation.retryNameForExistingIdentity(identityIdBase58, "alice3", null)
        )
        appScope.cancel()
    }

    /** A fence a wipe raises inside [WalletSecretWriteFence.track] (before or after the real call). */
    private fun fenceRaisedOnTrack(afterTrack: Boolean, armed: () -> Boolean): WalletSecretWriteFence {
        val fence = spyk(WalletSecretWriteFence())
        every { fence.track(any()) } answers {
            if (armed() && !afterTrack) fence.raise()
            callOriginal()
            if (armed() && afterTrack) fence.raise()
        }
        return fence
    }

    @Test
    fun `a wipe raising the fence while the name-only retry is tracked refuses it and frees the slot`() = runTest {
        for (afterTrack in listOf(false, true)) {
            val source = source(lockAfterFailure = null)
            coEvery { source.registerDpnsName(walletIdHex, identityId, "alice3") } returns "alice3.dash"
            var armed = true
            val fence = fenceRaisedOnTrack(afterTrack) { armed }
            val appScope = CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
            )
            val persisted = mutableListOf<String>()
            val creation = SdkTransparentUsernameCreation(
                source = source,
                cutoverCommitted = { true },
                feeDuffs = { feeDuffs },
                handOffToLegacy = {},
                driveCreationState = { _, _ -> },
                persistNameContext = { _, name, _, _ -> persisted += name },
                executorScope = appScope,
                secretWriteFence = fence
            ).also { it.ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) }

            assertFalse(
                "refused, not reported as started (afterTrack=$afterTrack)",
                creation.retryNameForExistingIdentity(identityIdBase58, "alice3", null)
            )
            advanceUntilIdle()
            assertTrue("the cancelled run never ran", persisted.isEmpty())
            coVerify(exactly = 0) { source.registerDpnsName(any(), any(), any()) }

            // the single-flight slot is free: once the wipe completes, the next retry is accepted
            armed = false
            fence.lower()
            assertTrue(
                "slot released (afterTrack=$afterTrack)",
                creation.retryNameForExistingIdentity(identityIdBase58, "alice3", null)
            )
            advanceUntilIdle()
            coVerify(exactly = 1) { source.registerDpnsName(walletIdHex, identityId, "alice3") }
            appScope.cancel()
        }
    }

    @Test
    fun `a wipe raising the fence while a creation is tracked refuses it and frees the slot`() = runTest {
        for (afterTrack in listOf(false, true)) {
            var armed = true
            val fence = fenceRaisedOnTrack(afterTrack) { armed }
            val appScope = CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
            )
            val creation = creation(source(lockAfterFailure = null), fence, executorScope = appScope)

            assertFalse("refused, not reported as started (afterTrack=$afterTrack)", creation.submit(label))
            assertEquals(ShieldedUsernameSubmitState.Idle, creation.submitState.value)

            armed = false
            fence.lower()
            assertTrue("slot released (afterTrack=$afterTrack)", creation.submit(label))
            appScope.cancel()
        }
    }

    @Test
    fun `a wipe between the track and the start refuses the name-only retry and frees the slot once`() = runTest {
        val source = source(lockAfterFailure = null)
        coEvery { source.registerDpnsName(walletIdHex, identityId, "alice3") } returns "alice3.dash"
        val fence = WalletSecretWriteFence()
        val appScope = CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        )
        val gate = CompletableDeferred<Unit>()
        var persistCalls = 0
        val creation = SdkTransparentUsernameCreation(
            source = source,
            cutoverCommitted = { true },
            feeDuffs = { feeDuffs },
            handOffToLegacy = {},
            driveCreationState = { _, _ -> },
            // the second (accepted) run holds the slot until the gate opens
            persistNameContext = { _, _, _, _ ->
                persistCalls++
                gate.await()
            },
            executorScope = appScope,
            secretWriteFence = fence
        ).also { it.ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) }
        // the wipe starts after the job passed the fence check and before it is started
        creation.beforeStartForTest = { runBlocking { assertTrue(fence.raiseAndQuiesce(1_000L)) } }

        assertFalse("refused, not reported as started", creation.retryNameForExistingIdentity(identityIdBase58, "alice3", null))
        advanceUntilIdle()
        assertEquals("the cancelled run never ran", 0, persistCalls)

        creation.beforeStartForTest = {}
        fence.lower()
        assertTrue("slot released", creation.retryNameForExistingIdentity(identityIdBase58, "alice3", null))
        advanceUntilIdle()
        assertFalse(
            "the refused run's release did not free the running retry's slot",
            creation.retryNameForExistingIdentity(identityIdBase58, "alice3", null)
        )
        assertFalse("nor a creation's", creation.submit(label))

        gate.complete(Unit)
        advanceUntilIdle()
        coVerify(exactly = 1) { source.registerDpnsName(walletIdHex, identityId, "alice3") }
        appScope.cancel()
    }

    @Test
    fun `a wipe between the track and the start refuses a creation and frees the slot`() = runTest {
        val fence = WalletSecretWriteFence()
        val appScope = CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        )
        val source = source(lockAfterFailure = null)
        val creation = creation(source, fence, executorScope = appScope)
        creation.beforeStartForTest = { runBlocking { assertTrue(fence.raiseAndQuiesce(1_000L)) } }

        assertFalse("refused, not reported as started", creation.submit(label))
        advanceUntilIdle()
        assertEquals(ShieldedUsernameSubmitState.Idle, creation.submitState.value)
        neverFunded(source)

        creation.beforeStartForTest = {}
        fence.lower()
        assertTrue("slot released", creation.submit(label))
        appScope.cancel()
    }

    @Test
    fun `a name that became the identity's after the preflight is not submitted`() = runTest {
        // the preflight read it as free; by the time it is registered an earlier Ambiguous attempt
        // has landed, so the re-check right before the submission skips it
        val source = source(lockAfterFailure = null)
        var reads = 0
        val run = retry(source, "alice3", null, nameAlreadyTaken = { _, _ -> reads++ > 0 })

        assertTrue(run.accepted)
        assertEquals("the preflight and the re-check", 2, reads)
        coVerify(exactly = 0) { source.registerDpnsName(any(), any(), any()) }
        assertEquals(IdentityCreationState.DONE, run.states.last().first)
        assertTrue("no error: ${run.states}", run.states.all { it.second == null })
        assertEquals(listOf(identityIdBase58), run.handedOff)
        neverFunded(source)
    }

    @Test
    fun `an unreadable re-check fails closed without registering the name`() = runTest {
        val source = source(lockAfterFailure = null)
        var reads = 0
        val run = retry(
            source,
            "alice3",
            null,
            nameAlreadyTaken = { _, _ -> if (reads++ == 0) false else throw java.io.IOException("DAPI unreachable") }
        )

        assertTrue("accepted on the preflight", run.accepted)
        assertTrue("nothing escapes: ${run.uncaught}", run.uncaught.isEmpty())
        coVerify(exactly = 0) { source.registerDpnsName(any(), any(), any()) }
        val error = run.states.last().second
        assertTrue("stamped for the name-only route: $error", SdkTransparentUsernameCreation.isNameOnlyRetryError(error))
        assertTrue("nothing handed off", run.handedOff.isEmpty())
        neverFunded(source)
    }

    @Test
    fun `a failed replacement stays on the name-only route and a tile retry registers it`() = runTest {
        // review 4301924854: the failure used to set restoring, which sent the tile retry and the
        // background discovery into the restore worker; that dropped the replacement
        val source = source(lockAfterFailure = null)
        coEvery { source.registerDpnsName(walletIdHex, identityId, "alice3") } throws
            DashSdkError.InvalidParameter("domain document rejected")

        val failed = retry(source, "alice3", null)

        assertTrue(failed.accepted)
        assertTrue("never routed to the restore worker: ${failed.nameContexts}", failed.nameContexts.all { it[3] == false })
        assertTrue("nothing handed off", failed.handedOff.isEmpty())
        val error = failed.states.last().second
        assertTrue("an error the tile sends back to the name-only retry: $error", SdkTransparentUsernameCreation.isNameOnlyRetryError(error))

        // the tile's retry re-enters the name-only retry with the persisted labels
        coEvery { source.registerDpnsName(walletIdHex, identityId, "alice3") } returns "alice3.dash"
        val retried = retry(source, "alice3", null)

        assertTrue(retried.accepted)
        assertEquals(IdentityCreationState.DONE, retried.states.last().first)
        assertEquals(listOf(identityIdBase58), retried.handedOff)
        neverFunded(source)
    }

    @Test
    fun `an ambiguous replacement is retried by the tile without being submitted again once it landed`() = runTest {
        val source = source(lockAfterFailure = null)
        coEvery { source.registerDpnsName(walletIdHex, identityId, "alice3") } throws RuntimeException("timeout")

        val ambiguous = retry(source, "alice3", null)

        assertTrue(
            "the unknown outcome is stamped for the name-only route: ${ambiguous.states}",
            SdkTransparentUsernameCreation.isNameOnlyRetryError(ambiguous.states.last().second)
        )
        assertTrue(ambiguous.nameContexts.all { it[3] == false })

        // it had landed: the re-entry finds it owned and does not submit it again
        val retried = retry(source, "alice3", null, nameAlreadyTaken = { _, label -> label == "alice3" })

        coVerify(exactly = 1) { source.registerDpnsName(walletIdHex, identityId, "alice3") }
        assertEquals(IdentityCreationState.DONE, retried.states.last().first)
        assertEquals(listOf(identityIdBase58), retried.handedOff)
    }

    // ── per-identity DPNS lock ───────────────────────────────────────────

    /** A source whose registerDpnsName blocks on [gate], then records the label as owned. */
    private fun gatedSource(gate: CompletableDeferred<Unit>, owned: MutableSet<String>) =
        source(lockAfterFailure = null).also { source ->
            coEvery { source.identityCreditBalance(identityIdBase58) } returns null
            coEvery { source.registerDpnsName(walletIdHex, identityId, any()) } coAnswers {
                gate.await()
                val name = thirdArg<String>()
                owned += name
                "$name.dash"
            }
        }

    @Test
    fun `two concurrent registrations of one name for one identity submit it once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val owned = mutableSetOf<String>()
        val source = gatedSource(gate, owned)
        val creation = SdkTransparentUsernameCreation(
            source = source,
            cutoverCommitted = { true },
            feeDuffs = { feeDuffs },
            handOffToLegacy = {},
            nameAlreadyTaken = { id, name -> id == identityIdBase58 && name in owned }
        ).also { it.ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) }

        val first = async { creation.registerDpnsNameForExistingIdentity(identityIdBase58, "alice3") }
        val second = async { creation.registerDpnsNameForExistingIdentity(identityIdBase58, "alice3") }
        advanceUntilIdle()
        // the first is submitting under the lock; the second waits for it, not yet re-checked
        coVerify(exactly = 1) { source.registerDpnsName(walletIdHex, identityId, "alice3") }
        assertFalse(second.isCompleted)

        gate.complete(Unit)

        assertEquals(SdkWriteResult.Broadcast("alice3.dash"), first.await())
        assertEquals(
            "the second re-checks under the lock, finds the name owned and skips it",
            SdkWriteResult.Broadcast("alice3.dash"),
            second.await()
        )
        coVerify(exactly = 1) { source.registerDpnsName(walletIdHex, identityId, "alice3") }
    }

    @Test
    fun `a name-only retry waiting on another registration of its name does not submit it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val owned = mutableSetOf<String>()
        val source = gatedSource(gate, owned)
        val states = mutableListOf<IdentityCreationState>()
        val appScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val creation = SdkTransparentUsernameCreation(
            source = source,
            cutoverCommitted = { true },
            feeDuffs = { feeDuffs },
            handOffToLegacy = {},
            driveCreationState = { state, _ -> states += state },
            executorScope = appScope,
            nameAlreadyTaken = { id, name -> id == identityIdBase58 && name in owned }
        ).also { it.ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) }

        // e.g. the restore worker re-driving the same name for the same identity
        val worker = async { creation.registerDpnsNameForExistingIdentity(identityIdBase58, "alice3") }
        advanceUntilIdle()
        // its preflight ran before the worker's name landed, so it accepts
        assertTrue(creation.retryNameForExistingIdentity(identityIdBase58, "alice3", null))
        advanceUntilIdle()
        coVerify(exactly = 1) { source.registerDpnsName(walletIdHex, identityId, "alice3") }

        gate.complete(Unit)
        assertEquals(SdkWriteResult.Broadcast("alice3.dash"), worker.await())
        advanceUntilIdle()

        coVerify(exactly = 1) { source.registerDpnsName(walletIdHex, identityId, "alice3") }
        assertEquals(
            "the retry re-checked under the lock and counted the name as registered",
            IdentityCreationState.DONE,
            states.last()
        )
        appScope.cancel()
    }

    @Test
    fun `a failure inside the accepted run is stamped for the name-only route`() = runTest {
        val source = source(lockAfterFailure = null)
        val run = RetryRun()

        retry(
            source,
            "alice3",
            null,
            persistNameContext = { _, _, _, _ -> throw java.io.IOException("datastore write failed") },
            run = run
        )

        assertTrue("nothing escapes: ${run.uncaught}", run.uncaught.isEmpty())
        val error = run.states.last().second
        assertTrue("the tile's Try again goes back to the name-only retry: $error", SdkTransparentUsernameCreation.isNameOnlyRetryError(error))
    }

    // --- MO-1069: protocol-gated funding amount ---

    private fun sdkService(protocolVersion: Int?) = mockk<DashSdkService> {
        coEvery { currentProtocolVersion() } returns protocolVersion
    }

    @Test
    fun `contested registration funds 0_25 DASH while the network still runs protocol 13`() = runTest {
        val amountDuffs = transparentUsernameFeeDuffs(contested = true, sdkService = sdkService(13))
        assertEquals(Constants.DASH_PAY_FEE_CONTESTED_LEGACY.value, amountDuffs)
    }

    @Test
    fun `contested registration funds 0_15 DASH once the network reports protocol 14`() = runTest {
        val amountDuffs = transparentUsernameFeeDuffs(contested = true, sdkService = sdkService(14))
        assertEquals(Constants.DASH_PAY_FEE_CONTESTED.value, amountDuffs)
    }

    @Test
    fun `an unresolved protocol version refuses to fund rather than falling back to a fee`() = runTest {
        // A lower successful quote followed by a failed funding-time lookup must
        // not become a higher actual funding; the service turns the throw into a
        // NotBroadcast the user can retry.
        assertThrows(ProtocolVersionUnavailableException::class.java) {
            runBlocking { transparentUsernameFeeDuffs(contested = true, sdkService = sdkService(null)) }
        }
    }

    @Test
    fun `non-contested registration always funds the fixed 0_03 DASH fee, regardless of protocol version`() = runTest {
        assertEquals(
            Constants.DASH_PAY_FEE.value,
            transparentUsernameFeeDuffs(contested = false, sdkService = sdkService(13))
        )
        assertEquals(
            Constants.DASH_PAY_FEE.value,
            transparentUsernameFeeDuffs(contested = false, sdkService = sdkService(14))
        )
    }

    // --- MO-1069: the strict fee boundary inside createUsernameTransparent ---

    /** Contestable label (letters only, 3–19 chars). */
    private val contestedLabel = "alice"

    /** The production `feeDuffs` wiring over a service whose protocol version is unknown. */
    private fun creationWithUnknownProtocol(source: TransparentUsernameSource, sdkService: DashSdkService) =
        SdkTransparentUsernameCreation(
            source = source,
            cutoverCommitted = { true },
            feeDuffs = { contested -> transparentUsernameFeeDuffs(contested, sdkService) },
            handOffToLegacy = {},
            driveCreationState = { state, _ -> creationStates += state },
            pause = { pauses += it }
        )

    @Test
    fun `an unknown protocol version refuses a fresh contested funding before anything is spent`() = runTest {
        val source = source(lockAfterFailure = null)
        val sdkService = sdkService(null)

        val result = creationWithUnknownProtocol(source, sdkService).createUsernameTransparent(contestedLabel)

        assertTrue("must be NotBroadcast, got $result", result is SdkWriteResult.NotBroadcast)
        val failure = result as SdkWriteResult.NotBroadcast
        assertEquals("username funding amount unavailable", failure.reason)
        assertTrue(failure.cause is ProtocolVersionUnavailableException)
        assertFalse(SdkTransparentUsernameCreation.isFundsPossiblyCommittedReason(failure.reason))
        coVerify(exactly = 1) { sdkService.currentProtocolVersion() }
        coVerify(exactly = 0) { source.registerWithWalletFunding(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { source.resumeRegistrationFromAssetLock(any(), any(), any(), any()) }
        coVerify(exactly = 0) { source.storeIdentityPrivateKey(any(), any(), any()) }
    }

    @Test
    fun `a tracked asset lock resumes without resolving a fee when the protocol version is unknown`() = runTest {
        val source = source(lockAfterFailure = null)
        coEvery { source.unresolvedRegistrationAssetLock(walletIdHex, 0) } returns lock
        coEvery { source.resumeRegistrationFromAssetLock(walletIdHex, lock, 0, any()) } returns identityId
        coEvery { source.registerDpnsName(walletIdHex, identityId, contestedLabel) } returns "$contestedLabel.dash"
        val sdkService = sdkService(null)

        val result = creationWithUnknownProtocol(source, sdkService).createUsernameTransparent(contestedLabel)

        assertTrue("must be Broadcast, got $result", result is SdkWriteResult.Broadcast)
        assertEquals(identityIdBase58, (result as SdkWriteResult.Broadcast).value.identityIdBase58)
        // The committed lock needs no new quote: the fee is never resolved.
        coVerify(exactly = 0) { sdkService.currentProtocolVersion() }
        coVerify(exactly = 1) { source.resumeRegistrationFromAssetLock(walletIdHex, lock, 0, any()) }
        coVerify(exactly = 0) { source.registerWithWalletFunding(any(), any(), any(), any(), any()) }
    }
}
