/*
 * Copyright 2021 Dash Core Group.
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
package org.dash.wallet.integrations.coinbase.ui.convert_currency.model

import org.dash.wallet.integrations.coinbase.CoinbaseConstants

data class ServiceWallet(
    val cryptoWalletName: String,
    val cryptoWalletService: String,
    override var balance: String,
    val currency: String,
    override var faitAmount: String,
    val icon: String?
): BaseServiceWallet(balance, faitAmount)

open class BaseServiceWallet(
    open var balance: String = CoinbaseConstants.VALUE_ZERO,
    open var faitAmount: String = CoinbaseConstants.VALUE_ZERO
)
