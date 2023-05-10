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

package de.schildbach.wallet.ui.transactions

import org.bitcoinj.core.RejectMessage
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence

enum class TxError {
    DoubleSpend,
    InConflict,
    Nonstandard,
    Dust,
    InsufficientFee,
    Duplicate,
    Invalid,
    Malformed,
    Obsolete,
    Unknown;

    companion object {
        fun fromTransaction(tx: Transaction): TxError {
            val confidence = tx.confidence

            if (confidence != null) {
                if (confidence.confidenceType == TransactionConfidence.ConfidenceType.DEAD) {
                    return DoubleSpend
                } else if (confidence.confidenceType == TransactionConfidence.ConfidenceType.IN_CONFLICT) {
                    return InConflict
                } else if (confidence.confidenceType != TransactionConfidence.ConfidenceType.BUILDING) {
                    // Errors from the Dash Network
                    val exception = confidence.rejectedTransactionException

                    if (exception != null) {
                        return when (exception.rejectMessage.reasonCode) {
                            RejectMessage.RejectCode.NONSTANDARD -> Nonstandard
                            RejectMessage.RejectCode.DUST -> Dust
                            RejectMessage.RejectCode.INSUFFICIENTFEE -> InsufficientFee
                            RejectMessage.RejectCode.DUPLICATE -> Duplicate
                            RejectMessage.RejectCode.INVALID -> Invalid
                            RejectMessage.RejectCode.MALFORMED -> Malformed
                            RejectMessage.RejectCode.OBSOLETE -> Obsolete
                            RejectMessage.RejectCode.CHECKPOINT, RejectMessage.RejectCode.OTHER -> Unknown
                            else -> Unknown
                        }
                    }
                }
            }

            return Unknown
        }
    }
}
