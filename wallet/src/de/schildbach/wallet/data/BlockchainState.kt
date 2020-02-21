/*
 * Copyright 2020 Dash Core Group.
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

package de.schildbach.wallet.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.*

@Entity(tableName = "blockchain_state")
data class BlockchainState(val bestChainDate: Date?,
                           val bestChainHeight: Int,
                           val replaying: Boolean,
                           val impediments: Set<Impediment>,
                           val chainlockHeight: Int,
                           val mnlistHeight: Int,
                           val percentageSync: Int) {

    @PrimaryKey
    var id = 1
        set(value) {
            field = 1
        }

    enum class Impediment {
        STORAGE, NETWORK
    }

    fun syncFailed(): Boolean {
        return impediments.contains(Impediment.NETWORK)
    }

    fun isSynced(): Boolean {
        return !replaying && percentageSync == 100 && !syncFailed()
    }

}
