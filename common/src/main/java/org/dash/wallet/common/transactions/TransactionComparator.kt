/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.common.transactions

import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence

class TransactionComparator: Comparator<Transaction> {
    override fun compare(tx1: Transaction, tx2: Transaction): Int {
        val pending1 = tx1.confidence.confidenceType == TransactionConfidence.ConfidenceType.PENDING
        val pending2 = tx2.confidence.confidenceType == TransactionConfidence.ConfidenceType.PENDING

        if (pending1 != pending2) return if (pending1) -1 else 1

        val updateTime1 = tx1.updateTime
        val time1 = updateTime1?.time ?: 0
        val updateTime2 = tx2.updateTime
        val time2 = updateTime2?.time ?: 0

        if (time1 != time2) {
            return if (time1 > time2) -1 else 1
        }

        return tx1.txId.compareTo(tx2.txId)
    }
}