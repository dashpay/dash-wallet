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

package de.schildbach.wallet.database.entity

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The record a recovery starts from is read strictly: an unreadable store must fail the recovery
 * (it is retried later, nothing reset), never read as "no record, no requested names", which is a
 * genuine fresh restore and starts the walk from an empty record.
 */
class RecoverySeedStrictReadTest {
    private val identityId = "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr"

    @Test
    fun `an unreadable store fails the recovery instead of seeding it empty`() {
        val config = mockk<BlockchainIdentityConfig> {
            // what the IOException-swallowing reads deliver on a failed read
            coEvery { load() } returns null
            coEvery { requestedNames() } returns null
            coEvery { readStrict() } throws java.io.IOException("datastore unreadable")
            coEvery { loadRecoverySeed(any(), any()) } answers { callOriginal() }
        }

        assertThrows(java.io.IOException::class.java) {
            runBlocking { config.loadRecoverySeed(identityId, fromCreation = false) }
        }
        assertThrows(java.io.IOException::class.java) {
            runBlocking { config.loadRecoverySeed(identityId, fromCreation = true) }
        }
    }

    @Test
    fun `the retry routing fields come from one strict read that throws when unreadable`() {
        val config = mockk<BlockchainIdentityConfig> {
            coEvery { readStrict() } throws java.io.IOException("datastore unreadable")
            coEvery { retryRouting() } answers { callOriginal() }
        }

        assertThrows(java.io.IOException::class.java) { runBlocking { config.retryRouting() } }
    }

    @Test
    fun `the retry routing snapshot carries every routing field of the one read`() = runBlocking {
        val prefs = androidx.datastore.preferences.core.mutablePreferencesOf(
            BlockchainIdentityConfig.CREATION_STATE_ERROR_MESSAGE to "boom",
            BlockchainIdentityConfig.IDENTITY_ID to identityId,
            BlockchainIdentityConfig.USERNAME to "alice3",
            BlockchainIdentityConfig.USERNAME_SECONDARY to "alice4",
            BlockchainIdentityConfig.RESTORING to true,
            BlockchainIdentityConfig.USING_INVITE to true
        )
        val config = mockk<BlockchainIdentityConfig> {
            coEvery { readStrict() } returns prefs
            coEvery { retryRouting() } answers { callOriginal() }
        }

        org.junit.Assert.assertEquals(
            BlockchainIdentityConfig.RetryRouting("boom", identityId, "alice3", "alice4", restoring = true, usingInvite = true),
            config.retryRouting()
        )
    }

    @Test
    fun `the requested names are read strictly`() {
        val config = mockk<BlockchainIdentityConfig> {
            coEvery { readStrict() } throws java.io.IOException("datastore unreadable")
            coEvery { requestedNames() } answers { callOriginal() }
        }

        assertThrows(java.io.IOException::class.java) { runBlocking { config.requestedNames() } }
    }
}
