/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.ui.username.adapters

import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.database.entity.UsernameVote
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Base class for items in the Username Request list.  This will be used to display
 * hold a date to display some presentation of time.
 */
open class UsernameRequestRowView(
    val localDate: LocalDate
) {
    override fun equals(other: Any?): Boolean {
        return other is UsernameRequestRowView && other.localDate == localDate
    }
}

/** Represents a username contest that has one or more username requests
 *  for the same normalizedLabel.
 */
data class UsernameRequestGroupView(
    val username: String,
    val requests: List<UsernameRequest>,
    var isExpanded: Boolean = false,
    var votes: List<UsernameVote>,
    val votingEndDate: Long
) : UsernameRequestRowView(Instant.ofEpochMilli(votingEndDate).atZone(ZoneId.systemDefault()).toLocalDate()) {
    val lastVote: UsernameVote?
        get() = votes.lastOrNull()

    val totalVotes: Int
        get() = votes.size

    // all username votes should have the same number of lock votes
    fun lockVotes(): Int {
        return requests.first().lockVotes
    }
}
