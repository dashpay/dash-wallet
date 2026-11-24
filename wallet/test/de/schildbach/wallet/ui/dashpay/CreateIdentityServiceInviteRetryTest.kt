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
import android.app.NotificationManager
import android.app.Service
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.database.entity.NameReplacementAttempt
import de.schildbach.wallet.database.entity.NameReplacementFailure
import de.schildbach.wallet.database.entity.NameReplacementState
import de.schildbach.wallet.payments.PendingDirectPaymentVerifier
import de.schildbach.wallet.service.platform.ContestedUsernameFees
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.ui.dashpay.work.InviterContactRequestOperation
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Base58
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.wallet.Wallet
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dashpay.IdentityStatus
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A replacement name for an invite-funded identity runs as one persisted attempt in the identity's own store:
 * APPROVED -> DISPATCHING -> DISPATCHED -> NAME_SAVED -> completed, stopping for the user on anything unknown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class CreateIdentityServiceInviteRetryTest {
    companion object {
        private const val REJECTED = "StateTransitionBroadcastException: DuplicateUniqueIndexError"
        private val IDENTITY_ID: String = Base58.encode(ByteArray(32) { 1 })
        private val KEY = ByteArray(20) { 7 }
        private val TX = ByteArray(40) { 9 }
        private val TOP_UP = Coin.parseCoin("0.03")
        private const val ENOUGH_CREDITS = 300_000_000L
    }

    private val config = BlockchainIdentityConfig(
        ApplicationProvider.getApplicationContext<Application>(),
        mockk(relaxed = true),
        mockk(relaxed = true)
    )
    private val identityRepository = mockk<IdentityRepository>(relaxed = true)
    private val platformRepo = mockk<PlatformRepo>(relaxed = true)
    private val topUpRepository = mockk<TopUpRepository>(relaxed = true)
    private val configuration = mockk<org.dash.wallet.common.Configuration>(relaxed = true)
    private val blockchainIdentity = mockk<BlockchainIdentity>(relaxed = true)
    private val topUps = mockk<NameReplacementTopUps>()
    private val invite = InvitationLinkData(Uri.parse("dashpay://invite?du=inviter&assetlocktx=00&pk=00"), true)
    private val topUpTx = mockk<AssetLockTransaction>(relaxed = true)
    private val walletApplication = mockk<WalletApplication>(relaxed = true)
    private val pendingPaymentVerifier = mockk<PendingDirectPaymentVerifier>(relaxed = true)
    private lateinit var service: CreateIdentityService

    @Before
    fun setUp() {
        mockkConstructor(InviterContactRequestOperation::class)
        coEvery { anyConstructed<InviterContactRequestOperation>().enqueue(any(), any()) } just Runs
        every { blockchainIdentity.getUsernames() } returns emptyList()
        every { blockchainIdentity.uniqueIdString } returns IDENTITY_ID
        every { identityRepository.blockchainIdentity } returns blockchainIdentity
        every { topUpTx.unsafeBitcoinSerialize() } returns TX
        coEvery { topUpRepository.createTopupTransaction(any(), any(), any(), any()) } returns topUpTx
        coEvery { platformRepo.getIdentityBalance(any()) } returns CreditBalanceInfo(ENOUGH_CREDITS)
        every { topUps.currentKeyHash(any()) } returns KEY
        every { topUps.resolve(any(), any()) } returns NameReplacementTopUps.Resolution.Ready(topUpTx)

        coEvery { identityRepository.loadBlockchainIdentityData() } coAnswers { config.load() }
        coEvery { identityRepository.loadBlockchainIdentityBaseData() } coAnswers { config.loadBase() }
        coEvery { identityRepository.initBlockchainIdentity(any(), any()) } returns blockchainIdentity
        coEvery { identityRepository.updateIdentityCreationState(any(), any(), any()) } coAnswers {
            val message = thirdArg<Throwable?>()?.let { "${it.javaClass.simpleName}: ${it.message}" }
            firstArg<BlockchainIdentityData>().creationState = secondArg()
            firstArg<BlockchainIdentityData>().creationStateErrorMessage = message
            config.updateCreationState(secondArg(), message)
        }
        coEvery { identityRepository.resetIdentityCreationStateError(any()) } coAnswers {
            firstArg<BlockchainIdentityData>().creationStateErrorMessage = null
            config.updateCreationState(firstArg<BlockchainIdentityData>().creationState, null)
        }
        coEvery { identityRepository.updateBlockchainIdentityData(any()) } coAnswers { config.insert(firstArg()) }
        coEvery { identityRepository.updateBlockchainIdentityData(any(), any()) } coAnswers {
            config.insert(firstArg<BlockchainIdentityData>())
        }

        service = Robolectric.buildService(CreateIdentityService::class.java).get().also {
            it.identityRepository = identityRepository
            it.platformRepo = platformRepo
            it.topUpRepository = topUpRepository
            it.configuration = configuration
            it.platformSyncService = mockk(relaxed = true)
            it.userAlertDao = mockk(relaxed = true)
            it.usernameRequestDao = mockk(relaxed = true)
            it.coinJoinConfig = mockk(relaxed = true)
            it.analytics = mockk(relaxed = true)
            it.blockchainIdentityDataDao = config
            it.replacementStore = config
            it.replacementTopUps = topUps
            it.pendingPaymentVerifier = pendingPaymentVerifier
        }
        val wallet = mockk<Wallet>(relaxed = true) {
            every { context } returns Context.getOrCreate(Constants.NETWORK_PARAMETERS)
        }
        every { walletApplication.wallet } returns wallet
        CreateIdentityService::class.java.getDeclaredField("walletApplication\$delegate").apply {
            isAccessible = true
            set(service, lazyOf(walletApplication))
        }
    }

    @After
    fun tearDown() {
        ContestedUsernameFees.reset()
        unmockkAll()
    }

    @Test
    fun creditsCoverTheName_registersOneReplacementWithoutTheWallet() = runBlocking {
        givenRejectedName()

        service.runReplacement(approve(Coin.ZERO))

        verify(exactly = 1) { platformRepo.preorderName(blockchainIdentity, any(), "dashuser2") }
        verify(exactly = 1) { platformRepo.registerName(blockchainIdentity, any(), "dashuser2") }
        verify(exactly = 1) { platformRepo.preorderName(any(), any(), any()) }
        assertEquals(IdentityCreationState.DONE, stored().creationState)
        assertNull(stored().creationStateErrorMessage)
        assertEquals("dashuser2", stored().username)
        assertNull(config.loadReplacement())
        verifyTheSpentInviteIsNotUsed()
        verifyNoTopUp()
    }

    @Test
    fun creditsCoverTheName_queuesTheContactRequestToTheInviterOnce() = runBlocking {
        givenRejectedName()

        service.runReplacement(approve(Coin.ZERO))

        coVerify(exactly = 1) { anyConstructed<InviterContactRequestOperation>().enqueue("inviter", IDENTITY_ID) }
    }

    @Test
    fun savedName_reloadsTheIdentityBeforeAnythingElseCanWriteTheOldName() = runBlocking {
        givenRejectedName()
        coEvery { identityRepository.init() } coAnswers {
            assertEquals("dashuser2", config.load()!!.username)
        }

        service.runReplacement(approve(Coin.ZERO))

        coVerifyOrder {
            identityRepository.init()
            platformRepo.preorderName(any(), any(), "dashuser2")
        }
    }

    @Test
    fun creditsNoLongerCoverTheName_stopsAndReopensTheForm() = runBlocking {
        givenRejectedName()
        coEvery { platformRepo.getIdentityBalance(any()) } returns CreditBalanceInfo(100_000_000L)

        service.runReplacement(approve(Coin.ZERO))

        assertEquals(NameReplacementFailure.FINAL, config.loadReplacement()!!.failure)
        assertRejectedNameRestored()
        verify(exactly = 0) { platformRepo.preorderName(any(), any(), any()) }
        verifyNoTopUp()
        val notification = errorNotification()
        assertTrue("a final stop offers no Retry", notification.actions.isNullOrEmpty())
        assertTrue("a final stop opens the wallet", notification.contentIntent != null)
    }

    @Test
    fun coldRetry_contestedNameQuotedAtTheReducedFee_isNotDiscardedBeforeTheVersionIsKnown() = runBlocking {
        givenRejectedName()
        val contested = "dashusers"
        val credits = ContestedUsernameFees.nameFeeFor(ContestedUsernameFees.REDUCED_FEE_PROTOCOL_VERSION).value *
            1000 + CreditBalanceInfo.MAX_OPERATION_COST * 2
        coEvery { platformRepo.getIdentityBalance(any()) } returns CreditBalanceInfo(credits)
        val approval = NameReplacementAttempt(
            "attempt", IDENTITY_ID, contested, null, Coin.ZERO,
            protocolVersion = ContestedUsernameFees.REDUCED_FEE_PROTOCOL_VERSION
        )
        assertTrue(config.approveReplacement(approval))
        ContestedUsernameFees.reset()

        service.runReplacement(config.loadReplacement()!!)

        verify(exactly = 1) { platformRepo.preorderName(blockchainIdentity, any(), contested) }
        assertTrue(config.loadReplacement()?.failure != NameReplacementFailure.FINAL)
        assertEquals(contested, stored().username)
        verifyNoTopUp()
    }

    @Test
    fun creditReadFails_isRetryableAndNeverFallsBackToTheWallet() = runBlocking {
        givenRejectedName()
        coEvery { platformRepo.getIdentityBalance(any()) } throws IllegalStateException("offline")

        service.runReplacement(approve(Coin.ZERO))

        val attempt = config.loadReplacement()!!
        assertEquals(NameReplacementFailure.RETRYABLE, attempt.failure)
        assertEquals(NameReplacementState.APPROVED, attempt.state)
        assertEquals(REJECTED, attempt.rejectedError)
        assertEquals("IllegalStateException: offline", stored().creationStateErrorMessage)
        assertEquals("dashuser", stored().username)
        verify(exactly = 0) { platformRepo.preorderName(any(), any(), any()) }
        verifyNoTopUp()
        assertEquals(1, errorNotification().actions.size)
    }

    @Test
    fun walletTopUp_savesTheDispatchBeforeBuildingAndTheTransactionBeforeSending() = runBlocking {
        givenRejectedName()
        coEvery { topUpRepository.createTopupTransaction(any(), any(), any(), any()) } coAnswers {
            assertEquals(NameReplacementState.DISPATCHING, config.loadReplacement()!!.state)
            assertArrayEquals(KEY, config.loadReplacement()!!.topUpKeyHash)
            topUpTx
        }
        coEvery { topUpRepository.topUpIdentity(any(), any()) } coAnswers {
            assertEquals(NameReplacementState.DISPATCHED, config.loadReplacement()!!.state)
            assertArrayEquals(TX, config.loadReplacement()!!.topUpTx)
            assertEquals("dashuser", config.load()!!.username)
        }

        service.runReplacement(approve(TOP_UP))

        coVerifyOrder {
            topUpRepository.createTopupTransaction(blockchainIdentity, TOP_UP, any(), any())
            walletApplication.saveWallet()
            topUpRepository.topUpIdentity(topUpTx, any())
            platformRepo.preorderName(blockchainIdentity, any(), "dashuser2")
        }
        coVerify(exactly = 1) { topUpRepository.createTopupTransaction(any(), any(), any(), any()) }
        assertEquals(IdentityCreationState.DONE, stored().creationState)
        assertNull(config.loadReplacement())
        verifyTheSpentInviteIsNotUsed()
    }

    @Test
    fun walletCannotPay_stopsAndReopensTheForm() = runBlocking {
        givenRejectedName()
        coEvery { topUpRepository.createTopupTransaction(any(), any(), any(), any()) } throws
            InsufficientMoneyException(TOP_UP)

        service.runReplacement(approve(TOP_UP))

        assertEquals(NameReplacementFailure.FINAL, config.loadReplacement()!!.failure)
        assertRejectedNameRestored()
        coVerify(exactly = 0) { topUpRepository.topUpIdentity(any(), any()) }
    }

    @Test
    fun crashAfterDispatching_withTheTopUpInTheWallet_adoptsItInsteadOfBuildingAnother() = runBlocking {
        givenRejectedName()
        every { topUps.findByKey(any(), KEY) } returns topUpTx

        service.runReplacement(dispatching())

        coVerify(exactly = 0) { topUpRepository.createTopupTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 1) { topUpRepository.topUpIdentity(topUpTx, any()) }
        verify(exactly = 1) { platformRepo.registerName(blockchainIdentity, any(), "dashuser2") }
        assertNull(config.loadReplacement())
    }

    @Test
    fun crashAfterDispatching_withoutATopUp_stopsWithoutBuildingOne() = runBlocking {
        givenRejectedName()
        every { topUps.findByKey(any(), KEY) } returns null

        service.runReplacement(dispatching())

        assertEquals(NameReplacementFailure.FINAL, config.loadReplacement()!!.failure)
        assertRejectedNameRestored()
        verifyNoTopUp()
        verify(exactly = 0) { platformRepo.preorderName(any(), any(), any()) }
    }

    @Test
    fun crashAfterDispatched_sendsTheSavedTransactionOncePaymentReservationsAreBack() = runBlocking {
        givenRejectedName()

        service.runReplacement(dispatched())

        coVerifyOrder {
            pendingPaymentVerifier.awaitRestored()
            topUps.resolve(any(), TX)
        }
        verify(exactly = 1) { topUps.resolve(any(), TX) }
        coVerify(exactly = 0) { topUpRepository.createTopupTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 1) { topUpRepository.topUpIdentity(topUpTx, any()) }
        verify(exactly = 1) { platformRepo.registerName(blockchainIdentity, any(), "dashuser2") }
    }

    @Test
    fun doubleSpentTopUp_stopsAndReopensTheForm() = runBlocking {
        givenRejectedName()
        every { topUps.resolve(any(), any()) } returns NameReplacementTopUps.Resolution.Dead

        service.runReplacement(dispatched())

        assertEquals(NameReplacementFailure.FINAL, config.loadReplacement()!!.failure)
        assertRejectedNameRestored()
        coVerify(exactly = 0) { topUpRepository.topUpIdentity(any(), any()) }
    }

    @Test
    fun topUpWithUnresolvedInputs_waitsForARetry() = runBlocking {
        givenRejectedName()
        every { topUps.resolve(any(), any()) } returns NameReplacementTopUps.Resolution.Pending("unknown input")

        service.runReplacement(dispatched())

        assertEquals(NameReplacementFailure.RETRYABLE, config.loadReplacement()!!.failure)
        assertEquals(NameReplacementState.DISPATCHED, config.loadReplacement()!!.state)
        coVerify(exactly = 0) { topUpRepository.topUpIdentity(any(), any()) }
    }

    @Test
    fun failedTopUp_keepsTheRejectionAndRetryReusesTheSameTransaction() {
        givenRejectedName()
        var calls = 0
        coEvery { topUpRepository.topUpIdentity(any(), any()) } answers {
            if (calls++ == 0) throw IllegalStateException("top-up failed")
        }
        runBlocking { service.runReplacement(approve(TOP_UP)) }

        val failed = runBlocking { config.loadReplacement()!! }
        assertEquals(NameReplacementFailure.RETRYABLE, failed.failure)
        assertEquals(REJECTED, failed.rejectedError)
        assertEquals("IllegalStateException: top-up failed", stored().creationStateErrorMessage)
        assertEquals("dashuser", stored().username)

        service.onStartCommand(CreateIdentityService.createIntentForRetryFromInvite(service), 0, 2)

        verify(timeout = 10_000, exactly = 1) { platformRepo.registerName(blockchainIdentity, any(), "dashuser2") }
        awaitIdle()
        coVerify(exactly = 1) { topUpRepository.createTopupTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 2) { topUpRepository.topUpIdentity(topUpTx, any()) }
        verify(exactly = 0) { platformRepo.registerName(any(), any(), "dashuser") }
    }

    @Test
    fun retryAfterAMissingPreorder_registersThePreorderAgain() {
        givenRejectedName()
        var registrations = 0
        every { platformRepo.registerName(any(), any(), "dashuser2") } answers {
            if (registrations++ == 0) {
                throw IllegalStateException("cannot find preorder document, though it should be somewhere")
            }
        }
        runBlocking { service.runReplacement(approve(Coin.ZERO)) }
        val failed = runBlocking { config.loadReplacement()!! }
        assertEquals(NameReplacementState.NAME_SAVED, failed.state)
        assertEquals(NameReplacementFailure.RETRYABLE, failed.failure)

        service.onStartCommand(CreateIdentityService.createIntentForRetryFromInvite(service), 0, 2)

        verify(timeout = 10_000, exactly = 2) { platformRepo.registerName(blockchainIdentity, any(), "dashuser2") }
        awaitIdle()
        verify(exactly = 2) { platformRepo.preorderName(blockchainIdentity, any(), "dashuser2") }
        assertNull(runBlocking { config.loadReplacement() })
        assertEquals(IdentityCreationState.DONE, stored().creationState)
    }

    @Test
    fun doubleTap_runsOneAttemptOnce() {
        givenRejectedName()
        runBlocking { approve(TOP_UP) }

        service.onStartCommand(CreateIdentityService.createIntentForNameReplacement(service), 0, 1)
        service.onStartCommand(CreateIdentityService.createIntentForNameReplacement(service), 0, 2)

        verify(timeout = 10_000, exactly = 1) { platformRepo.registerName(blockchainIdentity, any(), "dashuser2") }
        awaitIdle()
        assertNull(runBlocking { config.loadReplacement() })
        coVerify(exactly = 1) { topUpRepository.createTopupTransaction(any(), any(), any(), any()) }
        verify(exactly = 1) { platformRepo.registerName(any(), any(), any()) }
    }

    @Test
    fun retryWithAFailedAttempt_resumesTheAttemptNotTheRejectedName() {
        givenRejectedName()
        runBlocking {
            val attempt = dispatched()
            config.failReplacement(attempt.id, NameReplacementFailure.RETRYABLE, "offline")
        }

        service.onStartCommand(CreateIdentityService.createIntentForRetryFromInvite(service), 0, 1)

        verify(timeout = 10_000, exactly = 1) { platformRepo.registerName(blockchainIdentity, any(), "dashuser2") }
        awaitIdle()
        verify(exactly = 0) { platformRepo.preorderName(any(), any(), "dashuser") }
        coVerify(exactly = 0) { topUpRepository.createTopupTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun restartWithAFailedAttempt_waitsForTheUser() {
        givenRejectedName()
        runBlocking {
            val attempt = dispatched()
            config.failReplacement(attempt.id, NameReplacementFailure.RETRYABLE, "offline")
        }

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(NameReplacementFailure.RETRYABLE, runBlocking { config.loadReplacement()!!.failure })
        verify(exactly = 0) { platformRepo.preorderName(any(), any(), any()) }
        coVerify(exactly = 0) { topUpRepository.topUpIdentity(any(), any()) }
    }

    @Test
    fun restartWithAnUnfinishedAttempt_resumesTheAttempt() {
        givenRejectedName()
        runBlocking { dispatched() }

        service.onStartCommand(null, 0, 1)

        verify(timeout = 10_000, exactly = 1) { platformRepo.registerName(blockchainIdentity, any(), "dashuser2") }
        awaitIdle()
        verify(exactly = 0) { platformRepo.preorderName(any(), any(), "dashuser") }
        coVerify(exactly = 0) { topUpRepository.createTopupTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun stoppedAttempt_neitherRestartNorRetryRunsAnything() {
        givenRejectedName()
        runBlocking {
            val attempt = approve(TOP_UP)
            config.failReplacement(attempt.id, NameReplacementFailure.FINAL, "stop")
        }

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        service.onStartCommand(CreateIdentityService.createIntentForRetryFromInvite(service), 0, 2)

        awaitIdle()
        verify(exactly = 0) { platformRepo.preorderName(any(), any(), any()) }
        verifyNoTopUp()
    }

    @Test
    fun identityMovedOnWhileTheTopUpWaited_dropsTheAttemptWithoutTouchingTheIdentity() = runBlocking {
        givenRejectedName()
        val attempt = dispatched()
        config.updateCreationState(IdentityCreationState.DONE, null)

        service.runReplacement(attempt)

        assertNull(config.loadReplacement())
        assertEquals(IdentityCreationState.DONE, stored().creationState)
        assertEquals("dashuser", stored().username)
        verify(exactly = 0) { platformRepo.registerName(any(), any(), any()) }
    }

    @Test
    fun savedNameRejected_stopsWithTheNewRejectionForTheForm() = runBlocking {
        givenRejectedName()
        every { platformRepo.registerName(any(), any(), "dashuser2") } throws IllegalStateException(REJECTED)

        service.runReplacement(approve(Coin.ZERO))

        assertEquals(NameReplacementFailure.FINAL, config.loadReplacement()!!.failure)
        assertEquals(NameReplacementState.NAME_SAVED, config.loadReplacement()!!.state)
        assertEquals(IdentityCreationState.USERNAME_REGISTERING, stored().creationState)
        assertTrue(stored().creationStateErrorMessage!!.contains("DuplicateUniqueIndexError"))
        assertEquals("dashuser2", stored().username)
    }

    @Test
    fun savedNameFailsOtherwise_canStillBeReplaced() = runBlocking {
        givenRejectedName()
        every { platformRepo.registerName(any(), any(), "dashuser2") } throws IllegalStateException("BalanceIsNotEnough")

        service.runReplacement(approve(Coin.ZERO))

        assertEquals(NameReplacementFailure.RETRYABLE, config.loadReplacement()!!.failure)
        assertTrue(config.approveReplacement(NameReplacementAttempt("next", IDENTITY_ID, "dashuser4", null, Coin.ZERO)))
    }

    @Test
    fun identityAlreadyPastTheName_dropsTheAttemptWithoutPaying() = runBlocking {
        givenRejectedName()
        val attempt = approve(TOP_UP)
        config.updateCreationState(IdentityCreationState.DONE, null)

        service.runReplacement(attempt)

        assertNull(config.loadReplacement())
        verifyNoTopUp()
        verify(exactly = 0) { platformRepo.preorderName(any(), any(), any()) }
    }

    @Test
    fun inviterContactRequestNotQueued_stillCompletesTheName() = runBlocking {
        givenIdentity(inviteIdentity(IdentityCreationState.IDENTITY_REGISTERED, "dashuser2", rejected = false))
        coEvery { anyConstructed<InviterContactRequestOperation>().enqueue(any(), any()) } throws IllegalStateException("db")

        service.createIdentityFromInvitation(null, null, null)

        assertEquals(IdentityCreationState.DONE, stored().creationState)
        assertNull(stored().creationStateErrorMessage)
    }

    @Test
    fun interruptedReplacement_resumesWithoutTheSpentInvite() = runBlocking {
        givenIdentity(inviteIdentity(IdentityCreationState.PREORDER_REGISTERING, "dashuser2", rejected = false))

        service.createIdentityFromInvitation(null, null, null)

        verify(exactly = 1) { platformRepo.preorderName(blockchainIdentity, any(), "dashuser2") }
        verify(exactly = 1) { platformRepo.registerName(blockchainIdentity, any(), "dashuser2") }
        assertEquals(IdentityCreationState.DONE, stored().creationState)
        verifyTheSpentInviteIsNotUsed()
        verifyNoTopUp()
    }

    @Test
    fun interruptedRegistration_resumesAfterTheDomainWithoutRegisteringAgain() = runBlocking {
        givenIdentity(inviteIdentity(IdentityCreationState.USERNAME_REGISTERED, "dashuser2", rejected = false))

        service.createIdentityFromInvitation(null, null, null)

        verify(exactly = 0) { platformRepo.preorderName(any(), any(), any()) }
        verify(exactly = 0) { platformRepo.registerName(any(), any(), any()) }
        verifyTheSpentInviteIsNotUsed()
        verifyNoTopUp()
    }

    @Test
    fun restartedService_resumesAnInviteIdentityWithTheInviteFlow() {
        givenIdentity(inviteIdentity(IdentityCreationState.PREORDER_REGISTERING, "dashuser2", rejected = false))

        service.onStartCommand(null, 0, 1)

        verify(timeout = 10_000, exactly = 1) { platformRepo.registerName(blockchainIdentity, any(), "dashuser2") }
        verifyTheSpentInviteIsNotUsed()
        verifyNoTopUp()
    }

    @Test
    fun restartedService_leavesAFinishedInviteIdentityAlone() {
        givenIdentity(inviteIdentity(IdentityCreationState.VOTING, "Dashuser", rejected = false))

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))

        assertTrue(shadowOf(service).isStoppedBySelf)
        coVerify(exactly = 0) { identityRepository.loadBlockchainIdentityData() }
    }

    private fun verifyTheSpentInviteIsNotUsed() {
        verify(exactly = 0) { topUpRepository.obtainAssetLockTransaction(any(), any()) }
        coVerify(exactly = 0) { topUpRepository.createAssetLockTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { platformRepo.registerIdentity(any(), any()) }
    }

    private fun verifyNoTopUp() {
        coVerify(exactly = 0) { topUpRepository.createTopupTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { topUpRepository.topUpIdentity(any(), any()) }
    }

    private fun assertRejectedNameRestored() {
        assertEquals(IdentityCreationState.USERNAME_REGISTERING, stored().creationState)
        assertEquals(REJECTED, stored().creationStateErrorMessage)
        assertEquals("dashuser", stored().username)
    }

    private fun awaitIdle() {
        val workInProgress = CreateIdentityService::class.java.getDeclaredField("workInProgress")
            .apply { isAccessible = true }
        val deadline = System.currentTimeMillis() + 10_000
        while ((workInProgress.getBoolean(service) || !shadowOf(service).isStoppedBySelf) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
        }
        assertTrue("the service is still running", !workInProgress.getBoolean(service))
    }

    private fun errorNotification() = shadowOf(
        ApplicationProvider.getApplicationContext<Application>().getSystemService(NotificationManager::class.java)
    ).getNotification(Constants.NOTIFICATION_ID_DASHPAY_CREATE_IDENTITY_ERROR)

    private fun stored(): BlockchainIdentityData = runBlocking { config.load()!! }

    private suspend fun approve(topUp: Coin): NameReplacementAttempt {
        assertTrue(config.approveReplacement(NameReplacementAttempt("attempt", IDENTITY_ID, "dashuser2", null, topUp)))
        return config.loadReplacement()!!
    }

    private suspend fun dispatching(): NameReplacementAttempt {
        approve(TOP_UP)
        return config.markReplacementDispatching("attempt", KEY)!!
    }

    private suspend fun dispatched(): NameReplacementAttempt {
        dispatching()
        return config.markReplacementDispatched("attempt", TX)!!
    }

    private fun givenRejectedName() = givenIdentity(rejectedIdentity())

    private fun givenIdentity(identity: BlockchainIdentityData) = runBlocking { config.insert(identity) }

    private fun rejectedIdentity() = inviteIdentity(IdentityCreationState.USERNAME_REGISTERING, "dashuser", rejected = true)

    private fun inviteIdentity(state: IdentityCreationState, username: String, rejected: Boolean) =
        BlockchainIdentityData(
            state,
            if (rejected) REJECTED else null,
            username,
            null,
            IDENTITY_ID,
            false,
            usingInvite = true,
            invite = invite
        ).apply { registrationStatus = IdentityStatus.REGISTERED }
}
