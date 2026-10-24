package de.schildbach.wallet.ui.more.connections

import android.app.Application
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ConnectionExpiryTest {
    private val service = mockk<PlatformService>(relaxed = true)
    private val wallet = mockk<PlatformRepo>(relaxed = true)
    private val identities = mockk<IdentityRepository>(relaxed = true)
    private val config = mockk<DashConnectConfig>()
    private val records = MutableStateFlow<List<JSONObject>>(emptyList())
    private val repository: PlatformDashConnectRepository
    private val scope: String

    init {
        val identity = mockk<BlockchainIdentity>(relaxed = true)
        every { identity.uniqueIdentifier } returns Identifier.from(ByteArray(32) { 7 })
        every { identities.blockchainIdentity } returns identity
        every { identities.blockchainIdentityFlow } returns MutableStateFlow(identity)
        every { wallet.walletApplication.wallet!!.watchingKey.pubKey } returns ByteArray(33) { 1 }
        every { config.observeGrants() } returns records
        every { config.observeConnections() } returns MutableStateFlow(emptyList())
        scope = AppConnectSession(service, wallet, identities, config).scope()
        repository = PlatformDashConnectRepository(service, wallet, identities, config)
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
        assertEquals(ConnectionStatus.APPROVED, connections.single { it.id == "encryption" }.status)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionStatus.ACTIVE, connections.single { it.id == "app" }.status)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionStatus.APPROVED, connections.single { it.id == "app" }.status)
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
        runCurrent()
        assertEquals(ConnectionStatus.APPROVED, connections.single().status)
        records.value = listOf(grant("app", 4_000, state = "disabled")
            .put("permanentRevocationConfirmed", true).put("appRevocationConfirmed", true))
        runCurrent()
        assertEquals(ConnectionStatus.DISCONNECTED, connections.single().status)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionStatus.DISCONNECTED, connections.single().status)
        collector.cancel()
    }
}
