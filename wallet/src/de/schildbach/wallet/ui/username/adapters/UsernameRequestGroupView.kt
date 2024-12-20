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

data class UsernameRequestGroupView(
    val username: String,
    val requests: List<UsernameRequest>,
    var isExpanded: Boolean = false,
    var votes: List<UsernameVote>
) {
    val lastVote: UsernameVote?
        get() = votes.lastOrNull()

    val totalVotes: Int
        get() = votes.size

    // all username votes should have the same number of lock votes
    fun lockVotes(): Int {
        return requests.first().lockVotes
    }
}
