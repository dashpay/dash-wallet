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

package de.schildbach.wallet.ui.main

import androidx.paging.PagingSource
import androidx.paging.PagingState
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.ui.transactions.TransactionRowView
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.PresentableTxMetadata
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.dash.wallet.common.transactions.TransactionWrapper

class TransactionPagingSource(
    private val wrappedTransactions: List<TransactionWrapper>,
    private val metadata: Map<Sha256Hash, PresentableTxMetadata>,
    private val contactsByTxId: Map<String, DashPayProfile>,
    private val walletData: WalletDataProvider,
    private val chainLockBlockHeight: Int,
    private val onContactsNeeded: (List<Transaction>) -> Unit
) : PagingSource<Int, TransactionRowView>() {

    // Set to true before invalidate() when the underlying list was rebuilt (new/removed
    // transactions, filter change). This forces the next source to load from the top so
    // the user always sees the newest transactions first without a visible prepend flash.
    // Leave false for metadata/contact-only invalidations to preserve scroll position.
    var resetToTop: Boolean = false

    override fun getRefreshKey(state: PagingState<Int, TransactionRowView>): Int? =
        if (resetToTop) null else state.anchorPosition

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TransactionRowView> {
        val offset = params.key ?: 0
        val slice = wrappedTransactions.drop(offset).take(params.loadSize)

        val rows = slice.map { wrapper ->
            val tx = wrapper.transactions.values.first()
            val contact = contactsByTxId[tx.txId.toString()]
            TransactionRowView.fromTransactionWrapper(
                wrapper,
                walletData.transactionBag,
                Constants.CONTEXT,
                contact,
                metadata[tx.txId],
                chainLockBlockHeight
            )
        }

        // Viewport-driven: trigger contact resolution for this page (fire-and-forget)
        val txsNeedingContacts = slice
            .map { it.transactions.values.first() }
            .filter { tx ->
                !tx.isEntirelySelf(walletData.transactionBag) &&
                    contactsByTxId[tx.txId.toString()] == null
            }
        if (txsNeedingContacts.isNotEmpty()) {
            onContactsNeeded(txsNeedingContacts)
        }

        return LoadResult.Page(
            data = rows,
            prevKey = if (offset == 0) null else offset - 1,
            nextKey = if (rows.size < params.loadSize) null else offset + rows.size
        )
    }
}
