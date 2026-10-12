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
 * armed — nothing else would refill them. A wipe is unaffected by the arm.
 */
class BlockchainResetShutdownPolicyTest {

    private fun rescan(armed: Boolean) = BlockchainResetShutdownPolicy.plan(
        resetBlockchainOnShutdown = true,
        deleteWalletFileOnShutdown = false,
        sdkRescanArmedForReset = armed
    )

    @Test
    fun rescan_withArmedReplay_clearsRescanDatabases() {
        val plan = rescan(armed = true)
        assertTrue(plan.clearRescanDatabases)
        assertFalse(plan.keepRescanDatabases)
        assertFalse(plan.wipeWallet)
        assertTrue(plan.resetMnListsAndState)
    }

    @Test
    fun rescan_withoutArmedReplay_keepsRescanDatabases() {
        val plan = rescan(armed = false)
        assertFalse(plan.clearRescanDatabases)
        assertTrue(plan.keepRescanDatabases)
        assertFalse(plan.wipeWallet)
        assertTrue(plan.resetMnListsAndState)
    }

    @Test
    fun rescan_keepsDashjStores_forBothArmOutcomes() {
        assertFalse(rescan(armed = true).deleteStores)
        assertFalse(rescan(armed = false).deleteStores)
    }

    @Test
    fun wipe_deletesStoresAndWipes_regardlessOfArmAndResetFlag() {
        for (reset in listOf(false, true)) {
            for (armed in listOf(false, true)) {
                val plan = BlockchainResetShutdownPolicy.plan(
                    resetBlockchainOnShutdown = reset,
                    deleteWalletFileOnShutdown = true,
                    sdkRescanArmedForReset = armed
                )
                val case = "reset=$reset armed=$armed"
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
        for (armed in listOf(false, true)) {
            assertEquals(
                BlockchainResetShutdownPolicy.NOTHING,
                BlockchainResetShutdownPolicy.plan(
                    resetBlockchainOnShutdown = false,
                    deleteWalletFileOnShutdown = false,
                    sdkRescanArmedForReset = armed
                )
            )
        }
        with(BlockchainResetShutdownPolicy.NOTHING) {
            assertFalse(deleteStores || resetMnListsAndState || wipeWallet || clearRescanDatabases || keepRescanDatabases)
        }
    }

    // --- arm-result mapping ---

    @Test
    fun arm_preCutover_isFalse_andDoesNotArm() = runBlocking {
        var armCalled = false
        val result = BlockchainResetShutdownPolicy.armSdkRescan(
            sdkOwnsL1 = { false },
            arm = { armCalled = true; true },
            onFailure = { fail("no failure expected") }
        )
        assertFalse(result)
        assertFalse("pre-cutover must not arm an SDK rescan", armCalled)
    }

    @Test
    fun arm_false_isFalse() = runBlocking {
        assertFalse(
            BlockchainResetShutdownPolicy.armSdkRescan(
                sdkOwnsL1 = { true },
                arm = { false },
                onFailure = { fail("no failure expected") }
            )
        )
    }

    @Test
    fun arm_true_isTrue() = runBlocking {
        assertTrue(
            BlockchainResetShutdownPolicy.armSdkRescan(
                sdkOwnsL1 = { true },
                arm = { true },
                onFailure = { fail("no failure expected") }
            )
        )
    }

    @Test
    fun arm_throwing_isFalse_andReported() = runBlocking {
        val boom = IllegalStateException("boom")
        var reported: Throwable? = null
        val result = BlockchainResetShutdownPolicy.armSdkRescan(
            sdkOwnsL1 = { true },
            arm = { throw boom },
            onFailure = { reported = it }
        )
        assertFalse(result)
        assertSame(boom, reported)
    }

    @Test
    fun ownershipCheck_throwing_isFalse_andReported() = runBlocking {
        val boom = RuntimeException("store read failed")
        var reported: Throwable? = null
        val result = BlockchainResetShutdownPolicy.armSdkRescan(
            sdkOwnsL1 = { throw boom },
            arm = { fail("must not arm after a failed ownership check"); true },
            onFailure = { reported = it }
        )
        assertFalse(result)
        assertSame(boom, reported)
    }

    @Test
    fun arm_cancellation_isRethrown_andNotReported() = runBlocking {
        val cancel = CancellationException("cancelled")
        try {
            BlockchainResetShutdownPolicy.armSdkRescan(
                sdkOwnsL1 = { true },
                arm = { throw cancel },
                onFailure = { fail("cancellation must not be reported as a failure") }
            )
            fail("CancellationException must be rethrown")
        } catch (e: CancellationException) {
            assertSame(cancel, e)
        }
    }
}
