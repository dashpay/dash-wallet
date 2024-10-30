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

package de.schildbach.wallet.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import de.schildbach.wallet.Constants
import org.bitcoinj.core.NetworkParameters
import java.util.concurrent.TimeUnit

@Entity(tableName = "username_requests")
data class UsernameRequest(
    @PrimaryKey
    val requestId: String,
    val username: String,
    val normalizedLabel: String,
    val createdAt: Long,
    val identity: String,
    var link: String?,
    val votes: Int,
    val lockVotes: Int,
    val isApproved: Boolean
) {
    companion object {
        val VOTING_PERIOD_MILLIS = if (Constants.NETWORK_PARAMETERS.id == NetworkParameters.ID_MAINNET) TimeUnit.DAYS.toMillis(14) else TimeUnit.MINUTES.toMillis(90)
        val SUBMIT_PERIOD_MILLIS = VOTING_PERIOD_MILLIS / 2
        fun getRequestId(identity: String, username: String): String {
            return String.format("%s-%s", identity, username)
        }
    }
}
