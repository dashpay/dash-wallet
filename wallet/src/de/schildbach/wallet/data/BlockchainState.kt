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
data class BlockchainState(var bestChainDate: Date?,
                           var bestChainHeight: Int,
                           var replaying: Boolean,
                           var impediments: Set<Impediment>,
                           var chainlockHeight: Int,
                           var mnlistHeight: Int,
                           var percentageSync: Int) {

    @JvmOverloads
    constructor(replaying: Boolean = false) : this(null, 0, replaying,
            EnumSet.noneOf(Impediment::class.java), 0, 0, 0)

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
