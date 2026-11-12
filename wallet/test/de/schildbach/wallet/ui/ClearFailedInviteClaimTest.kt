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

import android.app.Application
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.database.entity.IdentityRecordKey
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MO-975: an invite claim that stopped on "Invite has already been used" is
 * dead, but its record still read as "being created" and every invite opened
 * afterwards was turned away. A new invite now clears it first; any other
 * record is left alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class ClearFailedInviteClaimTest {

    private val identityRepository = mockk<IdentityRepository>(relaxed = true)

    /** What the identity store holds; cleared by [identityRepository]. */
    @Volatile
    private var stored: BlockchainIdentityData? = null

    /** Runs inside the conditional clear, before its compare: where a concurrent retry lands. */
    private var beforeClear: () -> Unit = {}

    private fun record(errorMessage: String?) = BlockchainIdentityData(
        creationState = IdentityCreationState.CREDIT_FUNDING_TX_CREATING,
        creationStateErrorMessage = errorMessage,
        username = "dhhf",
        usernameSecondary = "dhhf5",
        userId = null,
        restoring = false,
        usingInvite = true
    )

    private fun viewModel(): InviteHandlerViewModel {
        val dashPayConfig = mockk<DashPayConfig>(relaxed = true)
        every { dashPayConfig.observe(DashPayConfig.INVITATION_LINK) } returns MutableStateFlow(null)
        every { dashPayConfig.observe(DashPayConfig.INVITATION_FROM_ONBOARDING) } returns flowOf(false)

        val identityConfig = mockk<BlockchainIdentityConfig>(relaxed = true)
        every { identityConfig.observe() } returns emptyFlow()
        coEvery { identityConfig.load() } answers { stored }
        coEvery { identityRepository.clearBlockchainIdentityDataIfUnchanged(any()) } answers {
            beforeClear()
            val current = stored
            if (current != null && IdentityRecordKey.of(current) == firstArg<IdentityRecordKey>()) {
                stored = null
                true
            } else {
                false
            }
        }

        return InviteHandlerViewModel(
            identityConfig,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            dashPayConfig,
            identityRepository
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a claim that failed because the invite was used no longer blocks a new invite`() = runBlocking {
        stored = record("IllegalStateException: Invite has already been used")
        val viewModel = viewModel()
        assertTrue(viewModel.isInviteClaimInProgress())

        assertTrue(viewModel.clearFailedInviteClaim())

        coVerify(exactly = 1) { identityRepository.clearBlockchainIdentityDataIfUnchanged(any()) }
        assertFalse(viewModel.isInviteClaimInProgress())
    }

    @Test
    fun `a retryable claim failure still blocks a new invite`() = runBlocking {
        stored = record("IllegalStateException: shielded invite claim failed: no bound shielded sub-wallet for the claimer yet")
        val viewModel = viewModel()

        assertFalse(viewModel.clearFailedInviteClaim())

        coVerify(exactly = 0) { identityRepository.clearBlockchainIdentityDataIfUnchanged(any()) }
        assertTrue(viewModel.isInviteClaimInProgress())
    }

    @Test
    fun `a claim still running is not cleared`() = runBlocking {
        stored = record(null)
        val viewModel = viewModel()

        assertFalse(viewModel.clearFailedInviteClaim())

        coVerify(exactly = 0) { identityRepository.clearBlockchainIdentityDataIfUnchanged(any()) }
        assertTrue(viewModel.isInviteClaimInProgress())
    }

    @Test
    fun `a retry that reset the error between the read and the clear keeps its record`() = runBlocking {
        stored = record("IllegalStateException: Invite has already been used")
        beforeClear = { stored = record(null) } // the retry reset the error and restarted the claim
        val viewModel = viewModel()

        assertFalse(viewModel.clearFailedInviteClaim())

        assertTrue("the retry's record survives", viewModel.isInviteClaimInProgress())
    }
}
