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

/**
 * Caches which transactions belong to which display group (individual, CoinJoin, CrowdNode).
 *
 * On next startup this table lets [MainViewModel] reconstruct the wrapped transaction list
 * without running the expensive [wrapAllTransactions] pass, so the first page appears in
 * ~100 ms instead of ~2.4 s.  The live rebuild still runs in the background and replaces
 * these rows once it finishes.
 */
@Entity(tableName = "tx_group_cache")
data class TxGroupCacheEntry(
    /** Transaction ID as hex string (Sha256Hash.toString()). */
    @PrimaryKey val txId: String,
    /** Unique identifier for the group this transaction belongs to (matches TransactionWrapper.id). */
    val groupId: String,
    /** Discriminator for the wrapper type: [TYPE_INDIVIDUAL], [TYPE_COINJOIN], [TYPE_CROWDNODE]. */
    val groupType: Int,
    /** LocalDate.toEpochDay() of the group — used to sort wrappers newest-first. */
    val groupDateEpoch: Long
) {
    companion object {
        const val TYPE_INDIVIDUAL = 0
        const val TYPE_COINJOIN = 1
        const val TYPE_CROWDNODE = 2
    }
}
