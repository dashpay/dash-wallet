package de.schildbach.wallet.service.platform

import android.app.Application
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.dash.wallet.common.WalletDataProvider
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class IdentityRepositoryConnectKeyTest {
    private val provider = mockk<WalletDataProvider>(relaxed = true)
    private val platform = mockk<PlatformRepo>(relaxed = true)
    private val identity = mockk<BlockchainIdentity>(relaxed = true)
    private val identityStorage = mockk<de.schildbach.wallet.database.entity.BlockchainIdentityConfig>(relaxed = true)
    private val config = mockk<de.schildbach.wallet.ui.dashpay.utils.DashPayConfig>(relaxed = true)
    private val repository = IdentityRepositoryImpl(mockk(relaxed = true), mockk(relaxed = true),
        identityStorage, provider, platform, config, mockk(relaxed = true))
    private val id = Identifier.from(ByteArray(32) { 7 })

    init {
        every { identity.uniqueIdentifier } returns id
        val field = IdentityRepositoryImpl::class.java.getDeclaredField("_blockchainIdentityFlow").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(repository) as MutableStateFlow<BlockchainIdentity?>).value = identity
    }

    @Test fun partialIdentityIsNotAnInitializedScopeButHealthyIdentityIs() {
        org.junit.Assert.assertTrue(repository.hasInitializedIdentityScope())
        every { identity.uniqueIdentifier } throws UninitializedPropertyAccessException("uniqueId")
        org.junit.Assert.assertFalse(repository.hasInitializedIdentityScope())
        every { identity.uniqueIdentifier } returns id
        org.junit.Assert.assertTrue(repository.hasInitializedIdentityScope())
    }

    @Test fun scopeReadDoesNotHideUnrelatedIdentityFailures() {
        every { identity.uniqueIdentifier } throws IllegalStateException("Identity storage unavailable")
        assertThrows(IllegalStateException::class.java) { repository.hasInitializedIdentityScope() }
    }

    @Test fun completedLoadWithoutIdentityIsDifferentFromStillLoading() = runBlocking {
        org.junit.Assert.assertNull(repository.initializationResult.value)
        coEvery { identityStorage.load() } returns null
        repository.init()
        org.junit.Assert.assertTrue(repository.initializationResult.value!!.isSuccess)
        org.junit.Assert.assertNull(repository.blockchainIdentity)
        org.junit.Assert.assertFalse(repository.hasInitializedIdentityScope())
    }

    @Test fun failedIdentityLoadIsPublishedToWaitingRequests() = runBlocking {
        val failure = IllegalStateException("Storage unavailable")
        coEvery { identityStorage.load() } throws failure
        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking { repository.init() } }
        org.junit.Assert.assertSame(failure, thrown)
        org.junit.Assert.assertSame(failure, repository.initializationResult.value!!.exceptionOrNull())
    }

    @Test fun reloadingAfterAnEmptyReadRereadsIdentityStorageWithoutRegisteringKeys() = runBlocking {
        val reloadable = spyk(repository)
        val wallet = mockk<org.bitcoinj.wallet.Wallet>(relaxed = true)
        val data = mockk<de.schildbach.wallet.database.entity.BlockchainIdentityData>(relaxed = true)
        every { provider.wallet } returns wallet
        coEvery { identityStorage.load() } returnsMany listOf(null, data)
        every { reloadable.initBlockchainIdentity(data, wallet) } returns identity
        reloadable.init()
        org.junit.Assert.assertTrue(reloadable.initializationResult.value!!.isSuccess)
        org.junit.Assert.assertNull(reloadable.blockchainIdentity)
        reloadable.init()
        org.junit.Assert.assertSame(identity, reloadable.blockchainIdentity)
        coVerify(exactly = 2) { identityStorage.load() }
        verify(exactly = 0) { identity.addMissingKeys(any(), any()) }
    }

    @Test fun cancelledIdentityLoadPublishesRetryableFailureAndStillPropagatesCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        coEvery { identityStorage.load() } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        val loading = async(start = CoroutineStart.UNDISPATCHED) { repository.init() }
        entered.await()
        org.junit.Assert.assertNull(repository.initializationResult.value)
        loading.cancel()
        assertThrows(CancellationException::class.java) { runBlocking { loading.await() } }
        val failure = repository.initializationResult.value!!.exceptionOrNull()!!
        org.junit.Assert.assertTrue(failure is IllegalStateException)
        org.junit.Assert.assertTrue(failure.cause is CancellationException)
        coEvery { identityStorage.load() } returns null
        repository.init()
        org.junit.Assert.assertTrue(repository.initializationResult.value!!.isSuccess)
    }

    @Test fun queuedInitializerWaitsForCancelledOwnerAndPublishesItsOwnResultLast() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { identityStorage.load() } coAnswers {
            calls++
            if (calls == 1) {
                entered.complete(Unit)
                awaitCancellation()
            }
            null
        }
        val first = launch(start = CoroutineStart.UNDISPATCHED) { repository.init() }
        entered.await()
        val second = launch(start = CoroutineStart.UNDISPATCHED) { repository.init() }
        org.junit.Assert.assertEquals(1, calls)
        first.cancelAndJoin()
        second.join()
        org.junit.Assert.assertEquals(2, calls)
        org.junit.Assert.assertTrue(repository.initializationResult.value!!.isSuccess)
    }

    @Test fun cancellingQueuedInitializerDoesNotSettleOrCancelTheActiveLoader() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { identityStorage.load() } coAnswers {
            entered.complete(Unit)
            release.await()
            null
        }
        val first = launch(start = CoroutineStart.UNDISPATCHED) { repository.init() }
        entered.await()
        val queued = launch(start = CoroutineStart.UNDISPATCHED) { repository.init() }
        queued.cancelAndJoin()
        org.junit.Assert.assertNull(repository.initializationResult.value)
        coVerify(exactly = 1) { identityStorage.load() }
        release.complete(Unit)
        first.join()
        org.junit.Assert.assertTrue(repository.initializationResult.value!!.isSuccess)
    }

    @Test fun purchaseMigrationRejectsAnIdentitySwitchBeforeAnyMutation() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.ensureCriticalAuthenticationKey(Identifier.from(ByteArray(32) { 8 }), null) {} }
        }
        verify(exactly = 0) { identity.addMissingKeys(any(), any()) }
    }

    @Test fun purchaseMigrationRejectsADifferentWalletObject() {
        every { identity.wallet } returns mockk(relaxed = true)
        every { provider.wallet } returns mockk(relaxed = true)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.ensureCriticalAuthenticationKey(id, null) {} }
        }
        verify(exactly = 0) { identity.addMissingKeys(any(), any()) }
    }

    @Test fun purchaseMigrationReportsFailureInsteadOfPretendingAKeyWasSubmitted() {
        every { provider.wallet } returns null
        every { identity.hasCriticalAuthenticationKey() } returns false
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.ensureCriticalAuthenticationKey(id, null) {} }
        }
        org.junit.Assert.assertTrue(error.message!!.contains("Unable to register"))
        verify(exactly = 0) { identity.addMissingKeys(any(), any()) }
    }

    @Test fun backgroundSyncDoesNotMigrateACriticalKeyOrSpendCredits() = runBlocking {
        val wallet = mockk<org.bitcoinj.wallet.Wallet>(relaxed = true)
        every { provider.wallet } returns wallet
        every { identity.wallet } returns wallet
        every { identity.hasTransferKey() } returns true
        every { identity.hasEncryptionKey() } returns true
        every { identity.hasCriticalAuthenticationKey() } returns false
        repeat(2) { org.junit.Assert.assertFalse(repository.upgradeIdentity(null)) }
        coEvery { config.get(de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.UPGRADE_IDENTITY_REQUIRED) } returns true
        org.junit.Assert.assertFalse(repository.upgradeIdentity(null))
        verify(exactly = 0) { identity.addMissingKeys(any(), any()) }
        org.junit.Assert.assertTrue(!identity.hasCriticalAuthenticationKey())
    }

    @Test fun purchaseSetupChecksAuthorizationBeforeAccessingKeys() {
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { repository.ensureCriticalAuthenticationKey(id, null) { throw kotlinx.coroutines.CancellationException("Locked") } }
        }
        verify(exactly = 0) { identity.addMissingKeys(any(), any()) }
    }

    private fun fundedWallet() {
        val wallet = mockk<org.bitcoinj.wallet.Wallet>(relaxed = true)
        every { provider.wallet } returns wallet
        every { identity.wallet } returns wallet
        every { wallet.getKeyChainExtension(org.bitcoinj.wallet.authentication.AuthenticationGroupExtension.EXTENSION_ID) } returns
            mockk<org.bitcoinj.wallet.authentication.AuthenticationGroupExtension>(relaxed = true)
        every { identity.hasTransferKey() } returns true
        every { identity.hasEncryptionKey() } returns true
        every { identity.hasCriticalAuthenticationKey() } returns false
        every { platform.platform.client.getIdentityBalance(any()) } returns 1_000_000_000L
        coEvery { identityStorage.load() } returns null
    }

    @Test fun setupDoesNotAcceptAnAttemptWithoutAUsableKey() {
        fundedWallet()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.ensureCriticalAuthenticationKey(id, null) {} }
        }
        verify(exactly = 1) { identity.addMissingKeys(any(), true) }
    }

    @Test fun setupRechecksAuthorizationAfterBalanceReadBeforeSubmitting() {
        fundedWallet()
        var authorized = true
        every { platform.platform.client.getIdentityBalance(any()) } answers { authorized = false; 1_000_000_000L }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { repository.ensureCriticalAuthenticationKey(id, null) {
                if (!authorized) throw kotlinx.coroutines.CancellationException("Locked")
            } }
        }
        verify(exactly = 0) { identity.addMissingKeys(any(), any()) }
    }
}
