/*
 * Copyright 2023 Dash Core Group.
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

import org.bitcoinj.coinjoin.CoinJoinCoinSelector
import org.bitcoinj.core.Coin
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.wallet.CoinSelection
import org.bitcoinj.wallet.Wallet

// refactor this class and derive it from MaxOutputAmountCoinSelector
class MaxOutputAmountCoinJoinCoinSelector(wallet: Wallet): MaxOutputAmountCoinSelector() {

    val coinJoinCoinSelector = CoinJoinCoinSelector(wallet)

    override fun select(target: Coin, candidates: MutableList<TransactionOutput>): CoinSelection {
        val coinJoinCandidates = coinJoinCoinSelector.select(target, candidates)
        return super.select(coinJoinCandidates.valueGathered, coinJoinCandidates.gathered.toMutableList())
    }
}
