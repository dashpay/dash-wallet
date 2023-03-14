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
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.entity.TransactionMetadata

/**
 * Exports in the TaxBit CSV format
 */
class TaxBitExporter(
    wallet: Wallet,
    metadataMap: Map<Sha256Hash, TransactionMetadata>
) : CSVExporter(
    wallet,
    metadataMap,
    listOf("Income", "Expense", "Transfer-in", "Transfer-out")
) {

    override val dataSpec: List<CSVColumn> = listOf(
        CSVColumn("Date and Time", iso8601DateField),
        CSVColumn("Transaction Type", taxCategory),
        CSVColumn("Sent Quantity", sentValueOnly),
        CSVColumn("Sent Currency", currency),
        CSVColumn("Sending Source", sourceDashWallet),
        CSVColumn("Received Quantity", receivedValueOnly),
        CSVColumn("Received Currency", currency),
        CSVColumn("Receiving Destination", sourceDashWallet),
        CSVColumn("Fee", emptyField),
        CSVColumn("Fee Currency", emptyField),
        CSVColumn("Exchange Transaction ID", emptyField),
        CSVColumn("Blockchain Transaction Hash", transactionId)
    )
}