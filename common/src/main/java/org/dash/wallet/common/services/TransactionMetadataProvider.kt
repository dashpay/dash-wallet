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

package org.dash.wallet.common.services

import kotlinx.coroutines.flow.Flow
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.transactions.TaxCategory
import org.dash.wallet.common.transactions.TransactionMetadata

interface TransactionMetadataProvider {
    suspend fun setTransactionMetadata(transactionMetadata: TransactionMetadata)
    suspend fun importTransactionMetadata(txid: Sha256Hash)
    suspend fun setTransactionTaxCategory(txid: Sha256Hash, taxCategory: TaxCategory)
    suspend fun setTransactionType(txid: Sha256Hash, type: Int)
    suspend fun setTransactionExchangeRate(txid: Sha256Hash, exchangeRate: ExchangeRate)
    suspend fun setTransactionMemo(txid: Sha256Hash, memo: String)

    suspend fun getTransactionMetadata(txid: Sha256Hash): TransactionMetadata?
    fun observeTransactionMetadata(txid: Sha256Hash): Flow<TransactionMetadata?>

    suspend fun getAllTransactionMetadata(): List<TransactionMetadata>
}