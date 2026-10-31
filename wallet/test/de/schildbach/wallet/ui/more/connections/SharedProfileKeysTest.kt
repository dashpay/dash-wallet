package de.schildbach.wallet.ui.more.connections

import android.app.Application
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.*
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE,
    shadows = [AppConnectKeyStateTest.ShadowSdkEnums::class])
class SharedProfileKeysTest {
    private val service = mockk<PlatformService>(relaxed = true)
    private val wallet = mockk<PlatformRepo>(relaxed = true)
    private val identities = mockk<IdentityRepository>(relaxed = true)
    private val config = mockk<DashConnectConfig>(relaxed = true)
    private val identityId = Identifier.from(ByteArray(32) { 15 })
    private val dashPay = Identifier.from("Bwr4WHCPz5rFVAD87RqTs3izo4zpzwsEdKPWUT1NS1C7")
    private val appId = Identifier.from(ByteArray(32) { 9 })
    private val session = AppConnectSession(service, wallet, identities, config)

    private fun auth(id: Int, bounds: ContractBounds, disabledAt: Long? = null) = IdentityPublicKey(id, KeyType.ECDSA_HASH160,
        Purpose.AUTHENTICATION, SecurityLevel.HIGH, bounds, ByteArray(20) { id.toByte() }, false, disabledAt = disabledAt)

    private val unlinked = auth(20, SingleContractDocumentType(dashPay, "profile"))
    private val disabled = auth(21, SingleContractDocumentType(dashPay, "profile"), disabledAt = 50)
    private val inFlight = auth(22, SingleContractDocumentType(dashPay, "profile"))
    private val otherDocument = auth(23, SingleContractDocumentType(dashPay, "contactRequest"))
    private val appKey = auth(24, SingleContractBounds(appId))

    private fun setup() {
        every { wallet.walletApplication.wallet!!.watchingKey.pubKey } returns ByteArray(33) { 1 }
        every { wallet.walletApplication.wallet!!.isEncrypted } returns false
        every { identities.blockchainIdentity!!.uniqueIdentifier } returns identityId
        every { service.platform.client.getIdentity(any(), true) } returns
            Identity(identityId, listOf(unlinked, disabled, inFlight, otherDocument, appKey), 100, 0, 14)
        // An approval still in flight records its keys before registering them.
        coEvery { config.getGrants() } returns listOf(JSONObject().put("id", "pending").put("scope", session.scope())
            .put("app", appId.toString()).put("state", "prepared")
            .put("keys", JSONArray(listOf(JSONObject(inFlight.toJSON())))))
    }

    @Test fun `only enabled DashPay profile keys without a record are listed`() = runBlocking {
        setup()
        assertEquals(listOf(20), session.sharedProfileKeys(session.scope()))
    }

    @Test fun `revoke submits nothing when no shown key is still unlinked`() = runBlocking(ConnectionAuthorization { true }) {
        setup()
        // Key 22 became recorded by an approval after the warning appeared; 99 was never shown as unlinked.
        val client = service.platform.client
        session.revokeSharedProfileKeys(listOf(22, 99), session.scope())
        // Signing an identity update starts by reading Platform time for the master key.
        verify(exactly = 1) { client.getIdentity(any(), true) }
        verify(exactly = 0) { client.getPlatformTime(any()) }
    }

    @Test fun `a disconnected connection that kept its encryption key still shows what the app could do`() = runBlocking {
        setup()
        val revoked = IdentityPublicKey(40, KeyType.ECDSA_HASH160, Purpose.AUTHENTICATION, SecurityLevel.HIGH,
            SingleContractBounds(appId), ByteArray(20) { 40 }, false, disabledAt = 60,
            totalBudget = java.math.BigInteger.valueOf(50_000_000_000), expiresAt = 5_000L, version = 1)
        // A disconnect keeps the shared encryption key enabled.
        val encryption = IdentityPublicKey(41, KeyType.ECDSA_SECP256K1, Purpose.ENCRYPTION, SecurityLevel.MEDIUM,
            SingleContractBounds(appId), ByteArray(33) { 41 }, false)
        every { service.platform.client.getIdentity(any(), true) } returns
            Identity(identityId, listOf(revoked, encryption), 100, 0, 14)
        coEvery { config.getGrants() } returns listOf(JSONObject().put("id", "login").put("scope", session.scope())
            .put("app", appId.toString()).put("state", "disabled").put("permanentRevocationConfirmed", true)
            .put("appRevocationConfirmed", true)
            .put("keys", JSONArray(listOf(revoked, encryption).map { JSONObject(it.toJSON()) })))
        val terms = session.terms(appId.toString(), session.scope())!!
        assertEquals(java.math.BigInteger.valueOf(50_000_000_000), terms.fundingLimit)
        assertTrue(terms.capabilities.any { it.kind == AppCapabilityKind.ACT_IN_APP })
    }

    @Test fun `details read what is left on each enabled key of the connection`() = runBlocking {
        setup()
        fun budgeted(id: Int, bounds: ContractBounds, budget: Long, disabledAt: Long? = null) = IdentityPublicKey(id,
            KeyType.ECDSA_HASH160, Purpose.AUTHENTICATION, SecurityLevel.HIGH, bounds, ByteArray(20) { id.toByte() }, false,
            disabledAt = disabledAt, totalBudget = java.math.BigInteger.valueOf(budget), expiresAt = 5_000L, version = 1)
        val own = budgeted(30, SingleContractBounds(appId), 50_800_000_000)
        val profile = budgeted(31, SingleContractDocumentType(dashPay, "profile"), 200_000_000)
        val revoked = budgeted(32, SingleContractBounds(appId), 1_000, disabledAt = 60)
        every { service.platform.client.getIdentity(any(), true) } returns
            Identity(identityId, listOf(own, profile, revoked), 100, 0, 14)
        coEvery { config.getGrants() } returns listOf(JSONObject().put("id", "login").put("scope", session.scope())
            .put("app", appId.toString()).put("state", "active")
            .put("keys", JSONArray(listOf(own, profile, revoked).map { JSONObject(it.toJSON()) })))
        every { service.platform.client.getIdentityKeyRemainingBudget(identityId, 30) } returns
            java.math.BigInteger.valueOf(32_000_000_000)
        every { service.platform.client.getIdentityKeyRemainingBudget(identityId, 31) } throws IllegalStateException("offline")
        val terms = session.terms(appId.toString(), session.scope())!!
        // The revoked key authorizes nothing, so its budget is not part of the connection's limit.
        assertEquals(java.math.BigInteger.valueOf(51_000_000_000), terms.fundingLimit)
        val funding = terms.keyFunding
        assertEquals(listOf(
            KeyFunding(AppCapabilityKind.ACT_IN_APP, null, java.math.BigInteger.valueOf(50_800_000_000),
                java.math.BigInteger.valueOf(32_000_000_000)),
            KeyFunding(AppCapabilityKind.EDIT_DASHPAY_PROFILE, "profile", java.math.BigInteger.valueOf(200_000_000), null)
        ), funding)
        val client = service.platform.client
        verify(exactly = 0) { client.getIdentityKeyRemainingBudget(any(), 32) }
    }

    @Test fun `a changed wallet cannot list or revoke keys`() {
        setup()
        val scope = session.scope()
        every { wallet.walletApplication.wallet!!.watchingKey.pubKey } returns ByteArray(33) { 2 }
        assertThrows(IllegalStateException::class.java) { runBlocking { session.sharedProfileKeys(scope) } }
        assertThrows(IllegalStateException::class.java) {
            runBlocking(ConnectionAuthorization { true }) { session.revokeSharedProfileKeys(listOf(20), scope) }
        }
        verify(exactly = 0) { service.platform.client.getIdentity(any(), any()) }
    }
}
