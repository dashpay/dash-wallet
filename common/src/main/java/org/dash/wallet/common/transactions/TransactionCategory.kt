/*
 * Copyright (c) 2022. Dash Core Group.
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

import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction


private const val EXPENSE  = 0x10000000L
private const val INCOME   = 0x20000000L
private const val TRANSFER_IN = 0x40000000L
private const val TRANSFER_OUT = 0x80000000L
private const val INCOMING = 0x010000000L
private const val OUTGOING = 0x02000000L
private const val INTERNAL = 0x04000000L
private const val TOGGLE   = EXPENSE or INCOME
private const val TRANSFER = TRANSFER_IN or TRANSFER_OUT

enum class TransactionCategory(val value: Long) {
    Sent(OUTGOING or 1),
    Received(INCOMING or 2),
    MiningReward(INCOME or 3),
    MasternodeRegister(EXPENSE or 4),
    MasternodeUpdateService(EXPENSE or 5),
    MasternodeUpdateRegistrar(EXPENSE or 6),
    MasternodeUpdateRevoke(EXPENSE or 7),
    TransferIn(TRANSFER_IN or 8),
    TransferOut(TRANSFER_OUT or 9),
    Internal(EXPENSE or INTERNAL or 10),
    Invalid(0);

    val canToggle = value and TOGGLE == 0L
    val isTransfer = value and TRANSFER != 0L

    companion object {
        fun fromValue(value: Long): TransactionCategory {
            return values().find { it.value == value } ?: Invalid
        }

        fun fromTransaction(type: Transaction.Type, value: Coin, isInternal: Boolean): TransactionCategory {
            return when (type) {
                Transaction.Type.TRANSACTION_COINBASE -> MiningReward
                Transaction.Type.TRANSACTION_PROVIDER_REGISTER -> MasternodeRegister
                Transaction.Type.TRANSACTION_PROVIDER_UPDATE_REGISTRAR -> MasternodeUpdateRegistrar
                Transaction.Type.TRANSACTION_PROVIDER_UPDATE_SERVICE -> MasternodeUpdateService
                Transaction.Type.TRANSACTION_PROVIDER_UPDATE_REVOKE -> MasternodeUpdateRevoke
                else -> {
                    when {
                        value.isPositive -> Received
                        isInternal -> Internal
                        else -> Sent
                    }
                }
            }
        }
    }
}