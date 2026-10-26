package de.schildbach.wallet.ui.more.connections

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class RevocationSettlementTest {
    private fun partial() = JSONObject().put("id", "grant").put("state", "partialRevocation")
        .put("permanentRevocationConfirmed", false).put("unrecordedKeyIds", JSONArray(listOf(7, 9)))

    @Test fun `request revocation preserves partial evidence without claiming app-wide completion`() {
        val record = partial()
        val settled = settleConfirmedRevocation(record, null)
        assertEquals("partialRevocation", settled.getString("state"))
        assertEquals("[7,9]", settled.getJSONArray("unrecordedKeyIds").toString())
        assertTrue(settled.getBoolean("permanentRevocationConfirmed"))
        assertFalse(settled.getBoolean("appRevocationConfirmed"))
        assertFalse(record.getBoolean("permanentRevocationConfirmed"))
    }

    @Test fun `revoking a new request never proves an app-wide disconnect`() {
        val settled = settleConfirmedRevocation(JSONObject().put("state", "confirmed"), null)
        assertEquals("disabled", settled.getString("state"))
        assertTrue(settled.getBoolean("permanentRevocationConfirmed"))
        assertFalse(settled.getBoolean("appRevocationConfirmed"))
    }

    @Test fun `partial evidence without specific IDs also survives request settlement`() {
        val settled = settleConfirmedRevocation(JSONObject().put("state", "partialRevocation"), null)
        assertEquals("partialRevocation", settled.getString("state"))
        assertFalse(settled.getBoolean("appRevocationConfirmed"))
    }

    @Test fun `whole-app audit records the currently surviving keys`() {
        val settled = settleConfirmedRevocation(partial(), listOf(12))
        assertEquals("partialRevocation", settled.getString("state"))
        assertEquals("[12]", settled.getJSONArray("unrecordedKeyIds").toString())
        assertFalse(settled.getBoolean("appRevocationConfirmed"))
    }

    @Test fun `only a successful empty whole-app audit clears partial evidence`() {
        val record = partial()
        val settled = settleConfirmedRevocation(record, emptyList())
        assertEquals("disabled", settled.getString("state"))
        assertEquals(0, settled.getJSONArray("unrecordedKeyIds").length())
        assertTrue(settled.getBoolean("appRevocationConfirmed"))
        assertEquals("[7,9]", record.getJSONArray("unrecordedKeyIds").toString())
        assertTrue(settleConfirmedRevocation(settled, null).getBoolean("appRevocationConfirmed"))
    }

    @Test fun `malformed evidence is not silently cleared by request settlement`() {
        assertThrows(Exception::class.java) {
            settleConfirmedRevocation(partial().put("unrecordedKeyIds", "unreadable"), null)
        }
    }

    @Test fun `state transitions update timestamp but repeated state does not`() {
        val record = JSONObject().put("state", "prepared").put("updatedAt", 1L)
        for ((index, state) in listOf("pending", "confirmed", "active", "partialRevocation", "disabled").withIndex()) {
            val time = index + 10L
            assertEquals(time, record.withGrantState(state, time).getLong("updatedAt"))
            assertEquals(time, record.withGrantState(state, time + 1).getLong("updatedAt"))
        }
        assertTrue(settleConfirmedRevocation(JSONObject().put("state", "active").put("updatedAt", 1L), emptyList())
            .getLong("updatedAt") > 1L)
    }

    @Test fun `QR names stay explicitly unverified and include their app identity`() {
        assertEquals("Unverified app: \"Yappr\" [contract]", unverifiedAppName("Yappr", "contract"))
        assertEquals("Unverified app: \"Yappr\" [contract]", unverifiedAppName("Yap\u202epr\n", "contract"))
        assertTrue(unverifiedAppName("", "contract").contains("Unknown app"))
    }

    @Test fun `app confirmation updates disabled timestamp without changing its state string`() {
        val record = JSONObject().put("state", "disabled").put("updatedAt", 1L)
            .put("permanentRevocationConfirmed", true).put("appRevocationConfirmed", false)
        val settled = settleConfirmedRevocation(record, emptyList())
        assertEquals("disabled", settled.getString("state"))
        assertTrue(settled.getBoolean("appRevocationConfirmed"))
        assertTrue(settled.getLong("updatedAt") > 1L)
        val repeated = settleConfirmedRevocation(settled, emptyList())
        assertEquals(settled.getLong("updatedAt"), repeated.getLong("updatedAt"))
        assertEquals(1L, record.getLong("updatedAt"))
    }
}
