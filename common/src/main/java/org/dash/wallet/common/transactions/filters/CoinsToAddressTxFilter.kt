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

open class CoinsToAddressTxFilter(
    val toAddress: String,
    val coins: Dash,
    val includeFee: Boolean = false
) : TransactionFilter {
    var fromAddresses = listOf<String>()
        private set

    override fun matches(tx: TxInfo): Boolean {
        val fee = tx.feeDuffs
        val actualValue = if (includeFee && fee != null) coins.duffs - fee else coins.duffs

        for (output in tx.outputs) {
            if (output.address != null && output.address == toAddress && output.valueDuffs == actualValue) {
                fromAddresses = tx.inputs.mapNotNull { it.connectedAddress }.distinct()
                return true
            }
        }

        return false
    }
}
