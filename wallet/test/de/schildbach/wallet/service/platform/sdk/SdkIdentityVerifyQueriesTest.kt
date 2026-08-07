/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.service.platform.sdk

import com.google.gson.JsonParser
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Optional

/**
 * Host-JVM tests for the identityVerify read facade — the voting UI's
 * per-contender verification-link lookup routed through the Kotlin SDK's
 * generic document search (dashpay/platform#4088, light way). No native
 * calls; the search surface is faked via [SdkIdentityVerifySearchSource].
 *
 * The query-mapping tests lock the where/order clauses to the LEGACY dashj
 * `IdentityVerify.get(userId, normalizedLabel, "dash")` `DocumentQuery`
 * (verified from dash-sdk-kotlin 4.0.0-RC2 bytecode).
 */
class SdkIdentityVerifyQueriesTest {

    private val ownerId = Identifier.from("5DbLwAxGBzUzo81VewMUwn4b5P4bpv9FNFybi25XB5Bk")
    private val url = "https://example.com/proof"

    // ── Query mapping: parity with the legacy DocumentQuery ─────────────

    @Test
    fun whereClause_matchesLegacyQuery_withNormalizedLabel() {
        val where = JsonParser.parseString(
            SdkIdentityVerifyQueryMapping.whereOwnerAndLabel(ownerId, "WilliamOslo")
        ).asJsonArray

        assertEquals(3, where.size())
        val byField = where.associateBy { it.asJsonObject.get("field").asString }

        val owner = byField.getValue("\$ownerId").asJsonObject
        assertEquals("=", owner.get("operator").asString)
        assertEquals(ownerId.toString(), owner.get("value").asString)

        val parent = byField.getValue("normalizedParentDomainName").asJsonObject
        assertEquals("=", parent.get("operator").asString)
        assertEquals("dash", parent.get("value").asString)

        // Names.normalizeString applied, exactly like legacy get(userId, username).
        val label = byField.getValue("normalizedLabel").asJsonObject
        assertEquals("=", label.get("operator").asString)
        assertEquals("w1111am0s10", label.get("value").asString)
    }

    @Test
    fun orderBy_matchesLegacyQuery() {
        val orderBy = JsonParser.parseString(
            SdkIdentityVerifyQueryMapping.ORDER_BY_NORMALIZED_LABEL
        ).asJsonArray
        assertEquals(1, orderBy.size())
        val clause = orderBy.first().asJsonObject
        assertEquals("normalizedLabel", clause.get("field").asString)
        assertTrue(clause.get("ascending").asBoolean)
    }

    // ── Payload mapping ───────────────────────────────────────────────────

    @Test
    fun urlFromSearchPayload_bareArray_rootUrl() {
        val payload = """[{"${'$'}ownerId":"$ownerId","normalizedLabel":"a11ce",""" +
            """"normalizedParentDomainName":"dash","url":"$url"}]"""
        assertEquals(Optional.of(url), SdkIdentityVerifyQueryMapping.urlFromSearchPayload(payload))
    }

    @Test
    fun urlFromSearchPayload_wrappedDocuments_dataNestedUrl() {
        val payload = """{"documents":[{"${'$'}ownerId":"$ownerId","data":{"url":"$url"}}],""" +
            """"total_count":1}"""
        assertEquals(Optional.of(url), SdkIdentityVerifyQueryMapping.urlFromSearchPayload(payload))
    }

    @Test
    fun urlFromSearchPayload_emptyArray_isDefinitivelyNoLink() {
        assertEquals(Optional.empty<String>(), SdkIdentityVerifyQueryMapping.urlFromSearchPayload("[]"))
        assertEquals(
            Optional.empty<String>(),
            SdkIdentityVerifyQueryMapping.urlFromSearchPayload("""{"documents":[],"total_count":0}""")
        )
    }

    @Test
    fun urlFromSearchPayload_malformed_isNullForFallback() {
        // Not JSON at all.
        assertNull(SdkIdentityVerifyQueryMapping.urlFromSearchPayload("not json"))
        // Not an array (and no documents array).
        assertNull(SdkIdentityVerifyQueryMapping.urlFromSearchPayload("""{"weird":true}"""))
        // First entry not an object.
        assertNull(SdkIdentityVerifyQueryMapping.urlFromSearchPayload("""["str"]"""))
        // Document found but no string url — shape mismatch, fall back so
        // dashj (which reads the real document) decides.
        assertNull(SdkIdentityVerifyQueryMapping.urlFromSearchPayload("""[{"normalizedLabel":"x"}]"""))
        assertNull(SdkIdentityVerifyQueryMapping.urlFromSearchPayload("""[{"url":42}]"""))
    }

    // ── Facade orchestration ─────────────────────────────────────────────

    private class FakeSource(
        var onSearch: (String, String?, Int) -> String? = { _, _, _ -> "[]" }
    ) : SdkIdentityVerifySearchSource {
        var calls = 0
        var lastWhere: String? = null
        var lastOrderBy: String? = null
        var lastLimit: Int? = null

        override suspend fun search(whereJson: String, orderByJson: String?, limit: Int): String? {
            calls++
            lastWhere = whereJson
            lastOrderBy = orderByJson
            lastLimit = limit
            return onSearch(whereJson, orderByJson, limit)
        }
    }

    private fun config(enabled: Boolean?): DashPayConfig = mockk {
        if (enabled == null) {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) } throws
                IllegalStateException("datastore unavailable")
        } else {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) } returns enabled
        }
    }

    private fun queries(source: FakeSource, enabled: Boolean? = true) =
        SdkIdentityVerifyQueries(source, config(enabled))

    @Test
    fun flagOff_returnsNull_withoutSdkCalls() = runBlocking {
        val source = FakeSource()
        assertNull(queries(source, enabled = false).getVerificationUrl(ownerId, "alice"))
        assertEquals(0, source.calls)
    }

    @Test
    fun flagReadFailure_returnsNull_withoutSdkCalls() = runBlocking {
        val source = FakeSource()
        assertNull(queries(source, enabled = null).getVerificationUrl(ownerId, "alice"))
        assertEquals(0, source.calls)
    }

    @Test
    fun found_returnsUrl_withLegacyQueryShape() = runBlocking {
        val source = FakeSource(onSearch = { _, _, _ -> """[{"url":"$url"}]""" })
        val result = queries(source).getVerificationUrl(ownerId, "WilliamOslo")

        assertEquals(Optional.of(url), result)
        assertEquals(1, source.calls)
        assertEquals(
            SdkIdentityVerifyQueryMapping.whereOwnerAndLabel(ownerId, "WilliamOslo"),
            source.lastWhere
        )
        assertEquals(SdkIdentityVerifyQueryMapping.ORDER_BY_NORMALIZED_LABEL, source.lastOrderBy)
        assertEquals(1, source.lastLimit)
    }

    @Test
    fun noDocument_returnsEmptyOptional_notNull() = runBlocking {
        // Empty result and null payload both mean "the SDK answered: no
        // document" — the caller must NOT re-query via dashj.
        val emptyArray = FakeSource(onSearch = { _, _, _ -> "[]" })
        assertEquals(Optional.empty<String>(), queries(emptyArray).getVerificationUrl(ownerId, "alice"))

        val nullPayload = FakeSource(onSearch = { _, _, _ -> null })
        assertEquals(Optional.empty<String>(), queries(nullPayload).getVerificationUrl(ownerId, "alice"))
    }

    @Test
    fun malformedPayload_returnsNull_forDashjFallback() = runBlocking {
        val source = FakeSource(onSearch = { _, _, _ -> "not json" })
        assertNull(queries(source).getVerificationUrl(ownerId, "alice"))
    }

    @Test
    fun searchFailure_returnsNull_forDashjFallback() = runBlocking {
        val source = FakeSource(onSearch = { _, _, _ -> throw DashSdkError.NetworkError("dapi down") })
        assertNull(queries(source).getVerificationUrl(ownerId, "alice"))
        assertEquals(1, source.calls)
    }

    @Test
    fun fallbackContract_nullMeansFallback_optionalMeansAnswered() {
        // Documentation-level sanity of the three-state contract consumed by
        // PlatformSynchronizationService: null → run dashj; Optional → done.
        val fallback: Optional<String>? = null
        val answeredNone: Optional<String>? = Optional.empty()
        val answeredUrl: Optional<String>? = Optional.of(url)

        assertNull(fallback)
        assertFalse(answeredNone!!.isPresent)
        assertEquals(url, answeredUrl!!.get())
    }
}
