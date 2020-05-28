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
import de.schildbach.wallet.Constants
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.TransactionOutPoint
import org.dashevo.dashpay.BlockchainIdentity
import org.dashevo.dpp.identity.IdentityPublicKey
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
    fun fromImpedimentsString(value: String?): Set<BlockchainState.Impediment> {
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
    fun toIdentityCreationState(value: Int): BlockchainIdentityData.State {
        return BlockchainIdentityData.State.values()[value]
    }

    @TypeConverter
    fun fromIdentityCreationState(identityCreationState: BlockchainIdentityData.State): Int {
        return identityCreationState.ordinal
    }

    @TypeConverter
    fun fromHash(hash: Sha256Hash?): ByteArray? {
        return hash?.reversedBytes
    }

    @TypeConverter
    fun byteArrayToHash(bytes: ByteArray?): Sha256Hash? {
        return bytes?.let { Sha256Hash.wrapReversed(it) }
    }

    @TypeConverter
    fun fromTransactionOutPoint(outpoint: TransactionOutPoint?): ByteArray? {
        return outpoint?.bitcoinSerialize()
    }

    @TypeConverter
    fun toTransactionOutPoint(bytes: ByteArray?): TransactionOutPoint? {
        return bytes?.let { TransactionOutPoint(Constants.NETWORK_PARAMETERS, it, 0) }
    }

    @TypeConverter
    fun toUsernameStatus(value: Int): BlockchainIdentity.UsernameStatus {
        return BlockchainIdentity.UsernameStatus.values()[value]
    }

    @TypeConverter
    fun fromUsernameStatus(usernameStatus: BlockchainIdentity.UsernameStatus?): Int {
        return usernameStatus?.value ?: BlockchainIdentity.UsernameStatus.NOT_PRESENT.value
    }

    @TypeConverter
    fun toRegistrationStatus(value: Int): BlockchainIdentity.RegistrationStatus {
        return BlockchainIdentity.RegistrationStatus.values()[value]
    }

    @TypeConverter
    fun fromRegistrationStatus(registrationStatus: BlockchainIdentity.RegistrationStatus): Int {
        return registrationStatus.ordinal
    }

    @TypeConverter
    fun toCreditBalance(value: Long): Coin? {
        return if (value >= 0) Coin.valueOf(value) else null
    }

    @TypeConverter
    fun fromCreditBalance(creditBalance: Coin?): Long {
        return creditBalance?.value ?: -1
    }

    @TypeConverter
    fun toCurrentMainKeyType(value: Int): IdentityPublicKey.TYPES? {
        return if (value >= 0) IdentityPublicKey.TYPES.values()[value] else null
    }

    @TypeConverter
    fun fromCurrentMainKeyType(currentMainKeyType: IdentityPublicKey.TYPES?): Int {
        return currentMainKeyType?.ordinal ?: -1
    }
}