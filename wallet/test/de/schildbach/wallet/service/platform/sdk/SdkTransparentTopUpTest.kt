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

import kotlinx.coroutines.runBlocking
import org.dashfoundation.dashsdk.wallet.TrackedAssetLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the transparent top-up pipeline's already-consumed
 * resume handling — the double-purchase regression (#1536 follow-up):
 *
 * - a WORKER RERUN (newUserIntent = false) whose resume is rejected
 *   "already completely used" found this purchase's OWN consumed lock —
 *   the credits landed on the earlier attempt, so the rerun must terminate
 *   as SUCCESS and never fresh-build a second full-amount asset lock;
 * - a NEW user intent (newUserIntent = true) hitting the same rejection
 *   found a STALE lock from an EARLIER purchase — the fresh build must
 *   still proceed (once), or every future purchase would fail forever.
 *
 * No native calls; the SDK surface is faked via [TransparentTopUpSource].
 */
class SdkTransparentTopUpTest {

    private val walletId = "ab".repeat(32)
    private val identityBase58 = "testIdentityBase58"
    private val identityId = ByteArray(32) { 7 }
    private val registrationIndex = 3
    private val amountDuffs = 100_000L

    private class FakeSource(
        var unresolvedLock: () -> TrackedAssetLock? = { null },
        var onResume: (TrackedAssetLock) -> Long? = { null },
        var onTopUpFromCore: () -> Long? = { null },
        var builtLockTxid: () -> String? = { null },
        private val identityId: ByteArray,
        private val registrationIndex: Int,
        private val walletId: String
    ) : TransparentTopUpSource {
        var resumeCalls = 0
        var topUpFromCoreCalls = 0
        var lockQueryCalls = 0

        override suspend fun boundWalletIdOrNull(): String? = walletId

        override suspend fun resolveManagedIdentity(
            walletIdHex: String,
            identityIdBase58: String
        ): ManagedIdentityRef? = ManagedIdentityRef(identityId, registrationIndex)

        override suspend fun unresolvedTopUpAssetLock(
            walletIdHex: String,
            registrationIndex: Int
        ): TrackedAssetLock? {
            lockQueryCalls++
            return unresolvedLock()
        }

        override suspend fun topUpAssetLockTxidDisplayHex(
            walletIdHex: String,
            registrationIndex: Int
        ): String? = builtLockTxid()

        override suspend fun topUpFromCore(
            walletIdHex: String,
            identityId: ByteArray,
            amountDuffs: Long,
            accountIndex: Int
        ): Long? {
            topUpFromCoreCalls++
            return onTopUpFromCore()
        }

        override suspend fun resumeTopUpFromAssetLock(
            walletIdHex: String,
            identityId: ByteArray,
            lock: TrackedAssetLock
        ): Long? {
            resumeCalls++
            return onResume(lock)
        }
    }

    private fun trackedLock(firstByte: Byte = 1) = TrackedAssetLock(
        outpointTxid = ByteArray(32) { if (it == 0) firstByte else 0 },
        outpointVout = 0,
        fundingType = TrackedAssetLock.FundingType.IDENTITY_TOP_UP,
        status = TrackedAssetLock.Status.BROADCAST,
        registrationIndex = registrationIndex,
        instantLockPresent = false,
        chainLockHeight = 0
    )

    private fun fakeSource() = FakeSource(
        identityId = identityId,
        registrationIndex = registrationIndex,
        walletId = walletId
    )

    /** The wording Platform's consumed-lock rejection arrives with (observed live 2026-08-04). */
    private fun alreadyConsumedFailure() =
        RuntimeException("Generic protocol error: output 0 already completely used")

    // ── worker rerun: no second build ─────────────────────────────────────

    @Test
    fun rerun_resumeRejectedAsConsumed_terminatesSuccess_neverFreshBuilds() {
        val source = fakeSource()
        val lock = trackedLock()
        source.unresolvedLock = { lock }
        source.onResume = { throw alreadyConsumedFailure() }

        val recorded = mutableListOf<Pair<String, String>>()
        val topUp = SdkTransparentTopUp(
            source = source,
            cutoverCommitted = { true },
            recordTopUp = { txHex, toUserId -> recorded += txHex to toUserId },
            fundingSelection = de.schildbach.wallet.payments.FundingSelectionLock()
        )

        val result = runBlocking {
            topUp.topUpTransparent(identityBase58, amountDuffs, newUserIntent = false)
        }

        // Terminal SUCCESS — the credits provably landed on the earlier attempt.
        assertTrue("expected Broadcast, got $result", result is SdkWriteResult.Broadcast)
        assertEquals(
            SdkTransparentTopUp.BALANCE_ALREADY_CREDITED,
            (result as SdkWriteResult.Broadcast).value
        )
        // The one funds-safety property this test exists for: the rerun must
        // NEVER build (and broadcast) a second full-amount asset lock.
        assertEquals(0, source.topUpFromCoreCalls)
        assertEquals(1, source.resumeCalls)
        // The completed purchase is labelled a Topup (the attempt that
        // actually credited died before recording it).
        assertEquals(1, recorded.size)
        assertEquals(displayHexOf(lock.outpointTxid), recorded[0].first)
        assertEquals(identityBase58, recorded[0].second)
    }

    @Test
    fun rerun_resumeSucceeds_isPlainBroadcast() {
        val source = fakeSource()
        source.unresolvedLock = { trackedLock() }
        source.onResume = { 42_000L }

        val topUp = SdkTransparentTopUp(source = source, cutoverCommitted = { true }, fundingSelection = de.schildbach.wallet.payments.FundingSelectionLock())
        val result = runBlocking {
            topUp.topUpTransparent(identityBase58, amountDuffs, newUserIntent = false)
        }

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(42_000L, (result as SdkWriteResult.Broadcast).value)
        assertEquals(0, source.topUpFromCoreCalls)
    }

    @Test
    fun rerun_viaTopUpEntryPoint_alsoTerminatesSuccess() {
        val source = fakeSource()
        source.unresolvedLock = { trackedLock() }
        source.onResume = { throw alreadyConsumedFailure() }

        val topUp = SdkTransparentTopUp(source = source, cutoverCommitted = { true }, fundingSelection = de.schildbach.wallet.payments.FundingSelectionLock())
        val result = runBlocking { topUp.topUp(identityBase58, amountDuffs, newUserIntent = false) }

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(
            SdkTransparentTopUp.BALANCE_ALREADY_CREDITED,
            (result as SdkWriteResult.Broadcast).value
        )
        assertEquals(0, source.topUpFromCoreCalls)
    }

    // ── new user intent: stale consumed lock must not block the purchase ──

    @Test
    fun newIntent_staleConsumedLock_freshBuildProceeds() {
        val source = fakeSource()
        val staleLock = trackedLock()
        // The recovery surface keeps offering the stale lock (the SDK never
        // marks it consumed locally — MO-998); the retry pass must skip it
        // by outpoint and take the fresh-build branch.
        source.unresolvedLock = { staleLock }
        source.onResume = { throw alreadyConsumedFailure() }
        source.onTopUpFromCore = { 77_000L }

        val topUp = SdkTransparentTopUp(source = source, cutoverCommitted = { true }, fundingSelection = de.schildbach.wallet.payments.FundingSelectionLock())
        val result = runBlocking {
            topUp.topUpTransparent(identityBase58, amountDuffs, newUserIntent = true)
        }

        assertTrue("expected Broadcast, got $result", result is SdkWriteResult.Broadcast)
        assertEquals(77_000L, (result as SdkWriteResult.Broadcast).value)
        // Exactly one rejected resume, then exactly one fresh build.
        assertEquals(1, source.resumeCalls)
        assertEquals(1, source.topUpFromCoreCalls)
        assertEquals(2, source.lockQueryCalls)
    }

    @Test
    fun newIntent_noTrackedLock_buildsFresh() {
        val source = fakeSource()
        source.unresolvedLock = { null }
        source.onTopUpFromCore = { 55_000L }

        val topUp = SdkTransparentTopUp(source = source, cutoverCommitted = { true }, fundingSelection = de.schildbach.wallet.payments.FundingSelectionLock())
        val result = runBlocking {
            topUp.topUpTransparent(identityBase58, amountDuffs, newUserIntent = true)
        }

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(55_000L, (result as SdkWriteResult.Broadcast).value)
        assertEquals(0, source.resumeCalls)
        assertEquals(1, source.topUpFromCoreCalls)
    }

    // ── unprotected pending payment (review 1b15e950af15) ─────────────────

    @Test
    fun freshTopUp_refusedWhileAPendingPaymentIsUnprotected() {
        val source = fakeSource()
        source.onTopUpFromCore = { 42_000L }
        val topUp = SdkTransparentTopUp(source = source, cutoverCommitted = { true }, freshFundingBlocked = { true }, fundingSelection = de.schildbach.wallet.payments.FundingSelectionLock())

        val result = runBlocking { topUp.topUp(identityBase58, amountDuffs, newUserIntent = true) }

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(isPaymentStillConfirmingReason((result as SdkWriteResult.NotBroadcast).reason))
        assertEquals("no fresh coin selection while the inputs are exposed", 0, source.topUpFromCoreCalls)
    }

    @Test
    fun resumingAnExistingLock_isNotBlockedByAnUnprotectedPendingPayment() {
        val source = fakeSource()
        source.unresolvedLock = { trackedLock() }
        source.onResume = { 42_000L }
        val topUp = SdkTransparentTopUp(source = source, cutoverCommitted = { true }, freshFundingBlocked = { true }, fundingSelection = de.schildbach.wallet.payments.FundingSelectionLock())

        val result = runBlocking { topUp.topUp(identityBase58, amountDuffs, newUserIntent = false) }

        // re-consumes the lock's own UTXOs, selects nothing fresh
        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(1, source.resumeCalls)
    }

    // ── the lock txid reported to External Send ───────────────────────────

    private fun topUpOf(source: FakeSource, freshFundingBlocked: Boolean = false) = SdkTransparentTopUp(
        source = source,
        cutoverCommitted = { true },
        freshFundingBlocked = { freshFundingBlocked },
        fundingSelection = de.schildbach.wallet.payments.FundingSelectionLock()
    )

    @Test
    fun reportingLockTxId_freshBuild_carriesTheBuiltLocksDisplayTxid() {
        val source = fakeSource()
        source.onTopUpFromCore = { 55_000L }
        source.builtLockTxid = { "cd".repeat(32) }

        val result = runBlocking {
            topUpOf(source).topUpReportingLockTxId(identityBase58, amountDuffs, newUserIntent = true)
        }

        assertEquals(SdkWriteResult.Broadcast(TopUpOutcome(55_000L, "cd".repeat(32))), result)
    }

    @Test
    fun reportingLockTxId_resume_carriesTheResumedLocksDisplayTxid() {
        val source = fakeSource()
        val lock = trackedLock(firstByte = 9)
        source.unresolvedLock = { lock }
        source.onResume = { 42_000L }

        val result = runBlocking {
            topUpOf(source).topUpReportingLockTxId(identityBase58, amountDuffs, newUserIntent = false)
        }

        assertEquals(SdkWriteResult.Broadcast(TopUpOutcome(42_000L, displayHexOf(lock.outpointTxid))), result)
    }

    @Test
    fun reportingLockTxId_newIntentResumingAnEarlierLock_isFlaggedResumedEarlier_plainTopUpUnchanged() {
        val lock = trackedLock(firstByte = 9)
        val source = fakeSource().apply {
            unresolvedLock = { lock }
            onResume = { 42_000L }
        }

        val result = runBlocking {
            topUpOf(source).topUpReportingLockTxId(identityBase58, amountDuffs, newUserIntent = true)
        }

        // The earlier request's lock was completed; nothing of this request's amount was built.
        assertEquals(
            SdkWriteResult.Broadcast(TopUpOutcome(42_000L, displayHexOf(lock.outpointTxid), resumedEarlier = true)),
            result
        )
        assertEquals(0, source.topUpFromCoreCalls)

        // topUp's own result (Buy Credits, PerformTopUpWorker) is unchanged.
        val plain = fakeSource().apply {
            unresolvedLock = { lock }
            onResume = { 42_000L }
        }
        assertEquals(
            SdkWriteResult.Broadcast(42_000L),
            runBlocking { topUpOf(plain).topUp(identityBase58, amountDuffs, newUserIntent = true) }
        )
    }

    @Test
    fun reportingLockTxId_newIntentSkippingAStaleConsumedLock_buildsFresh_notResumedEarlier() {
        val source = fakeSource().apply {
            unresolvedLock = { trackedLock() }
            onResume = { throw alreadyConsumedFailure() }
            onTopUpFromCore = { 77_000L }
            builtLockTxid = { "cd".repeat(32) }
        }

        val result = runBlocking {
            topUpOf(source).topUpReportingLockTxId(identityBase58, amountDuffs, newUserIntent = true)
        }

        assertEquals(SdkWriteResult.Broadcast(TopUpOutcome(77_000L, "cd".repeat(32))), result)
        assertEquals(1, source.topUpFromCoreCalls)
    }

    @Test
    fun reportingLockTxId_rerunOnItsOwnConsumedLock_carriesThatLocksTxid() {
        val source = fakeSource()
        val lock = trackedLock(firstByte = 5)
        source.unresolvedLock = { lock }
        source.onResume = { throw alreadyConsumedFailure() }

        val result = runBlocking {
            topUpOf(source).topUpReportingLockTxId(identityBase58, amountDuffs, newUserIntent = false)
        }

        assertEquals(
            SdkWriteResult.Broadcast(
                TopUpOutcome(SdkTransparentTopUp.BALANCE_ALREADY_CREDITED, displayHexOf(lock.outpointTxid))
            ),
            result
        )
        assertEquals(0, source.topUpFromCoreCalls)
    }

    @Test
    fun reportingLockTxId_unrecoverableTxid_isStillBroadcast_withoutIt() {
        val source = fakeSource()
        source.onTopUpFromCore = { 55_000L }
        source.builtLockTxid = { throw IllegalStateException("asset_locks unreadable") }

        val result = runBlocking {
            topUpOf(source).topUpReportingLockTxId(identityBase58, amountDuffs, newUserIntent = true)
        }

        assertEquals(SdkWriteResult.Broadcast(TopUpOutcome(55_000L, null)), result)
    }

    @Test
    fun reportingLockTxId_failuresKeepTheirClass_andPlainTopUpIsUnchanged() {
        val blocked = fakeSource().apply { onTopUpFromCore = { 55_000L } }
        val refused = runBlocking {
            topUpOf(blocked, freshFundingBlocked = true).topUpReportingLockTxId(identityBase58, amountDuffs, true)
        }
        assertTrue(refused is SdkWriteResult.NotBroadcast)

        val unknown = fakeSource().apply { onTopUpFromCore = { null } }
        val ambiguous = runBlocking { topUpOf(unknown).topUpReportingLockTxId(identityBase58, amountDuffs, true) }
        assertTrue(ambiguous is SdkWriteResult.Ambiguous)

        val plain = fakeSource().apply {
            onTopUpFromCore = { 55_000L }
            builtLockTxid = { "cd".repeat(32) }
        }
        assertEquals(
            SdkWriteResult.Broadcast(55_000L),
            runBlocking { topUpOf(plain).topUp(identityBase58, amountDuffs, newUserIntent = true) }
        )
    }
}
