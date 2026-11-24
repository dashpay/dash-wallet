/*
 * Copyright 2026 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.dashpay

import android.app.Application
import android.net.Uri
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Context
import org.bitcoinj.wallet.Wallet
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dashpay.IdentityStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A replacement name for an invite-funded identity is paid from its credits and never touches the spent invite. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class CreateIdentityServiceInviteRetryTest {
    private val identityRepository = mockk<IdentityRepository>(relaxed = true)
    private val platformRepo = mockk<PlatformRepo>(relaxed = true)
    private val topUpRepository = mockk<TopUpRepository>(relaxed = true)
    private val configuration = mockk<org.dash.wallet.common.Configuration>(relaxed = true)
    private val blockchainIdentity = mockk<BlockchainIdentity>(relaxed = true)
    private val invite = InvitationLinkData(Uri.parse("dashpay://invite?du=inviter&assetlocktx=00&pk=00"), true)
    private val inviterId = "inviterUserId"
    private var savedInviter: String? = null
    private lateinit var identityData: BlockchainIdentityData
    private lateinit var service: CreateIdentityService

    @Before
    fun setUp() {
        mockkConstructor(SendContactRequestOperation::class)
        every { anyConstructed<SendContactRequestOperation>().create(any()) } returns mockk(relaxed = true)
        every { configuration.inviter } answers { savedInviter }
        every { configuration.inviter = any() } answers { savedInviter = firstArg() }
        every { blockchainIdentity.getUsernames() } returns emptyList()

        coEvery { identityRepository.loadBlockchainIdentityData() } answers { identityData }
        coEvery { identityRepository.loadBlockchainIdentityBaseData() } answers { identityData }
        coEvery { identityRepository.initBlockchainIdentity(any(), any()) } returns blockchainIdentity
        coEvery { identityRepository.updateIdentityCreationState(any(), any(), any()) } answers {
            firstArg<BlockchainIdentityData>().creationState = secondArg()
            firstArg<BlockchainIdentityData>().creationStateErrorMessage = thirdArg<Throwable?>()?.message
        }
        coEvery { identityRepository.resetIdentityCreationStateError(any()) } answers {
            firstArg<BlockchainIdentityData>().creationStateErrorMessage = null
        }
        coEvery { identityRepository.replaceUsername(any(), any()) } answers {
            firstArg<BlockchainIdentityData>().apply {
                username = secondArg()
                creationState = IdentityCreationState.IDENTITY_REGISTERED
                creationStateErrorMessage = null
            }
        }
        coEvery { identityRepository.getUser("inviter") } returns listOf(
            UsernameSearchResult("inviter", DashPayProfile(inviterId, "inviter"), null, null)
        )

        service = CreateIdentityService().also {
            it.identityRepository = identityRepository
            it.platformRepo = platformRepo
            it.topUpRepository = topUpRepository
            it.configuration = configuration
            it.platformSyncService = mockk(relaxed = true)
            it.userAlertDao = mockk(relaxed = true)
            it.usernameRequestDao = mockk(relaxed = true)
            it.coinJoinConfig = mockk(relaxed = true)
            it.analytics = mockk(relaxed = true)
        }
        CreateIdentityService::class.java.getDeclaredField("walletApplication\$delegate").apply {
            isAccessible = true
            val wallet = mockk<Wallet>(relaxed = true) {
                every { context } returns Context.getOrCreate(Constants.NETWORK_PARAMETERS)
            }
            set(service, lazyOf(mockk<WalletApplication>(relaxed = true) { every { this@mockk.wallet } returns wallet }))
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun rejectedName_registersOneReplacementNameFromTheIdentityCredits() = runBlocking {
        identityData = inviteIdentity(IdentityCreationState.USERNAME_REGISTERING, "dashuser", rejected = true)

        service.retryInviteWithNewUsername("dashuser2")

        coVerify(exactly = 1) { identityRepository.replaceUsername(identityData, "dashuser2") }
        verify(exactly = 1) { platformRepo.preorderName(blockchainIdentity, any(), "dashuser2") }
        verify(exactly = 1) { platformRepo.registerName(blockchainIdentity, any(), "dashuser2") }
        verify(exactly = 1) { platformRepo.preorderName(any(), any(), any()) }
        verify(exactly = 1) { platformRepo.registerName(any(), any(), any()) }
        assertEquals(IdentityCreationState.DONE, identityData.creationState)
        assertNull(identityData.creationStateErrorMessage)
        verifyTheSpentInviteIsNotUsed()
    }

    @Test
    fun rejectedName_queuesTheInviterContactRequestOnce() = runBlocking {
        identityData = inviteIdentity(IdentityCreationState.USERNAME_REGISTERING, "dashuser", rejected = true)
        service.retryInviteWithNewUsername("dashuser2")

        identityData = inviteIdentity(IdentityCreationState.USERNAME_REGISTERED, "dashuser2", rejected = false)
        service.createIdentityFromInvitation(null, null, null)

        verify(exactly = 1) { anyConstructed<SendContactRequestOperation>().create(inviterId) }
        assertEquals(inviterId, savedInviter)
    }

    @Test
    fun inviterContactRequest_isQueuedOnceTheIdentityIsLoaded() = runBlocking {
        identityData = inviteIdentity(IdentityCreationState.IDENTITY_REGISTERED, "dashuser2", rejected = false)

        service.createIdentityFromInvitation(null, null, null)

        coVerifyOrder {
            identityRepository.init()
            anyConstructed<SendContactRequestOperation>().create(inviterId)
        }
    }

    @Test
    fun interruptedReplacement_resumesWithoutTheSpentInvite() = runBlocking {
        identityData = inviteIdentity(IdentityCreationState.PREORDER_REGISTERING, "dashuser2", rejected = false)

        service.createIdentityFromInvitation(null, null, null)

        verify(exactly = 1) { platformRepo.preorderName(blockchainIdentity, any(), "dashuser2") }
        verify(exactly = 1) { platformRepo.registerName(blockchainIdentity, any(), "dashuser2") }
        assertEquals(IdentityCreationState.DONE, identityData.creationState)
        verifyTheSpentInviteIsNotUsed()
    }

    @Test
    fun interruptedRegistration_resumesAfterTheDomainWithoutRegisteringAgain() = runBlocking {
        identityData = inviteIdentity(IdentityCreationState.USERNAME_REGISTERED, "dashuser2", rejected = false)

        service.createIdentityFromInvitation(null, null, null)

        verify(exactly = 0) { platformRepo.preorderName(any(), any(), any()) }
        verify(exactly = 0) { platformRepo.registerName(any(), any(), any()) }
        verifyTheSpentInviteIsNotUsed()
    }

    @Test
    fun walletFundedIdentity_isNotRetriedFromCredits() {
        identityData = inviteIdentity(IdentityCreationState.USERNAME_REGISTERING, "dashuser", rejected = true)
            .apply { usingInvite = false }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { service.retryInviteWithNewUsername("dashuser2") }
        }
        coVerify(exactly = 0) { identityRepository.replaceUsername(any(), any()) }
        verify(exactly = 0) { platformRepo.preorderName(any(), any(), any()) }
    }

    @Test
    fun identityWithASecondaryName_isNotRetriedFromCredits() {
        identityData = inviteIdentity(IdentityCreationState.USERNAME_REGISTERING, "Dashuser", rejected = true)
            .apply { usernameSecondary = "dashuser3" }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { service.retryInviteWithNewUsername("dashuser2") }
        }
        coVerify(exactly = 0) { identityRepository.replaceUsername(any(), any()) }
    }

    @Test
    fun registeredName_isNotReplaced() {
        identityData = inviteIdentity(IdentityCreationState.USERNAME_REGISTERED, "dashuser", rejected = true)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { service.retryInviteWithNewUsername("dashuser2") }
        }
        coVerify(exactly = 0) { identityRepository.replaceUsername(any(), any()) }
    }

    @Test
    fun restartedService_resumesAnInviteIdentityWithTheInviteFlow() {
        identityData = inviteIdentity(IdentityCreationState.PREORDER_REGISTERING, "dashuser2", rejected = false)

        service.onStartCommand(null, 0, 1)

        verify(timeout = 10_000, exactly = 1) { platformRepo.registerName(blockchainIdentity, any(), "dashuser2") }
        verifyTheSpentInviteIsNotUsed()
    }

    @Test
    fun restartedService_leavesAFinishedInviteIdentityAlone() {
        identityData = inviteIdentity(IdentityCreationState.VOTING, "Dashuser", rejected = false)

        service.onStartCommand(null, 0, 1)

        val workInProgress = CreateIdentityService::class.java.getDeclaredField("workInProgress")
            .apply { isAccessible = true }
        assertFalse(workInProgress.getBoolean(service))
    }

    private fun verifyTheSpentInviteIsNotUsed() {
        verify(exactly = 0) { topUpRepository.obtainAssetLockTransaction(any(), any()) }
        coVerify(exactly = 0) { topUpRepository.createAssetLockTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { topUpRepository.createTopupTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { topUpRepository.topUpIdentity(any(), any()) }
        coVerify(exactly = 0) { platformRepo.registerIdentity(any(), any()) }
    }

    private fun inviteIdentity(state: IdentityCreationState, username: String, rejected: Boolean) =
        BlockchainIdentityData(
            state,
            if (rejected) "StateTransitionBroadcastException: DuplicateUniqueIndexError" else null,
            username,
            null,
            "userId",
            false,
            usingInvite = true,
            invite = invite
        ).apply { registrationStatus = IdentityStatus.REGISTERED }
}
