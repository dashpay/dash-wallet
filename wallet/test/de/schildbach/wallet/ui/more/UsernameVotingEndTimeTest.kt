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

package de.schildbach.wallet.ui.more

import de.schildbach.wallet.database.entity.UsernameRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Date guard for the More-screen requested-username tile
 * ([usernameVotingEndTime]): a missing or unset (0/-1) persisted voting
 * start must yield NO date — rendering epoch 0 produced "Results on
 * Dec 31, 1969" live. A real start yields start + the voting period.
 */
class UsernameVotingEndTimeTest {

    @Test
    fun `null voting start yields no date`() {
        assertNull(usernameVotingEndTime(null))
    }

    @Test
    fun `zero voting start (epoch placeholder) yields no date`() {
        assertNull(usernameVotingEndTime(0L))
    }

    @Test
    fun `negative voting start (-1 placeholder) yields no date`() {
        assertNull(usernameVotingEndTime(-1L))
    }

    @Test
    fun `real voting start yields start plus voting period`() {
        val start = 1_752_500_000_000L // mid-2025, arbitrary real timestamp
        assertEquals(
            start + UsernameRequest.VOTING_PERIOD_MILLIS,
            usernameVotingEndTime(start)
        )
    }
}
