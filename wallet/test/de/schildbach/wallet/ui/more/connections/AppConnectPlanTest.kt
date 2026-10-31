package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.ui.more.connections.protocol.AuthBinding
import de.schildbach.wallet.ui.more.connections.protocol.KeyExchangeCrypto
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class AppConnectPlanTest {
    private val time = 1_790_000_000_000L
    private val day = 86_400_000L
    private fun plan() = AppConnectPlan("request", "scope", "identity", listOf(
        AuthBinding(2, 1, ByteArray(32), BigInteger("500000000000"), time + 365 * day),
        AuthBinding(1, 2, ByteArray(32), BigInteger("1000000000"), time + 7 * day, "profile")
    ), "Consent", blockTime = time, groupScopes = org.dashj.platform.dpp.identifier.Identifier.from(ByteArray(32)).let { id ->
        mapOf(id.toString() to org.dashj.platform.dpp.contract.VerifiedContractGroup(id, id, emptySet(), null, null,
            emptySet(), emptySet(), emptySet()))
    })

    private val dashPay = org.bitcoinj.core.Base58.decode("Bwr4WHCPz5rFVAD87RqTs3izo4zpzwsEdKPWUT1NS1C7")
    private fun dash(amount: String) = java.math.BigDecimal(amount).movePointRight(11).toBigIntegerExact()

    /** Yappr's request: 0.5 DASH for its own key, 0.01 DASH for the DashPay profile key, 90 days. */
    private fun yappr(): AppConnectPlan {
        val grants = listOf(AuthBinding(2, 1, ByteArray(32), dash("0.5"), time + 90 * day),
            AuthBinding(1, 2, dashPay, dash("0.01"), time + 90 * day, "profile"))
        return plan().copy(grants = grants, proposedGrants = grants)
    }

    @Test fun `limits start at the total request capped at five DASH`() {
        val requested = yappr().withRequestedLimits()
        assertEquals(dash("0.51"), requested.fundingTotal)
        assertEquals(dash("0.51"), requested.fundingLimit())
        assertEquals(time + 90 * day, requested.expiry)
        assertTrue(requested.grants.all { it.expiresAt == time + 90 * day })
        assertEquals(AppConnectLimits.MAX_BUDGET, plan().withRequestedLimits().fundingTotal)
        val unlimited = yappr().grants.map { it.copy(totalBudget = null) }
        assertEquals(AppConnectLimits.MAX_BUDGET,
            yappr().copy(grants = unlimited, proposedGrants = unlimited).withRequestedLimits().fundingTotal)
        assertEquals(time + 365 * day, plan().withRequestedLimits().expiry)
    }

    @Test fun `the profile key gets two thousandths of a DASH and halves below four thousandths`() {
        val split = yappr().withLimits(dash("2"), time + 30 * day)
        assertEquals(listOf(dash("1.998"), dash("0.002")), split.grants.map { it.totalBudget })
        assertEquals(dash("2"), split.fundingLimit())
        val small = yappr().withLimits(dash("0.003"), time + 30 * day)
        assertEquals(listOf(dash("0.0015"), dash("0.0015")), small.grants.map { it.totalBudget })
        // Without the profile key the app's key gets the whole limit.
        val appOnly = split.copy(selectedAuth = setOf(0)).withCurrentLimits()
        assertEquals(dash("2"), appOnly.grants[0].totalBudget)
        assertEquals(dash("2"), appOnly.fundingLimit())
        // Keys without a DashPay profile binding share evenly, the remainder going to the first.
        val even = plan().withLimits(BigInteger("101"), time + 30 * day)
        assertEquals(listOf(BigInteger("51"), BigInteger("50")), even.grants.map { it.totalBudget })
    }

    @Test fun `limits may go above the request up to five DASH and a year`() {
        val raised = yappr().withLimits(AppConnectLimits.MAX_BUDGET, time + 365 * day)
        assertEquals(AppConnectLimits.MAX_BUDGET, raised.fundingLimit())
        assertTrue(raised.grants.all { it.expiresAt == time + 365 * day })
        for ((total, expiry) in listOf(BigInteger.ZERO to time + 30 * day, AppConnectLimits.MAX_BUDGET + BigInteger.ONE to time + 30 * day,
            dash("1") to time + 365 * day + 1, dash("1") to time + AppConnectLimits.MIN_LIFETIME)) {
            assertThrows(IllegalArgumentException::class.java) { yappr().withLimits(total, expiry) }
        }
    }

    @Test fun `capabilities describe the approved grants in plain language`() {
        val dashPay = org.bitcoinj.core.Base58.decode("Bwr4WHCPz5rFVAD87RqTs3izo4zpzwsEdKPWUT1NS1C7")
        val grants = plan().grants.let { listOf(it[0], it[1].copy(id = dashPay)) }
        val capabilities = plan().copy(grants = grants, encryptionChoices = listOf("Encryption"),
            selectedEncryption = setOf(0)).capabilities()
        assertEquals(listOf(AppCapabilityKind.ACT_IN_APP, AppCapabilityKind.EDIT_DASHPAY_PROFILE,
            AppCapabilityKind.RECEIVE_ENCRYPTED), capabilities.map { it.kind })
        assertTrue(capabilities[0].tokens)
        assertEquals(BigInteger("1000000000"), capabilities[1].budget)
        assertEquals(AppCapabilityKind.EDIT_DOCUMENTS, plan().capabilities()[1].kind)
        assertEquals(BigInteger("501000000000"), plan().fundingLimit())
        assertEquals(time + 365 * day, plan().connectionExpiresAt())
    }

    @Test fun `critical consent discloses credit spending and the payee within the grant budget`() {
        val choices = plan().choices()
        assertTrue(choices[0].description.contains("spend your Platform credits"))
        assertTrue(choices[0].description.contains("within this budget"))
        assertTrue(choices[0].description.contains("purchased token's contract owner"))
        assertTrue(choices[0].description.contains("may differ from the app owner"))
        assertFalse(choices[1].description.contains("token purchases"))
    }

    @Test fun `pending recovery cannot change approved limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            plan().copy(recovering = true).withLimits(dash("1"), time + 30 * day)
        }
        val recovering = plan().copy(recovering = true)
        assertSame(recovering, recovering.withCurrentLimits())
    }

    @Test fun `consent accepts any preset and checks only the wallet maximums`() {
        val requested = plan().grants.map { it.copy(totalBudget = null) }
        val original = plan().copy(grants = requested, proposedGrants = requested)
        val uri = com.google.gson.JsonParser.parseReader(javaClass.getResourceAsStream("/app-connect-vectors.json")!!.reader())
            .asJsonObject.getAsJsonObject("request")["uri"].asString
        val decoded = de.schildbach.wallet.ui.more.connections.protocol.DashConnectUri.parseKeyRequest(uri)
        val request = decoded.copy(appConnect = decoded.appConnect!!.copy(auth = requested))
        for (budget in AppConnectPresets.budgets) for (days in AppConnectPresets.lifetimeDays) {
            val chosen = original.withLimits(budget, time + days * day)
            assertTrue(chosen.grants.all { it.totalBudget != null && it.totalBudget.signum() > 0 })
            chosen.requireMatches(request)
            assertTrue(chosen.choices().all { !it.description.contains("No budget limit") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(grants = requested.map { it.copy(totalBudget = BigInteger.ZERO) }).requireMatches(request)
        }
        // Each key within 5 DASH but together above it: only a recovering approval keeps such limits.
        val overTotal = original.copy(grants = requested.map { it.copy(totalBudget = AppConnectLimits.MAX_BUDGET,
            expiresAt = time + 30 * day) })
        assertThrows(IllegalArgumentException::class.java) { overTotal.requireMatches(request) }
        overTotal.copy(recovering = true).requireMatches(request)
        // An approval prepared by an earlier version may be unlimited; recovery keeps it, a new one may not.
        assertThrows(IllegalArgumentException::class.java) { original.requireMatches(request) }
        original.copy(recovering = true).requireMatches(request)
    }

    @Test fun `u64 requested budgets are capped without inventing absent budgets`() {
        val grant = AuthBinding(0, 2, ByteArray(32), null, null)
        val defaults = AppConnectLimits.clamp(grant, time)
        assertNull(defaults.totalBudget)
        assertEquals(time + 90 * day, defaults.expiresAt)
        val huge = AppConnectLimits.clamp(grant.copy(totalBudget = BigInteger("18446744073709551615"),
            expiresAt = time + 1000 * day), time)
        assertEquals(BigInteger("500000000000"), huge.totalBudget)
        assertEquals(time + 365 * day, huge.expiresAt)
        val small = AppConnectLimits.clamp(grant.copy(totalBudget = BigInteger.ONE, expiresAt = time + 2 * day), time)
        assertEquals(BigInteger.ONE, small.totalBudget)
        assertEquals(time + 2 * day, small.expiresAt)
        assertThrows(IllegalArgumentException::class.java) { AppConnectLimits.clamp(grant.copy(expiresAt = time), time) }
    }

    @Test fun `display uses credits per DASH not satoshis`() {
        assertEquals("1", formatDash(BigInteger("100000000000")))
        assertEquals("0.01", formatDash(BigInteger("1000000000")))
        assertEquals("0.00000000001", formatDash(BigInteger.ONE))
    }

    @Test fun `binding leaves are distinct and retain original indices`() {
        val public = byteArrayOf(2) + ByteArray(32) { 1 }
        val legacy = KeyExchangeCrypto.sessionRequestLeaf(public, null)
        val first = KeyExchangeCrypto.sessionRequestLeaf(public, 0)
        val third = KeyExchangeCrypto.sessionRequestLeaf(public, 2)
        assertArrayEquals(KeyExchangeCrypto.hash256(public), legacy)
        assertArrayEquals(KeyExchangeCrypto.hash256(public + byteArrayOf(0)), first)
        assertArrayEquals(KeyExchangeCrypto.hash256(public + byteArrayOf(2)), third)
        assertFalse(legacy.contentEquals(first))
        assertFalse(first.contentEquals(third))
        assertEquals(20, KeyExchangeCrypto.hash160(public).size)
        assertEquals(32, third.size)
    }
}
