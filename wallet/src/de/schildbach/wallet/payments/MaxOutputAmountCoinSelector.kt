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

package de.schildbach.wallet.payments

import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.core.VarInt
import org.bitcoinj.wallet.CoinSelection
import org.bitcoinj.wallet.CoinSelector

open class MaxOutputAmountCoinSelector: CoinSelector {
    companion object {
        private const val TX_OUTPUT_SIZE = 34 // estimated size for a typical transaction output
        private const val TX_INPUT_SIZE = 148 // estimated size for a typical compact pubkey transaction input
    }

    override fun select(target: Coin, candidates: MutableList<TransactionOutput>): CoinSelection {
        val value = Coin.valueOf(candidates.sumOf { it.value.value })
        val inputCount = candidates.size.toLong()
        val outputCount = 1L
        // Formula is lifted from DashSync for ios: https://github.com/dashpay/dashsync-iOS/blob/master/DashSync/shared/Models/Wallet/DSAccount.m#L1680-L1711
        // Android has an extra byte per input
        val txSize = 8 + VarInt.sizeOf(inputCount) + (TX_INPUT_SIZE + 1) * inputCount +
            VarInt.sizeOf(outputCount) + TX_OUTPUT_SIZE * outputCount
        val fee = Transaction.DEFAULT_TX_FEE.multiply(txSize).divide(1000)

        return CoinSelection((value - fee).coerceAtLeast(Coin.ZERO), candidates)
    }
}
