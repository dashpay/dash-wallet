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

import android.net.Uri
import androidx.room.TypeConverter
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.identity.IdentityPublicKey
import java.util.*
import kotlin.collections.ArrayList


class BlockchainStateRoomConverters {

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
    fun toIdentityCreationState(value: Int): BlockchainIdentityData.CreationState {
        return BlockchainIdentityData.CreationState.values()[value]
    }

    @TypeConverter
    fun fromIdentityCreationState(identityCreationState: BlockchainIdentityData.CreationState): Int {
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
    fun toUsernameStatus(value: Int): BlockchainIdentity.UsernameStatus {
        return BlockchainIdentity.UsernameStatus.values()[value]
    }

    @TypeConverter
    fun fromUsernameStatus(usernameStatus: BlockchainIdentity.UsernameStatus?): Int {
        return usernameStatus?.value ?: BlockchainIdentity.UsernameStatus.NOT_PRESENT.value
    }

    @TypeConverter
    fun toRegistrationStatus(value: Int): BlockchainIdentity.RegistrationStatus? {
        return if (value > -1) BlockchainIdentity.RegistrationStatus.values()[value] else null
    }

    @TypeConverter
    fun fromRegistrationStatus(registrationStatus: BlockchainIdentity.RegistrationStatus?): Int {
        return registrationStatus?.ordinal ?: -1
    }

    @TypeConverter
    fun toCreditBalance(value: Long): Coin {
        return Coin.valueOf(value)
    }

    @TypeConverter
    fun fromCreditBalance(creditBalance: Coin?): Long {
        return creditBalance?.value ?: 0L
    }

    @TypeConverter
    fun toCurrentMainKeyType(value: Int): IdentityPublicKey.Type? {
        return if (value > -1) IdentityPublicKey.Type.values()[value] else null
    }

    @TypeConverter
    fun fromCurrentMainKeyType(currentMainKeyType: IdentityPublicKey.Type?): Int {
        return currentMainKeyType?.ordinal ?: -1
    }

    @TypeConverter
    fun toArrayList(data: String?): ArrayList<String>? {
        return data?.run { ArrayList(data.split(",")) }
    }

    @TypeConverter
    fun fromArrayList(data: ArrayList<String>?): String? {
        return data?.joinToString(",")
    }

    @TypeConverter
    fun fromIdentity(identity: Identity?): ByteArray? {
        return identity?.toBuffer()
    }

    @TypeConverter
    fun toIdentity(data: ByteArray?): Identity? {
        return data?.run {
            return try {
                PlatformRepo.getInstance().platform.dpp.identity.createFromBuffer(data)
            } catch (e: Exception) {
                null
            }
        }
    }

    @TypeConverter
    fun fromInvitationLinkData(invite: InvitationLinkData?): String? {
        return invite?.link?.toString()
    }

    @TypeConverter
    fun toInvitationLinkData(data: String?): InvitationLinkData? {
        return data?.run {
            InvitationLinkData(Uri.parse(data),false)
        }
    }
}