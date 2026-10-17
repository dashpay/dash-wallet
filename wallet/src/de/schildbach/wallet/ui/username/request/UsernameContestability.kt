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

package de.schildbach.wallet.ui.username.request

import de.schildbach.wallet.Constants

/**
 * The DPNS contested-name rule, and the two UI hints derived from it.
 *
 * THE RULE — there is exactly ONE contestability predicate in the app,
 * `org.dashj.platform.sdk.platform.Names.isUsernameContestable`, which
 * matches the DPNS data contract's `contestedIndex` regex:
 *
 *     ^[a-zA-Z01-]{3,19}$
 *
 * A name is contestable when it is 3..19 characters long AND every
 * character is a letter, a hyphen, or the digit `0`/`1`. `0` and `1` are
 * in the alphabet because they are HOMOGLYPHS of `o` and `l` — "he11o"
 * and "hello" are confusable, so DPNS puts both in the contest. The digits
 * `2`-`9` have no letter lookalike, so ONE of them anywhere in the name
 * takes it out of the contested index.
 *
 * The consequence that repeatedly surprises readers of the logs: a name
 * containing digits can still be contestable. `asd10augsh` is contestable
 * — its only digits are `1` and `0`. `asd10augda6` is not.
 *
 * Everything that spends money on a username creation (the balance gate in
 * [RequestUserNameViewModel], `SdkShieldedUsernameCreation`,
 * `SdkTransparentUsernameCreation`, `TopUpRepository`, `CreateIdentityService`)
 * calls `Names.isUsernameContestable` directly. Do NOT re-implement the rule;
 * a divergent copy would fund a contested name with the non-contested
 * denomination.
 *
 * WHAT THIS FILE IS FOR — the request screen shows two "avoid the contest"
 * checkmarks, one per REASON a name can fall outside the contested index
 * (too long, or contains a `2`-`9` digit). Those hints have to decompose the
 * rule, because the rule's two clauses map to two separate rows of UI. They
 * live here, as pure functions, so the decomposition can be pinned against
 * `Names.isUsernameContestable` in [UsernameContestabilityTest] instead of
 * drifting silently inside the ViewModel. They are LABELS ONLY: no funding
 * denomination is derived from them.
 */

/**
 * Longest name the DPNS `contestedIndex` regex still matches. A name longer
 * than this is outside the contest regardless of its characters.
 */
internal const val DPNS_CONTESTED_MAX_LENGTH = 19

/**
 * Shortest name the DPNS `contestedIndex` regex matches. Names below it are
 * rejected by DPNS outright, so the request screen never offers them.
 */
internal const val DPNS_CONTESTED_MIN_LENGTH = 3

/**
 * Digits with no letter homoglyph. One of these anywhere in a name takes it
 * out of the contested index — the complement of the `0`/`1` the DPNS
 * alphabet admits.
 */
private val NON_HOMOGLYPH_DIGIT = Regex("[2-9]")

/**
 * True when [uname] escapes the contest BY LENGTH — long enough to fall out
 * of the DPNS contested index, and still short enough for DPNS to accept.
 */
internal fun isNonContestedByLength(uname: String): Boolean =
    uname.length in Constants.USERNAME_NON_CONTESTED_MIN_LENGTH..Constants.USERNAME_MAX_LENGTH

/**
 * True when [uname] escapes the contest BY CHARACTERS — it contains at least
 * one `2`-`9` digit, which the DPNS contested alphabet does not admit.
 */
internal fun isNonContestedByCharacters(uname: String): Boolean =
    NON_HOMOGLYPH_DIGIT.containsMatchIn(uname)
