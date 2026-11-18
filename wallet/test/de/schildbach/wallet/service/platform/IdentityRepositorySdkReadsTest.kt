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

import android.app.Application
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.service.platform.sdk.DashSdkService
import de.schildbach.wallet.service.platform.sdk.SdkIdentityJsonException
import de.schildbach.wallet.service.platform.sdk.SdkIdentityQueryTimeoutException
import de.schildbach.wallet.service.platform.sdk.SdkNativeLibraryUnavailableException
import de.schildbach.wallet.service.platform.sdk.SdkIdentityQueries
import de.schildbach.wallet.service.platform.sdk.SdkIdentityReadSource
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.testPlatformRepo
import de.schildbach.wallet.ui.more.connections.AppConnectKeyStateTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Base58
import org.bitcoinj.core.Utils
import org.bitcoinj.crypto.IDeterministicKey
import org.bouncycastle.crypto.params.KeyParameter
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import org.dashj.platform.sdk.platform.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Identity discovery ([IdentityRepository.getIdentityFromPublicKeyId]) and the two balance reads
 * ([IdentityRepository.getIdentityBalance], [PlatformRepo.getIdentityBalance]) go through the
 * Kotlin SDK only (no legacy fallback on any network) and keep each caller's null/throw contract:
 * discovery is null only for a proved absence and throws for every failure (network, timeout,
 * native library), the repository balance is null for every failure, and the PlatformRepo balance
 * throws an ordinary exception. Only a found identity constructs the legacy `Platform` client.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [29],
    manifest = Config.NONE,
    shadows = [AppConnectKeyStateTest.ShadowSdkEnums::class]
)
class IdentityRepositorySdkReadsTest {
    private val firstKeyHash = ByteArray(20) { 4 }
    private val hashHex = Utils.HEX.encode(firstKeyHash)
    private val identityId = ByteArray(32) { 7 }
    private val keyData = ByteArray(33) { 2 }

    private val source = mockk<SdkIdentityReadSource>()

    /** Parks a simulated blocking SDK call past the budget; released in [releaseHungReads]. */
    private val release = CountDownLatch(1)

    @After
    fun releaseHungReads() {
        release.countDown()
    }
    private val sdkIdentityQueries = SdkIdentityQueries(source)

    /** The legacy service; reading its `platform` constructs the legacy client and loads libsdklib. */
    private val platformService = mockk<PlatformService>(relaxed = true)
    private val platformRepo = mockk<PlatformRepo>(relaxed = true)
    private val encryptionKey = mockk<KeyParameter>()

    init {
        every { platformRepo.platform } returns platformService
        every { platformService.platform } returns mockk<Platform>(relaxed = true)
        every { platformRepo.getWalletEncryptionKey() } returns encryptionKey
        val firstKey = mockk<IDeterministicKey>()
        every { firstKey.pubKeyHash } returns firstKeyHash
        every { platformRepo.getBlockchainIdentityKey(0, encryptionKey) } returns firstKey
    }

    private fun repository(
        repo: PlatformRepo = platformRepo,
        identity: BlockchainIdentity? = null,
        queries: SdkIdentityQueries = sdkIdentityQueries
    ) =
        IdentityRepositoryImpl(
            walletApplication = mockk(relaxed = true),
            appDatabase = mockk(relaxed = true),
            blockchainIdentityDataStorage = mockk(relaxed = true),
            walletDataProvider = mockk(relaxed = true),
            platformRepo = repo,
            dashPayConfig = mockk(relaxed = true),
            dashSystemService = mockk(relaxed = true),
            sdkUsernameQueries = mockk(relaxed = true),
            sdkProfileQueries = mockk(relaxed = true),
            sdkIdentityQueries = queries
        ).also { repository ->
            if (identity != null) {
                val field = IdentityRepositoryImpl::class.java.getDeclaredField("_blockchainIdentityFlow")
                    .also { it.isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                (field.get(repository) as MutableStateFlow<BlockchainIdentity?>).value = identity
            }
        }

    /**
     * [actual] is [expected] or the copy kotlinx.coroutines' stack-trace recovery makes of it when
     * it crosses `withContext` (the copy keeps the original as its cause).
     */
    private fun assertSameFailure(expected: Throwable, actual: Throwable) {
        if (actual === expected) return
        assertSame(expected, actual.cause)
        assertEquals(expected.javaClass, actual.javaClass)
    }

    private fun bound() = """{"${'$'}type":"documentType","id":"${Base58.encode(ByteArray(32) { 9 })}",""" +
        """"documentTypeName":"contactRequest"}"""

    private fun key(id: Int, purpose: Int, level: Int, bounds: String = "null", extra: String = "") =
        """{"${'$'}formatVersion":"0","id":$id,"purpose":$purpose,"securityLevel":$level,"contractBounds":$bounds,""" +
            """"type":0,"readOnly":false,"data":"${Base64.getEncoder().encodeToString(keyData)}"$extra}"""

    private fun identityJson(vararg keys: String) =
        """{"${'$'}formatVersion":"0","id":"${Base58.encode(identityId)}","publicKeys":[${keys.joinToString(",")}],""" +
            """"balance":250000000,"revision":2}"""

    // ── discovery ──────────────────────────────────────────────────────

    @Test
    fun foundIdentityWithoutBoundsIsMappedAfterTheLegacyClientExists() = runBlocking {
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } returns
            identityJson(key(0, 0, 0), key(1, 0, 2))

        val identity = checkNotNull(repository().getIdentityFromPublicKeyId())

        assertEquals(Identifier.from(identityId), identity.id)
        assertEquals(250_000_000L, identity.balance)
        assertEquals(2, identity.revision)
        assertEquals(listOf(SecurityLevel.MASTER, SecurityLevel.HIGH), identity.publicKeys.map { it.securityLevel })
        verify(exactly = 1) { platformService.platform }
    }

    @Test
    fun foundIdentityWithContractBoundAndDisabledKeysKeepsThem() = runBlocking {
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } returns identityJson(
            key(0, 0, 0),
            key(1, 0, 1, extra = ""","disabledAt":1700000000000"""),
            key(4, 1, 3, bounds = bound()),
            key(5, 2, 3, bounds = bound())
        )

        val identity = checkNotNull(repository().getIdentityFromPublicKeyId())

        assertEquals(1_700_000_000_000L, identity.getPublicKeyById(1)?.disabledAt)
        val encryption = checkNotNull(identity.getPublicKeyById(4))
        assertEquals(Purpose.ENCRYPTION, encryption.purpose)
        assertEquals(KeyType.ECDSA_SECP256K1, encryption.type)
        assertEquals(Identifier.from(ByteArray(32) { 9 }), encryption.contractBounds?.identifier)
        verify(exactly = 1) { platformService.platform }
    }

    @Test
    fun absentIdentityIsNullAndNeverConstructsTheLegacyClient() = runBlocking {
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } returns null
        coEvery { source.identitiesJsonByNonUniquePublicKeyHash(hashHex, null) } returns "[]"

        assertNull(repository().getIdentityFromPublicKeyId())

        verify(exactly = 0) { platformService.platform }
        verify(exactly = 0) { platformService.client }
        verify(exactly = 0) { platformService.stateRepository }
    }

    @Test
    fun unreachableNetworkPropagatesAndIsNeverNoIdentity() {
        for (failure in listOf(DashSdkError.NetworkError("all DAPI nodes failed"), DashSdkError.Timeout("deadline"))) {
            coEvery { source.identityJsonByPublicKeyHash(hashHex) } throws failure
            assertSameFailure(failure, assertThrows(failure.javaClass) {
                runBlocking { repository().getIdentityFromPublicKeyId() }
            })
        }
        // a network failure on a non-unique page propagates too
        val network = DashSdkError.NetworkError("page")
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } returns null
        coEvery { source.identitiesJsonByNonUniquePublicKeyHash(hashHex, null) } throws network
        assertSameFailure(network, assertThrows(DashSdkError.NetworkError::class.java) {
            runBlocking { repository().getIdentityFromPublicKeyId() }
        })
        verify(exactly = 0) { platformService.platform }
        verify(exactly = 0) { platformService.client }
        verify(exactly = 0) { platformService.stateRepository }
    }

    @Test
    fun discoveryPastItsBudgetIsAFailureWithoutTheLegacyClient() {
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } answers { release.await(30, TimeUnit.SECONDS); null }
        val repository = repository(queries = SdkIdentityQueries(source, timeoutMs = 50))
        assertThrows(SdkIdentityQueryTimeoutException::class.java) {
            runBlocking { repository.getIdentityFromPublicKeyId() }
        }
        coVerify(exactly = 0) { source.identitiesJsonByNonUniquePublicKeyHash(any(), any()) }
        verify(exactly = 0) { platformService.platform }
        verify(exactly = 0) { platformService.client }
    }

    @Test
    fun nativeLibraryFailureIsAnOrdinaryDiscoveryFailure() {
        val service = mockk<DashSdkService>()
        val link = UnsatisfiedLinkError("dlopen failed: libdash_sdk.so")
        coEvery { service.ensureStarted() } throws link
        val thrown = assertThrows(SdkNativeLibraryUnavailableException::class.java) {
            runBlocking { repository(queries = SdkIdentityQueries(service)).getIdentityFromPublicKeyId() }
        }
        assertSame(link, thrown.cause)
        verify(exactly = 0) { platformService.platform }
        verify(exactly = 0) { platformService.client }
    }

    /**
     * Devnet: the wallet gives the SDK no devnet quorum URL (nor a devnet name), so `Sdk.create`
     * fails at bootstrap: rs-sdk-ffi cannot build the trusted context provider and returns an
     * `InternalError`.
     * Every read fails closed through the SDK and never reaches the legacy client.
     */
    @Test
    fun anSdkThatCannotStartOnDevnetFailsClosedForDiscoveryAndBothBalances() = runBlocking {
        val service = mockk<DashSdkService>()
        val devnet = DashSdkError.InternalError(
            "Failed to create trusted context provider: Devnet name must be provided for devnet network"
        )
        coEvery { service.ensureStarted() } throws devnet
        val queries = SdkIdentityQueries(service)
        val unstartable = testPlatformRepo(sdkIdentityQueries = queries)

        assertSameFailure(devnet, assertThrows(DashSdkError.InternalError::class.java) {
            runBlocking { repository(queries = queries).getIdentityFromPublicKeyId() }
        })
        assertNull(repository(unstartable, walletIdentity, queries).getIdentityBalance())
        assertSameFailure(devnet, assertThrows(DashSdkError.InternalError::class.java) {
            runBlocking { unstartable.getIdentityBalance(Identifier.from(identityId)) }
        })
        verify(exactly = 0) { platformService.platform }
        verify(exactly = 0) { unstartable.platform.platform }
        verify(exactly = 0) { unstartable.platform.client }
    }

    @Test
    fun otherFailuresPropagateWithoutTheLegacyClient() {
        val internal = DashSdkError.InternalError("proof verification failed")
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } throws internal
        assertSameFailure(internal, assertThrows(DashSdkError.InternalError::class.java) {
            runBlocking { repository().getIdentityFromPublicKeyId() }
        })

        val boot = IllegalStateException("SDK runtime missing after ensureStarted()")
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } throws boot
        assertSameFailure(boot, assertThrows(IllegalStateException::class.java) {
            runBlocking { repository().getIdentityFromPublicKeyId() }
        })

        coEvery { source.identityJsonByPublicKeyHash(hashHex) } returns "{\"${'$'}formatVersion\":\"9\"}"
        assertThrows(SdkIdentityJsonException::class.java) { runBlocking { repository().getIdentityFromPublicKeyId() } }

        verify(exactly = 0) { platformService.platform }
    }

    @Test
    fun cancellationPropagatesUnwrapped() {
        val cancellation = CancellationException("stopped")
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } throws cancellation
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { repository().getIdentityFromPublicKeyId() }
        }
        assertEquals("stopped", thrown.message)
        verify(exactly = 0) { platformService.platform }
    }

    @Test
    fun noEncryptionKeyOrNoFirstIdentityKeyIsNullWithoutAnyQuery() = runBlocking {
        every { platformRepo.getWalletEncryptionKey() } returns null
        assertNull(repository().getIdentityFromPublicKeyId())

        every { platformRepo.getWalletEncryptionKey() } returns encryptionKey
        every { platformRepo.getBlockchainIdentityKey(0, encryptionKey) } returns null
        assertNull(repository().getIdentityFromPublicKeyId())

        coVerify(exactly = 0) { source.identityJsonByPublicKeyHash(any()) }
        verify(exactly = 0) { platformService.platform }
    }

    // ── balances ───────────────────────────────────────────────────────

    private val idBase58 = Base58.encode(identityId)
    private val walletIdentity = mockk<BlockchainIdentity>(relaxed = true).also {
        every { it.uniqueIdentifier } returns Identifier.from(identityId)
    }
    private val realPlatformRepo by lazy { testPlatformRepo(sdkIdentityQueries = sdkIdentityQueries) }

    @Test
    fun balanceIsTheSdkCreditValueForBothCallers() = runBlocking {
        coEvery { source.creditBalance(idBase58) } returns 1_234_567_890L

        assertEquals(CreditBalanceInfo(1_234_567_890L), repository(realPlatformRepo, walletIdentity).getIdentityBalance())
        assertEquals(CreditBalanceInfo(1_234_567_890L), realPlatformRepo.getIdentityBalance(Identifier.from(identityId)))
        verify(exactly = 0) { realPlatformRepo.platform.platform }
        verify(exactly = 0) { realPlatformRepo.platform.client }
    }

    @Test
    fun sdkNullBalanceIsUnknownForTheRepositoryAndThrowsForPlatformRepo() = runBlocking {
        coEvery { source.creditBalance(idBase58) } returns null

        assertNull(repository(realPlatformRepo, walletIdentity).getIdentityBalance())
        assertThrows(IllegalStateException::class.java) {
            runBlocking { realPlatformRepo.getIdentityBalance(Identifier.from(identityId)) }
        }
        Unit
    }

    @Test
    fun sdkFailureIsUnknownForTheRepositoryAndPropagatesFromPlatformRepo() = runBlocking {
        val failure = DashSdkError.InternalError("Identity balance not found")
        coEvery { source.creditBalance(idBase58) } throws failure

        assertNull(repository(realPlatformRepo, walletIdentity).getIdentityBalance())
        assertSameFailure(failure, assertThrows(DashSdkError.InternalError::class.java) {
            runBlocking { realPlatformRepo.getIdentityBalance(Identifier.from(identityId)) }
        })
        verify(exactly = 0) { realPlatformRepo.platform.client }
    }

    @Test
    fun sdkThatCannotStartIsUnknownForTheRepositoryAndThrowsForPlatformRepo() = runBlocking {
        val service = mockk<DashSdkService>()
        val boot = IllegalStateException("SDK bootstrap failed")
        coEvery { service.ensureStarted() } throws boot
        val unstartable = testPlatformRepo(sdkIdentityQueries = SdkIdentityQueries(service))

        assertNull(repository(unstartable, walletIdentity).getIdentityBalance())
        assertSameFailure(boot, assertThrows(IllegalStateException::class.java) {
            runBlocking { unstartable.getIdentityBalance(Identifier.from(identityId)) }
        })
        verify(exactly = 0) { unstartable.platform.platform }
        verify(exactly = 0) { unstartable.platform.client }
    }

    @Test
    fun nativeLibraryFailureIsUnknownForTheRepositoryAndAnOrdinaryExceptionFromPlatformRepo() = runBlocking {
        val service = mockk<DashSdkService>()
        val link = UnsatisfiedLinkError("dlopen failed: libdash_sdk.so")
        coEvery { service.ensureStarted() } throws link
        val unloadable = testPlatformRepo(sdkIdentityQueries = SdkIdentityQueries(service))

        assertNull(repository(unloadable, walletIdentity).getIdentityBalance())
        val thrown = assertThrows(SdkNativeLibraryUnavailableException::class.java) {
            runBlocking { unloadable.getIdentityBalance(Identifier.from(identityId)) }
        }
        assertSame(link, thrown.cause)
        // what BuyCreditsViewModel, RequestUserNameViewModel and CreateIdentityService catch
        assertTrue(thrown is Exception)
        verify(exactly = 0) { unloadable.platform.platform }
        verify(exactly = 0) { unloadable.platform.client }
    }

    @Test
    fun balancePastItsBudgetIsUnknownForTheRepositoryAndThrowsForPlatformRepo() = runBlocking {
        coEvery { source.creditBalance(idBase58) } answers { release.await(30, TimeUnit.SECONDS); 5L }
        val queries = SdkIdentityQueries(source, timeoutMs = 50)
        val slow = testPlatformRepo(sdkIdentityQueries = queries)

        assertNull(repository(slow, walletIdentity, queries).getIdentityBalance())
        assertThrows(SdkIdentityQueryTimeoutException::class.java) {
            runBlocking { slow.getIdentityBalance(Identifier.from(identityId)) }
        }
        verify(exactly = 0) { slow.platform.platform }
        verify(exactly = 0) { slow.platform.client }
    }

    @Test
    fun noIdentityIsNullWithoutAnSdkRead() = runBlocking {
        assertNull(repository(realPlatformRepo).getIdentityBalance())
        coVerify(exactly = 0) { source.creditBalance(any()) }
    }

    @Test
    fun cancellationDuringTheRepositoryBalanceReadPropagates() {
        coEvery { source.creditBalance(idBase58) } throws CancellationException("stopped")
        assertThrows(CancellationException::class.java) {
            runBlocking { repository(realPlatformRepo, walletIdentity).getIdentityBalance() }
        }
    }
}
