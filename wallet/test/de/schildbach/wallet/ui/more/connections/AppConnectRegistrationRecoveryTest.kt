package de.schildbach.wallet.ui.more.connections

import android.app.Application
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.more.connections.protocol.AppConnectRequest
import de.schildbach.wallet.ui.more.connections.protocol.AuthBinding
import de.schildbach.wallet.ui.more.connections.protocol.DashConnectNetwork
import de.schildbach.wallet.ui.more.connections.protocol.DashKeyRequest
import de.schildbach.wallet.ui.more.connections.protocol.KeyExchangeCrypto
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Base58
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.identity.IdentityPublicKey
import org.dashj.platform.dpp.identity.SingleContractBounds
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE,
    shadows = [AppConnectKeyStateTest.ShadowSdkEnums::class])
class AppConnectRegistrationRecoveryTest {
    private val config = mockk<DashConnectConfig>(relaxed = true)
    private val service = mockk<PlatformService>(relaxed = true)
    private val wallet = mockk<PlatformRepo>(relaxed = true)
    private val identities = mockk<IdentityRepository>(relaxed = true)
    private val session = AppConnectSession(service, wallet, identities, config)
    private val identityId = Identifier.from(ByteArray(32) { 3 })
    private val appId = Identifier.from(ByteArray(32) { 4 })
    private val time = System.currentTimeMillis()
    private fun key(data: Int = 1, disabledAt: Long? = null, expiry: Long? = null) = IdentityPublicKey(
        7, KeyType.ECDSA_HASH160, Purpose.AUTHENTICATION, SecurityLevel.HIGH,
        SingleContractBounds(appId), ByteArray(20) { data.toByte() }, false,
        disabledAt = disabledAt, expiresAt = expiry)

    private fun identity(vararg keys: IdentityPublicKey) = Identity(identityId, keys.toList(), 100, 0, 14)

    private fun record(id: String = "other", scope: String = "wallet", app: String = "another-app",
                       connection: String = "another-connection", state: String = "pending",
                       confirmed: Boolean = false, stored: IdentityPublicKey = key()) = JSONObject()
        .put("id", id).put("scope", scope).put("app", app).put("connection", connection).put("state", state)
        .put("permanentRevocationConfirmed", confirmed)
        .put("keys", JSONArray(listOf(JSONObject(stored.toJSON()))))

    private fun requireSettled(records: List<JSONObject>, current: Identity = identity()) = runBlocking {
        coEvery { config.getGrants() } returns records
        session.requireOtherRegistrationsSettled("wallet", "current", current, time)
    }

    @Test fun `pending registration for another app blocks new IDs`() {
        val saved = record()
        assertThrows(IllegalStateException::class.java) { requireSettled(listOf(saved)) }
        assertEquals("pending", saved.getString("state"))
        coVerify(exactly = 0) { config.saveGrant(any()) }
    }

    @Test fun `same app with another or absent connection ID also blocks`() {
        for (connection in listOf("another-connection", "")) {
            assertThrows(IllegalStateException::class.java) {
                requireSettled(listOf(record(app = "current-app", connection = connection)))
            }
        }
    }

    @Test fun `only current recovery and other wallet scopes are excluded`() {
        requireSettled(listOf(record(id = "current"), record(scope = "another-wallet")))
        assertThrows(IllegalStateException::class.java) {
            requireSettled(listOf(record(id = "current"), record(id = "another-pending-request")))
        }
    }

    @Test fun `unsubmitted prepared and permanently revoked records do not block`() {
        requireSettled(listOf(record(state = "prepared"), record(state = "disabled", confirmed = true)))
    }

    @Test fun `partial or unconfirmed revocation cannot settle a missing registration`() {
        for (state in listOf("pending", "confirmed", "active", "partialRevocation", "disabled")) {
            assertThrows(IllegalStateException::class.java) { requireSettled(listOf(record(state = state))) }
        }
    }

    @Test fun `proved present disabled expired or conflicting keys settle old registrations`() {
        for (current in listOf(key(), key(disabledAt = time), key(expiry = time - 1), key(data = 2))) {
            requireSettled(listOf(record()), identity(current))
        }
    }

    @Test fun `expiry cannot settle an absent submitted key`() {
        assertThrows(IllegalStateException::class.java) {
            requireSettled(listOf(record(stored = key(expiry = time - 1))))
        }
    }

    @Test fun `storage failure blocks rather than treating records as empty`() {
        coEvery { config.getGrants() } throws IllegalStateException("Storage unavailable")
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { session.requireOtherRegistrationsSettled("wallet", "current", identity(), time) }
        }
        assertEquals("Storage unavailable", error.message)
    }

    @Test fun `approval checks other pending grants before derivation and saving a new record`() {
        val grant = AuthBinding(0, 2, appId.toBuffer(), null, time + 86_400_000)
        val parsed = AppConnectRequest(byteArrayOf(2, 3), null, listOf(grant), emptyList(), null, false, time, null)
        val request = DashKeyRequest(ByteArray(33) { 2 }, appId.toBuffer(), "App", DashConnectNetwork.TESTNET, parsed)
        every { wallet.walletApplication.wallet!!.watchingKey.pubKey } returns ByteArray(33) { 1 }
        every { wallet.walletApplication.wallet!!.isEncrypted } returns false
        every { wallet.getWalletSeed()!!.seedBytes } returns ByteArray(32) { 1 }
        every { identities.blockchainIdentity!!.uniqueIdentifier } returns identityId
        every { service.platform.client.refreshProtocolVersion() } returns 14
        every { service.platform.client.getIdentity(any(), true) } returns identity()
        every { service.platform.client.getPlatformTime(any()) } returns time
        every { service.platform.client.getDataContract(appId.toBuffer(), true) } returns mockk(relaxed = true) {
            every { ownerId } returns identityId
        }
        val scope = session.scope()
        val plan = AppConnectPlan(Base58.encode(KeyExchangeCrypto.hash256(parsed.bytes)), scope,
            identityId.toString(), listOf(grant), "Consent", blockTime = time)
        coEvery { config.getGrants() } returns listOf(record(scope = scope))
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking(ConnectionAuthorization { true }) { session.approve(request, plan) }
        }
        assertTrue(error.message!!.contains("Registration outcome is still unknown"))
        coVerify(exactly = 0) { config.saveGrant(any()) }
    }
}
