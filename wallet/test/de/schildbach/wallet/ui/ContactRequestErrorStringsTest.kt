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

package de.schildbach.wallet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host-JVM tests for the accept-vs-send error wording selection
 * ([contactRequestErrorStrings]) used by DashPayUserActivity, which both sends
 * outgoing contact requests and accepts incoming ones through the same code
 * path. A failed ACCEPT must say "accepting", not "sending".
 */
class ContactRequestErrorStringsTest {

    private val acceptTitle = 11
    private val acceptMessage = 12
    private val sendTitle = 21
    private val sendMessage = 22

    @Test
    fun accept_usesAcceptStrings() {
        val result = contactRequestErrorStrings(
            isAccept = true,
            acceptTitleRes = acceptTitle,
            acceptMessageRes = acceptMessage,
            sendTitleRes = sendTitle,
            sendMessageRes = sendMessage
        )
        assertEquals(acceptTitle, result.titleRes)
        assertEquals(acceptMessage, result.messageRes)
    }

    @Test
    fun send_usesSendStrings() {
        val result = contactRequestErrorStrings(
            isAccept = false,
            acceptTitleRes = acceptTitle,
            acceptMessageRes = acceptMessage,
            sendTitleRes = sendTitle,
            sendMessageRes = sendMessage
        )
        assertEquals(sendTitle, result.titleRes)
        assertEquals(sendMessage, result.messageRes)
    }
}
