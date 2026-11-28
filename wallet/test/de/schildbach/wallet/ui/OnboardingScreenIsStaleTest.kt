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

package de.schildbach.wallet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MO-975: an onboarding screen left over from a Reset Wallet + invite link
 * offered Create / Recover after another onboarding screen had already
 * created and saved a wallet, and Create replaced that wallet without a
 * reset. Such a screen must route away instead.
 */
class OnboardingScreenIsStaleTest {

    @Test
    fun `a saved and installed wallet makes the Create and Recover choice stale`() {
        assertTrue(onboardingScreenIsStale(walletFileExists = true, walletInstalled = true, wipeInProgress = false))
    }

    @Test
    fun `first run and a reset that finished are not stale`() {
        assertFalse(onboardingScreenIsStale(walletFileExists = false, walletInstalled = false, wipeInProgress = false))
    }

    @Test
    fun `a new wallet not saved yet can still be replaced`() {
        // Created on this screen, then the user backed out of Set PIN before
        // the first save: creating again only replaces that unsaved wallet.
        assertFalse(onboardingScreenIsStale(walletFileExists = false, walletInstalled = true, wipeInProgress = false))
    }

    @Test
    fun `a reset in progress is not a stale screen`() {
        assertFalse(onboardingScreenIsStale(walletFileExists = true, walletInstalled = true, wipeInProgress = true))
    }

    @Test
    fun `a wallet file without a loaded wallet is the degraded path, not a stale screen`() {
        assertFalse(onboardingScreenIsStale(walletFileExists = true, walletInstalled = false, wipeInProgress = false))
    }
}
