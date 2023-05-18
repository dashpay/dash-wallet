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
package de.schildbach.wallet.database.entity

import androidx.room.Entity
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.TaxCategory

@Entity(tableName = "transaction_metadata_platform", primaryKeys = ["id", "txId"])
class TransactionMetadataDocument(
    val id: String,
    val timestamp: Long,
    var txId: Sha256Hash,
    var sentTimestamp: Long? = null,
    var taxCategory: TaxCategory? = null,
    var currencyCode: String? = null,
    var rate: Double? = null,
    var memo: String? = null,
    var service: String? = null,
    var customIconUrl: String? = null
)
