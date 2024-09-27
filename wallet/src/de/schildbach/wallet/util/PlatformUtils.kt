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

package de.schildbach.wallet.util

import org.bitcoinj.core.Sha256Hash
import org.dashj.platform.dpp.util.Converters
import java.lang.IllegalArgumentException
import java.math.BigInteger
import java.util.*

object PlatformUtils {
    fun longHashFromEncodedString(s: String): Long {
        return try {
            val byteArray = Converters.byteArrayFromString(s)
            val bigInteger = BigInteger(byteArray)
            bigInteger.toLong()
        } catch (e: IllegalArgumentException) {
            UUID.fromString(s).mostSignificantBits
        }
    }

    fun longHash(s: Sha256Hash): Long {
        return longHashFromEncodedString(s.toString())
    }
}