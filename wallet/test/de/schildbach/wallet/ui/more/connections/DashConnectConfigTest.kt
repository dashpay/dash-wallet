package de.schildbach.wallet.ui.more.connections

import android.app.Application
import android.content.ContextWrapper
import androidx.datastore.preferences.core.stringPreferencesKey
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.WalletDataProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class DashConnectConfigTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun config(files: File, provider: WalletDataProvider = mockk(relaxed = true)) = DashConnectConfig(
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = files
        },
        provider
    )

    @Test fun `wallet wipe preserves scoped authorization and unknown purchase receipts but clears legacy rows`() = runBlocking {
        val files = temporaryFolder.newFolder()
        val listener = slot<suspend () -> Unit>()
        val provider = mockk<WalletDataProvider>(relaxed = true)
        every { provider.attachOnWalletWipedListener(capture(listener)) } just Runs
        val config = config(files, provider)
        config.saveGrant(JSONObject().put("id", "same-seed:grant").put("scope", "same-seed").put("app", "app").put("state", "active"))
        config.savePurchase(JSONObject().put("id", "same-seed:purchase").put("scope", "same-seed").put("status", "outcome-unknown"))
        config.upsertConnection(StoredConnection("old", "Old app", "", "approved", 1))
        val ordinary = File(files, "datastore/ordinary.preferences_pb").apply { writeText("settings") }
        val app = spyk(de.schildbach.wallet.WalletApplication())
        every { app.filesDir } returns files
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(app, "clearDatastorePrefs")
        listener.captured.invoke()
        assertFalse(ordinary.exists())
        assertTrue(File(files, "datastore/${DashConnectConfig.AUDIT_FILE_NAME}").isFile)
        assertEquals("same-seed:grant", config.getGrants().single().getString("id"))
        assertTrue(config.purchaseWasSubmitted("same-seed:purchase"))
        assertFalse(config.purchaseWasSubmitted("different-seed:purchase"))
        assertTrue(config.getConnections().isEmpty())
    }

    @Test fun `local legacy removal cannot erase a grant audit`() = runBlocking {
        val config = config(temporaryFolder.newFolder())
        config.upsertConnection(StoredConnection("app", "Old app", "", "approved", 1))
        config.saveGrant(JSONObject().put("id", "grant").put("scope", "wallet").put("app", "app").put("state", "disabled"))
        assertThrows(IllegalStateException::class.java) { runBlocking { config.removeLegacyConnection("app", "wallet") } }
        assertEquals(1, config.getConnections().size)
        assertEquals(1, config.getGrants().size)
        config.upsertConnection(StoredConnection("legacy", "Old app", "", "approved", 1))
        config.removeLegacyConnection("legacy", "wallet")
        assertEquals(listOf("app"), config.getConnections().map { it.contractId })
        assertEquals(1, config.getGrants().size)
    }

    @Test fun `restored audit is visible only in its original wallet scope`() = runBlocking {
        val config = config(temporaryFolder.newFolder())
        val service = mockk<de.schildbach.wallet.service.platform.PlatformService>(relaxed = true)
        val wallet = mockk<de.schildbach.wallet.ui.dashpay.PlatformRepo>(relaxed = true)
        val identities = mockk<de.schildbach.wallet.service.platform.IdentityRepository>(relaxed = true)
        val identity = mockk<org.dashj.platform.dashpay.BlockchainIdentity>(relaxed = true)
        every { identity.uniqueIdentifier } returns org.dashj.platform.dpp.identifier.Identifier.from(ByteArray(32) { 7 })
        every { identities.blockchainIdentity } returns identity
        every { identities.blockchainIdentityFlow } returns kotlinx.coroutines.flow.MutableStateFlow(identity)
        var watchingKey = ByteArray(33) { 1 }
        every { wallet.walletApplication.wallet!!.watchingKey.pubKey } answers { watchingKey }
        val session = AppConnectSession(service, wallet, identities, config)
        val scope = session.scope()
        config.saveGrant(JSONObject().put("id", "$scope:grant").put("scope", scope).put("app", "app")
            .put("state", "active").put("keys", org.json.JSONArray()))
        val repository = PlatformDashConnectRepository(service, wallet, identities, config)
        assertEquals(listOf("app"), repository.observeConnections().first().map { it.id })
        watchingKey = ByteArray(33) { 2 }
        assertTrue(repository.observeConnections().first().isEmpty())
        watchingKey = ByteArray(33) { 1 }
        assertEquals(listOf("app"), repository.observeConnections().first().map { it.id })
        config.upsertConnection(StoredConnection("legacy", "Old app", "", "approved", 1))
        assertTrue(repository.observeConnections().first().single { it.id == "legacy" }.legacyOnly)
        repository.removeConnection("legacy", scope)
        assertEquals(listOf("app"), repository.observeConnections().first().map { it.id })
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.removeConnection("app", scope) } }
        assertEquals(1, config.getGrants().size)
    }

    @Test fun `grant reads observation and writes propagate storage IO failures`() = runBlocking {
        val files = temporaryFolder.newFolder()
        // An existing directory where the preferences file should be produces a read IOException,
        // rather than the valid empty state used for a genuinely missing file.
        val unreadable = File(files, "datastore/dash_connect.preferences_pb")
        assertTrue(unreadable.mkdirs())
        val config = config(files)
        assertThrows(IOException::class.java) { runBlocking { config.getGrants() } }
        assertThrows(IOException::class.java) { runBlocking { config.observeGrants().first() } }
        assertThrows(IOException::class.java) {
            runBlocking { config.saveGrant(JSONObject().put("id", "new")) }
        }
        Unit
    }

    @Test fun `grant updates retain other records and refuse malformed storage`() = runBlocking {
        val config = config(temporaryFolder.newFolder())
        assertTrue(config.getGrants().isEmpty())
        config.saveGrant(JSONObject().put("id", "old").put("state", "active"))
        config.saveGrant(JSONObject().put("id", "new").put("state", "pending"))
        config.saveGrant(JSONObject().put("id", "old").put("state", "disabled"))
        assertEquals(setOf("old", "new"), config.getGrants().map { it.getString("id") }.toSet())
        assertEquals("disabled", config.getGrants().single { it.getString("id") == "old" }.getString("state"))
        val grants = stringPreferencesKey("app_connect_grants_v2")
        config.set(grants, "not-json")
        assertThrows(Exception::class.java) { runBlocking { config.getGrants() } }
        assertThrows(Exception::class.java) { runBlocking { config.observeGrants().first() } }
        assertThrows(Exception::class.java) {
            runBlocking { config.saveGrant(JSONObject().put("id", "replacement")) }
        }
        assertEquals("not-json", config.get(grants))
        Unit
    }
}
