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

package de.schildbach.wallet.service

import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.service.platform.sdk.SdkBlockchainStateUpdate
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.SyncStage
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.BlockchainState.Impediment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Date
import java.util.EnumSet

/**
 * Host-JVM tests for the kill-list Step B writer/rollback surface of
 * [BlockchainStateDataProvider]: [BlockchainStateDataProvider.updateSdkBlockchainState]
 * applied against a REAL prior Room row (chainlock preservation,
 * replaying=false, null-field preservation, impediment composition, the
 * bestChainHeightEver advance) and [BlockchainStateDataProvider.clearSdkDerivedState]
 * (the rollback hook that un-latches the SDK stall NETWORK impediment).
 *
 * The provider serializes writers on its own single-thread scope, so
 * assertions poll ([awaitUntil]) instead of assuming synchronous writes.
 */
class BlockchainStateDataProviderTest {

    /** In-memory stand-in for the Room DAO; @Volatile publishes writer-thread mutations. */
    private class FakeBlockchainStateDao : BlockchainStateDao() {
        @Volatile
        var state: BlockchainState? = null
        public override suspend fun insert(blockchainState: BlockchainState) {
            state = blockchainState
        }
        override suspend fun getState(): BlockchainState? = state
        override fun observeState(): Flow<BlockchainState?> = flowOf(state)
    }

    private val dao = FakeBlockchainStateDao()
    private val configuration = mockk<Configuration>(relaxed = true)
    private val provider = BlockchainStateDataProvider(
        context = mockk(relaxed = true),
        dashSystemService = mockk(relaxed = true),
        blockchainStateDao = dao,
        walletDataProvider = mockk(relaxed = true),
        configuration = configuration
    )

    private fun sdkUpdate(
        bestChainHeight: Int? = null,
        bestChainDateMs: Long? = null,
        percentageSync: Int? = null,
        mnListHeight: Int? = null,
        syncStage: SyncStage = SyncStage.BLOCKS,
        networkStalled: Boolean = false,
        chainlockHeight: Int? = null
    ) = SdkBlockchainStateUpdate(
        bestChainHeight = bestChainHeight,
        bestChainDateMs = bestChainDateMs,
        percentageSync = percentageSync,
        mnListHeight = mnListHeight,
        syncStage = syncStage,
        networkStalled = networkStalled,
        chainlockHeight = chainlockHeight
    )

    private fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out after ${timeoutMs}ms waiting for: $what")
            }
            Thread.sleep(10)
        }
    }

    @Test
    fun updateSdkBlockchainState_appliedAgainstRealPriorRow() {
        // A realistic dashj-written prior row, mid-replay, with a chainlock
        // height the SDK has no feed for and a STORAGE impediment reported
        // by the still-running Android-side monitors.
        dao.state = BlockchainState(
            Date(1_000_000_000L),
            1_500_000,
            true,
            EnumSet.of(Impediment.STORAGE),
            1_499_990,
            1_499_000,
            80
        )
        provider.updateImpediments(setOf(Impediment.STORAGE))

        provider.updateSdkBlockchainState(
            sdkUpdate(
                bestChainHeight = 1_500_100,
                bestChainDateMs = 2_000_000_000L,
                percentageSync = 90,
                mnListHeight = null, // no masternode knowledge → preserve
                syncStage = SyncStage.BLOCKS,
                networkStalled = true
            )
        )
        // The stage flow is set LAST on the writer thread — awaiting it is
        // the barrier that makes every earlier row mutation visible.
        awaitUntil("SDK update applied") { provider.getSyncStage() == SyncStage.BLOCKS }

        val row = dao.state!!
        assertEquals(1_500_100, row.bestChainHeight)
        assertEquals(Date(2_000_000_000L), row.bestChainDate)
        assertEquals(90, row.percentageSync)
        assertFalse("the SDK has no replay concept — the flag must clear", row.replaying)
        assertEquals("a null chainlockHeight preserves the row's value", 1_499_990, row.chainlockHeight)
        assertEquals("null mnListHeight preserves the row's value", 1_499_000, row.mnlistHeight)
        assertEquals(
            "service impediments compose with the SDK stall NETWORK bit",
            setOf(Impediment.STORAGE, Impediment.NETWORK),
            row.impediments
        )
    }

    @Test
    fun updateSdkBlockchainState_advancesBestChainHeightEver() {
        // FIX-pin: the ongoing-sync notification dismisses only when the
        // row's height reaches config.bestChainHeightEver, and only dashj
        // paths ever advanced that pref — the SDK writer must mirror
        // WalletApplication's maybe-increment write.
        provider.updateSdkBlockchainState(sdkUpdate(bestChainHeight = 1_600_000, percentageSync = 50))
        // The percent is mutated after the config call on the writer thread.
        awaitUntil("first update applied") { dao.state?.percentageSync == 50 }
        verify(exactly = 1) { configuration.maybeIncrementBestChainHeightEver(1_600_000) }

        // No header knowledge → no advance attempt either.
        provider.updateSdkBlockchainState(sdkUpdate(bestChainHeight = null, percentageSync = 51))
        awaitUntil("second update applied") { dao.state?.percentageSync == 51 }
        verify(exactly = 1) { configuration.maybeIncrementBestChainHeightEver(any()) }
    }

    @Test
    fun updateSdkBlockchainState_nullPercentagePreservesPriorPercent() {
        // FIX-pin: a transient SDK ERROR propagates percentageSync=null —
        // the row keeps its 100 (no 100 → 0 → 100 isSynced() flap) while
        // the stall NETWORK impediment raises.
        provider.updateSdkBlockchainState(sdkUpdate(percentageSync = 100, syncStage = SyncStage.COMPLETE))
        awaitUntil("synced row written") { dao.state?.percentageSync == 100 }

        provider.updateSdkBlockchainState(
            sdkUpdate(percentageSync = null, syncStage = SyncStage.OFFLINE, networkStalled = true)
        )
        awaitUntil("error update applied") { dao.state?.syncFailed() == true }
        assertEquals("percent preserved through the transient error", 100, dao.state!!.percentageSync)
    }

    @Test
    fun updateSdkBlockchainState_advancesChainlockHeightMonotonically() {
        // FIX-pin (chainlockHeight post-cutover): dashj's
        // chainLockHandler.bestChainLockBlockHeight stops advancing the moment
        // the peergroup is held, so the row froze at the cutover snapshot (0 on
        // a fresh restore) forever — ChainLockedCoinSelector then permanently
        // took its FALLBACK_CONFIRMATIONS depth branch. The SDK writer now
        // carries the engine's applied-chainlock height into the row.
        dao.state = BlockchainState(
            Date(1_000_000_000L),
            1_500_000,
            false,
            EnumSet.noneOf(Impediment::class.java),
            1_499_990,
            1_499_000,
            100
        )

        provider.updateSdkBlockchainState(sdkUpdate(percentageSync = 99, chainlockHeight = 1_500_050))
        awaitUntil("chainlock advance applied") { dao.state?.percentageSync == 99 }
        assertEquals(
            "an SDK-observed chainlock advances the row",
            1_500_050,
            dao.state!!.chainlockHeight
        )

        // A fresh session starts with no observed chainlock and the engine's
        // first event can be BELOW what dashj (or the last session) proved.
        // Monotonic: it must never walk the row backwards.
        provider.updateSdkBlockchainState(sdkUpdate(percentageSync = 98, chainlockHeight = 1_400_000))
        awaitUntil("lower chainlock update applied") { dao.state?.percentageSync == 98 }
        assertEquals(
            "a lower SDK chainlock height must not regress the row",
            1_500_050,
            dao.state!!.chainlockHeight
        )
    }

    @Test
    fun clearSdkDerivedState_unlatchesStallNetworkImpedimentAndSyncStage() {
        // FIX-pin: gate true → stalled update → gate false (rollback). The
        // stall bit must reset — otherwise every later composition keeps
        // NETWORK latched (syncFailed() until process restart) — and the
        // persisted row's impediments must recompose immediately.
        provider.updateSdkBlockchainState(
            sdkUpdate(percentageSync = 40, syncStage = SyncStage.BLOCKS, networkStalled = true)
        )
        awaitUntil("stall raised") { dao.state?.syncFailed() == true }
        awaitUntil("SDK stage driven") { provider.getSyncStage() == SyncStage.BLOCKS }

        provider.clearSdkDerivedState()
        awaitUntil("stall cleared") { dao.state?.syncFailed() == false }
        assertTrue("no service impediments → row composes empty", dao.state!!.impediments.isEmpty())
        assertEquals(
            "SDK-written sync stage neutralized for the dashj path to re-drive",
            SyncStage.OFFLINE,
            provider.getSyncStage()
        )
    }
}
