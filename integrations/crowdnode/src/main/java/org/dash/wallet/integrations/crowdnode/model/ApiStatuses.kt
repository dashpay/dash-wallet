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

// Order matters
enum class SignUpStatus {
    NotStarted,
    FundingWallet,
    SigningUp,
    AcceptingTerms,
    Finished,
    Error,
    LinkedOnline
}

// Order matters
enum class OnlineAccountStatus {
    None,
    Linking,
    Validating,
    Confirming,
    Done
}

class CrowdNodeException(message: String): Exception(message) {
    companion object {
        const val DEPOSIT_ERROR = "deposit_error"
        const val CONFIRMATION_ERROR = "confirmation_error"
        const val WITHDRAWAL_ERROR = "withdrawal_error"
    }
}