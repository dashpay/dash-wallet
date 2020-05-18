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
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.evolution.CreditFundingTransaction
import org.dashevo.dashpay.BlockchainIdentity

@Entity(tableName = "blockchain_identity")
class BlockchainIdentityData(var index: Int?,
                             var username: String?,
                             var creditFundingTxId: Sha256Hash? = null,
                             var lockedOutpoint: TransactionOutPoint? = null,
                             var preorderSalt: ByteArray? = null,
                             var registrationStatus: BlockchainIdentity.RegistrationStatus? = null,
                             var usernameStatus: BlockchainIdentity.UsernameStatus? = null,
                             var domain: String? = null) {

    @PrimaryKey
    var id = 1
        set(value) {
            field = 1
        }
}