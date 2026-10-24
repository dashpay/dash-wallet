package de.schildbach.wallet.service.platform

import android.app.Application
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.coEvery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
    private val repository = IdentityRepositoryImpl(mockk(relaxed = true), mockk(relaxed = true),
        identityStorage, provider, platform, mockk(relaxed = true), mockk(relaxed = true))
    private val id = Identifier.from(ByteArray(32) { 7 })

    init {
        every { identity.uniqueIdentifier } returns id
        val field = IdentityRepositoryImpl::class.java.getDeclaredField("_blockchainIdentityFlow").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(repository) as MutableStateFlow<BlockchainIdentity?>).value = identity
    }

    @Test fun completedLoadWithoutIdentityIsDifferentFromStillLoading() = runBlocking {
        org.junit.Assert.assertNull(repository.initializationResult.value)
        coEvery { identityStorage.load() } returns null
        repository.init()
        org.junit.Assert.assertTrue(repository.initializationResult.value!!.isSuccess)
        org.junit.Assert.assertNull(repository.blockchainIdentity)
    }

    @Test fun failedIdentityLoadIsPublishedToWaitingRequests() = runBlocking {
        val failure = IllegalStateException("Storage unavailable")
        coEvery { identityStorage.load() } throws failure
        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking { repository.init() } }
        org.junit.Assert.assertSame(failure, thrown)
        org.junit.Assert.assertSame(failure, repository.initializationResult.value!!.exceptionOrNull())
    }

    @Test fun purchaseMigrationRejectsAnIdentitySwitchBeforeAnyMutation() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.ensureCriticalAuthenticationKey(Identifier.from(ByteArray(32) { 8 }), null) }
        }
        verify(exactly = 0) { identity.addMissingKeys(any()) }
    }

    @Test fun purchaseMigrationRejectsADifferentWalletObject() {
        every { identity.wallet } returns mockk(relaxed = true)
        every { provider.wallet } returns mockk(relaxed = true)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.ensureCriticalAuthenticationKey(id, null) }
        }
        verify(exactly = 0) { identity.addMissingKeys(any()) }
    }

    @Test fun purchaseMigrationReportsFailureInsteadOfPretendingAKeyWasSubmitted() {
        every { provider.wallet } returns null
        every { identity.hasCriticalAuthenticationKey() } returns false
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.ensureCriticalAuthenticationKey(id, null) }
        }
        org.junit.Assert.assertTrue(error.message!!.contains("Unable to register"))
        verify(exactly = 0) { identity.addMissingKeys(any()) }
    }
}
