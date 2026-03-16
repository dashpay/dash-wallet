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

/**
 * Persists the grouping structure of transaction wrappers so that [MainViewModel] can
 * reconstruct [wrappedTransactionList] at startup without calling [wrapAllTransactions].
 *
 * Each row represents one transaction belonging to a group:
 * - [groupId] identifies the wrapper (e.g. "coinjoin_2025-03-16", "crowdnode", or the txId
 *   base58 string for single-tx wrappers).
 * - [txId] is the lowercase hex string of the transaction hash ([Sha256Hash.toString]).
 * - [wrapperType] is one of [TYPE_SINGLE], [TYPE_COINJOIN], or [TYPE_CROWDNODE].
 * - [groupDate] is a [java.time.LocalDate] in ISO-8601 format (YYYY-MM-DD).
 * - [sortOrder] preserves the insertion order required for correct factory reconstruction
 *   (especially important for CrowdNode whose [tryInclude] is order-sensitive).
 */
@Entity(tableName = "tx_group_cache", primaryKeys = ["groupId", "txId"])
data class TxGroupCacheEntry(
    val groupId: String,
    val txId: String,
    val wrapperType: String,
    val groupDate: String,
    val sortOrder: Int
) {
    companion object {
        const val TYPE_SINGLE    = "single"
        const val TYPE_COINJOIN  = "coinjoin"
        const val TYPE_CROWDNODE = "crowdnode"
    }
}