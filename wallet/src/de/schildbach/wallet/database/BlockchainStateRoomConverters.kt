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

package de.schildbach.wallet.database

import androidx.room.TypeConverter
import org.dash.wallet.common.data.entity.BlockchainState
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import java.util.*

class BlockchainStateRoomConverters {

    @TypeConverter
    fun fromImpedimentsEnumSet(value: String?): Set<BlockchainState.Impediment> {
        val impedimentSet = EnumSet.noneOf(BlockchainState.Impediment::class.java)
        if (value == null || value.isEmpty()) {
            return impedimentSet
        }

        for (v in value.split(",")) {
            impedimentSet.add(BlockchainState.Impediment.values()[v.toInt()])
        }
        return impedimentSet
    }

    @TypeConverter
    fun toImpedimentsString(impediments: Set<BlockchainState.Impediment>): String {
        val sb = StringBuilder()
        impediments.forEach {
            if (sb.isNotEmpty()) {
                sb.append(",")
            }
            sb.append(it.ordinal)
        }
        return sb.toString()
    }

    @TypeConverter
    fun fromSha256Hash(hash: Sha256Hash?) : ByteArray? {
        return hash?.bytes
    }

    @TypeConverter
    fun toSha256Hash(bytes: ByteArray?): Sha256Hash? {
        bytes?.let {
            return Sha256Hash.wrap(bytes)
        }

        return null
    }

    @TypeConverter
    fun toCoin(value: Long): Coin = Coin.valueOf(value)

    @TypeConverter
    fun fromCoin(coin: Coin) = coin.value
}