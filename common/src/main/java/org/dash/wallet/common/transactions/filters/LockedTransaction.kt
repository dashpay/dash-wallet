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

package org.dash.wallet.common.transactions.filters

import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence

class LockedTransaction(private val topUpTxId: Sha256Hash? = null): TransactionFilter {
    constructor() : this(null)

    override fun matches(tx: Transaction): Boolean {
        val confidence = tx.confidence
        val type = confidence.confidenceType
        val isLocked = confidence.isTransactionLocked ||
                type == TransactionConfidence.ConfidenceType.BUILDING ||
                (type == TransactionConfidence.ConfidenceType.PENDING && confidence.numBroadcastPeers() > 1)

        return if (topUpTxId != null) {
            tx.txId == topUpTxId && isLocked
        } else {
            isLocked
        }
    }
}