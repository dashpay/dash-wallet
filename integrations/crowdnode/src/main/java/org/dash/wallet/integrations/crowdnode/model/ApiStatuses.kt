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

package org.dash.wallet.integrations.crowdnode.model

// Order matters. If modifications are required,
// it's better make this initializable with a value
// that maps to the old order value (see ApiCode)
enum class SignUpStatus {
    NotStarted,

    // Create New Account
    FundingWallet,
    SigningUp,
    AcceptingTerms,
    Finished,
    Error,

    // Link Existing Account
    LinkedOnline
}

// Order matters. If modifications are required,
// it's better make this initializable with a value
// that maps to the old order value (see ApiCode)
enum class OnlineAccountStatus {
    None,
    Linking,
    Validating,
    Confirming,
    Creating,
    SigningUp,
    Done
}

open class CrowdNodeException(message: String) : Exception(message) {
    companion object {
        const val DEPOSIT_ERROR = "deposit_error"
        const val CONFIRMATION_ERROR = "confirmation_error"
        const val WITHDRAWAL_ERROR = "withdrawal_error"
        const val MISSING_PRIMARY = "primary_not_specified"
    }
}

class MessageStatusException(details: String) : CrowdNodeException(details)
