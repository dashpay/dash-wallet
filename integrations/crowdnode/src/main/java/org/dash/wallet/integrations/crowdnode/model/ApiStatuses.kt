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
        const val SERVICE_UNAVAILABLE = "service_unavailable"
    }
}

class MessageStatusException(details: String) : CrowdNodeException(details)

/**
 * A retired on-chain operation was invoked. CrowdNode has disabled account
 * creation and deposits service-side, and the remaining users are all on the
 * API path, so the dashj senders in
 * [org.dash.wallet.integrations.crowdnode.api.CrowdNodeBlockchainApi] no
 * longer build or broadcast anything — they raise this instead.
 *
 * Deliberately an exception rather than a silent no-op: an operation that
 * reports success while moving no funds is the failure mode being refused
 * here. It is raised BEFORE any side effect (no output locking, no partial
 * flow), so nothing is left half-done for the caller to reconcile.
 *
 * Extends [CrowdNodeException] on purpose — the existing handlers
 * (`CrowdNodeApi.signUp`/`deposit`'s `catch (Exception)` and
 * `CrowdNodeConfirmationTxHandler`'s `catch (CrowdNodeException)`) then turn
 * it into an error state rather than an uncaught crash.
 *
 * The UI gate ([org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants.SIGNUP_AND_DEPOSITS_ENABLED])
 * is what users normally meet; this is the backstop for any path the gate
 * misses.
 *
 * @property operation the retired call, for logs and analytics.
 */
class CrowdNodeServiceUnavailableException(
    val operation: String
) : CrowdNodeException("$SERVICE_UNAVAILABLE: $operation is no longer available")
