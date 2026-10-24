package de.schildbach.wallet.ui.more.connections

import android.app.Application
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class AppConnectResponseCleanupTest {
    private val config = mockk<DashConnectConfig>()
    private val session = AppConnectSession(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), config)

    private fun record(id: String, connection: String = "", state: String = "active", confirmed: Boolean = false,
                       scope: String = "wallet", app: String = "app") = JSONObject()
        .put("id", id).put("connection", connection).put("state", state)
        .put("permanentRevocationConfirmed", confirmed).put("scope", scope).put("app", app)

    @Test fun `publication and retry preserve independent live responses including requests without connection ID`() = runBlocking {
        val current = record("current", "browser-b")
        val independent = record("independent", "browser-a")
        val noConnection = record("no-connection")
        val sameConnection = record("same-connection", "browser-b")
        coEvery { config.getGrants() } returns listOf(current, independent, noConnection, sameConnection)
        repeat(2) {
            assertTrue(session.responsesToDelete(current, "wallet").isEmpty())
            assertTrue(session.responsesToDelete(independent, "wallet").isEmpty())
            assertTrue(session.responsesToDelete(noConnection, "wallet").isEmpty())
        }
    }

    @Test fun `only confirmed revoked responses in same app and wallet can be cleaned up`() = runBlocking {
        val current = record("current", state = "disabled", confirmed = true)
        val superseded = record("superseded", "renewed-browser", "disabled", true)
        coEvery { config.getGrants() } returns listOf(current, superseded,
            record("unconfirmed", state = "disabled"),
            record("partial", state = "partialRevocation", confirmed = true),
            record("pending", state = "pending"),
            record("prepared", state = "prepared"),
            record("confirmed", state = "confirmed"),
            record("other-wallet", state = "disabled", confirmed = true, scope = "other"),
            record("other-app", state = "disabled", confirmed = true, app = "other"))
        assertEquals(listOf("superseded"), session.responsesToDelete(current, "wallet").map { it.getString("id") })
    }
}
