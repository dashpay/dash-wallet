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
package org.dash.wallet.common.data

import androidx.datastore.preferences.core.Preferences

/**
 * The stored [value] when it is wallet [walletId]'s: stored with that wallet's id in [owner].
 * Null (unknown) otherwise, including for a value with no id (an earlier version stored it,
 * and it may be a previous wallet's: a restore of another wallet from the recovery screen
 * installs without a reset) and when [walletId] is null. The value and its [owner] come from
 * this one snapshot, so a concurrent write cannot pair one wallet's value with another's id.
 */
fun Preferences.lastKnownBalance(
    value: Preferences.Key<Long>,
    owner: Preferences.Key<String>,
    walletId: String?
): Long? = this[value].takeIf { walletId != null && this[owner] == walletId }
