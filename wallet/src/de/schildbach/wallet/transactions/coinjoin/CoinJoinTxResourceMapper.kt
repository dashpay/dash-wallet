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

package de.schildbach.wallet.transactions.coinjoin

import de.schildbach.wallet.ui.transactions.TxResourceMapper

import android.text.format.DateUtils
import androidx.annotation.StringRes
import de.schildbach.wallet_test.R
import org.bitcoinj.coinjoin.utils.CoinJoinTransactionType
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.wallet.WalletEx

class CoinJoinTxResourceMapper: TxResourceMapper() {
    override val dateTimeFormat: Int
        get() = DateUtils.FORMAT_SHOW_TIME

    @StringRes
    override fun getTransactionTypeName(tx: Transaction, bag: TransactionBag): Int {
        if ((tx.type != Transaction.Type.TRANSACTION_NORMAL &&
                    tx.type != Transaction.Type.TRANSACTION_UNKNOWN) ||
            tx.confidence.hasErrors() ||
            tx.isCoinBase
        ) {
            return super.getTransactionTypeName(tx, bag)
        }

        return when (CoinJoinTransactionType.fromTx(tx, bag as WalletEx)) {
            CoinJoinTransactionType.CreateDenomination -> R.string.transaction_row_status_coinjoin_create_denominations
            CoinJoinTransactionType.Mixing -> R.string.transaction_row_status_coinjoin_mixing
            CoinJoinTransactionType.MixingFee -> R.string.transaction_row_status_coinjoin_mixing_fee
            CoinJoinTransactionType.MakeCollateralInputs -> R.string.transaction_row_status_coinjoin_make_collateral
            CoinJoinTransactionType.CombineDust -> R.string.transaction_row_status_coinjoin_combine_dust
            else -> super.getTransactionTypeName(tx, bag)
        }
    }
}