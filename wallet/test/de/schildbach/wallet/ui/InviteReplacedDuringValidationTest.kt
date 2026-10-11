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
import android.net.Uri
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.data.InvitationValidationState
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.services.BlockchainStateProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Replacing invite A with invite B while A's network validation is still in
 * flight must not let A's late verdict win: a VALID verdict used to restore A
 * in memory, and a terminal one (INVALID / ALREADY_CLAIMED) was shown by the
 * home screen, whose clearInvitation() then deleted B from preferences.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class InviteReplacedDuringValidationTest {
    private val inviteA =
        InvitationLinkData(
            Uri.parse("dashpay://invite?du=alice&assetlocktx=aaaa&pk=keyA&islock=null")
        )
    private val inviteB =
        InvitationLinkData(
            Uri.parse("dashpay://invite?du=bob&assetlocktx=bbbb&pk=keyB&islock=null")
        )

    /** The persisted INVITATION_LINK preference. */
    private val storedLink = MutableStateFlow<String?>(null)

    /** Released by the test to let the in-flight network validation of A finish. */
    private val releaseValidation = CountDownLatch(1)

    /** Counted down once validation of A has reached the network call. */
    private val validationStarted = CountDownLatch(1)

    private fun viewModel(validationOutcome: () -> Boolean): InviteHandlerViewModel {
        val dashPayConfig = mockk<DashPayConfig>(relaxed = true)
        every { dashPayConfig.observe(DashPayConfig.INVITATION_LINK) } returns storedLink
        every { dashPayConfig.observe(DashPayConfig.INVITATION_FROM_ONBOARDING) } returns flowOf(false)
        coEvery { dashPayConfig.get(DashPayConfig.INVITATION_LINK) } answers { storedLink.value }
        coEvery { dashPayConfig.set(DashPayConfig.INVITATION_LINK, any()) } answers { storedLink.value = secondArg() }

        val identityConfig = mockk<BlockchainIdentityConfig>(relaxed = true)
        every { identityConfig.observe() } returns emptyFlow()
        coEvery { identityConfig.load() } returns null

        val syncedState = mockk<BlockchainState> { every { isSynced() } returns true }
        val stateProvider = mockk<BlockchainStateProvider>(relaxed = true)
        coEvery { stateProvider.getState() } returns syncedState

        val topUpRepository = mockk<TopUpRepository>(relaxed = true)
        every { topUpRepository.validateInvitation(any()) } answers {
            validationStarted.countDown()
            check(releaseValidation.await(10, TimeUnit.SECONDS)) { "validation was never released" }
            validationOutcome()
        }

        return InviteHandlerViewModel(
            identityConfig,
            mockk(relaxed = true),
            stateProvider,
            topUpRepository,
            dashPayConfig
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

    /**
     * Stores A, starts validating it, stores B while the network check is
     * blocked, then lets A's check finish. Returns A's validation result.
     */
    private fun InviteHandlerViewModel.replaceAWithBDuringValidation(): InvitationValidationState =
        runBlocking {
            setInvitationLink(inviteA, false)
            val validation = async(Dispatchers.Default) { validateInvitation() }
            assertTrue(validationStarted.await(10, TimeUnit.SECONDS))
            setInvitationLink(inviteB, false)
            releaseValidation.countDown()
            validation.await()
        }

    @Test
    fun `a late VALID verdict for A does not bring A back over B`() {
        val viewModel = viewModel { true }

        val result = viewModel.replaceAWithBDuringValidation()

        assertEquals(InvitationValidationState.NONE, result)
        val current = viewModel.invitation.value
        assertEquals(inviteB.link, current?.link)
        assertNull("B must still be unvalidated so it gets its own verdict", current?.validationState)
        assertEquals(inviteB.link.toString(), storedLink.value)
    }

    @Test
    fun `a late terminal verdict for A is not published and does not clear B`() {
        // ALREADY_CLAIMED: the network says A's funds are gone
        val viewModel = viewModel { false }

        val result = viewModel.replaceAWithBDuringValidation()

        assertEquals(InvitationValidationState.NONE, result)
        assertEquals(inviteB.link, viewModel.invitation.value?.link)
        assertNull(viewModel.invitation.value?.validationState)
        assertEquals(inviteB.link.toString(), storedLink.value)
    }

    @Test
    fun `clearing A's terminal verdict after B replaced it leaves B stored`() {
        val viewModel = viewModel { false }
        runBlocking {
            setUpAValidatedThenReplaced(viewModel)

            // The dialog for A's verdict clears "its" invite — B must survive.
            val cleared =
                viewModel.clearInvitation(
                    expected = inviteA.validate(InvitationValidationState.ALREADY_CLAIMED)
                )

            assertFalse(cleared)
            assertEquals(inviteB.link.toString(), storedLink.value)
            assertEquals(inviteB.link, viewModel.invitation.value?.link)
        }
    }

    @Test
    fun `clearing the invite that was validated still clears it`() {
        val viewModel = viewModel { false }
        releaseValidation.countDown()
        runBlocking {
            viewModel.setInvitationLink(inviteA, false)
            assertEquals(InvitationValidationState.ALREADY_CLAIMED, viewModel.validateInvitation())
            val verdict = requireNotNull(viewModel.invitation.value)

            assertTrue(viewModel.clearInvitation(expected = verdict))
            assertEquals("", storedLink.value)
            assertNull(viewModel.invitation.value)
        }
    }

    /** A is validated (verdict published), then B is opened before A's dialog clears. */
    private suspend fun setUpAValidatedThenReplaced(viewModel: InviteHandlerViewModel) {
        releaseValidation.countDown()
        viewModel.setInvitationLink(inviteA, false)
        assertEquals(InvitationValidationState.ALREADY_CLAIMED, viewModel.validateInvitation())
        viewModel.setInvitationLink(inviteB, false)
    }
}
