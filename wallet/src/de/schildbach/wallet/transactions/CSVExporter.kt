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

package de.schildbach.wallet.transactions

import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider

abstract class CSVExporter(
    transactionMetadataProvider: TransactionMetadataProvider,
    transactions: Collection<TxInfo>,
    taxCategories: List<String>
) :
    TransactionExporter(transactionMetadataProvider, transactions, taxCategories) {

    companion object {
        const val NEW_LINE = "\n"
    }

    inner class CSVColumn(
        val name: String,
        val dataFunction: (TxInfo, TransactionMetadata?) -> String
    )

    abstract val dataSpec: List<CSVColumn>

    private fun getHeader(): String {
        val columnList = dataSpec.map { it.name }
        return columnList.joinToString(",")
    }

    override suspend fun exportString(): String {
        ensureMetadataMap()
        val history = StringBuilder()

        history.append(getHeader()).append(NEW_LINE)
        for (tx in sortedTransactions) {
            val columnData = arrayListOf<String>()
            // A CoinJoin mixing round spends the wallet's own coins into the wallet's own
            // denominated outputs, so it is entirely-self and this one test still excludes it.
            // The former explicit `coinJoinTransactionType` check required a dashj Transaction,
            // which does not exist post-cutover (see TransactionExporter's class docs).
            //
            // EXCEPT an entirely-self tx that burns value into an OP_RETURN — the
            // asset-lock credit-purchase shape. That burn+fee is real money leaving
            // the wallet (the S22 reconciliation's hidden 0.03000241 expense), so it
            // must appear as an Expense row; getTransactionValue() supplies the
            // negative burn+fee for it.
            val shouldExclude = excludeInternal && isInternal(tx) && opReturnBurnDuffs(tx) == 0L
            if (!shouldExclude) {
                for (spec in dataSpec) {
                    columnData.add(spec.dataFunction(tx, metadataMap[tx.txId.lowercase()]))
                }
                history.append(columnData.joinToString(",")).append(NEW_LINE)
            }
        }
        return history.toString()
    }
}