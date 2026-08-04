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

import androidx.datastore.preferences.core.Preferences
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.dash.wallet.common.money.Dash
import org.dashfoundation.dashsdk.credits.FundingInput
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the invite-claim overage completion
 * ([ShieldedInviteOverageTopUp]): the product decision that ALL of an
 * invitation's value — including the part above the clamped exit denomination
 * (a legacy 0.3 note exits 0.25, leaving 0.05 in the claimer's pool) — must
 * end up as credits on the claimed identity. Covers the overage pipeline
 * (unshield with fee convergence → address top-up), the exact-denomination
 * no-op, and crash-resume at every persisted stage.
 */
class ShieldedInviteOverageTopUpTest {

    private val identityId = Identifier.from(ByteArray(32) { 7 }).toString()

    /** 0.05 DASH in credits — the legacy 0.3 note's overage over 0.25. */
    private val overageCredits = 5_000_000_000L

    /** A plausible Rust-computed unshield fee (2-action bundle + address write). */
    private val unshieldFeeCredits = 144_000_000L
    private val netCredits = overageCredits - unshieldFeeCredits

    private val addressHash = ByteArray(20) { 3 }

    /** Fixed test clock; records default to AGED (past the abandon budget). */
    private val nowMs = 1_754_300_000_000L
    private val agedCreatedAt = nowMs - ShieldedInviteOverageTopUp.MIN_ABANDON_AGE_MS - 1
    private val youngCreatedAt = nowMs - 5_000L // seconds old — the S22 shape

    private fun assertRecordKeysGone(backing: Map<Preferences.Key<*>, Any>) {
        assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_IDENTITY_ID))
        assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_CREDITS))
        assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_NET_CREDITS))
        assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_TOPUP_STARTED))
        assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_CREATED_AT_MS))
        // Every v2 clear supersedes the kind-unknown v1 marker.
        assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_RECONCILED_IDENTITY))
    }

    /** A SUCCESS-class clear: record gone, permanent success outcome stamped. */
    private fun assertClearedAsSuccess(backing: Map<Preferences.Key<*>, Any>) {
        assertRecordKeysGone(backing)
        assertEquals(identityId, backing[DashPayConfig.INVITE_OVERAGE_OUTCOME_SUCCESS])
        assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_OUTCOME_ABANDONED))
    }

    /** An ABANDON-class clear: record gone, re-mintable abandon outcome stamped. */
    private fun assertClearedAsAbandoned(backing: Map<Preferences.Key<*>, Any>) {
        assertRecordKeysGone(backing)
        assertEquals(identityId, backing[DashPayConfig.INVITE_OVERAGE_OUTCOME_ABANDONED])
        assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_OUTCOME_SUCCESS))
    }

    // ── Fakes ─────────────────────────────────────────────────────────────

    /** DataStore-free DashPayConfig fake over a plain map. */
    private fun configFake(backing: MutableMap<Preferences.Key<*>, Any>): DashPayConfig {
        val config = mockk<DashPayConfig>()
        coEvery { config.get(any<Preferences.Key<Any>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            backing[firstArg<Preferences.Key<Any>>()]
        }
        coEvery { config.set(any<Preferences.Key<Any>>(), any()) } answers {
            backing[firstArg<Preferences.Key<Any>>()] = secondArg()
        }
        coEvery { config.remove(any<Preferences.Key<Any>>()) } answers {
            backing.remove(firstArg<Preferences.Key<Any>>())
        }
        return config
    }

    private fun pendingRecordBacking(
        net: Long? = null,
        topUpStarted: Boolean = false,
        createdAtMs: Long? = agedCreatedAt
    ): MutableMap<Preferences.Key<*>, Any> {
        val map = mutableMapOf<Preferences.Key<*>, Any>()
        map[DashPayConfig.INVITE_OVERAGE_IDENTITY_ID] = identityId
        map[DashPayConfig.INVITE_OVERAGE_CREDITS] = overageCredits
        net?.let { map[DashPayConfig.INVITE_OVERAGE_NET_CREDITS] = it }
        if (topUpStarted) map[DashPayConfig.INVITE_OVERAGE_TOPUP_STARTED] = true
        createdAtMs?.let { map[DashPayConfig.INVITE_OVERAGE_CREATED_AT_MS] = it }
        return map
    }

    private fun balanceService(
        poolCredits: Long = overageCredits,
        ready: Boolean = true,
        settled: Boolean = true
    ): ShieldedBalanceService = mockk {
        coEvery { ensureShieldedReady() } returns ready
        coEvery { observeShieldedBalance() } returns MutableStateFlow(creditsToDash(poolCredits))
        every { shieldedSyncStatus } returns MutableStateFlow(
            if (settled) ShieldedSyncStatus.READY else ShieldedSyncStatus.NOT_READY
        )
    }

    /** The verbatim note-selection refusal shape the fee convergence parses. */
    private fun insufficientRefusal(availableCredits: Long, requiredCredits: Long) =
        SdkWriteResult.NotBroadcast(
            "pre-broadcast shielded note-selection failure",
            RuntimeException(
                "shielded unshield failed: Insufficient shielded balance: " +
                    "available $availableCredits, required $requiredCredits"
            )
        )

    private fun service(
        backing: MutableMap<Preferences.Key<*>, Any>,
        balance: ShieldedBalanceService,
        source: InviteOverageSource
    ) = ShieldedInviteOverageTopUp(configFake(backing), balance, source, now = { nowMs })

    // ── packOverageInputs (pure) ─────────────────────────────────────────

    @Test
    fun packInputs_capsAtTheTarget_neverMovesMore() {
        val candidates = listOf(
            FundingInput(0, ByteArray(20) { 1 }, 3_000_000_000L),
            FundingInput(0, ByteArray(20) { 2 }, 4_000_000_000L)
        )
        val packed = packOverageInputs(candidates, netCredits)
        // Largest first, final input trimmed to the target.
        assertEquals(2, packed.size)
        assertEquals(4_000_000_000L, packed[0].credits)
        assertEquals(netCredits - 4_000_000_000L, packed[1].credits)
        assertEquals(netCredits, packed.sumOf { it.credits })
    }

    @Test
    fun packInputs_partialCoverage_movesWhatExists() {
        val packed = packOverageInputs(listOf(FundingInput(0, addressHash, 1_000L)), netCredits)
        assertEquals(1_000L, packed.sumOf { it.credits })
    }

    @Test
    fun packInputs_emptyOrNonPositive() {
        assertTrue(packOverageInputs(emptyList(), netCredits).isEmpty())
        assertTrue(packOverageInputs(listOf(FundingInput(0, addressHash, 100L)), 0L).isEmpty())
    }

    // ── inviteClaimOverageCredits (pure — the record trigger) ────────────

    @Test
    fun overage_legacy0Point3NoteClaimedAt0Point25_is0Point05() {
        assertEquals(
            overageCredits,
            inviteClaimOverageCredits(30_000_000_000L, 25_000_000_000L)
        )
    }

    @Test
    fun overage_exactDenominationInvites_haveNone() {
        // Today's mints (0.03 / 0.25) and the legacy 0.1 all exit in full.
        assertNull(inviteClaimOverageCredits(3_000_000_000L, 3_000_000_000L))
        assertNull(inviteClaimOverageCredits(25_000_000_000L, 25_000_000_000L))
        assertNull(inviteClaimOverageCredits(10_000_000_000L, 10_000_000_000L))
    }

    @Test
    fun overage_descendedClaimProvesTheLinkLied_neverRecorded() {
        // amt said 0.3 but the ladder descended to 0.1 — the note was smaller
        // than claimed; a 0.2 "overage" would be fiction.
        assertNull(inviteClaimOverageCredits(30_000_000_000L, 10_000_000_000L))
    }

    @Test
    fun overage_unreadableLinkValues_neverRecorded() {
        assertNull(inviteClaimOverageCredits(null, 25_000_000_000L))
        assertNull(inviteClaimOverageCredits(12_345L, 25_000_000_000L))
        // 0.5 is an exit denomination but not an invite note value.
        assertNull(inviteClaimOverageCredits(50_000_000_000L, 25_000_000_000L))
    }

    // ── The overage pipeline ─────────────────────────────────────────────

    @Test
    fun overagePath_unshieldsWithFeeConvergence_thenTopsUp_thenClears() = runTest {
        val backing = pendingRecordBacking()
        val balance = balanceService()
        // The change note holds EXACTLY the overage, so the full-amount
        // attempt is short by the fee; the adjusted retry succeeds.
        coEvery { balance.unshieldToCredits(creditsToDash(overageCredits)) } returns
            insufficientRefusal(overageCredits, overageCredits + unshieldFeeCredits)
        coEvery { balance.unshieldToCredits(creditsToDash(netCredits)) } returns
            SdkWriteResult.Broadcast(Unit)

        val source = mockk<InviteOverageSource> {
            coEvery { addressesWithBalances() } returns
                listOf(FundingInput(0, addressHash, netCredits))
            coEvery { topUpFromAddresses(any(), any()) } returns netCredits
        }

        val outcome = service(backing, balance, source).runPending()

        assertEquals(InviteOverageOutcome.DONE, outcome)
        coVerify(exactly = 1) {
            source.topUpFromAddresses(
                Identifier.from(identityId).toBuffer(),
                listOf(FundingInput(0, addressHash, netCredits))
            )
        }
        assertClearedAsSuccess(backing)
    }

    @Test
    fun exactDenominationClaim_noRecord_isIdle_andTouchesNothing() = runTest {
        val backing = mutableMapOf<Preferences.Key<*>, Any>()
        val balance = mockk<ShieldedBalanceService>()
        val source = mockk<InviteOverageSource>()

        assertEquals(InviteOverageOutcome.IDLE, service(backing, balance, source).runPending())
        // No unshield, no top-up — mockk without stubs would have thrown on
        // any call; also assert the store stayed COMPLETELY empty (an idle
        // pass must not even stamp the reconcile marker).
        assertTrue(backing.isEmpty())
    }

    // ── Crash resume ─────────────────────────────────────────────────────

    @Test
    fun resumeAfterInterruption_netPersisted_skipsUnshield_runsOnlyTopUp() = runTest {
        // Simulated interruption AFTER the unshield stage persisted its net:
        // the rerun must not touch the pool again.
        val backing = pendingRecordBacking(net = netCredits)
        val balance = mockk<ShieldedBalanceService>() // any call would throw
        val source = mockk<InviteOverageSource> {
            coEvery { addressesWithBalances() } returns
                listOf(FundingInput(0, addressHash, netCredits))
            coEvery { topUpFromAddresses(any(), any()) } returns netCredits
        }

        assertEquals(InviteOverageOutcome.DONE, service(backing, balance, source).runPending())
        coVerify(exactly = 1) { source.topUpFromAddresses(any(), any()) }
        assertClearedAsSuccess(backing)
    }

    @Test
    fun resumeAfterUnshieldBroadcastButBeforePersist_advancesViaAddressEvidence() = runTest {
        // Simulated crash BETWEEN the unshield broadcast and the net persist:
        // the rerun's unshield finds an empty pool (the note is spent), and the
        // address balance is the positive evidence that the credits landed.
        val backing = pendingRecordBacking()
        val balance = balanceService(poolCredits = 0L)
        coEvery { balance.unshieldToCredits(creditsToDash(overageCredits)) } returns
            insufficientRefusal(0L, overageCredits + unshieldFeeCredits)

        val source = mockk<InviteOverageSource> {
            coEvery { addressesWithBalances() } returns
                listOf(FundingInput(0, addressHash, netCredits))
            coEvery { topUpFromAddresses(any(), any()) } returns netCredits
        }

        assertEquals(InviteOverageOutcome.DONE, service(backing, balance, source).runPending())
        coVerify(exactly = 1) { source.topUpFromAddresses(any(), any()) }
        assertClearedAsSuccess(backing)
    }

    @Test
    fun resumeAfterTopUpBroadcastButBeforeClear_recognizesConsumedCredits() = runTest {
        // Simulated crash BETWEEN the top-up broadcast and the record clear:
        // the rerun finds no address balance, and TOPUP_STARTED tells it the
        // top-up consumed the credits — resolve DONE, never re-pay.
        val backing = pendingRecordBacking(net = netCredits, topUpStarted = true)
        val balance = mockk<ShieldedBalanceService>()
        val source = mockk<InviteOverageSource> {
            coEvery { addressesWithBalances() } returns emptyList()
        }

        assertEquals(InviteOverageOutcome.DONE, service(backing, balance, source).runPending())
        coVerify(exactly = 0) { source.topUpFromAddresses(any(), any()) }
        assertClearedAsSuccess(backing)
    }

    @Test
    fun unshieldLanded_butAddressCacheStale_retriesWithoutConsuming() = runTest {
        // The unshield broadcast landed this pass, but the SDK's address cache
        // hasn't surfaced the credits yet — the pass must RETRY, keep the
        // record, and not mark the top-up attempted.
        val backing = pendingRecordBacking()
        val balance = balanceService()
        coEvery { balance.unshieldToCredits(creditsToDash(overageCredits)) } returns
            SdkWriteResult.Broadcast(Unit)
        val source = mockk<InviteOverageSource> {
            coEvery { addressesWithBalances() } returns emptyList()
        }

        assertEquals(InviteOverageOutcome.RETRY, service(backing, balance, source).runPending())
        // Stage advanced past the unshield; the top-up was never attempted.
        assertEquals(overageCredits, backing[DashPayConfig.INVITE_OVERAGE_NET_CREDITS])
        assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_TOPUP_STARTED))
        coVerify(exactly = 0) { source.topUpFromAddresses(any(), any()) }
    }

    @Test
    fun overageNowhereToBeFound_givesUpAndClears() = runTest {
        // Pool empty, address empty, nothing persisted past the unshield,
        // AND the absence is provable (pool settled + record aged past the
        // budget — the defaults) — the overage never existed (or was moved
        // manually). Only then is the record abandoned, with the ABANDON
        // outcome (never success) so the reconcile keeps its pool-guarded
        // re-mint path.
        val backing = pendingRecordBacking()
        val balance = balanceService(poolCredits = 0L)
        coEvery { balance.unshieldToCredits(creditsToDash(overageCredits)) } returns
            insufficientRefusal(0L, overageCredits + unshieldFeeCredits)
        val source = mockk<InviteOverageSource> {
            coEvery { addressesWithBalances() } returns emptyList()
        }

        assertEquals(InviteOverageOutcome.DONE, service(backing, balance, source).runPending())
        assertClearedAsAbandoned(backing)
    }

    @Test
    fun runtimeNotReady_retriesWithoutSpending() = runTest {
        val backing = pendingRecordBacking()
        val balance = balanceService(ready = false)
        val source = mockk<InviteOverageSource>()

        assertEquals(InviteOverageOutcome.RETRY, service(backing, balance, source).runPending())
        // Record intact for the next pass.
        assertEquals(identityId, backing[DashPayConfig.INVITE_OVERAGE_IDENTITY_ID])
    }

    @Test
    fun topUpFailure_keepsTheRecordForRetry() = runTest {
        val backing = pendingRecordBacking(net = netCredits)
        val balance = mockk<ShieldedBalanceService>()
        val source = mockk<InviteOverageSource> {
            coEvery { addressesWithBalances() } returns
                listOf(FundingInput(0, addressHash, netCredits))
            coEvery { topUpFromAddresses(any(), any()) } throws
                RuntimeException("transient DAPI failure")
        }

        assertEquals(InviteOverageOutcome.RETRY, service(backing, balance, source).runPending())
        // Record intact; the attempt is marked so a consumed-credits rerun
        // can distinguish done from stale.
        assertEquals(identityId, backing[DashPayConfig.INVITE_OVERAGE_IDENTITY_ID])
        assertEquals(true, backing[DashPayConfig.INVITE_OVERAGE_TOPUP_STARTED])
    }

    // ── persistPendingRecord ─────────────────────────────────────────────

    @Test
    fun persistPendingRecord_freshRecordSupersedesStaleStages() = runTest {
        // A stale record with advanced stages must not leak into a NEW claim's
        // record (there is at most one in-flight claim).
        val backing = pendingRecordBacking(net = 123L, topUpStarted = true)
        val config = configFake(backing)

        ShieldedInviteOverageTopUp.persistPendingRecord(config, identityId, overageCredits, nowMs)

        assertEquals(identityId, backing[DashPayConfig.INVITE_OVERAGE_IDENTITY_ID])
        assertEquals(overageCredits, backing[DashPayConfig.INVITE_OVERAGE_CREDITS])
        assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_NET_CREDITS))
        assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_TOPUP_STARTED))
        // The settle-aware give-up's age input is stamped at mint…
        assertEquals(nowMs, backing[DashPayConfig.INVITE_OVERAGE_CREATED_AT_MS])
        // …and NO reconcile marker is (v2): the pending record itself
        // suppresses the reconcile; outcome provenance is stamped at CLEAR.
        assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_RECONCILED_IDENTITY))
        assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_OUTCOME_SUCCESS))
        assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_OUTCOME_ABANDONED))
    }

    // ── Completed-claim reconcile (the S22 retro-fit) ────────────────────

    /**
     * THE COMPLETED-CLAIM SHAPE: claim done (identity on chain), amt=0.3 in
     * the persisted link, nothing pending, no outcome stamps → the provable
     * un-topped overage is 0.05.
     */
    @Test
    fun reconcile_completedLegacyClaim_yieldsTheClampedOverage() {
        assertEquals(
            overageCredits,
            reconcilable(state = IdentityCreationState.DONE_AND_DISMISS)
        )
        // Any completed state from IDENTITY_REGISTERED on qualifies.
        assertEquals(
            overageCredits,
            reconcilable(state = IdentityCreationState.USERNAME_REGISTERING)
        )
    }

    private fun reconcilable(
        id: String? = identityId,
        state: IdentityCreationState = IdentityCreationState.DONE_AND_DISMISS,
        usingInvite: Boolean = true,
        shielded: Boolean = true,
        amt: Long? = 30_000_000_000L,
        pending: Boolean = false,
        success: String? = null,
        abandoned: String? = null,
        legacy: String? = null,
        pool: Long? = 0L
    ) = reconcilableOverageCredits(
        identityIdBase58 = id,
        creationState = state,
        usingInvite = usingInvite,
        inviteIsShielded = shielded,
        fundingCreditsFromLink = amt,
        hasPendingRecord = pending,
        successOutcomeIdentity = success,
        abandonedOutcomeIdentity = abandoned,
        legacyMarkerIdentity = legacy,
        poolCredits = pool
    )

    // ── The v2 outcome semantics (the S22 un-strand) ─────────────────────

    /** A SUCCESSFUL top-up never re-runs — even with a full pool. */
    @Test
    fun reconcile_successOutcome_isPermanent() {
        assertNull(reconcilable(success = identityId, pool = overageCredits))
        assertNull(reconcilable(success = identityId, pool = Long.MAX_VALUE))
    }

    /**
     * ABANDON-then-note-appears (the S22 stranding): an abandoned outcome is
     * overridden EXACTLY when the pool now holds at least the derivable
     * overage — the change note was scanned after the give-up.
     */
    @Test
    fun reconcile_abandonedOutcome_remintsOnlyOnPoolEvidence() {
        // Note appeared → re-mint.
        assertEquals(overageCredits, reconcilable(abandoned = identityId, pool = overageCredits))
        assertEquals(overageCredits, reconcilable(abandoned = identityId, pool = overageCredits + 1))
        // Pool below the overage / unreadable → genuine absence → stay suppressed.
        assertNull(reconcilable(abandoned = identityId, pool = overageCredits - 1))
        assertNull(reconcilable(abandoned = identityId, pool = 0L))
        assertNull(reconcilable(abandoned = identityId, pool = null))
    }

    /**
     * THE LIVE S22 STATE: the v1 marker (stamped by the premature .54
     * abandon, clear-kind unknown) — treated like an abandon: one
     * pool-guarded re-mint.
     */
    @Test
    fun reconcile_legacyV1Marker_remintsOnlyOnPoolEvidence() {
        assertEquals(overageCredits, reconcilable(legacy = identityId, pool = overageCredits))
        assertNull(reconcilable(legacy = identityId, pool = overageCredits - 1))
        assertNull(reconcilable(legacy = identityId, pool = null))
        // A DIFFERENT identity's stamps never suppress this one.
        assertEquals(overageCredits, reconcilable(legacy = "someoneElse", pool = 0L))
        assertEquals(overageCredits, reconcilable(abandoned = "someoneElse", pool = 0L))
    }

    @Test
    fun reconcile_pendingRecord_alwaysSuppresses() {
        assertNull(reconcilable(pending = true, pool = overageCredits))
    }

    @Test
    fun reconcile_nonProvableShapes_neverMintARecord() {
        // No claimed identity / claim not completed.
        assertNull(reconcilable(id = null))
        assertNull(reconcilable(state = IdentityCreationState.CREDIT_FUNDING_TX_CREATING))
        assertNull(reconcilable(state = IdentityCreationState.IDENTITY_REGISTERING))
        // Not an invite / not a shielded invite.
        assertNull(reconcilable(usingInvite = false))
        assertNull(reconcilable(shielded = false))
        // No amt (legacy link), junk amt, non-note-value amt.
        assertNull(reconcilable(amt = null))
        assertNull(reconcilable(amt = 12_345L))
        assertNull(reconcilable(amt = 50_000_000_000L))
        // Exact-denomination mints have no overage.
        assertNull(reconcilable(amt = 25_000_000_000L))
        assertNull(reconcilable(amt = 3_000_000_000L))
        assertNull(reconcilable(amt = 10_000_000_000L))
    }

    // ── Settle-aware give-up ─────────────────────────────────────────────

    private fun emptyEverywhere(balance: ShieldedBalanceService): InviteOverageSource {
        coEvery { balance.unshieldToCredits(creditsToDash(overageCredits)) } returns
            insufficientRefusal(0L, overageCredits + unshieldFeeCredits)
        return mockk<InviteOverageSource> {
            coEvery { addressesWithBalances() } returns emptyList()
        }
    }

    /**
     * THE S22 PREMATURE ABANDON, pinned: a record only seconds old with an
     * empty pool is SCAN LAG, not absence — the pass must RETRY and keep the
     * record fully intact, no matter what the sync status claims.
     */
    @Test
    fun youngRecord_emptyPool_retriesNeverAbandons() = runTest {
        for (settled in listOf(true, false)) {
            val backing = pendingRecordBacking(createdAtMs = youngCreatedAt)
            val balance = balanceService(poolCredits = 0L, settled = settled)
            val source = emptyEverywhere(balance)

            assertEquals(
                "settled=$settled must retry",
                InviteOverageOutcome.RETRY,
                service(backing, balance, source).runPending()
            )
            assertEquals(identityId, backing[DashPayConfig.INVITE_OVERAGE_IDENTITY_ID])
            assertFalse(backing.containsKey(DashPayConfig.INVITE_OVERAGE_OUTCOME_ABANDONED))
        }
    }

    /** An AGED record with an UNSETTLED pool still retries — absence unproven. */
    @Test
    fun agedRecord_unsettledPool_retries() = runTest {
        val backing = pendingRecordBacking(createdAtMs = agedCreatedAt)
        val balance = balanceService(poolCredits = 0L, settled = false)
        val source = emptyEverywhere(balance)

        assertEquals(InviteOverageOutcome.RETRY, service(backing, balance, source).runPending())
        assertEquals(identityId, backing[DashPayConfig.INVITE_OVERAGE_IDENTITY_ID])
    }

    /** Settled + aged + empty everywhere: absence is provable → abandon. */
    @Test
    fun agedRecord_settledEmptyPool_abandons_stampingTheAbandonOutcome() = runTest {
        val backing = pendingRecordBacking(createdAtMs = agedCreatedAt)
        val balance = balanceService(poolCredits = 0L, settled = true)
        val source = emptyEverywhere(balance)

        assertEquals(InviteOverageOutcome.DONE, service(backing, balance, source).runPending())
        assertClearedAsAbandoned(backing)
    }

    /**
     * A record minted before the created-at key existed is backfilled to NOW
     * (treated as young) — never abandoned on its first pass.
     */
    @Test
    fun legacyRecordWithoutCreatedAt_backfilled_andRetried() = runTest {
        val backing = pendingRecordBacking(createdAtMs = null)
        val balance = balanceService(poolCredits = 0L, settled = true)
        val source = emptyEverywhere(balance)

        assertEquals(InviteOverageOutcome.RETRY, service(backing, balance, source).runPending())
        assertEquals(nowMs, backing[DashPayConfig.INVITE_OVERAGE_CREATED_AT_MS])
        assertEquals(identityId, backing[DashPayConfig.INVITE_OVERAGE_IDENTITY_ID])
    }

    @Test
    fun drainedRecord_staysDrained_reconcileNeverRemintsIt() = runTest {
        // Complete the pipeline, then verify the SUCCESS outcome suppresses
        // the reconcile permanently — even with a full pool.
        val backing = pendingRecordBacking(net = netCredits)
        val balance = mockk<ShieldedBalanceService>()
        val source = mockk<InviteOverageSource> {
            coEvery { addressesWithBalances() } returns
                listOf(FundingInput(0, addressHash, netCredits))
            coEvery { topUpFromAddresses(any(), any()) } returns netCredits
        }
        assertEquals(InviteOverageOutcome.DONE, service(backing, balance, source).runPending())

        assertClearedAsSuccess(backing)
        assertNull(
            reconcilable(
                success = backing[DashPayConfig.INVITE_OVERAGE_OUTCOME_SUCCESS] as String?,
                pool = overageCredits
            )
        )
    }
}
