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
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashfoundation.dashsdk.wallet.TrackedAssetLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the top-up recovery drain (#1520 item 3 / MO-998):
 * the pass resumes exactly the resumable top-up locks, contains per-lock
 * failures, treats already-consumed as terminal, and the checkTopUps
 * trigger predicate never boots the SDK. No native calls; the recovery
 * surface is faked via [SdkTopUpRecoverySource].
 */
class SdkTopUpRecoveryServiceTest {

    private val walletId = "cd".repeat(32)
    private val identityId = ByteArray(32) { 7 }
    private val newBalance = 42_000_000_000L

    private class FakeSource(
        var boundWalletId: () -> String? = { null },
        var recoveryLocks: () -> List<TrackedAssetLock> = { emptyList() },
        var onResume: (TrackedAssetLock) -> Long = { 0L }
    ) : SdkTopUpRecoverySource {
        var boundCalls = 0
        var resumeCalls = 0
        var lastIdentityId: ByteArray? = null
        val resumedLocks = mutableListOf<TrackedAssetLock>()

        override suspend fun boundWalletIdOrNull(): String? {
            boundCalls++
            return boundWalletId()
        }

        override suspend fun trackedRecoveryLocks(walletIdHex: String): List<TrackedAssetLock> =
            recoveryLocks()

        override suspend fun resumeTopUp(
            walletIdHex: String,
            identityId: ByteArray,
            lock: TrackedAssetLock
        ): Long {
            resumeCalls++
            lastIdentityId = identityId
            resumedLocks += lock
            return onResume(lock)
        }
    }

    private fun lock(
        fundingType: TrackedAssetLock.FundingType,
        firstByte: Byte = 1,
        status: TrackedAssetLock.Status = TrackedAssetLock.Status.BROADCAST
    ) = TrackedAssetLock(
        outpointTxid = ByteArray(32) { if (it == 0) firstByte else 0 },
        outpointVout = 0,
        fundingType = fundingType,
        status = status,
        registrationIndex = 0,
        instantLockPresent = false,
        chainLockHeight = 0
    )

    private fun service(
        source: FakeSource,
        identity: suspend () -> ByteArray? = { identityId },
        sdkStarted: Boolean = true
    ) = SdkTopUpRecoveryService(
        source = source,
        identityIdBytes = identity,
        sdkIsStarted = { sdkStarted }
    )

    // ── drainPendingTopUps ────────────────────────────────────────────────

    @Test
    fun drain_unboundWallet_isNothingToDo() {
        val source = FakeSource(boundWalletId = { null })
        val report = runBlocking { service(source).drainPendingTopUps() }
        assertEquals(TopUpDrainReport.NOTHING_TO_DO, report)
        assertFalse(report.retryNeeded)
        assertEquals(0, source.resumeCalls)
    }

    @Test
    fun drain_bindLookupFailure_isSurfaceUnavailable_retryNeeded() {
        val source = FakeSource(boundWalletId = { throw IllegalStateException("bootstrap failed") })
        val report = runBlocking { service(source).drainPendingTopUps() }
        assertTrue(report.surfaceUnavailable)
        assertTrue(report.retryNeeded)
    }

    @Test
    fun drain_emptySurface_isNothingToDo() {
        val source = FakeSource(boundWalletId = { walletId }, recoveryLocks = { emptyList() })
        val report = runBlocking { service(source).drainPendingTopUps() }
        assertEquals(TopUpDrainReport.NOTHING_TO_DO, report)
        assertEquals(0, source.resumeCalls)
    }

    @Test
    fun drain_listFailure_isSurfaceUnavailable_noResumeCalls() {
        val source = FakeSource(
            boundWalletId = { walletId },
            recoveryLocks = { throw IllegalStateException("FFI unavailable") }
        )
        val report = runBlocking { service(source).drainPendingTopUps() }
        assertTrue(report.surfaceUnavailable)
        assertEquals(0, source.resumeCalls)
    }

    @Test
    fun drain_skipsRegistrationLocks_resumesBothTopUpTypes() {
        val registration = lock(TrackedAssetLock.FundingType.IDENTITY_REGISTRATION, firstByte = 1)
        val bound = lock(TrackedAssetLock.FundingType.IDENTITY_TOP_UP, firstByte = 2)
        val unbound = lock(TrackedAssetLock.FundingType.IDENTITY_TOP_UP_NOT_BOUND, firstByte = 3)
        val source = FakeSource(
            boundWalletId = { walletId },
            recoveryLocks = { listOf(registration, bound, unbound) },
            onResume = { newBalance }
        )
        val report = runBlocking { service(source).drainPendingTopUps() }
        assertEquals(TopUpDrainReport(pending = 2, resumed = 2, alreadyConsumed = 0, failed = 0), report)
        assertFalse(report.retryNeeded)
        assertEquals(listOf(bound, unbound), source.resumedLocks)
        assertTrue(identityId.contentEquals(source.lastIdentityId!!))
    }

    @Test
    fun drain_alreadyConsumed_isTerminal_notRetryable() {
        val source = FakeSource(
            boundWalletId = { walletId },
            recoveryLocks = { listOf(lock(TrackedAssetLock.FundingType.IDENTITY_TOP_UP)) },
            onResume = { throw DashSdkError.PlatformWallet.AssetLockAlreadyConsumed("already consumed") }
        )
        val report = runBlocking { service(source).drainPendingTopUps() }
        assertEquals(TopUpDrainReport(pending = 1, resumed = 0, alreadyConsumed = 1, failed = 0), report)
        assertFalse(report.retryNeeded)
    }

    @Test
    fun drain_platformAlreadyUsedMessage_isTerminal_notRetryable() {
        // Platform's own rejection is NOT the SDK's typed error: it arrives as
        // a Generic protocol error reading "output N already completely used"
        // (observed live). Treating it as retryable made WorkManager back off
        // forever on a lock whose credits had already landed.
        val wrapped = RuntimeException(
            "SDK error",
            DashSdkError.PlatformWallet.Generic(
                99,
                "SDK error: Protocol error: Asset lock transaction " +
                    "8012039dc8500f0899171365986cdfd5982dc2967843236c0e8467ca566945ef " +
                    "output 0 already completely used"
            )
        )
        val source = FakeSource(
            boundWalletId = { walletId },
            recoveryLocks = { listOf(lock(TrackedAssetLock.FundingType.IDENTITY_TOP_UP)) },
            onResume = { throw wrapped }
        )
        val report = runBlocking { service(source).drainPendingTopUps() }
        assertEquals(TopUpDrainReport(pending = 1, resumed = 0, alreadyConsumed = 1, failed = 0), report)
        assertFalse(report.retryNeeded)
    }

    @Test
    fun drain_oneFailure_doesNotStopTheRest_andRequestsRetry() {
        val failing = lock(TrackedAssetLock.FundingType.IDENTITY_TOP_UP, firstByte = 2)
        val fine = lock(TrackedAssetLock.FundingType.IDENTITY_TOP_UP_NOT_BOUND, firstByte = 3)
        val source = FakeSource(
            boundWalletId = { walletId },
            recoveryLocks = { listOf(failing, fine) },
            onResume = { l ->
                if (l === failing) throw DashSdkError.NetworkError("proof fetch timed out")
                newBalance
            }
        )
        val report = runBlocking { service(source).drainPendingTopUps() }
        assertEquals(TopUpDrainReport(pending = 2, resumed = 1, alreadyConsumed = 0, failed = 1), report)
        assertTrue(report.retryNeeded)
        assertEquals(2, source.resumeCalls)
    }

    @Test
    fun drain_locksButNoIdentity_countsAllFailed_noResumeCalls() {
        val source = FakeSource(
            boundWalletId = { walletId },
            recoveryLocks = { listOf(lock(TrackedAssetLock.FundingType.IDENTITY_TOP_UP)) }
        )
        val report = runBlocking { service(source, identity = { null }).drainPendingTopUps() }
        assertEquals(TopUpDrainReport(pending = 1, resumed = 0, alreadyConsumed = 0, failed = 1), report)
        assertTrue(report.retryNeeded)
        assertEquals(0, source.resumeCalls)
    }

    @Test
    fun drain_identityLookupThrow_countsAllFailed_noResumeCalls() {
        val source = FakeSource(
            boundWalletId = { walletId },
            recoveryLocks = { listOf(lock(TrackedAssetLock.FundingType.IDENTITY_TOP_UP)) }
        )
        val report = runBlocking {
            service(source, identity = { throw IllegalStateException("db closed") }).drainPendingTopUps()
        }
        assertEquals(TopUpDrainReport(pending = 1, resumed = 0, alreadyConsumed = 0, failed = 1), report)
        assertEquals(0, source.resumeCalls)
    }

    // ── hasPendingTopUpLocks ─────────────────────────────────────────────

    @Test
    fun hasPending_trueOnlyForTopUpTypes() {
        val source = FakeSource(
            boundWalletId = { walletId },
            recoveryLocks = { listOf(lock(TrackedAssetLock.FundingType.IDENTITY_REGISTRATION)) }
        )
        assertFalse(runBlocking { service(source).hasPendingTopUpLocks() })
        source.recoveryLocks = { listOf(lock(TrackedAssetLock.FundingType.IDENTITY_TOP_UP_NOT_BOUND)) }
        assertTrue(runBlocking { service(source).hasPendingTopUpLocks() })
    }

    @Test
    fun hasPending_containedOnFailure_andFalseWhenUnbound() {
        val throwing = FakeSource(
            boundWalletId = { walletId },
            recoveryLocks = { throw IllegalStateException("FFI unavailable") }
        )
        assertFalse(runBlocking { service(throwing).hasPendingTopUpLocks() })
        val unbound = FakeSource(boundWalletId = { null })
        assertFalse(runBlocking { service(unbound).hasPendingTopUpLocks() })
    }

    @Test
    fun hasPending_neverBootsTheSdk_whenNotStarted() {
        // The periodic-sync trigger must be a no-boot probe: SDK down ->
        // false WITHOUT touching the source (which would ensureStarted()).
        val source = FakeSource(
            boundWalletId = { walletId },
            recoveryLocks = { listOf(lock(TrackedAssetLock.FundingType.IDENTITY_TOP_UP)) }
        )
        assertFalse(runBlocking { service(source, sdkStarted = false).hasPendingTopUpLocks() })
        assertEquals(0, source.boundCalls)
    }
}
