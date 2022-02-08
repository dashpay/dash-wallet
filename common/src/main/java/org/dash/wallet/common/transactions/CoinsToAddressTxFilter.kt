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

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.script.ScriptPattern

open class CoinsToAddressTxFilter(
    val toAddress: Address,
    val coins: Coin
): TransactionFilter {
    var fromAddress: Address? = null
        private set

    override fun matches(tx: Transaction): Boolean {
        val networkParameters = toAddress.parameters

        for (output in tx.outputs) {
            val script = output.scriptPubKey

            if ((ScriptPattern.isP2PKH(script) || ScriptPattern.isP2SH(script)) &&
                script.getToAddress(networkParameters) == toAddress &&
                output.value == coins
            ) {
                fromAddress = tx.inputs.firstOrNull {
                    it.value == coins
                }?.connectedOutput?.scriptPubKey?.getToAddress(networkParameters)
                return true
            }
        }

        return false
    }
}