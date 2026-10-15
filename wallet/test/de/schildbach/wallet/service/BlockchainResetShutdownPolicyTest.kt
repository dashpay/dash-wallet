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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Settings → Rescan Blockchain keeps the dashj blockstore; only the SDK
 * replays. The rescan databases may only be cleared when an SDK replay was
 * owed — nothing else would refill them. A wipe is unaffected by the owe result.
 */
class BlockchainResetShutdownPolicyTest {

    private fun rescan(owed: Boolean) = BlockchainResetShutdownPolicy.plan(
        resetBlockchainOnShutdown = true,
        deleteWalletFileOnShutdown = false,
        sdkRescanOwedForReset = owed
    )

    @Test
    fun rescan_withOwedReplay_clearsRescanDatabases() {
        val plan = rescan(owed = true)
        assertTrue(plan.clearRescanDatabases)
        assertFalse(plan.keepRescanDatabases)
        assertFalse(plan.wipeWallet)
        assertTrue(plan.resetMnListsAndState)
    }

    @Test
    fun rescan_withoutOwedReplay_keepsRescanDatabases() {
        val plan = rescan(owed = false)
        assertFalse(plan.clearRescanDatabases)
        assertTrue(plan.keepRescanDatabases)
        assertFalse(plan.wipeWallet)
        assertTrue(plan.resetMnListsAndState)
    }

    @Test
    fun rescan_keepsDashjStores_forBothOweOutcomes() {
        assertFalse(rescan(owed = true).deleteStores)
        assertFalse(rescan(owed = false).deleteStores)
    }

    @Test
    fun wipe_deletesStoresAndWipes_regardlessOfOweAndResetFlag() {
        for (reset in listOf(false, true)) {
            for (owed in listOf(false, true)) {
                val plan = BlockchainResetShutdownPolicy.plan(
                    resetBlockchainOnShutdown = reset,
                    deleteWalletFileOnShutdown = true,
                    sdkRescanOwedForReset = owed
                )
                val case = "reset=$reset owed=$owed"
                assertTrue(case, plan.deleteStores)
                assertTrue(case, plan.wipeWallet)
                assertTrue(case, plan.resetMnListsAndState)
                assertFalse(case, plan.clearRescanDatabases)
                assertFalse(case, plan.keepRescanDatabases)
            }
        }
    }

    @Test
    fun neitherFlag_doesNothing() {
        for (owed in listOf(false, true)) {
            assertEquals(
                BlockchainResetShutdownPolicy.NOTHING,
                BlockchainResetShutdownPolicy.plan(
                    resetBlockchainOnShutdown = false,
                    deleteWalletFileOnShutdown = false,
                    sdkRescanOwedForReset = owed
                )
            )
        }
        with(BlockchainResetShutdownPolicy.NOTHING) {
            assertFalse(deleteStores || resetMnListsAndState || wipeWallet || clearRescanDatabases || keepRescanDatabases)
        }
    }

    // --- owe-result mapping ---

    @Test
    fun owe_preCutover_isFalse_andOwesNothing() = runBlocking {
        var oweCalled = false
        val result = BlockchainResetShutdownPolicy.oweSdkRescan(
            sdkOwnsL1 = { false },
            owe = { oweCalled = true; true },
            onFailure = { fail("no failure expected") }
        )
        assertFalse(result)
        assertFalse("pre-cutover must not owe an SDK rescan", oweCalled)
    }

    @Test
    fun owe_false_isFalse() = runBlocking {
        assertFalse(
            BlockchainResetShutdownPolicy.oweSdkRescan(
                sdkOwnsL1 = { true },
                owe = { false },
                onFailure = { fail("no failure expected") }
            )
        )
    }

    @Test
    fun owe_true_isTrue() = runBlocking {
        assertTrue(
            BlockchainResetShutdownPolicy.oweSdkRescan(
                sdkOwnsL1 = { true },
                owe = { true },
                onFailure = { fail("no failure expected") }
            )
        )
    }

    @Test
    fun owe_throwing_isFalse_andReported() = runBlocking {
        val boom = IllegalStateException("boom")
        var reported: Throwable? = null
        val result = BlockchainResetShutdownPolicy.oweSdkRescan(
            sdkOwnsL1 = { true },
            owe = { throw boom },
            onFailure = { reported = it }
        )
        assertFalse(result)
        assertSame(boom, reported)
    }

    @Test
    fun ownershipCheck_throwing_isFalse_andReported() = runBlocking {
        val boom = RuntimeException("store read failed")
        var reported: Throwable? = null
        val result = BlockchainResetShutdownPolicy.oweSdkRescan(
            sdkOwnsL1 = { throw boom },
            owe = { fail("must not owe after a failed ownership check"); true },
            onFailure = { reported = it }
        )
        assertFalse(result)
        assertSame(boom, reported)
    }

    @Test
    fun owe_cancellation_isRethrown_andNotReported() = runBlocking {
        val cancel = CancellationException("cancelled")
        try {
            BlockchainResetShutdownPolicy.oweSdkRescan(
                sdkOwnsL1 = { true },
                owe = { throw cancel },
                onFailure = { fail("cancellation must not be reported as a failure") }
            )
            fail("CancellationException must be rethrown")
        } catch (e: CancellationException) {
            assertSame(cancel, e)
        }
    }
}
