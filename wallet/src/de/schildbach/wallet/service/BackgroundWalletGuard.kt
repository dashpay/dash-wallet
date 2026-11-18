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
package de.schildbach.wallet.service

import de.schildbach.wallet.WalletApplication

/**
 * Whether background work that was scheduled earlier (the inactivity reminder, the delayed
 * blockchain service start after an update) may still act on the wallet.
 *
 * Not while Reset Wallet runs, not after a reset that stopped partway (it clears the wipe flag
 * but leaves the durable recovery-required state, and may leave the old wallet file behind),
 * and not without a wallet file. A file check rather than the loaded wallet, so a caller on
 * the main thread never waits for a deferred wallet load.
 */
internal fun mayActOnWalletInBackground(
    wipeInProgress: Boolean,
    wipeRecoveryRequired: Boolean,
    walletFileExists: Boolean
): Boolean = !wipeInProgress && !wipeRecoveryRequired && walletFileExists

internal fun WalletApplication.mayActOnWalletInBackground(): Boolean = mayActOnWalletInBackground(
    wipeInProgress = isWipeInProgress,
    wipeRecoveryRequired = isWalletWipeRecoveryRequired,
    walletFileExists = walletFileExists()
)
