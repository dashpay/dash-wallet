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

import org.bitcoinj.core.Context
import org.bitcoinj.core.MasternodeSync
import org.bitcoinj.core.MasternodeSync.SYNC_FLAGS
import org.bitcoinj.core.MasternodeSync.VERIFY_FLAGS
import org.bitcoinj.params.TestNet3Params
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.EnumSet

class DashjEngineRoleTest {

    // The app's Constants.SYNC_FLAGS / VERIFY_FLAGS are dashj's statics plus
    // the flavor's additions; build the same shape locally so the test never
    // touches (or depends on) the real statics.
    private lateinit var baseSync: EnumSet<SYNC_FLAGS>
    private lateinit var baseVerify: EnumSet<VERIFY_FLAGS>
    private lateinit var masternodeSync: MasternodeSync

    @Before
    fun setUp() {
        Context.propagate(Context.getOrCreate(TestNet3Params.get()))
        baseSync = EnumSet.copyOf(MasternodeSync.SYNC_DEFAULT_SPV).apply {
            add(SYNC_FLAGS.SYNC_HEADERS_MN_LIST_FIRST)
            add(SYNC_FLAGS.SYNC_BLOCKS_AFTER_PREPROCESSING)
        }
        baseVerify = EnumSet.copyOf(MasternodeSync.VERIFY_DEFAULT_SPV)
        // As WalletApplication.finalizeInitialization's initDash builds it:
        // the 3-argument constructor keeps both passed sets by reference.
        masternodeSync = MasternodeSync(Context.get(), baseSync, baseVerify)
    }

    // ── role resolution ──────────────────────────────────────────────

    @Test
    fun `pre-cutover is PRIMARY whatever the toggle says`() {
        assertEquals(DashjEngineRole.PRIMARY, DashjEngineRole.resolve(dashjHeldByCutover = false, false))
        assertEquals(DashjEngineRole.PRIMARY, DashjEngineRole.resolve(dashjHeldByCutover = false, true))
    }

    @Test
    fun `cutover committed with the toggle off is HELD`() {
        assertEquals(DashjEngineRole.HELD, DashjEngineRole.resolve(dashjHeldByCutover = true, false))
    }

    @Test
    fun `cutover committed with the toggle on is DIAGNOSTIC`() {
        val role = DashjEngineRole.resolve(dashjHeldByCutover = true, dashjSyncDiagnostic = true)
        assertEquals(DashjEngineRole.DIAGNOSTIC, role)
        assertTrue(role.isDiagnostic)
        assertFalse(DashjEngineRole.PRIMARY.isDiagnostic)
        assertFalse(DashjEngineRole.HELD.isDiagnostic)
    }

    // ── sync flag sets ───────────────────────────────────────────────

    @Test
    fun `PRIMARY and HELD keep the base sync set itself`() {
        assertSame(baseSync, dashjSyncFlags(DashjEngineRole.PRIMARY, baseSync))
        assertSame(baseSync, dashjSyncFlags(DashjEngineRole.HELD, baseSync))
    }

    @Test
    fun `DIAGNOSTIC drops only InstantSend and ChainLocks, on a copy`() {
        val before = EnumSet.copyOf(baseSync)
        val flags = dashjSyncFlags(DashjEngineRole.DIAGNOSTIC, baseSync)

        assertNotSame(baseSync, flags)
        assertEquals("base set must never be mutated (it is dashj's static)", before, baseSync)
        assertFalse(flags.contains(SYNC_FLAGS.SYNC_INSTANTSENDLOCKS))
        assertFalse(flags.contains(SYNC_FLAGS.SYNC_CHAINLOCKS))
        val expected = EnumSet.copyOf(baseSync).apply {
            remove(SYNC_FLAGS.SYNC_INSTANTSENDLOCKS)
            remove(SYNC_FLAGS.SYNC_CHAINLOCKS)
        }
        assertEquals(expected, flags)
        // Sporks, headers-first, blocks, masternode list and quorums stay.
        assertTrue(flags.contains(SYNC_FLAGS.SYNC_SPORKS))
        assertTrue(flags.contains(SYNC_FLAGS.SYNC_QUORUM_LIST))
        assertTrue(flags.contains(SYNC_FLAGS.SYNC_MASTERNODE_LIST))
        assertTrue(flags.contains(SYNC_FLAGS.SYNC_HEADERS_MN_LIST_FIRST))
        assertTrue(flags.contains(SYNC_FLAGS.SYNC_BLOCKS_AFTER_PREPROCESSING))
    }

    @Test
    fun `DIAGNOSTIC copy is independent of later changes to the base`() {
        val flags = dashjSyncFlags(DashjEngineRole.DIAGNOSTIC, baseSync)
        baseSync.add(SYNC_FLAGS.SYNC_GOVERNANCE)
        assertFalse(flags.contains(SYNC_FLAGS.SYNC_GOVERNANCE))
    }

    // ── applying to MasternodeSync ───────────────────────────────────

    @Test
    fun `PRIMARY and HELD leave the MasternodeSync exactly as initDash built it`() {
        assertFalse(applyDashjEngineSyncFlags(masternodeSync, DashjEngineRole.PRIMARY, baseSync))
        assertFalse(applyDashjEngineSyncFlags(masternodeSync, DashjEngineRole.HELD, baseSync))
        assertSame(baseSync, masternodeSync.syncFlags)
        assertSame(baseVerify, masternodeSync.verifyFlags)
    }

    @Test
    fun `DIAGNOSTIC turns off IS and CL requests but keeps verification`() {
        val baseBefore = EnumSet.copyOf(baseSync)

        assertTrue(applyDashjEngineSyncFlags(masternodeSync, DashjEngineRole.DIAGNOSTIC, baseSync))

        assertFalse(masternodeSync.hasSyncFlag(SYNC_FLAGS.SYNC_INSTANTSENDLOCKS))
        assertFalse(masternodeSync.hasSyncFlag(SYNC_FLAGS.SYNC_CHAINLOCKS))
        assertTrue(masternodeSync.hasSyncFlag(SYNC_FLAGS.SYNC_QUORUM_LIST))
        assertTrue(masternodeSync.hasSyncFlag(SYNC_FLAGS.SYNC_SPORKS))
        assertEquals(baseBefore, baseSync)
        // An unsolicited lock must still be verified, never trusted blindly.
        assertSame(baseVerify, masternodeSync.verifyFlags)
        assertTrue(masternodeSync.hasVerifyFlag(VERIFY_FLAGS.CHAINLOCK))
        assertTrue(masternodeSync.hasVerifyFlag(VERIFY_FLAGS.INSTANTSENDLOCK))
    }

    @Test
    fun `applying DIAGNOSTIC twice changes nothing the second time`() {
        assertTrue(applyDashjEngineSyncFlags(masternodeSync, DashjEngineRole.DIAGNOSTIC, baseSync))
        val first = masternodeSync.syncFlags
        assertFalse(applyDashjEngineSyncFlags(masternodeSync, DashjEngineRole.DIAGNOSTIC, baseSync))
        assertSame(first, masternodeSync.syncFlags)
    }

    @Test
    fun `dashj owning L1 again in the same process restores the original set`() {
        applyDashjEngineSyncFlags(masternodeSync, DashjEngineRole.DIAGNOSTIC, baseSync)

        assertTrue(applyDashjEngineSyncFlags(masternodeSync, DashjEngineRole.PRIMARY, baseSync))

        assertSame(baseSync, masternodeSync.syncFlags)
        assertTrue(masternodeSync.hasSyncFlag(SYNC_FLAGS.SYNC_INSTANTSENDLOCKS))
        assertTrue(masternodeSync.hasSyncFlag(SYNC_FLAGS.SYNC_CHAINLOCKS))
    }

    @Test
    fun `back to HELD after the diagnostic restores the original set too`() {
        applyDashjEngineSyncFlags(masternodeSync, DashjEngineRole.DIAGNOSTIC, baseSync)
        assertTrue(applyDashjEngineSyncFlags(masternodeSync, DashjEngineRole.HELD, baseSync))
        assertSame(baseSync, masternodeSync.syncFlags)
    }
}
