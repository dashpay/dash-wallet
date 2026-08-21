/*
 * Copyright 2026 Dash Core Group.
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

/**
 * The failure contract of [AuthenticationManager.signMessage].
 *
 * ## Why this type exists in `:common`
 *
 * The signing implementation lives in the `:wallet` module and delegates to
 * the Dash Platform Kotlin SDK, whose typed errors
 * (`org.dashfoundation.dashsdk.errors.DashSdkError`) are NOT on the
 * classpath of the feature modules that call [AuthenticationManager]
 * (`:integrations:crowdnode` depends on `:common` only). A caller therefore
 * has no way to `catch` the SDK type. Since the interface being implemented
 * is declared here, its error contract has to be expressible here too — so
 * the wallet-side implementation maps every signing failure onto this type
 * and keeps the SDK error as [cause] for logging/analytics.
 *
 * ## Behavior change vs. the previous dashj implementation
 *
 * The dashj implementation returned an EMPTY STRING when the wallet did not
 * own the requested address. That silently produced a valid-looking request
 * carrying no signature, which the CrowdNode server then rejected with an
 * opaque error — the real cause (wrong/foreign address) never reached the
 * user or the logs. Signing failures are now thrown, never swallowed; there
 * is no dashj fallback (the codebase's fail-closed cutover philosophy, cf.
 * `cutoverSendRoute` in `SendCoinsTaskRunner`).
 *
 * @property reason machine-readable classification, for callers that want
 *   to distinguish "this address isn't ours" from a generic failure.
 */
class MessageSigningException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    enum class Reason {
        /**
         * The wallet cannot produce a signature for the requested address:
         * it does not own the corresponding private key, or the key is not
         * derivable/available. Maps from the SDK's
         * `DashSdkError.PlatformWallet.SigningKeyUnavailable`.
         *
         * This is the case the old dashj code answered with `""`.
         */
        SIGNING_KEY_UNAVAILABLE,

        /**
         * The address (or message) was rejected as malformed before any key
         * lookup happened. Maps from the SDK's platform-wallet
         * `ErrorInvalidParameter` (native code 2, surfaced as
         * `DashSdkError.PlatformWallet.Generic` with `nativeCode == 2`).
         */
        INVALID_ADDRESS,

        /**
         * Anything else: the SDK was not startable, no wallet was bound, or
         * the signing call failed for an unclassified reason. Always carries
         * a [cause].
         */
        UNAVAILABLE
    }
}
