/*
 * Copyright 2024 Dash Core Group.
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

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "username_votes")
data class UsernameVote(
    @PrimaryKey(autoGenerate = true)
    val id: Int,
    val username: String,
    val identity: String,
    val type: String, // approve, abstain, lock
    val timestamp: Long = System.currentTimeMillis()
) {
    constructor(username: String, identity: String, type: String): this(0, username, identity, type)
    companion object {
        const val APPROVE = "approve"
        const val LOCK = "lock"
        const val ABSTAIN = "abstain"
        const val MAX_VOTES = 6
    }
}
