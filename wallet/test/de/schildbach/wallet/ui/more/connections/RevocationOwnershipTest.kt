package de.schildbach.wallet.ui.more.connections

import android.app.Application
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.dashj.platform.dpp.contract.DataContract
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
class RevocationOwnershipTest {
    private val service = mockk<PlatformService>(relaxed = true)
    private val wallet = mockk<PlatformRepo>(relaxed = true)
    private val identities = mockk<IdentityRepository>(relaxed = true)
    private val config = mockk<DashConnectConfig>(relaxed = true)
    private val appId = Identifier.from(ByteArray(32) { 9 })
    private val target = Identifier.from(ByteArray(32) { 11 })
    private val owner = Identifier.from(ByteArray(32) { 13 })
    private val identityId = Identifier.from(ByteArray(32) { 15 })
    private val session = AppConnectSession(service, wallet, identities, config)

    private fun setup(group: Boolean = false, expired: Boolean = false): org.dashj.platform.dpp.identity.Identity {
        every { wallet.walletApplication.wallet!!.watchingKey.pubKey } returns ByteArray(33) { 1 }
        every { identities.blockchainIdentity!!.uniqueIdentifier } returns identityId
        val key = IdentityPublicKey(7, KeyType.ECDSA_HASH160, Purpose.AUTHENTICATION, SecurityLevel.HIGH,
            if (group) ContractGroupBounds(target) else SingleContractBounds(target), ByteArray(20), false,
            expiresAt = if (expired) 99 else null)
        val recorded = IdentityPublicKey(8, KeyType.ECDSA_HASH160, Purpose.AUTHENTICATION, SecurityLevel.HIGH,
            SingleContractBounds(appId), ByteArray(20) { 8 }, false, totalBudget = java.math.BigInteger.valueOf(1000), expiresAt = 200)
        val identity = org.dashj.platform.dpp.identity.Identity(identityId, listOf(key, recorded), 100, 0, 14)
        every { service.platform.client.getIdentity(any(), true) } returns identity
        every { service.platform.client.getPlatformTime(any()) } returns 100
        every { service.platform.client.getIdentityKeyRemainingBudget(any(), any()) } returns null
        every { service.platform.client.getDataContract(appId.toBuffer(), true) } returns mockk<DataContract> {
            every { ownerId } returns owner
        }
        coEvery { config.getGrants() } returns listOf(JSONObject().put("id", "recorded").put("scope", session.scope())
            .put("app", appId.toString()).put("state", "partialRevocation")
            .put("keys", JSONArray(listOf(JSONObject(recorded.toJSON())))))
        return identity
    }

    @Test fun `absent sibling ownership cannot establish complete revocation`() {
        val identity = setup()
        every { service.platform.client.getDataContract(target.toBuffer(), true) } returns null
        assertThrows(IllegalStateException::class.java) { runBlocking { session.unrecordedAppKeys(identity, appId.toString(), session.scope()) } }
        val details = runBlocking { session.details(appId.toString(), session.scope()) }
        assertTrue(details.contains("Key 8:"))
        assertTrue(details.contains("ownership checks are unavailable"))
        coVerify(exactly = 0) { config.saveGrant(any()) }
    }

    @Test fun `failed sibling proof cannot establish complete revocation`() {
        val identity = setup()
        every { service.platform.client.getDataContract(target.toBuffer(), true) } throws IllegalStateException("invalid proof")
        val error = assertThrows(IllegalStateException::class.java) { runBlocking { session.unrecordedAppKeys(identity, appId.toString(), session.scope()) } }
        assertEquals("invalid proof", error.message)
        val details = runBlocking { session.details(appId.toString(), session.scope()) }
        assertTrue(details.contains("Revoke access is still available"))
        assertTrue(details.contains("Complete revocation cannot be verified"))
        val controller = org.robolectric.Robolectric.buildActivity(androidx.fragment.app.FragmentActivity::class.java)
        controller.get().setTheme(org.dash.wallet.common.R.style.My_Theme)
        val activity = controller.setup().get()
        val dialog = connectionDetailsDialog(activity, "App", details, ConnectionStatus.APPROVED)
        try {
            dialog.showNow(activity.supportFragmentManager, "ownership-unavailable")
            assertTrue(dialog.requireDialog().isShowing)
            assertEquals(activity.getString(de.schildbach.wallet_test.R.string.dash_connect_revoke),
                dialog.requireView().findViewById<android.widget.TextView>(org.dash.wallet.common.R.id.dialog_positive_button).text.toString())
        } finally {
            dialog.dismissNow()
            controller.pause().stop().destroy()
        }
        coVerify(exactly = 0) { config.saveGrant(any()) }
    }

    @Test fun `missing group owner fails closed`() {
        val identity = setup(group = true)
        every { service.platform.client.getContractGroupOwner(target) } returns null
        assertThrows(IllegalStateException::class.java) { runBlocking { session.unrecordedAppKeys(identity, appId.toString(), session.scope()) } }
        assertTrue(runBlocking { session.details(appId.toString(), session.scope()) }.contains("ownership checks are unavailable"))
    }

    @Test fun `proved same owner reports the surviving key`() = runBlocking {
        setup()
        every { service.platform.client.getDataContract(target.toBuffer(), true) } returns mockk<DataContract> {
            every { ownerId } returns owner
        }
        assertTrue(session.details(appId.toString(), session.scope()).contains("Other enabled application keys: 7"))
    }

    @Test fun `expired surviving keys are included in supplementary ownership checks`() = runBlocking {
        setup(expired = true)
        every { service.platform.client.getDataContract(target.toBuffer(), true) } returns mockk<DataContract> {
            every { ownerId } returns owner
        }
        assertTrue(session.details(appId.toString(), session.scope()).contains("Other enabled application keys: 7"))
    }

    @Test fun `missing app contract leaves details and revoke available`() = runBlocking {
        setup()
        every { service.platform.client.getDataContract(appId.toBuffer(), true) } returns null
        assertTrue(session.details(appId.toString(), session.scope()).contains("Revoke access is still available"))
    }

    @Test fun `ownership cancellation is never converted to a warning`() {
        setup()
        every { service.platform.client.getDataContract(target.toBuffer(), true) } throws CancellationException("cancelled")
        assertThrows(CancellationException::class.java) { runBlocking { session.details(appId.toString(), session.scope()) } }
    }

    @Test fun `optional budget read failure cannot hide details`() = runBlocking {
        setup()
        every { service.platform.client.getIdentityKeyRemainingBudget(any(), any()) } throws IllegalStateException("budget unavailable")
        every { service.platform.client.getDataContract(target.toBuffer(), true) } returns null
        val details = session.details(appId.toString(), session.scope())
        assertTrue(details.contains("Remaining budget unavailable"))
        assertTrue(details.contains("Revoke access is still available"))
    }

    @Test fun `budget cancellation is never converted to missing budget`() {
        setup()
        every { service.platform.client.getIdentityKeyRemainingBudget(any(), any()) } throws CancellationException("cancelled")
        assertThrows(CancellationException::class.java) { runBlocking { session.details(appId.toString(), session.scope()) } }
    }

    @Test fun `supplementary failure cannot hide a wallet scope change`() {
        setup()
        val scope = session.scope()
        every { service.platform.client.getDataContract(target.toBuffer(), true) } answers {
            every { wallet.walletApplication.wallet!!.watchingKey.pubKey } returns ByteArray(33) { 2 }
            error("unavailable")
        }
        val error = assertThrows(IllegalStateException::class.java) { runBlocking { session.details(appId.toString(), scope) } }
        assertTrue(error.message!!.contains("Wallet, identity or network changed"))
    }
}
