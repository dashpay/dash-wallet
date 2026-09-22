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
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitProviderError

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
 * Error codes are documented in `integrations/maya/SWAPKIT_PROTOCOL.md` (§4 quote, §5 swap);
 * its "User-Facing Error Display" section lists which screens show which of these messages.
 */
object SwapKitErrors {
    /** Top-level code for "no provider can carry this pair/amount"; also our no-provider-error fallback. */
    const val NO_ROUTES_FOUND = "noRoutesFound"

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
        val code = codeOf(rawError)
        // Per-provider below-minimum codes are family-matched, not enumerated (see isBelowMinimumCode).
        if (isBelowMinimumCode(code)) return R.string.dex_error_amount_too_small
        return when (code) {
            // /v3/quote
            NO_ROUTES_FOUND -> R.string.dex_error_no_route
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

    /**
     * True when [rawError] means the sell amount is under the minimum this swap can fill — the
     * case the UI surfaces as an inline hint (raise the amount and retry) rather than a blocking
     * modal. `noRoutesFound` is the ambiguous top-level form; the per-provider below-minimum codes
     * from `providerErrors[]` are family-matched (see [isBelowMinimumCode]).
     */
    fun isAmountTooLow(rawError: String?): Boolean {
        val code = codeOf(rawError)
        return code == NO_ROUTES_FOUND || isBelowMinimumCode(code)
    }

    /**
     * True when [code] is a per-provider "amount is below the route's minimum" code. SwapKit
     * reports these with a code ending in `AmountTooSmall` (e.g. `sellAssetAmountTooSmall` from
     * MAYACHAIN). Matched by suffix rather than an exact list because SwapKit doesn't document the
     * per-provider vocabulary and the prefix names whichever side/field was too small;
     * `AmountTooLow` is accepted as the same family. Both require "Amount" in the code, so
     * unrelated below-threshold codes (a too-low fee, say) stay out.
     */
    private fun isBelowMinimumCode(code: String): Boolean =
        code.endsWith("AmountTooSmall", ignoreCase = true) ||
            code.endsWith("AmountTooLow", ignoreCase = true)

    /**
     * The failure of a quote that came back with no routes, rendered in the same
     * `"<code>: <detail>"` shape the top-level `error` field uses. A provider reports its code in
     * [SwapKitProviderError.errorCode] and prose in `message`; only the code is a stable
     * identifier, so it must lead — matching on the prose would silently fall through to the
     * generic message (the `sellAssetAmountTooSmall` case, where the user needs to be told to
     * raise the amount). Null when there is no provider error to report.
     */
    fun providerErrorMessage(error: SwapKitProviderError?): String? {
        val code = error?.errorCode?.trim()?.takeIf { it.isNotEmpty() }
        val detail = error?.message?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            code != null && detail != null -> "$code: $detail"
            else -> code ?: detail
        }
    }

    /**
     * The SwapKit error code carried by [rawError]: the leading token before an optional
     * `": <detail>"`, so both a bare `validation_error` and `validation_error: <detail>` match.
     */
    private fun codeOf(rawError: String?): String = rawError?.substringBefore(':')?.trim().orEmpty()
}
