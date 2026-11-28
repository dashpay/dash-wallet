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

import de.schildbach.wallet.WalletApplicationExt.resetInMemoryWalletState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The process-wide seam lock registry does not outlive Reset Wallet into the next wallet. */
class SeamOutputLockRegistryTest {

    @Test
    fun clearForWalletWipe_dropsEveryLock() {
        val registry = SeamOutputLockRegistry()
        registry.lockOutput("EE".repeat(32), 0)
        registry.lockOutput("ff".repeat(32), 3)
        assertTrue(registry.isLocked("ee".repeat(32), 0))
        assertTrue(registry.outputsAppLocked("test") { false })

        registry.clearForWalletWipe()

        assertFalse(registry.hasAnyLocks())
        assertFalse(registry.isLocked("ee".repeat(32), 0))
        assertFalse(registry.outputsAppLocked("test") { false })
        // The next wallet's own locks work as before.
        registry.lockOutput("aa".repeat(32), 1)
        assertTrue(registry.isLocked("aa".repeat(32), 1))
    }

    /**
     * The wipe's in-memory reset clears the registry, stops the migration watcher and
     * resets the shield resume budget — each even when an earlier step fails.
     */
    @Test
    fun resetInMemoryWalletState_runsEveryStepDespiteFailures() = runBlocking {
        val registry = SeamOutputLockRegistry().apply { lockOutput("ee".repeat(32), 0) }
        var stops = 0
        var resets = 0
        resetInMemoryWalletState(
            registry,
            { stops++; throw IllegalStateException("watcher stop failed") },
            { resets++ }
        )
        assertFalse(registry.hasAnyLocks())
        assertEquals(1, stops)
        assertEquals(1, resets)

        val broken = mockk<SeamOutputLockRegistry> {
            every { clearForWalletWipe() } throws IllegalStateException("registry unavailable")
        }
        resetInMemoryWalletState(broken, { stops++ }, { resets++ })
        verify(exactly = 1) { broken.clearForWalletWipe() }
        assertEquals(2, stops)
        assertEquals(2, resets)
    }
}
