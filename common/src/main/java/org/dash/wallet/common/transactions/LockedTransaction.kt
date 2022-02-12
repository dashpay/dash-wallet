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

import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.transactions.TransactionFilter

// TODO: In the event that InstantSend is not working, then we may want to broaden the definition of
// lock as is done in other parts of the app (seen by other peers && confidence is BUILDING)
class LockedTransaction(private val topUpTxId: Sha256Hash): TransactionFilter {
    override fun matches(tx: Transaction): Boolean {
        return tx.txId == topUpTxId && tx.confidence.isTransactionLocked
    }
}