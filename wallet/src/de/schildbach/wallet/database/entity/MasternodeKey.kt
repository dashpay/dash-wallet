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
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash

@Entity(tableName = "imported_masternode_keys")
data class ImportedMasternodeKey(
    @PrimaryKey
    val proTxHash: Sha256Hash,
    val address: String,
    val votingPrivateKey: ByteArray,
    val votingPublicKey: ByteArray,
    val votingPubKeyHash: ByteArray
) {
    fun toECKey(): ECKey = ECKey.fromPrivateAndPrecalculatedPublic(votingPrivateKey, votingPrivateKey)
}
