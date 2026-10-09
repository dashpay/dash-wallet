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

package de.schildbach.wallet.ui.dashpay.work

import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.sdk.ContestedUsernameFees
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SendInviteWorker.contestedFor]: explicit flag wins; a persisted pre-flag request infers it. */
class SendInviteWorkerContestedTest {

    @Test
    fun `explicit flag is honoured either way`() {
        assertTrue(SendInviteWorker.contestedFor(Constants.DASH_PAY_FEE.value, explicit = true))
        assertFalse(SendInviteWorker.contestedFor(ContestedUsernameFees.LEGACY.contested.value, explicit = false))
    }

    @Test
    fun `a persisted request without the flag is inferred from its stored amount`() {
        assertTrue(SendInviteWorker.contestedFor(ContestedUsernameFees.LEGACY.contested.value, explicit = null))
        assertTrue(SendInviteWorker.contestedFor(ContestedUsernameFees.CURRENT.contested.value, explicit = null))
        assertFalse(SendInviteWorker.contestedFor(Constants.DASH_PAY_FEE.value, explicit = null))
    }
}
