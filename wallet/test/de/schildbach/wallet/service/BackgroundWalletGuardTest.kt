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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guard the inactivity reminder and the delayed service start both check. */
class BackgroundWalletGuardTest {
    @Test
    fun `a normally loaded wallet may be acted on`() {
        assertTrue(mayActOnWalletInBackground(wipeInProgress = false, wipeRecoveryRequired = false, walletFileExists = true))
    }

    @Test
    fun `not while Reset Wallet runs, even with the wallet file still there`() {
        assertFalse(mayActOnWalletInBackground(wipeInProgress = true, wipeRecoveryRequired = false, walletFileExists = true))
    }

    @Test
    fun `not after a reset that stopped partway and left the wallet file`() {
        assertFalse(mayActOnWalletInBackground(wipeInProgress = false, wipeRecoveryRequired = true, walletFileExists = true))
    }

    @Test
    fun `not without a wallet file`() {
        assertFalse(mayActOnWalletInBackground(wipeInProgress = false, wipeRecoveryRequired = false, walletFileExists = false))
    }
}
