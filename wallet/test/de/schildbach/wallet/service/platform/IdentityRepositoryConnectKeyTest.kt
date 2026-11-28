package de.schildbach.wallet.service.platform

import android.app.Application
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.testPlatformRepo
import de.schildbach.wallet.ui.more.connections.AppConnectKeyStateTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.IDeterministicKey
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.WalletEx
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.dash.wallet.common.WalletDataProvider
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.identity.IdentityPublicKey
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import org.dashj.platform.sdk.platform.Platform
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.EnumSet

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class IdentityRepositoryConnectKeyTest {
    private val provider = mockk<de.schildbach.wallet.data.WalletData>(relaxed = true)
    private val platform = mockk<PlatformRepo>(relaxed = true)
    private val identity = mockk<BlockchainIdentity>(relaxed = true)
    private val identityStorage = mockk<de.schildbach.wallet.database.entity.BlockchainIdentityConfig>(relaxed = true)
    private val config = mockk<de.schildbach.wallet.ui.dashpay.utils.DashPayConfig>(relaxed = true)
    private val repository = IdentityRepositoryImpl(mockk(relaxed = true), mockk(relaxed = true),
        identityStorage, provider, platform, config, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
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

    @Test
    @Config(shadows = [de.schildbach.wallet.ui.more.connections.AppConnectKeyStateTest.ShadowSdkEnums::class])
    fun realInitializerReplacesPartialIdentityWithoutReadingItsUninitializedId() = runBlocking {
        val reloadable = spyk(repository)
        val wallet = mockk<org.bitcoinj.wallet.Wallet>(relaxed = true)
        every { provider.wallet } returns wallet
        every { reloadable.authenticationGroupExtension } returns mockk(relaxed = true)
        every { platform.platform.platform.params } returns org.bitcoinj.params.TestNet3Params.get()
        val data = mockk<de.schildbach.wallet.database.entity.BlockchainIdentityData>(relaxed = true)
        every { data.creationState } returns de.schildbach.wallet.database.entity.IdentityCreationState.IDENTITY_REGISTERING
        every { data.userId } returns id.toString()
        every { data.username } returns null
        every { data.usernameSecondary } returns null
        every { data.creditBalance } returns null
        every { data.registrationStatus } returns org.dashj.platform.dashpay.IdentityStatus.REGISTERED
        coEvery { identityStorage.load() } returns data
        reloadable.init()
        val partial = reloadable.blockchainIdentity!!
        org.junit.Assert.assertTrue(reloadable.initializationResult.value!!.isSuccess)
        org.junit.Assert.assertFalse(reloadable.hasInitializedIdentityScope())
        assertThrows(UninitializedPropertyAccessException::class.java) { partial.uniqueIdString }
        every { data.creationState } returns de.schildbach.wallet.database.entity.IdentityCreationState.IDENTITY_REGISTERED
        reloadable.init()
        org.junit.Assert.assertNotSame(partial, reloadable.blockchainIdentity)
        org.junit.Assert.assertTrue(reloadable.initializationResult.value!!.isSuccess)
        org.junit.Assert.assertTrue(reloadable.hasInitializedIdentityScope())
        org.junit.Assert.assertEquals(id, reloadable.blockchainIdentity!!.uniqueIdentifier)
        coVerify(exactly = 2) { identityStorage.load() }
        coVerify(exactly = 0) { reloadable.ensureCriticalAuthenticationKey(any(), any(), any()) }
        coVerify(exactly = 0) { reloadable.upgradeIdentity(any()) }
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
        verify(exactly = 0) { platform.requireIdentityChainKeys(any(), any()) }
    }

    /**
     * A real dashj [BlockchainIdentity] on a real identity key chain, the way an identity the
     * Kotlin SDK created reaches dashj: its keys are registered on Platform but the wallet's
     * identity chain has issued none of them beyond what a new chain holds (indexes 0 and 1). The
     * CRITICAL authentication key sits at keyId/index 4, so dashj's own key check
     * (`hasCriticalAuthenticationKey`, which resolves the key on the chain) cannot see it until
     * the real [PlatformRepo.requireIdentityChainKeys] issues it.
     */
    private class RealIdentityFixture(
        val repository: IdentityRepositoryImpl,
        val platformRepo: PlatformRepo,
        val identity: BlockchainIdentity,
        val auth: AuthenticationGroupExtension,
        val criticalKey: IdentityPublicKey
    )

    private val params = TestNet3Params.get()

    private fun identityChainAuth() = AuthenticationGroupExtension(params).apply {
        addKeyChains(
            params,
            DeterministicSeed(ByteArray(16) { 1 }, "", 0L),
            EnumSet.of(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY)
        )
    }

    private fun chainPublicKey(auth: AuthenticationGroupExtension, index: Int): ByteArray =
        auth.identityKeyChain.watchingKey.deriveChildKey(ChildNumber(index, true)).pubKey

    /** @param backfillAuth the extension the backfill (PlatformRepo) sees; the identity always uses the real one */
    private fun realIdentityFixture(
        backfillAuth: (AuthenticationGroupExtension) -> AuthenticationGroupExtension = { it }
    ): RealIdentityFixture {
        val auth = identityChainAuth()
        val wallet = mockk<WalletEx>(relaxed = true)
        every { wallet.isEncrypted } returns false
        val realPlatformRepo = testPlatformRepo()
        every { realPlatformRepo.walletApplication.wallet } returns wallet
        every { realPlatformRepo.walletApplication.authenticationGroupExtension } returns backfillAuth(auth)
        every { realPlatformRepo.platform.client.getIdentityBalance(any()) } returns 1_000_000_000L
        val sdkPlatform = mockk<Platform>(relaxed = true)
        every { sdkPlatform.params } returns params
        fun key(id: Int, purpose: Purpose, level: SecurityLevel) =
            IdentityPublicKey(id, KeyType.ECDSA_SECP256K1, purpose, level, null, chainPublicKey(auth, id), false)
        val criticalKey = key(4, Purpose.AUTHENTICATION, SecurityLevel.CRITICAL)
        val blockchainIdentity = BlockchainIdentity(sdkPlatform, 0, wallet, auth).apply {
            uniqueId = Sha256Hash.wrap(ByteArray(32) { 7 })
            identity = Identity(
                uniqueIdentifier,
                listOf(
                    key(0, Purpose.AUTHENTICATION, SecurityLevel.MASTER),
                    key(1, Purpose.AUTHENTICATION, SecurityLevel.HIGH),
                    key(2, Purpose.ENCRYPTION, SecurityLevel.MEDIUM),
                    key(3, Purpose.TRANSFER, SecurityLevel.CRITICAL),
                    criticalKey
                ),
                1_000_000L, 1, 1
            )
        }
        // a spy only to verify the paid registration is never called; every check runs the real code
        val spiedIdentity = spyk(blockchainIdentity)
        val walletProvider = mockk<de.schildbach.wallet.data.WalletData>(relaxed = true)
        every { walletProvider.wallet } returns wallet
        val realRepository = IdentityRepositoryImpl(mockk(relaxed = true), mockk(relaxed = true),
            identityStorage, walletProvider, realPlatformRepo, config, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true))
        val field = IdentityRepositoryImpl::class.java.getDeclaredField("_blockchainIdentityFlow").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(realRepository) as MutableStateFlow<BlockchainIdentity?>).value = spiedIdentity
        return RealIdentityFixture(realRepository, realPlatformRepo, spiedIdentity, auth, criticalKey)
    }

    @Test
    @Config(shadows = [AppConnectKeyStateTest.ShadowSdkEnums::class])
    fun sdkIdentityCriticalKeyIsFoundAfterBackfillAndNoSecondKeyIsRegistered() = runBlocking {
        val fixture = realIdentityFixture()
        val chain = fixture.auth.identityKeyChain
        // before: the registered CRITICAL key is unissued, so dashj's real check reports it missing
        org.junit.Assert.assertNull(chain.findKeyFromPubKey(fixture.criticalKey.data))
        org.junit.Assert.assertFalse(fixture.identity.hasCriticalAuthenticationKey())
        org.junit.Assert.assertTrue(fixture.identity.hasTransferKey() && fixture.identity.hasEncryptionKey())

        fixture.repository.ensureCriticalAuthenticationKey(fixture.identity.uniqueIdentifier, null) {}

        // after: the real backfill issued it and the same real check finds it
        org.junit.Assert.assertNotNull(chain.findKeyFromPubKey(fixture.criticalKey.data))
        org.junit.Assert.assertTrue(fixture.identity.hasCriticalAuthenticationKey())
        // so no second CRITICAL key is registered: no paid identity update, not even a balance read
        verify(exactly = 0) { fixture.identity.addMissingKeys(any(), any()) }
        val client = fixture.platformRepo.platform.client
        verify(exactly = 0) { client.getIdentityBalance(any()) }
        coVerify { config.set(de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.UPGRADE_IDENTITY_REQUIRED, false) }
    }

    @Test
    @Config(shadows = [AppConnectKeyStateTest.ShadowSdkEnums::class])
    fun cancellationInsideTheRealBackfillPropagatesUnwrappedAndRegistersNothing() {
        val cancellation = CancellationException("cancelled while deriving")
        val fixture = realIdentityFixture { realAuth ->
            // the real backfill, with the identity chain's parent key cancelled mid-derivation
            val realParent = realAuth.identityKeyChain.watchingKey
            val parent = mockk<IDeterministicKey>()
            every { parent.deriveChildKey(any<ChildNumber>()) } answers {
                val child = firstArg<ChildNumber>()
                if (child.num() == 4) throw cancellation else realParent.deriveChildKey(child)
            }
            val chain = mockk<AuthenticationKeyChain>(relaxed = true)
            every { chain.watchingKey } returns parent
            every { chain.findKeyFromPubKey(any()) } returns null
            mockk<AuthenticationGroupExtension>(relaxed = true).also {
                every { it.getKeyChain(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY) } returns chain
            }
        }

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { fixture.repository.ensureCriticalAuthenticationKey(fixture.identity.uniqueIdentifier, null) {} }
        }

        org.junit.Assert.assertEquals(cancellation.message, thrown.message)
        org.junit.Assert.assertTrue(
            generateSequence<Throwable>(thrown) { it.cause }.none { it is IdentityKeyChainBackfillException }
        )
        val backfillAuth = checkNotNull(fixture.platformRepo.authenticationGroupExtension)
        verify(exactly = 0) { backfillAuth.addNewKey(any(), any()) }
        verify(exactly = 0) { fixture.identity.addMissingKeys(any(), any()) }
        val client = fixture.platformRepo.platform.client
        verify(exactly = 0) { client.getIdentityBalance(any()) }
    }

    @Test fun backfillRunsOnlyWhenAKeyLooksMissingAndBeforeTheMasterSignature() {
        fundedWallet()
        val passedThrough = mutableListOf<String>()
        every { platform.requireIdentityChainKeys(any(), any()) } answers { passedThrough += "backfill"; 0 }
        every { identity.hasCriticalAuthenticationKey() } answers { passedThrough += "critical check"; false }
        every { identity.addMissingKeys(any(), true) } answers { passedThrough += "register" }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.ensureCriticalAuthenticationKey(id, null) {} }
        }
        org.junit.Assert.assertEquals(
            listOf("critical check", "backfill", "critical check", "register"),
            passedThrough.take(4)
        )
    }

    @Test fun noBackfillWhenNoKeyLooksMissing() = runBlocking {
        fundedWallet()
        every { identity.hasCriticalAuthenticationKey() } returns true
        repository.ensureCriticalAuthenticationKey(id, null) {}
        verify(exactly = 0) { platform.requireIdentityChainKeys(any(), any()) }
        verify(exactly = 0) { identity.addMissingKeys(any(), any()) }
        coVerify { config.set(de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.UPGRADE_IDENTITY_REQUIRED, false) }
    }

    @Test fun failedBackfillOnPurchaseSetupRegistersNoKeyAndReportsTheFailure() {
        fundedWallet()
        // the SDK identity's existing CRITICAL key stays invisible to dashj, so it looks missing
        every { platform.requireIdentityChainKeys(any(), any()) } throws
            IdentityKeyChainBackfillException("no identity key chain")
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.ensureCriticalAuthenticationKey(id, null) {} }
        }
        org.junit.Assert.assertTrue(error.message!!.contains("no purchase signing key was registered"))
        org.junit.Assert.assertTrue(error.cause is IdentityKeyChainBackfillException)
        verify(exactly = 0) { identity.addMissingKeys(any(), any()) }
        val client = platform.platform.client
        verify(exactly = 0) { client.getIdentityBalance(any()) }
        coVerify { config.set(de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.UPGRADE_IDENTITY_REQUIRED, true) }
    }

    @Test fun failedBackfillOnSyncUpgradeRegistersNothingAndReportsNoUpgrade() = runBlocking {
        fundedWallet()
        // transfer and encryption keys look missing too, but re-registering them costs fees as well
        every { identity.hasTransferKey() } returns false
        every { identity.hasEncryptionKey() } returns false
        every { platform.requireIdentityChainKeys(any(), any()) } throws
            IdentityKeyChainBackfillException("wallet is encrypted but no key was provided")
        org.junit.Assert.assertFalse(repository.upgradeIdentity(null))
        verify(exactly = 0) { identity.addMissingKeys(any(), any()) }
        val client = platform.platform.client
        verify(exactly = 0) { client.getIdentityBalance(any()) }
        coVerify { config.set(de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.UPGRADE_IDENTITY_REQUIRED, true) }
        coVerify(exactly = 0) {
            config.set(de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.UPGRADE_IDENTITY_REQUIRED, false)
        }
    }

    @Test fun cancellationDuringBackfillPropagates() {
        fundedWallet()
        every { platform.requireIdentityChainKeys(any(), any()) } throws CancellationException("Locked")
        assertThrows(CancellationException::class.java) {
            runBlocking { repository.ensureCriticalAuthenticationKey(id, null) {} }
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
