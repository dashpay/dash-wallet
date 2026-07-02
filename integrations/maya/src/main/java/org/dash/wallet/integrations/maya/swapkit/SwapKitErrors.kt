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

package org.dash.wallet.integrations.maya.swapkit

import androidx.annotation.StringRes
import org.dash.wallet.integrations.maya.R

/**
 * Maps a raw SwapKit error into a user-facing, localized message resource.
 *
 * SwapKit surfaces failures as a short machine code in the top-level `error` field of
 * `/v3/quote` and `/v3/swap` (e.g. `noRoutesFound`, `validation_error`, `isSanctionedAddress`).
 * The provider side ([SwapKitApiAggregator]) wraps that into a [org.dash.wallet.integrations.maya.api.MayaException]
 * whose message is either the bare code or `"<code>: <detail>"`. This object turns that back into
 * an `@StringRes` so the UI can show friendly copy instead of the raw code.
 *
 * Kept Context-free (returns the resource id, not a resolved string) to match the maya module's
 * convention — the composable resolves it via `stringResource`. Reusable by any swap surface
 * (buy/receive today, sell/preview later), not just [org.dash.wallet.integrations.maya.ui.DEXReceiveViewModel].
 *
 * Error codes are documented in `integrations/maya/SWAPKIT_PROTOCOL.md` (§4 quote, §5 swap).
 */
object SwapKitErrors {
    /**
     * Friendly message resource for [rawError] — the message carried by the failed swap's
     * exception. The leading token (before an optional `": <detail>"`) is treated as the SwapKit
     * error code; unrecognised or null values fall back to [R.string.dex_error_generic].
     *
     * Messages that reference the coin (e.g. the invalid-refund-address copy) use `%1$s`; callers
     * should resolve with the coin code as the format argument. Messages without a placeholder
     * simply ignore the extra argument, so a single `stringResource(res, coinCode)` call is safe
     * for every mapped value.
     */
    @StringRes
    fun messageResFor(rawError: String?): Int {
        // Match on the code prefix so both a bare "validation_error" and a
        // "validation_error: <detail>" map to the same friendly message.
        val code = rawError?.substringBefore(':')?.trim().orEmpty()
        return when (code) {
            // /v3/quote
            "noRoutesFound" -> R.string.dex_error_no_route
            "blackListAsset" -> R.string.dex_error_blacklisted
            "invalidRequest", "validation_error" -> R.string.dex_error_validation
            "apiKeyInvalid", "unauthorized" -> R.string.dex_error_unavailable
            // /v3/swap
            "swapRouteNotFound" -> R.string.dex_error_quote_expired
            "isSanctionedAddress" -> R.string.dex_error_sanctioned
            "insufficientBalance" -> R.string.dex_error_insufficient_balance
            "insufficientAllowance" -> R.string.dex_error_allowance
            "unableToBuildTransaction" -> R.string.dex_error_build_failed
            "invalidSourceAddress" -> R.string.dex_error_invalid_refund_address
            "invalidDestinationAddress" -> R.string.dex_error_invalid_destination
            "outputAmountDeviationTooHigh" -> R.string.dex_error_price_moved
            else -> R.string.dex_error_generic
        }
    }
}
