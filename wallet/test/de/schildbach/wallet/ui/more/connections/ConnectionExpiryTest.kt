package de.schildbach.wallet.ui.more.connections

import android.app.Application
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dpp.identifier.Identifier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE,
    shadows = [AppConnectKeyStateTest.ShadowSdkEnums::class])
class ConnectionExpiryTest {
    private val service = mockk<PlatformService>(relaxed = true)
    private val wallet = mockk<PlatformRepo>(relaxed = true)
    private val identities = mockk<IdentityRepository>(relaxed = true)
    private val config = mockk<DashConnectConfig>()
    private val identityFlow = MutableStateFlow<BlockchainIdentity?>(null)
    private val records = MutableStateFlow<List<JSONObject>>(emptyList())
    private val repository: PlatformDashConnectRepository
    private val scope: String

    init {
        val identity = mockk<BlockchainIdentity>(relaxed = true)
        every { identity.uniqueIdentifier } returns Identifier.from(ByteArray(32) { 7 })
        identityFlow.value = identity
        every { identities.blockchainIdentity } answers { identityFlow.value }
        every { identities.blockchainIdentityFlow } returns identityFlow
        every { wallet.walletApplication.wallet!!.watchingKey.pubKey } returns ByteArray(33) { 1 }
        every { config.observeGrants() } returns records
        every { config.observeConnections() } returns MutableStateFlow(emptyList())
        scope = AppConnectSession(service, wallet, identities, config).scope()
        repository = PlatformDashConnectRepository(service, wallet, identities, config)
        // Platform holds none of the recorded keys unless a test says otherwise.
        platformKeys()
    }

    // Expiry is verified by a Platform read on the IO dispatcher, outside the test scheduler.
    private fun TestScope.awaitStatus(expected: ConnectionStatus, status: () -> ConnectionStatus?) {
        repeat(200) {
            runCurrent()
            if (status() == expected) return
            Thread.sleep(10)
        }
        assertEquals(expected, status())
    }

    private fun grant(app: String, vararg expiries: Long, purpose: Int = 0, state: String = "active") =
        JSONObject().put("id", "$scope:$app").put("scope", scope).put("app", app).put("state", state)
            .put("keys", JSONArray(expiries.map { JSONObject().put("purpose", purpose).put("expiresAt", it) }))

    @Test fun `expiry updates idle collector and keeps app active until last authentication key expires`() = runTest {
        records.value = listOf(grant("app", 1_000, 2_000), grant("encryption", 500, purpose = 1))
        var connections = emptyList<DAppConnection>()
        backgroundScope.launch { repository.observeConnections { testScheduler.currentTime }.collect { connections = it } }
        runCurrent()
        assertEquals(ConnectionStatus.ACTIVE, connections.single { it.id == "app" }.status)
        assertEquals(0L, connections.single { it.id == "app" }.updatedAt)
        assertEquals(ConnectionStatus.APPROVED, connections.single { it.id == "encryption" }.status)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionStatus.ACTIVE, connections.single { it.id == "app" }.status)
        assertEquals(0L, connections.single { it.id == "app" }.updatedAt)
        advanceTimeBy(1_000)
        awaitStatus(ConnectionStatus.EXPIRED) { connections.single { it.id == "app" }.status }
        assertEquals(2_000L, connections.single { it.id == "app" }.updatedAt)
        // Expiry must not fabricate a confirmed app-wide revocation.
        assertFalse(records.value.first().optBoolean("appRevocationConfirmed"))
    }

    @Test fun `grant updates replace expiry schedule and disabled grants stay disconnected`() = runTest {
        records.value = listOf(grant("app", 1_000))
        var connections = emptyList<DAppConnection>()
        val collector = backgroundScope.launch {
            repository.observeConnections { testScheduler.currentTime }.collect { connections = it }
        }
        runCurrent()
        advanceTimeBy(500)
        records.value = listOf(grant("app", 3_000))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(ConnectionStatus.ACTIVE, connections.single().status)
        advanceTimeBy(2_000)
        awaitStatus(ConnectionStatus.EXPIRED) { connections.single().status }
        records.value = listOf(grant("app", 4_000, state = "disabled")
            .put("permanentRevocationConfirmed", true).put("appRevocationConfirmed", true))
        runCurrent()
        assertEquals(ConnectionStatus.DISCONNECTED, connections.single().status)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionStatus.DISCONNECTED, connections.single().status)
        collector.cancel()
    }

    @Test fun `restart derives final expiry across grants without replacing later status writes`() = runTest {
        val old = grant("app", 1_000, 2_000).put("updatedAt", 10L)
        val recent = grant("app", 3_000).put("updatedAt", 20L)
        val encryption = grant("app", 9_000, purpose = 1).put("updatedAt", 15L)
        records.value = listOf(recent, encryption, old)
        val expired = repository.observeConnections { 4_000L }.first().single()
        assertEquals(ConnectionStatus.EXPIRED, expired.status)
        assertEquals(3_000L, expired.updatedAt)
        assertEquals(20L, recent.getLong("updatedAt"))
        records.value = listOf(old, encryption, recent, grant("app", 8_000, state = "pending").put("updatedAt", 5_000L))
        assertEquals(5_000L, repository.observeConnections { 6_000L }.first().single().updatedAt)
        // A newer login still registering may already hold live access, so the old expiry does not win.
        assertEquals(ConnectionStatus.APPROVED, repository.observeConnections { 6_000L }.first().single().status)
        records.value = listOf(grant("app", 9_000, state = "disabled").put("updatedAt", 6_000L)
            .put("permanentRevocationConfirmed", true).put("appRevocationConfirmed", true))
        val disconnected = repository.observeConnections { 10_000L }.first().single()
        assertEquals(ConnectionStatus.DISCONNECTED, disconnected.status)
        assertEquals(6_000L, disconnected.updatedAt)
    }

    private fun key(expiresAt: Long?, disabledAt: Long? = null) = org.dashj.platform.dpp.identity.IdentityPublicKey(7,
        org.dashj.platform.sdk.KeyType.ECDSA_HASH160, org.dashj.platform.sdk.Purpose.AUTHENTICATION,
        org.dashj.platform.sdk.SecurityLevel.HIGH, org.dashj.platform.dpp.identity.SingleContractBounds(
            Identifier.from(ByteArray(32) { 9 })), ByteArray(20) { 7 }, false, expiresAt = expiresAt, disabledAt = disabledAt)

    private fun recordedGrant(savedExpiry: Long) = JSONObject().put("id", "$scope:app").put("scope", scope).put("app", "app")
        .put("state", "active").put("keys", JSONArray(listOf(JSONObject(key(savedExpiry).toJSON()))))

    private fun platformKeys(vararg keys: org.dashj.platform.dpp.identity.IdentityPublicKey) {
        every { service.platform.client.getIdentity(any(), true) } returns org.dashj.platform.dpp.identity.Identity(
            Identifier.from(ByteArray(32) { 7 }), keys.toList(), 100, 0, 14)
    }

    @Test fun `a key extended on Platform keeps the connection active until its new expiry`() = runTest {
        records.value = listOf(recordedGrant(1_000))
        platformKeys(key(5_000))
        var connections = emptyList<DAppConnection>()
        backgroundScope.launch { repository.observeConnections { testScheduler.currentTime + 2_000 }.collect { connections = it } }
        awaitStatus(ConnectionStatus.ACTIVE) { connections.singleOrNull()?.status }
        // The schedule follows the verified expiry, then Platform is read again.
        advanceTimeBy(3_000)
        awaitStatus(ConnectionStatus.EXPIRED) { connections.singleOrNull()?.status }
    }

    @Test fun `a connection is expired only when Platform confirms no recorded key is live`() = runTest {
        records.value = listOf(recordedGrant(1_000))
        platformKeys(key(1_000))
        assertEquals(ConnectionStatus.EXPIRED, repository.observeConnections { 2_000L }.first().single().status)
        platformKeys(key(5_000, disabledAt = 1_500))
        assertEquals(ConnectionStatus.EXPIRED, repository.observeConnections { 2_000L }.first().single().status)
        // A different key in the recorded ID slot is not the recorded key.
        platformKeys(org.dashj.platform.dpp.identity.IdentityPublicKey(7, org.dashj.platform.sdk.KeyType.ECDSA_HASH160,
            org.dashj.platform.sdk.Purpose.AUTHENTICATION, org.dashj.platform.sdk.SecurityLevel.HIGH,
            null, ByteArray(20) { 8 }, false, expiresAt = 5_000))
        assertEquals(ConnectionStatus.EXPIRED, repository.observeConnections { 2_000L }.first().single().status)
    }

    @Test fun `unreadable Platform never reports an expired connection`() = runTest {
        records.value = listOf(recordedGrant(1_000))
        every { service.platform.client.getIdentity(any(), true) } throws IllegalStateException("unavailable")
        assertEquals(ConnectionStatus.APPROVED, repository.observeConnections { 2_000L }.first().single().status)
    }

    @Test fun `a live connection is listed without reading Platform`() = runTest {
        records.value = listOf(recordedGrant(5_000))
        val client = service.platform.client
        assertEquals(ConnectionStatus.ACTIVE, repository.observeConnections { 2_000L }.first().single().status)
        verify(exactly = 0) { client.getIdentity(any(), any()) }
    }

    @Test fun `unavailable identity never projects persisted grants as an empty list`() = runTest {
        records.value = listOf(grant("app", 9_000))
        identityFlow.value = null
        val error = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { repository.observeConnections { 0L }.first() }
        }
        assertEquals("Identity unavailable", error.message)
        assertEquals(1, records.value.size)
    }

    @Test fun `unavailable wallet never projects a successful empty list`() = runTest {
        records.value = listOf(grant("app", 9_000))
        every { wallet.walletApplication.wallet } returns null
        val error = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { repository.observeConnections { 0L }.first() }
        }
        assertEquals("Wallet unavailable", error.message)
        assertEquals(1, records.value.size)
    }
}
