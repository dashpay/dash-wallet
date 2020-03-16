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

import androidx.room.TypeConverter
import java.util.*

class RoomConverters {

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

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

}