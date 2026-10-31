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
