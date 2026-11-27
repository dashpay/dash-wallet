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

import org.dash.wallet.common.money.Dash

/**
 * The balance the "You have X Dash" username disclaimer quotes — pure.
 *
 * When the username fee will be paid from the shielded pool
 * ([UsernamePaymentUIState.canPayFeeFromShielded], which already requires a
 * trustworthy, READY shielded balance that covers the fee) the disclaimer must
 * quote that shielded balance; quoting the transparent L1 balance there told a
 * user with an empty L1 wallet "You have 0 Dash" while paying from shielded.
 * Otherwise the fee comes from L1 and the L1 balance is quoted, as before.
 */
internal fun usernameDisclaimerBalance(
    canPayFeeFromShielded: Boolean,
    shieldedBalance: Dash,
    walletBalanceDuffs: Long
): Dash = if (canPayFeeFromShielded) shieldedBalance else Dash(walletBalanceDuffs)
