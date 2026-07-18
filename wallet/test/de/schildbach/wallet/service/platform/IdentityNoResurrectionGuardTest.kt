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

package de.schildbach.wallet.service.platform

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.AppDatabase
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.service.DashSystemService
import de.schildbach.wallet.service.platform.sdk.SdkProfileQueries
import de.schildbach.wallet.service.platform.sdk.SdkUsernameQueries
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import de.schildbach.wallet.data.WalletData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the no-resurrection guard.
 *
 * Confirmed live: "Reset Wallet" does not restart the process, and a platform
 * sync iteration that loaded the previous wallet's BlockchainIdentityData
 * before the reset re-persisted it AFTER BlockchainIdentityConfig.clear() ran
 * — resurrecting the old identity (username, DONE_AND_DISMISS) onto a
 * brand-new wallet and re-enabling the whole DashPay UI. The guard makes
 * IdentityRepositoryImpl.updateBlockchainIdentityData refuse to write a
 * completed (>= DONE) identity over a cleared store, while leaving the
 * creation / restore / invite flows (which march creationState through
 * intermediate persisted states) untouched.
 */
class IdentityNoResurrectionGuardTest {

    // ----- pure classifier -------------------------------------------------

    @Test
    fun clearedStore_doneIdentity_isResurrection() {
        assertTrue(isResurrectingClearedIdentity(null, IdentityCreationState.DONE))
        assertTrue(isResurrectingClearedIdentity(null, IdentityCreationState.DONE_AND_DISMISS))
        assertTrue(isResurrectingClearedIdentity("NONE", IdentityCreationState.DONE))
        assertTrue(isResurrectingClearedIdentity("NONE", IdentityCreationState.DONE_AND_DISMISS))
    }

    @Test
    fun populatedStore_doneIdentity_isNotResurrection() {
        // normal completion transitions: the store already has a state
        assertFalse(isResurrectingClearedIdentity("VOTING", IdentityCreationState.DONE_AND_DISMISS))
        assertFalse(isResurrectingClearedIdentity("DASHPAY_PROFILE_CREATED", IdentityCreationState.DONE))
        assertFalse(isResurrectingClearedIdentity("DONE", IdentityCreationState.DONE_AND_DISMISS))
    }

    @Test
    fun clearedStore_inProgressIdentity_isNotResurrection() {
        // creation / restore / invite flows legitimately start from an empty
        // store and persist progressive states — the guard must not block them
        assertFalse(isResurrectingClearedIdentity(null, IdentityCreationState.NONE))
        assertFalse(isResurrectingClearedIdentity(null, IdentityCreationState.UPGRADING_WALLET))
        assertFalse(isResurrectingClearedIdentity(null, IdentityCreationState.IDENTITY_REGISTERING))
        assertFalse(isResurrectingClearedIdentity(null, IdentityCreationState.VOTING))
        assertFalse(isResurrectingClearedIdentity("NONE", IdentityCreationState.CREDIT_FUNDING_TX_CREATING))
    }

    // ----- repository behavior --------------------------------------------

    private fun repository(storage: BlockchainIdentityConfig): IdentityRepositoryImpl {
        return IdentityRepositoryImpl(
            walletApplication = mockk<WalletApplication>(relaxed = true),
            appDatabase = mockk<AppDatabase>(relaxed = true),
            blockchainIdentityDataStorage = storage,
            walletDataProvider = mockk<WalletData>(relaxed = true),
            platformRepo = mockk<PlatformRepo>(relaxed = true),
            dashPayConfig = mockk<DashPayConfig>(relaxed = true),
            dashSystemService = mockk<DashSystemService>(relaxed = true),
            sdkUsernameQueries = mockk<SdkUsernameQueries>(relaxed = true),
            sdkProfileQueries = mockk<SdkProfileQueries>(relaxed = true)
        )
    }

    private fun identityData(state: IdentityCreationState) = BlockchainIdentityData(
        creationState = state,
        creationStateErrorMessage = null,
        username = "stale-user",
        usernameSecondary = null,
        userId = "6qKq6dvbcJ8fH8ekXCbrbQpNfKPRgcHLw1TEQSyx3KVy",
        restoring = false
    )

    @Test
    fun updateBlockchainIdentityData_refusesDoneIdentityOverClearedStore() = runBlocking {
        val storage = mockk<BlockchainIdentityConfig>(relaxed = true)
        coEvery { storage.get(BlockchainIdentityConfig.CREATION_STATE) } returns null

        repository(storage).updateBlockchainIdentityData(identityData(IdentityCreationState.DONE_AND_DISMISS))

        coVerify(exactly = 0) { storage.insert(any<BlockchainIdentityData>()) }
    }

    @Test
    fun updateBlockchainIdentityData_refusesDoneIdentityOverNoneState() = runBlocking {
        val storage = mockk<BlockchainIdentityConfig>(relaxed = true)
        coEvery { storage.get(BlockchainIdentityConfig.CREATION_STATE) } returns
            IdentityCreationState.NONE.name

        repository(storage).updateBlockchainIdentityData(identityData(IdentityCreationState.DONE))

        coVerify(exactly = 0) { storage.insert(any<BlockchainIdentityData>()) }
    }

    @Test
    fun updateBlockchainIdentityData_persistsDoneIdentityOverPopulatedStore() = runBlocking {
        val storage = mockk<BlockchainIdentityConfig>(relaxed = true)
        coEvery { storage.get(BlockchainIdentityConfig.CREATION_STATE) } returns
            IdentityCreationState.VOTING.name

        val data = identityData(IdentityCreationState.DONE_AND_DISMISS)
        repository(storage).updateBlockchainIdentityData(data)

        coVerify(exactly = 1) { storage.insert(data) }
    }

    @Test
    fun updateBlockchainIdentityData_persistsInProgressIdentityOverClearedStore() = runBlocking {
        // fresh identity creation/restore starts from an empty store — must not be blocked
        val storage = mockk<BlockchainIdentityConfig>(relaxed = true)
        coEvery { storage.get(BlockchainIdentityConfig.CREATION_STATE) } returns null

        val data = identityData(IdentityCreationState.UPGRADING_WALLET)
        repository(storage).updateBlockchainIdentityData(data)

        coVerify(exactly = 1) { storage.insert(data) }
    }
}
