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

import org.bitcoinj.core.*
import org.bitcoinj.script.ScriptPattern
import org.dash.wallet.common.transactions.TransactionUtils
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf

open class CoinsReceivedTxFilter(
    private val bag: TransactionBag,
    private val coins: Coin
): TransactionFilter {
    var toAddress: Address? = null
        private set

    override fun matches(tx: Transaction): Boolean {
        // this check prevents a CoinJoin TX from being marked as a Crowdnode TX
        if (tx.isEntirelySelf(bag) || tx.getValue(bag).signum() < 0) {
            // Not an incoming transaction
            return false
        }

        val output = tx.outputs.firstOrNull { it.isMine(bag) && it.value == coins }

        if (output != null) {
            val script = output.scriptPubKey

            if (ScriptPattern.isP2PKH(script) || ScriptPattern.isP2SH(script)) {
                toAddress = script.getToAddress(tx.params)
            }

            return true
        }

        return false
    }
}