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

import org.bitcoinj.coinjoin.utils.CoinJoinTransactionType
import org.bitcoinj.core.Transaction
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptPattern
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.transactions.filters.TransactionFilter

open class CoinJoinTxFilter(private val wallet: WalletEx, val type: CoinJoinTransactionType) : TransactionFilter {
    override fun matches(tx: Transaction): Boolean {
        return CoinJoinTransactionType.fromTx(tx, wallet) == type &&
            tx.outputs.any { output ->
                ScriptPattern.isP2PKH(output.scriptPubKey) &&
                    wallet.coinJoin.findKeyFromPubKeyHash(
                        ScriptPattern.extractHashFromP2PKH(output.scriptPubKey),
                        Script.ScriptType.P2PKH
                    ) != null
            }
    }
}

class CreateDenominationTxFilter(wallet: WalletEx) : CoinJoinTxFilter(
    wallet,
    CoinJoinTransactionType.CreateDenomination
)
class MakeCollateralTxFilter(wallet: WalletEx) : CoinJoinTxFilter(wallet, CoinJoinTransactionType.MakeCollateralInputs)
class MixingFeeTxFilter(wallet: WalletEx) : CoinJoinTxFilter(wallet, CoinJoinTransactionType.MixingFee)
class MixingTxFilter(wallet: WalletEx) : CoinJoinTxFilter(wallet, CoinJoinTransactionType.Mixing)
