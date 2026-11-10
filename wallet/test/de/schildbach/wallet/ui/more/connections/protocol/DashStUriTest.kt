package de.schildbach.wallet.ui.more.connections.protocol

import org.bitcoinj.core.Base58
import org.junit.Assert.*
import org.junit.Test

class DashStUriTest {
    private val payload = Base58.encode(byteArrayOf(1, 2, 3))

    @Test fun `accepts the v1 operation envelope on each known network`() {
        DashConnectNetwork.entries.forEach { network ->
            val parsed = DashConnectUri.parseStRequest("dash-st:$payload?n=${network.code}&v=1")
            assertEquals(network, parsed.network)
            assertArrayEquals(byteArrayOf(1, 2, 3), parsed.transitionBytes)
        }
    }

    @Test fun `rejects malformed ambiguous and oversized operation links before native decode`() {
        listOf("dash-st://$payload?n=d&v=1", "dash-st:$payload", "dash-st:?n=d&v=1",
            "dash-st:$payload?n=x&v=1", "dash-st:$payload?n=d&v=2", "dash-st:$payload?n=d&v=01",
            "dash-st:$payload?n=d&n=t&v=1", "dash-st:$payload?n=d&v=1&v=1",
            "dash-st:$payload?n=d&v=1#extra", "dash-st:0OIl?n=d&v=1",
            "dash-st:${"1".repeat(6000)}?n=d&v=1",
            "dash-st:${Base58.encode(ByteArray(4097))}?n=d&v=1"
        ).forEach { uri -> assertThrows(uri.take(80), DashConnectUriException::class.java) {
            DashConnectUri.parseStRequest(uri)
        } }
    }

    @Test fun `both carriers route to Connect without accepting unrelated schemes`() {
        assertTrue(DashConnectUri.isConnectUri("dash-key:any?n=d&v=2"))
        assertTrue(DashConnectUri.isConnectUri("dash-st:$payload?n=d&v=1"))
        assertFalse(DashConnectUri.isConnectUri("https://example.org"))
        assertFalse(DashConnectUri.isConnectUri("dash:address"))
    }
}
