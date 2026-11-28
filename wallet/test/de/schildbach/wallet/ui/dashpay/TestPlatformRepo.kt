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

package de.schildbach.wallet.ui.dashpay

import de.schildbach.wallet.WalletApplication
import io.mockk.mockk

/** A real [PlatformRepo] whose collaborators are all relaxed mocks; stub [walletApplication] as needed. */
fun testPlatformRepo(walletApplication: WalletApplication = mockk(relaxed = true)) = PlatformRepo(
    walletApplication = walletApplication,
    appDatabase = mockk(relaxed = true),
    platform = mockk(relaxed = true),
    dashPayConfig = mockk(relaxed = true),
    sdkUsernameQueries = mockk(relaxed = true),
    sdkVotingQueries = mockk(relaxed = true),
    sdkProfileQueries = mockk(relaxed = true),
    identityCreationStatus = mockk(relaxed = true)
)
