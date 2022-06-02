/*
 * Copyright (c) 2022. Dash Core Group.
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
package org.dash.wallet.common.transactions

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash

@Entity(tableName = "transaction_metadata")
data class TransactionMetadata(
    @PrimaryKey var txid: Sha256Hash,
    var timestamp: Long,
    var value: Coin,
    var type: TransactionCategory,
    var taxCategory: TaxCategory? = null,
    var currencyCode: String? = null,
    var rate: String? = null,
    var memo: String = ""
) {
    @Ignore
    val canToggle = type.canToggle

    @Ignore
    val isTransfer = type.isTransfer

    @Ignore
    val defaultTaxCategory = TaxCategory.getDefault(value.isPositive, isTransfer)
}
