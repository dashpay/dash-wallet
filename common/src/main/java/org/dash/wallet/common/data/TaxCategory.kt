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

package org.dash.wallet.common.data

enum class TaxCategory(val value: Int) {
    Income(0),
    Expense(1),
    TransferIn(2),
    TransferOut(3),
    Invalid(99);

    fun toggle(): TaxCategory {
        return when (this) {
            Income -> TransferIn
            TransferIn -> Income
            Expense -> TransferOut
            TransferOut -> Expense
            Invalid -> Invalid
        }
    }

    companion object {
        var defaultIncoming = Income
        var defaultOutgoing = Expense
        var defaultTransferIn = TransferIn
        var defaultTransferOut = TransferOut

        fun fromValue(value: Int): TaxCategory {
            return values()[value];
        }

        fun fromValue(name: String): TaxCategory? {
            return values().find { it.name.lowercase() == name.lowercase() }
        }

        fun getDefault(isIncoming: Boolean, isTransfer: Boolean): TaxCategory {
            return when {
                isIncoming && !isTransfer -> defaultIncoming
                isIncoming && isTransfer -> defaultTransferIn
                !isIncoming && !isTransfer -> defaultOutgoing
                !isIncoming && isTransfer -> defaultTransferOut
                else -> Invalid
            }
        }

        fun setDefaults(
            incoming: TaxCategory,
            outgoing: TaxCategory,
            transferIn: TaxCategory,
            transferOut: TaxCategory
        ) {
            defaultIncoming = incoming
            defaultOutgoing = outgoing
            defaultTransferIn = transferIn
            defaultTransferOut = transferOut
        }
    }
}