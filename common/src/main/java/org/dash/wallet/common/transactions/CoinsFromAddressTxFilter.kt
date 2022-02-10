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

open class CoinsFromAddressTxFilter(
    private val fromAddress: Address,
    private val coins: Coin,
    private val includeFee: Boolean = false
): TransactionFilter {
    var toAddress: Address? = null
        private set

    override fun matches(tx: Transaction): Boolean {
        val actualValue = if (includeFee && tx.fee != null) coins - tx.fee else coins
        val networkParameters = fromAddress.parameters

        for (input in tx.inputs) {
            input.outpoint.connectedOutput?.let { connectedOutput ->
                val script = connectedOutput.scriptPubKey

                if ((ScriptPattern.isP2PKH(script) || ScriptPattern.isP2SH(script)) &&
                    script.getToAddress(networkParameters) == fromAddress
                ) {
                    val output = tx.outputs.firstOrNull { it.value == actualValue }
                    output?.run {
                        toAddress = this.scriptPubKey.getToAddress(networkParameters)
                        return true
                    }
                }
            }
        }

        return false
    }
}