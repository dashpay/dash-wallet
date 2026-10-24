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
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.platform.sdk.SdkTransparentUsernameCreation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The More screen's "Try again" (and its tile tap) for a name-only retry's error goes back into
 * the name-only retry with the requested names on record: never to CreateIdentityService, which
 * would reset the identity and fund it again after the cutover.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateIdentityViewModelNameOnlyRetryTest {
    private val identityId = "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr"
    private val walletApplication = mockk<WalletApplication>(relaxed = true)
    private val transparentUsernameCreation = mockk<SdkTransparentUsernameCreation>()
    private val config = mockk<BlockchainIdentityConfig> {
        every { observe() } returns emptyFlow()
        every { observe(BlockchainIdentityConfig.CREATION_STATE) } returns emptyFlow()
        every { observe(BlockchainIdentityConfig.CREATION_STATE_ERROR_MESSAGE) } returns emptyFlow()
        coEvery { get(BlockchainIdentityConfig.RESTORING) } returns false
        coEvery { get(BlockchainIdentityConfig.USING_INVITE) } returns false
        coEvery { get(BlockchainIdentityConfig.IDENTITY_ID) } returns identityId
        coEvery { get(BlockchainIdentityConfig.USERNAME) } returns "alice3"
        coEvery { get(BlockchainIdentityConfig.USERNAME_SECONDARY) } returns null
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = CreateIdentityViewModel(
        walletApplication,
        mockk(relaxed = true),
        config,
        mockk(relaxed = true),
        mockk(relaxed = true),
        transparentUsernameCreation
    )

    @Test
    fun `a name-only error retries the name, never CreateIdentityService`() {
        coEvery { config.get(BlockchainIdentityConfig.CREATION_STATE_ERROR_MESSAGE) } returns
            SdkTransparentUsernameCreation.NAME_ONLY_RETRY_ERROR + ": timeout"
        coEvery { transparentUsernameCreation.retryNameForExistingIdentity(any(), any(), any()) } returns true

        viewModel().retryCreateIdentity()

        coVerify(exactly = 1, timeout = 5_000) {
            transparentUsernameCreation.retryNameForExistingIdentity(identityId, "alice3", null)
        }
        verify(exactly = 0) { walletApplication.startService(any()) }
    }

    @Test
    fun `a refused name-only retry is reported once, with the error on record`() = runBlocking {
        val error = SdkTransparentUsernameCreation.NAME_ONLY_RETRY_ERROR + ": timeout"
        coEvery { config.get(BlockchainIdentityConfig.CREATION_STATE_ERROR_MESSAGE) } returns error
        coEvery { transparentUsernameCreation.retryNameForExistingIdentity(any(), any(), any()) } returns false

        val viewModel = viewModel()
        viewModel.retryCreateIdentity()

        assertEquals(error, withTimeout(5_000) { viewModel.nameRetryRefused.first() })
        verify(exactly = 0) { walletApplication.startService(any()) }
    }

    @Test
    fun `a name-only retry with no requested name on record is reported as refused`() = runBlocking {
        val error = SdkTransparentUsernameCreation.NAME_ONLY_RETRY_ERROR + ": timeout"
        coEvery { config.get(BlockchainIdentityConfig.CREATION_STATE_ERROR_MESSAGE) } returns error
        coEvery { config.get(BlockchainIdentityConfig.USERNAME) } returns null

        val viewModel = viewModel()
        viewModel.retryCreateIdentity()

        assertEquals(error, withTimeout(5_000) { viewModel.nameRetryRefused.first() })
        coVerify(exactly = 0) { transparentUsernameCreation.retryNameForExistingIdentity(any(), any(), any()) }
        verify(exactly = 0) { walletApplication.startService(any()) }
    }

    @Test
    fun `any other error keeps the legacy retry`() {
        coEvery { config.get(BlockchainIdentityConfig.CREATION_STATE_ERROR_MESSAGE) } returns "some other failure"

        viewModel().retryCreateIdentity()

        verify(exactly = 1, timeout = 5_000) { walletApplication.startService(any()) }
        coVerify(exactly = 0) { transparentUsernameCreation.retryNameForExistingIdentity(any(), any(), any()) }
    }
}
