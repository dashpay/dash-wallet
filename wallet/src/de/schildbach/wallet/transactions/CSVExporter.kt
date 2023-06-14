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

import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.transactions.TransactionUtils
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf

abstract class CSVExporter(
    wallet: Wallet,
    metadataMap: Map<Sha256Hash, TransactionMetadata>,
    taxCategories: List<String>
) :
    TransactionExporter(wallet, metadataMap, taxCategories) {

    companion object {
        const val NEW_LINE = "\n"
    }

    inner class CSVColumn(
        val name: String,
        val dataFunction: (Transaction, TransactionMetadata?) -> String
    )

    abstract val dataSpec: List<CSVColumn>

    fun getHeader(): String {
        val columnList = dataSpec.map { it.name }
        return columnList.joinToString(",")
    }

    override fun exportString(): String {
        val history = StringBuilder()

        history.append(getHeader()).append(NEW_LINE)

        for (tx in sortedTransactions) {
            val columnData = arrayListOf<String>()
            val shouldExclude = excludeInternal && tx.isEntirelySelf(wallet)
            if (!shouldExclude) {
                for (spec in dataSpec) {
                    columnData.add(spec.dataFunction(tx, metadataMap[tx.txId]))
                }
                history.append(columnData.joinToString(",")).append(NEW_LINE)
            }
        }

        return history.toString()
    }
}