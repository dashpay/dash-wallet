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

import androidx.datastore.preferences.core.mutablePreferencesOf
import de.schildbach.wallet.service.platform.RequestedNameContext
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A name-only retry's start is one write: the record the home tile and the More screen retry from
 * (identity id, names, restoring, the old request status) changes together with the pending mark,
 * so a partial write can never leave a pending-free record that has lost the requested secondary.
 */
class NameOnlyRetryStartWriteTest {
    private val identityId = "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr"

    private fun lostVoteRecord() = mutablePreferencesOf(
        BlockchainIdentityConfig.CREATION_STATE to IdentityCreationState.VOTING.name,
        BlockchainIdentityConfig.USERNAME to "alice2",
        BlockchainIdentityConfig.USERNAME_SECONDARY to "alice2instant",
        BlockchainIdentityConfig.IDENTITY_ID to identityId,
        BlockchainIdentityConfig.RESTORING to true,
        BlockchainIdentityConfig.USERNAME_REQUESTED to UsernameRequestStatus.LOST_VOTE.name
    )

    @Test
    fun `the retry context is written with the pending mark`() {
        val prefs = lostVoteRecord()

        BlockchainIdentityConfig.writeNameOnlyRetryStart(prefs, RequestedNameContext(identityId, "alice3", "alice4"))

        assertEquals(identityId, prefs[BlockchainIdentityConfig.IDENTITY_ID])
        assertEquals("alice3", prefs[BlockchainIdentityConfig.USERNAME])
        assertEquals("alice4", prefs[BlockchainIdentityConfig.USERNAME_SECONDARY])
        assertEquals(false, prefs[BlockchainIdentityConfig.RESTORING])
        assertNull("the old name's lost vote is gone", prefs[BlockchainIdentityConfig.USERNAME_REQUESTED])
        assertEquals(IdentityCreationState.USERNAME_REGISTERING.name, prefs[BlockchainIdentityConfig.CREATION_STATE])
        assertEquals(true, prefs[BlockchainIdentityConfig.NAME_ONLY_RETRY_PENDING])
        assertEquals("alice4", prefs[BlockchainIdentityConfig.REQUESTED_NAMES_USERNAME_SECONDARY])
    }

    @Test
    fun `with no requested secondary the registered one stays`() {
        val prefs = lostVoteRecord()

        BlockchainIdentityConfig.writeNameOnlyRetryStart(prefs, RequestedNameContext(identityId, "alice3", null))

        assertEquals("alice3", prefs[BlockchainIdentityConfig.USERNAME])
        assertEquals("alice2instant", prefs[BlockchainIdentityConfig.USERNAME_SECONDARY])
        assertNull(prefs[BlockchainIdentityConfig.REQUESTED_NAMES_USERNAME_SECONDARY])
    }
}
