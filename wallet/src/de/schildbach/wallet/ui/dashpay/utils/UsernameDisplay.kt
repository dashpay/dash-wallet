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

package de.schildbach.wallet.ui.dashpay.utils

/**
 * A DPNS name has TWO forms: the `label` the user typed ("brian-s21") and the
 * `normalizedLabel` DPNS derives from it by folding visually-confusable
 * characters ("br1an-s21" — o→0, i/l→1, lowercased). The normalized form is a
 * LOOKUP key: it is what contested-name checks, uniqueness comparisons, DPNS
 * queries and on-chain document indexes are keyed on. It must never be shown
 * to a human, who typed the label and expects to read it back.
 *
 * dashj hands the wallet the normalized form in several places —
 * `BlockchainIdentity.primaryUsername` / `secondaryUsername` /
 * `currentUsername` / `getUniqueUsername()` are all rewritten to
 * `DomainDocument.normalizedLabel` by `recoverUsernames()` — and those values
 * flowed straight into fields the UI renders (the home hello card greeted the
 * user with "Hello br1an-s21,").
 *
 * [preferDisplayLabel] repairs that at the boundary: given a candidate
 * [display] label from a source that keeps the human form (the identity
 * record's requested username, the local DashPay profile built from the
 * domain document's `.label`) and the [normalized] value dashj returned,
 * it keeps the display label ONLY when it provably names the same DPNS
 * entry — i.e. when normalizing it reproduces [normalized] exactly. Any
 * other candidate (a stale name, a different name, none at all) loses to
 * [normalized], which remains the authoritative on-chain answer.
 *
 * [normalize] is injected so the helper stays pure and host-testable; every
 * production call site passes dashj's `Names::normalizeString`, the same
 * function DPNS itself uses to derive `normalizedLabel`.
 */
internal fun preferDisplayLabel(
    display: String?,
    normalized: String,
    normalize: (String) -> String
): String = when {
    display.isNullOrEmpty() -> normalized
    // Already the display form (nothing was folded), or the exact same
    // string — either way there is nothing to prefer.
    display == normalized -> normalized
    normalize(display) == normalized -> display
    else -> normalized
}
