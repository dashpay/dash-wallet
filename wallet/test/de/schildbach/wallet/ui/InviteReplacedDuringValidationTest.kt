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
import org.junit.Assert.assertNotNull
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
 *
 * The invite is identified by its generation, not its link, so the same
 * holds when A itself is reopened (A -> B -> A); and overlapping validations
 * of one invite (the home screen runs one per feed) cannot let a provisional
 * NOT_SYNCED verdict suppress the synced one.
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

    /** Released by the test to let the in-flight network validation finish. */
    private val releaseValidation = CountDownLatch(1)

    /** Counted down once a validation has reached the network call. */
    private val validationStarted = CountDownLatch(1)

    private val syncedState = mockk<BlockchainState> { every { isSynced() } returns true }
    private val unsyncedState = mockk<BlockchainState> { every { isSynced() } returns false }

    /** What the next read of the blockchain state answers (synced by default). */
    @Volatile
    private var stateAnswer: () -> BlockchainState = { syncedState }

    private fun viewModel(validationOutcome: () -> Boolean): InviteHandlerViewModel {
        val dashPayConfig = mockk<DashPayConfig>(relaxed = true)
        every { dashPayConfig.observe(DashPayConfig.INVITATION_LINK) } returns storedLink
        every { dashPayConfig.observe(DashPayConfig.INVITATION_FROM_ONBOARDING) } returns flowOf(false)
        coEvery { dashPayConfig.get(DashPayConfig.INVITATION_LINK) } answers { storedLink.value }
        coEvery { dashPayConfig.set(DashPayConfig.INVITATION_LINK, any()) } answers { storedLink.value = secondArg() }

        val identityConfig = mockk<BlockchainIdentityConfig>(relaxed = true)
        every { identityConfig.observe() } returns emptyFlow()
        coEvery { identityConfig.load() } returns null

        val stateProvider = mockk<BlockchainStateProvider>(relaxed = true)
        coEvery { stateProvider.getState() } answers { stateAnswer() }

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

    private val InviteHandlerViewModel.current: PendingInvitation
        get() = requireNotNull(pendingInvitation.value) { "no pending invite" }

    /**
     * Stores A, starts validating it, runs [meanwhile] while the network check
     * is blocked, then lets A's check finish. Returns A's validation result.
     */
    private fun InviteHandlerViewModel.validateAWhile(meanwhile: suspend () -> Unit): PendingInvitation? =
        runBlocking {
            setInvitationLink(inviteA, false)
            val validation = async(Dispatchers.Default) { validateInvitation() }
            assertTrue(validationStarted.await(10, TimeUnit.SECONDS))
            meanwhile()
            releaseValidation.countDown()
            validation.await()
        }

    @Test
    fun `a late VALID verdict for A does not bring A back over B`() {
        val viewModel = viewModel { true }

        val result = viewModel.validateAWhile { viewModel.setInvitationLink(inviteB, false) }

        assertNull(result)
        val current = viewModel.current.data
        assertEquals(inviteB.link, current.link)
        assertNull("B must still be unvalidated so it gets its own verdict", current.validationState)
        assertEquals(inviteB.link.toString(), storedLink.value)
    }

    @Test
    fun `a late terminal verdict for A is not published and does not clear B`() {
        // ALREADY_CLAIMED: the network says A's funds are gone
        val viewModel = viewModel { false }

        val result = viewModel.validateAWhile { viewModel.setInvitationLink(inviteB, false) }

        assertNull(result)
        assertEquals(inviteB.link, viewModel.current.data.link)
        assertNull(viewModel.current.data.validationState)
        assertEquals(inviteB.link.toString(), storedLink.value)
    }

    @Test
    fun `reopening A while A's first validation is in flight discards the stale verdict`() {
        val viewModel = viewModel { false }

        val result =
            viewModel.validateAWhile {
                viewModel.setInvitationLink(inviteB, false)
                viewModel.setInvitationLink(inviteA, false)
            }

        // Same link as the validated snapshot, but a different generation:
        // the reopened A must get its own validation and its own dialog.
        assertNull(result)
        assertEquals(inviteA.link, viewModel.current.data.link)
        assertNull(viewModel.current.data.validationState)
        assertEquals(inviteA.link.toString(), storedLink.value)
    }

    @Test
    fun `clearing A's terminal verdict after B replaced it leaves B stored`() {
        val viewModel = viewModel { false }
        runBlocking {
            val verdictForA = validateAToCompletion(viewModel)
            viewModel.setInvitationLink(inviteB, false)

            // The dialog for A's verdict clears "its" invite — B must survive.
            assertFalse(viewModel.clearInvitation(expectedGeneration = verdictForA.generation))
            assertEquals(inviteB.link.toString(), storedLink.value)
            assertEquals(inviteB.link, viewModel.current.data.link)
        }
    }

    @Test
    fun `clearing A's terminal verdict after A was reopened leaves the reopened A stored`() {
        val viewModel = viewModel { false }
        runBlocking {
            val verdictForA = validateAToCompletion(viewModel)
            viewModel.setInvitationLink(inviteB, false)
            viewModel.setInvitationLink(inviteA, false)

            assertFalse(viewModel.clearInvitation(expectedGeneration = verdictForA.generation))
            assertEquals(inviteA.link.toString(), storedLink.value)
            assertEquals(inviteA.link, viewModel.current.data.link)
            assertNull(viewModel.current.data.validationState)
        }
    }

    @Test
    fun `clearing the invite that was validated still clears it`() {
        val viewModel = viewModel { false }
        runBlocking {
            val verdict = validateAToCompletion(viewModel)

            assertTrue(viewModel.clearInvitation(expectedGeneration = verdict.generation))
            assertEquals("", storedLink.value)
            assertNull(viewModel.pendingInvitation.value)
        }
    }

    @Test
    fun `an unsynced verdict landing first does not suppress the synced VALID one`() {
        val viewModel = viewModel { true }

        // The synced validation blocks on the network; meanwhile an
        // overlapping call reads the chain as still syncing and publishes
        // NOT_SYNCED for the same, unreplaced invite.
        val synced =
            viewModel.validateAWhile {
                stateAnswer = { unsyncedState }
                val provisional = viewModel.validateInvitation()
                assertEquals(InvitationValidationState.NOT_SYNCED, provisional?.data?.validationState)
            }

        assertEquals(InvitationValidationState.VALID, synced?.data?.validationState)
        assertEquals(InvitationValidationState.VALID, viewModel.current.data.validationState)
    }

    @Test
    fun `an unsynced verdict landing after the synced VALID one is discarded`() {
        val viewModel = viewModel { true }
        releaseValidation.countDown()
        val unsyncedReadStarted = CountDownLatch(1)
        val releaseUnsyncedRead = CountDownLatch(1)
        runBlocking {
            viewModel.setInvitationLink(inviteA, false)
            // The first call reads the chain as still syncing, but is slow to
            // get there; the second sees it synced and settles the invite.
            stateAnswer = {
                unsyncedReadStarted.countDown()
                check(releaseUnsyncedRead.await(10, TimeUnit.SECONDS)) { "state read was never released" }
                unsyncedState
            }
            val unsynced = async(Dispatchers.Default) { viewModel.validateInvitation() }
            assertTrue(unsyncedReadStarted.await(10, TimeUnit.SECONDS))
            stateAnswer = { syncedState }

            val synced = viewModel.validateInvitation()
            assertEquals(InvitationValidationState.VALID, synced?.data?.validationState)

            releaseUnsyncedRead.countDown()
            assertNull(unsynced.await())
            assertEquals(InvitationValidationState.VALID, viewModel.current.data.validationState)
        }
    }

    @Test
    fun `a second definitive verdict for the same invite is not published again`() {
        val viewModel = viewModel { false }
        runBlocking {
            val first = validateAToCompletion(viewModel)

            // The first definitive verdict is final for its generation.
            assertNull(viewModel.validateInvitation())
            assertEquals(first, viewModel.current)
        }
    }

    /** The lock screen, as the home screen sees it. */
    @Volatile
    private var locked = false

    /**
     * What WalletTransactionsFragment.processInvitation() does with an invite:
     * validate it if it has no verdict yet, then — only if the lock screen is
     * not up at that point — take the undelivered verdict to show its dialog.
     * Returns the verdict this caller delivered, if any.
     */
    private suspend fun InviteHandlerViewModel.processLikeHomeScreen(
        lockScreenActive: () -> Boolean = { locked }
    ): PendingInvitation? {
        if (pendingInvitation.value?.data?.validationState == null) {
            validateInvitation()
        }
        if (lockScreenActive()) return null
        return takeUndeliveredVerdict { !lockScreenActive() }
    }

    @Test
    fun `a verdict published behind the lock screen is delivered once by the unlock`() {
        val viewModel = viewModel { false }
        runBlocking {
            locked = true
            viewModel.setInvitationLink(inviteA, false)
            // The locked caller starts validating; the user unlocks meanwhile,
            // and the unlock handler finds A still unvalidated and starts its
            // own validation (blocked on the same network check).
            val lockedCaller = async(Dispatchers.Default) { viewModel.processLikeHomeScreen() }
            assertTrue(validationStarted.await(10, TimeUnit.SECONDS))
            // The locked caller finishes first, while still locked: it
            // publishes ALREADY_CLAIMED but must not consume the delivery.
            releaseValidation.countDown()
            assertNull(lockedCaller.await())
            assertEquals(InvitationValidationState.ALREADY_CLAIMED, viewModel.current.data.validationState)

            // Unlock: the unlock handler's own validation is rejected (already
            // settled), yet it still delivers the pending verdict.
            locked = false
            assertNull(viewModel.validateInvitation())
            val delivered = viewModel.takeUndeliveredVerdict { true }
            assertEquals(InvitationValidationState.ALREADY_CLAIMED, delivered?.data?.validationState)
            assertTrue(viewModel.clearInvitation(expectedGeneration = requireNotNull(delivered).generation))
            assertEquals("", storedLink.value)
        }
    }

    @Test
    fun `unlocking during validation delivers the verdict exactly once`() {
        val viewModel = viewModel { false }
        runBlocking {
            locked = true
            viewModel.setInvitationLink(inviteA, false)
            val lockedCaller = async(Dispatchers.Default) { viewModel.processLikeHomeScreen() }
            assertTrue(validationStarted.await(10, TimeUnit.SECONDS))
            // Unlock while A's network check is in flight: the unlock handler
            // finds A unvalidated and runs its own validation.
            locked = false
            val unlockHandler = async(Dispatchers.Default) { viewModel.processLikeHomeScreen() }
            releaseValidation.countDown()

            // Whichever finishes first — the locked-at-start caller checks the
            // lock at delivery time, so it may deliver too — exactly one shows
            // the dialog.
            val delivered = listOfNotNull(lockedCaller.await(), unlockHandler.await())
            assertEquals(1, delivered.size)
            assertEquals(InvitationValidationState.ALREADY_CLAIMED, delivered.single().data.validationState)

            // Later callers (e.g. the invitation feed re-emitting the settled
            // invite) find it already delivered.
            assertNull(viewModel.processLikeHomeScreen())
        }
    }

    @Test
    fun `a verdict is not delivered while the lock screen is up`() {
        val viewModel = viewModel { false }
        runBlocking {
            validateAToCompletion(viewModel)

            assertNull(viewModel.takeUndeliveredVerdict { false })
            assertNotNull(viewModel.takeUndeliveredVerdict { true })
        }
    }

    @Test
    fun `an undelivered verdict for a replaced invite is never delivered`() {
        val viewModel = viewModel { false }
        runBlocking {
            validateAToCompletion(viewModel)
            viewModel.setInvitationLink(inviteB, false)

            // B is unvalidated: nothing to deliver, and A's verdict is gone.
            assertNull(viewModel.takeUndeliveredVerdict { true })
        }
    }

    /** Validates A to ALREADY_CLAIMED and returns the published verdict. */
    private suspend fun validateAToCompletion(viewModel: InviteHandlerViewModel): PendingInvitation {
        releaseValidation.countDown()
        viewModel.setInvitationLink(inviteA, false)
        val verdict = requireNotNull(viewModel.validateInvitation())
        assertEquals(InvitationValidationState.ALREADY_CLAIMED, verdict.data.validationState)
        return verdict
    }
}
