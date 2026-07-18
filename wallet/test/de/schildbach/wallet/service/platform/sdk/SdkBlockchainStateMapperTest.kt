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

import org.dash.wallet.common.data.SyncStage
import org.dashfoundation.dashsdk.wallet.SpvSubProgress
import org.dashfoundation.dashsdk.wallet.SpvSyncProgressData
import org.dashfoundation.dashsdk.wallet.SpvSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the kill-list Step B sync-state derivation: the pure
 * SDK-progress → BlockchainState field mapping ([deriveBlockchainStateUpdate]),
 * the stage mapping ([sdkSyncStage]) and the stall verdict
 * ([isSpvProgressStalled]) behind the derived NETWORK impediment.
 */
class SdkBlockchainStateMapperTest {

    private val now = 1_753_000_000_000L

    private fun progress(
        phase: ShadowSyncPhase = ShadowSyncPhase.FILTERS,
        headerHeight: Long = 1_514_000,
        headerTarget: Long = 1_514_660,
        filterHeight: Long = 1_400_000,
        filterTarget: Long = 1_514_660,
        mnListHeight: Long = 0
    ) = ShadowSyncProgress(phase, 0.0, headerHeight, headerTarget, filterHeight, filterTarget, mnListHeight)

    private fun snapshot(
        p: ShadowSyncProgress = progress(),
        tip: Long = 0,
        stalled: Boolean = false
    ) = SdkChainSnapshot(p, tip, stalled)

    // ── sdkSyncStage ──────────────────────────────────────────────────

    @Test
    fun stage_mapsEveryPhase() {
        assertEquals(SyncStage.OFFLINE, sdkSyncStage(ShadowSyncPhase.IDLE))
        assertEquals(SyncStage.OFFLINE, sdkSyncStage(ShadowSyncPhase.CONNECTING))
        assertEquals(SyncStage.OFFLINE, sdkSyncStage(ShadowSyncPhase.ERROR))
        assertEquals(SyncStage.HEADERS, sdkSyncStage(ShadowSyncPhase.HEADERS))
        assertEquals(SyncStage.HEADERS, sdkSyncStage(ShadowSyncPhase.FILTER_HEADERS))
        assertEquals(SyncStage.MNLIST, sdkSyncStage(ShadowSyncPhase.MASTERNODES))
        assertEquals(SyncStage.BLOCKS, sdkSyncStage(ShadowSyncPhase.FILTERS))
        assertEquals(SyncStage.COMPLETE, sdkSyncStage(ShadowSyncPhase.SYNCED))
    }

    // ── isSpvProgressStalled ──────────────────────────────────────────

    @Test
    fun stall_errorIsImmediate() {
        assertTrue(isSpvProgressStalled(ShadowSyncPhase.ERROR, 0))
    }

    @Test
    fun stall_idleAndSyncedNeverStall() {
        assertFalse(isSpvProgressStalled(ShadowSyncPhase.IDLE, Long.MAX_VALUE / 2))
        assertFalse(isSpvProgressStalled(ShadowSyncPhase.SYNCED, Long.MAX_VALUE / 2))
    }

    @Test
    fun stall_activePhasesStallAtThreshold() {
        for (phase in listOf(
            ShadowSyncPhase.CONNECTING,
            ShadowSyncPhase.HEADERS,
            ShadowSyncPhase.FILTER_HEADERS,
            ShadowSyncPhase.MASTERNODES,
            ShadowSyncPhase.FILTERS
        )) {
            assertFalse("$phase below", isSpvProgressStalled(phase, SPV_STALL_THRESHOLD_MS - 1))
            assertTrue("$phase at", isSpvProgressStalled(phase, SPV_STALL_THRESHOLD_MS))
        }
    }

    // ── deriveBlockchainStateUpdate ───────────────────────────────────

    @Test
    fun derive_syncingSnapshotMapsAllFields() {
        val update = deriveBlockchainStateUpdate(
            snapshot(progress(mnListHeight = 1_513_000)),
            now
        )
        assertEquals(1_514_000, update.bestChainHeight)
        assertEquals(1_513_000, update.mnListHeight)
        assertEquals(SyncStage.BLOCKS, update.syncStage)
        assertFalse(update.networkStalled)
        // Combined headers+filters percent — the shadowSyncPercent math.
        assertEquals(
            shadowSyncPercent(progress(mnListHeight = 1_513_000)),
            update.percentageSync
        )
        // No tip timestamp → estimated from the 660-block header gap.
        assertEquals(now - 660 * SDK_BLOCK_TARGET_SPACING_MS, update.bestChainDateMs)
    }

    @Test
    fun derive_tipTimestampWinsOverEstimate() {
        val tipSec = 1_752_999_000L
        val update = deriveBlockchainStateUpdate(snapshot(tip = tipSec), now)
        assertEquals(tipSec * 1000, update.bestChainDateMs)
    }

    @Test
    fun derive_noHeightKnowledgePreservesHeightAndDate() {
        val idle = progress(
            phase = ShadowSyncPhase.CONNECTING,
            headerHeight = 0, headerTarget = 0, filterHeight = 0, filterTarget = 0
        )
        val update = deriveBlockchainStateUpdate(snapshot(idle), now)
        assertNull(update.bestChainHeight)
        assertNull(update.bestChainDateMs)
        assertNull(update.mnListHeight)
        assertEquals(0, update.percentageSync)
        assertEquals(SyncStage.OFFLINE, update.syncStage)
    }

    @Test
    fun derive_syncedSnapshotIsComplete() {
        val synced = progress(
            phase = ShadowSyncPhase.SYNCED,
            headerHeight = 1_514_660, headerTarget = 1_514_660,
            filterHeight = 1_514_660, filterTarget = 1_514_660
        )
        val update = deriveBlockchainStateUpdate(snapshot(synced, tip = now / 1000), now)
        assertEquals(100, update.percentageSync)
        assertEquals(SyncStage.COMPLETE, update.syncStage)
        assertEquals(1_514_660, update.bestChainHeight)
        assertEquals(now / 1000 * 1000, update.bestChainDateMs)
    }

    @Test
    fun derive_headerAtTargetEstimatesDateAsNow() {
        val p = progress(headerHeight = 100, headerTarget = 100)
        assertEquals(now, deriveBlockchainStateUpdate(snapshot(p), now).bestChainDateMs)
    }

    @Test
    fun derive_stallPassesThrough() {
        assertTrue(deriveBlockchainStateUpdate(snapshot(stalled = true), now).networkStalled)
    }

    // ── mnListHeight through the SDK snapshot mapping ─────────────────

    @Test
    fun toShadowSyncProgress_carriesMasternodeHeight() {
        val data = SpvSyncProgressData(
            SpvSyncState.SYNCING,
            10.0,
            SpvSubProgress(SpvSyncState.SYNCED, 200, 200, 100.0),
            SpvSubProgress(SpvSyncState.SYNCED, 200, 200, 100.0),
            SpvSubProgress(SpvSyncState.SYNCING, 50, 200, 25.0),
            SpvSubProgress(SpvSyncState.SYNCED, 190, 200, 95.0)
        )
        assertEquals(190L, toShadowSyncProgress(data).mnListHeight)
        assertEquals(0L, toShadowSyncProgress(SpvSyncProgressData.EMPTY).mnListHeight)
    }
}
