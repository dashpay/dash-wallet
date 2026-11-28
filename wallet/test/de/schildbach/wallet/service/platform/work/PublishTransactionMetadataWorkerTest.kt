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

package de.schildbach.wallet.service.platform.work

import de.schildbach.wallet.service.platform.TxMetadataSaveInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The publish worker must neither publish nor record a save once Reset Wallet is under way. */
class PublishTransactionMetadataWorkerTest {

    private val calls = mutableListOf<String>()
    private val complete = TxMetadataSaveInfo(itemsSaved = 3, itemsToSave = 3)

    private suspend fun publish(resetChecks: List<Boolean>, saveInfo: TxMetadataSaveInfo = complete): TxMetadataSaveInfo? {
        val answers = resetChecks.iterator()
        return PublishTransactionMetadataWorker.publishUnlessWalletReset(
            walletResetInProgress = { answers.next() },
            publish = { calls += "publish"; saveInfo },
            recordSave = { calls += "record save" }
        )
    }

    @Test
    fun noResetPublishesAndRecordsTheSave() = runTest {
        assertEquals(complete, publish(resetChecks = listOf(false, false)))
        assertEquals(listOf("publish", "record save"), calls)
    }

    @Test
    fun aResetBeforeTheWorkerStartsPublishesNothing() = runTest {
        assertNull(publish(resetChecks = listOf(true)))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun aResetDuringThePublishDoesNotRecordTheSave() = runTest {
        assertNull(publish(resetChecks = listOf(false, true)))
        assertEquals(listOf("publish"), calls)
    }

    @Test
    fun anIncompletePublishIsNotRecorded() = runTest {
        val partial = TxMetadataSaveInfo(itemsSaved = 1, itemsToSave = 3)

        assertEquals(partial, publish(resetChecks = listOf(false), saveInfo = partial))
        assertEquals(listOf("publish"), calls)
    }
}
