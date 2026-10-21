package de.schildbach.wallet.ui.more.connections

import android.app.Application
import android.content.ContextWrapper
import androidx.datastore.preferences.core.stringPreferencesKey
import io.mockk.mockk
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

    private fun config(files: File) = DashConnectConfig(
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = files
        },
        mockk<WalletDataProvider>(relaxed = true)
    )

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
