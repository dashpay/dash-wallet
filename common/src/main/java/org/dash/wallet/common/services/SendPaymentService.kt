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

package org.dash.wallet.common.services

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.CoinSelector
import kotlin.jvm.Throws

class LeftoverBalanceException(missing: Coin, message: String): InsufficientMoneyException(missing, message)

interface SendPaymentService {
    @Throws(LeftoverBalanceException::class)
    suspend fun sendCoins(
        address: Address,
        amount: Coin,
        coinSelector: CoinSelector? = null,
        emptyWallet: Boolean = false,
        checkBalanceConditions: Boolean = true
    ): Transaction

    suspend fun estimateNetworkFee(
        address: Address,
        amount: Coin,
        emptyWallet: Boolean = false
    ): TransactionDetails

    data class TransactionDetails(
        val fee: String,
        val amountToSend: Coin,
        val totalAmount: String
    )
}
