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

package de.schildbach.wallet.ui.main

import de.schildbach.wallet.database.entity.UsernameRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Display-form selection for the requested username
 * ([resolveRequestedUsernameDisplay]): the More-screen tile must show the
 * name the user typed ("contested1"), never the DPNS-normalized label
 * ("c0ntested1") the restore path historically persisted (observed live).
 */
class RequestedUsernameDisplayTest {

    private val identityId = "GbGHf6nvRRTGrM8GsUXBHKtNBCkkcHWnxSSHY2SpYT4h"

    private fun request(
        username: String,
        normalizedLabel: String,
        identity: String = identityId
    ) = UsernameRequest(
        requestId = "$identity-$normalizedLabel",
        username = username,
        normalizedLabel = normalizedLabel,
        createdAt = 1_752_500_000_000L,
        identity = identity,
        link = null,
        votes = 0,
        lockVotes = 0,
        isApproved = false
    )

    @Test
    fun `normalized stored label resolves to the request's display label`() {
        assertEquals(
            "contested1",
            resolveRequestedUsernameDisplay(
                stored = "c0ntested1",
                identityId = identityId,
                candidates = listOf(request(username = "contested1", normalizedLabel = "c0ntested1"))
            )
        )
    }

    @Test
    fun `display-form stored label stays as-is`() {
        assertEquals(
            "contested1",
            resolveRequestedUsernameDisplay(
                stored = "contested1",
                identityId = identityId,
                candidates = listOf(request(username = "contested1", normalizedLabel = "c0ntested1"))
            )
        )
    }

    @Test
    fun `no matching request falls back to the stored value`() {
        assertEquals(
            "c0ntested1",
            resolveRequestedUsernameDisplay(
                stored = "c0ntested1",
                identityId = identityId,
                candidates = emptyList()
            )
        )
    }

    @Test
    fun `another identity's request never wins`() {
        assertEquals(
            "c0ntested1",
            resolveRequestedUsernameDisplay(
                stored = "c0ntested1",
                identityId = identityId,
                candidates = listOf(
                    request(username = "cOntested1", normalizedLabel = "c0ntested1", identity = "someoneElse")
                )
            )
        )
    }

    @Test
    fun `unknown identity accepts any matching request`() {
        assertEquals(
            "contested1",
            resolveRequestedUsernameDisplay(
                stored = "c0ntested1",
                identityId = null,
                candidates = listOf(request(username = "contested1", normalizedLabel = "c0ntested1"))
            )
        )
    }

    @Test
    fun `request with an empty display label falls back to the stored value`() {
        assertEquals(
            "c0ntested1",
            resolveRequestedUsernameDisplay(
                stored = "c0ntested1",
                identityId = identityId,
                candidates = listOf(request(username = "", normalizedLabel = "c0ntested1"))
            )
        )
    }
}
