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

    @Test fun `presets only narrow budgets and expiry`() {
        val changed = plan().withPresets(0, 0)
        assertEquals(BigInteger("10000000000"), changed.grants[0].totalBudget)
        assertEquals(time + 30 * day, changed.grants[0].expiresAt)
        assertEquals(BigInteger("1000000000"), changed.grants[1].totalBudget)
        assertEquals(time + 7 * day, changed.grants[1].expiresAt)
        val reset = changed.withRequestedPresets()
        assertEquals(plan().grants.map { it.totalBudget }, reset.grants.map { it.totalBudget })
        assertEquals(plan().grants.map { it.expiresAt }, reset.grants.map { it.expiresAt })
    }

    @Test fun `requested presets are the smallest at or above the request`() {
        val requested = plan().withRequestedPresets()
        assertEquals(3, requested.budgetPreset)
        assertEquals(2, requested.lifetimePreset)
        val small = plan().grants.map { it.copy(totalBudget = BigInteger("30000000000"), expiresAt = time + 60 * day) }
        val narrow = plan().copy(grants = small, proposedGrants = small).withRequestedPresets()
        assertEquals(1, narrow.budgetPreset)
        assertEquals(1, narrow.lifetimePreset)
        assertEquals(small.map { it.totalBudget }, narrow.grants.map { it.totalBudget })
        assertEquals(small.map { it.expiresAt }, narrow.grants.map { it.expiresAt })
        val unlimited = plan().grants.map { it.copy(totalBudget = null) }
        assertEquals(3, plan().copy(grants = unlimited, proposedGrants = unlimited).withRequestedPresets().budgetPreset)
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
        assertEquals(BigInteger("500000000000"), plan().fundingLimit())
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
        assertThrows(IllegalArgumentException::class.java) { plan().copy(recovering = true).withPresets(1, 1) }
    }

    @Test fun `largest preset is five DASH and never increases a smaller request`() {
        val changed = plan().withPresets(3, 0)
        assertEquals(BigInteger("500000000000"), changed.grants[0].totalBudget)
        assertEquals(plan().grants[1].totalBudget, changed.grants[1].totalBudget)
    }

    @Test fun `finite presets cap an unlimited request and consent accepts narrowing`() {
        val requested = plan().grants.map { it.copy(totalBudget = null) }
        val original = plan().copy(grants = requested, proposedGrants = requested)
        val uri = com.google.gson.JsonParser.parseReader(javaClass.getResourceAsStream("/app-connect-vectors.json")!!.reader())
            .asJsonObject.getAsJsonObject("request")["uri"].asString
        val decoded = de.schildbach.wallet.ui.more.connections.protocol.DashConnectUri.parseKeyRequest(uri)
        val request = decoded.copy(appConnect = decoded.appConnect!!.copy(auth = requested))
        for (preset in 0..3) {
            val narrowed = original.withPresets(preset, 0)
            assertTrue(narrowed.grants.all { it.totalBudget != null && it.totalBudget.signum() > 0 })
            narrowed.requireMatches(request)
            assertTrue(narrowed.choices().all { !it.description.contains("No budget limit") })
        }
        assertTrue(original.withRequestedPresets().grants.all { it.totalBudget == BigInteger("500000000000") })
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(grants = requested.map { it.copy(totalBudget = BigInteger.ZERO) }).requireMatches(request)
        }
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
