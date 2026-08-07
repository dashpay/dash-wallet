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

import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.transactions.TxInfo

open class CoinsFromAddressTxFilter(
    private val fromAddress: String,
    private val coins: Dash,
    private val includeFee: Boolean = false
) : TransactionFilter {
    var toAddress: String? = null
        private set

    override fun matches(tx: TxInfo): Boolean {
        val fee = tx.feeDuffs
        val actualValue = if (includeFee && fee != null) coins.duffs - fee else coins.duffs

        for (input in tx.inputs) {
            if (input.connectedAddress != null && input.connectedAddress == fromAddress) {
                val output = tx.outputs.firstOrNull { it.valueDuffs == actualValue }
                output?.run {
                    toAddress = this.address
                    return true
                }
            }
        }

        return false
    }
}
