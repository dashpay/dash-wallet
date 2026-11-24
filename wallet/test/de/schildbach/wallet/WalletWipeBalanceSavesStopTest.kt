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

package de.schildbach.wallet

import de.schildbach.wallet.WalletApplicationExt.clearWithBalanceSavesStopped
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reset Wallet stops the balance saves before it clears anything, and never leaves them stopped. */
class WalletWipeBalanceSavesStopTest {

    @Test
    fun balanceSavesAreStoppedBeforeAnythingIsCleared() = runBlocking {
        val steps = mutableListOf<String>()
        clearWithBalanceSavesStopped(
            stopBalanceSaves = { steps += "stop" },
            clearBeforeDatabases = { steps += "configs" },
            clearDatabases = { steps += "databases" },
            resumeSdkUiPipeline = { steps += "resume" }
        )
        // The database clear re-arms the pipeline itself, so no resume here.
        assertEquals(listOf("stop", "configs", "databases"), steps)
    }

    @Test
    fun aFailedStopDoesNotSkipTheClears() = runBlocking {
        val steps = mutableListOf<String>()
        clearWithBalanceSavesStopped(
            stopBalanceSaves = { throw IllegalStateException("stop failed") },
            clearBeforeDatabases = { steps += "configs" },
            clearDatabases = { steps += "databases" },
            resumeSdkUiPipeline = { steps += "resume" }
        )
        assertEquals(listOf("configs", "databases"), steps)
    }

    @Test
    fun aWipeThatFailsBeforeTheDatabaseClearReArmsThePipeline() = runBlocking {
        val steps = mutableListOf<String>()
        val failure = runCatching {
            clearWithBalanceSavesStopped(
                stopBalanceSaves = { steps += "stop" },
                clearBeforeDatabases = { throw IllegalStateException("file delete failed") },
                clearDatabases = { steps += "databases" },
                resumeSdkUiPipeline = { steps += "resume" }
            )
        }
        assertTrue(failure.isFailure)
        assertEquals(listOf("stop", "resume"), steps)
    }
}
