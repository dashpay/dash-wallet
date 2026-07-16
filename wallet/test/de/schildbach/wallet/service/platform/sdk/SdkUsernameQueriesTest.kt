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

import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.sdk.platform.DomainDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the Phase 3c DPNS read facade: the SDK-JSON → dashj
 * `Document`/`DomainDocument` mapping and the flag/fallback orchestration.
 * No native calls — the native query surface is faked via [SdkDpnsSource].
 *
 * Fixture JSON mirrors the shapes produced by the SDK's FFI layer
 * (`rs-sdk-ffi/src/dpns/queries/{resolve,search,availability}.rs`):
 * - resolve:  `{"identityId": "<base58>"}`; not-found = error
 *   `InternalError("Name '<x>' not found")`
 * - search:   `[{"label","normalizedLabel","fullName","ownerId",
 *   "recordsIdentityId"?}, …]`
 */
class SdkUsernameQueriesTest {

    // Known testnet ids (also used by the SDK's own instrumented tests).
    private val dpnsContractId = "GWRSAVFMjXx8HpQFaNJMqBV7MBgMK4br5UESsB4S31Ec"
    private val identityId = "5DbLwAxGBzUzo81VewMUwn4b5P4bpv9FNFybi25XB5Bk"
    private val otherIdentityId = Identifier.from(ByteArray(32) { 2 }).toString()

    private val contract = SdkDpnsMapping.minimalDpnsContract(Identifier.from(dpnsContractId))

    // ── SdkDpnsMapping: resolve ───────────────────────────────────────────

    @Test
    fun resolveJson_mapsToDomainDocument() {
        val document = SdkDpnsMapping.documentFromResolveJson(
            """{"identityId":"$identityId"}""",
            label = "Alice",
            normalizedLabel = "al1ce",
            contract = contract
        )

        assertNotNull(document)
        val domain = DomainDocument(document!!)
        assertEquals("Alice", domain.label)
        assertEquals("al1ce", domain.normalizedLabel)
        assertEquals("dash", domain.normalizedParentDomainName)
        assertEquals(Identifier.from(identityId), domain.dashUniqueIdentityId)
        assertNull(domain.dashAliasIdentityId)
        assertEquals(Identifier.from(identityId), document.ownerId)
        assertEquals(Identifier.from(dpnsContractId), document.dataContractId)
        assertEquals("domain", document.type)
    }

    @Test
    fun resolveJson_malformed_returnsNull() {
        // missing identityId
        assertNull(SdkDpnsMapping.documentFromResolveJson("""{"foo":"bar"}""", "a", "a", contract))
        // identityId not a string
        assertNull(SdkDpnsMapping.documentFromResolveJson("""{"identityId":7}""", "a", "a", contract))
        // not an object
        assertNull(SdkDpnsMapping.documentFromResolveJson("""["x"]""", "a", "a", contract))
        // not JSON at all
        assertNull(SdkDpnsMapping.documentFromResolveJson("garbage{", "a", "a", contract))
    }

    // ── SdkDpnsMapping: search ────────────────────────────────────────────

    @Test
    fun searchJson_mapsEntries_andSkipsMalformedOnes() {
        val json = """
            [
              {"label":"bob","normalizedLabel":"b0b","fullName":"bob.dash",
               "ownerId":"$identityId","recordsIdentityId":"$otherIdentityId"},
              {"label":"bobby","normalizedLabel":"b0bby","fullName":"bobby.dash",
               "ownerId":"$identityId"},
              {"normalizedLabel":"missing-label","ownerId":"$identityId"},
              "not-an-object"
            ]
        """.trimIndent()

        val documents = SdkDpnsMapping.documentsFromSearchJson(json, contract)

        assertNotNull(documents)
        assertEquals(2, documents!!.size)

        val bob = DomainDocument(documents[0])
        assertEquals("bob", bob.label)
        assertEquals("b0b", bob.normalizedLabel)
        assertEquals("dash", bob.normalizedParentDomainName)
        // records.identity comes from recordsIdentityId when present…
        assertEquals(Identifier.from(otherIdentityId), bob.dashUniqueIdentityId)
        assertEquals(Identifier.from(identityId), documents[0].ownerId)

        val bobby = DomainDocument(documents[1])
        // …and falls back to ownerId when absent.
        assertEquals(Identifier.from(identityId), bobby.dashUniqueIdentityId)
        assertEquals("bobby", bobby.label)
    }

    @Test
    fun searchJson_nonArray_returnsNull() {
        assertNull(SdkDpnsMapping.documentsFromSearchJson("""{"label":"x"}""", contract))
        assertNull(SdkDpnsMapping.documentsFromSearchJson("garbage{", contract))
    }

    @Test
    fun searchJson_emptyArray_returnsEmptyList() {
        val documents = SdkDpnsMapping.documentsFromSearchJson("[]", contract)
        assertNotNull(documents)
        assertTrue(documents!!.isEmpty())
    }

    // ── SdkDpnsMapping: not-found classification ──────────────────────────

    @Test
    fun isNotFound_matchesFfiNotFoundShapes() {
        // Current FFI behavior (rs-sdk-ffi resolve.rs).
        assertTrue(SdkDpnsMapping.isNotFound(DashSdkError.InternalError("Name 'alice' not found")))
        // The proper code, if/when the FFI is fixed.
        assertTrue(SdkDpnsMapping.isNotFound(DashSdkError.NotFound("no such name")))
        // Real failures must NOT be treated as "unregistered".
        assertFalse(SdkDpnsMapping.isNotFound(DashSdkError.NetworkError("connection reset")))
        assertFalse(SdkDpnsMapping.isNotFound(IllegalStateException("Name 'alice' not found")))
    }

    // ── Facade orchestration ──────────────────────────────────────────────

    private class FakeSource(
        var resolveResult: () -> String? = { null },
        var searchResult: () -> String? = { null },
        var usernamesResult: (String) -> String? = { null }
    ) : SdkDpnsSource {
        var resolvedName: String? = null
        var searchedPrefix: String? = null
        var searchedLimit: Int? = null
        var usernamesIds = mutableListOf<String>()
        var usernamesLimit: Int? = null
        var calls = 0

        override suspend fun resolve(name: String): String? {
            calls++
            resolvedName = name
            return resolveResult()
        }

        override suspend fun search(prefix: String, limit: Int): String? {
            calls++
            searchedPrefix = prefix
            searchedLimit = limit
            return searchResult()
        }

        override suspend fun usernames(identityId: String, limit: Int): String? {
            calls++
            usernamesIds.add(identityId)
            usernamesLimit = limit
            return usernamesResult(identityId)
        }
    }

    private fun config(enabled: Boolean): DashPayConfig = mockk {
        coEvery { get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) } returns enabled
    }

    private fun queries(
        source: FakeSource,
        enabled: Boolean = true,
        contractId: Identifier? = Identifier.from(dpnsContractId)
    ) = SdkUsernameQueries(source, config(enabled), { contractId })

    @Test
    fun flagOff_returnsNull_andNeverTouchesSdk() {
        val source = FakeSource()
        val queries = queries(source, enabled = false)

        assertNull(queries.getUsernameOrNull("alice"))
        assertNull(runBlocking { queries.searchDomainDocumentsOrNull("ali", false, 100) })
        assertNull(runBlocking { queries.getDomainDocumentsByOwnerOrNull(identityId) })
        assertNull(
            runBlocking {
                queries.getDomainDocumentsForIdentitiesOrNull(listOf(Identifier.from(identityId)))
            }
        )
        assertEquals(0, source.calls)
    }

    @Test
    fun missingContractId_returnsNull_forDashjFallback() {
        val source = FakeSource(resolveResult = { """{"identityId":"$identityId"}""" })
        val queries = queries(source, contractId = null)

        assertNull(queries.getUsernameOrNull("alice"))
    }

    @Test
    fun getUsername_resolved_returnsSuccessWithDocument() {
        val source = FakeSource(resolveResult = { """{"identityId":"$identityId"}""" })
        val queries = queries(source)

        val resource = queries.getUsernameOrNull("Alice")

        assertNotNull(resource)
        assertEquals(Status.SUCCESS, resource!!.status)
        assertNotNull(resource.data)
        val domain = DomainDocument(resource.data!!)
        assertEquals("Alice", domain.label)
        assertEquals(Identifier.from(identityId), domain.dashUniqueIdentityId)
        // The SDK is queried with the normalized label (dashj parity):
        // Names.normalizeString maps o→0 and i/l→1 after lowercasing.
        assertEquals("a11ce", source.resolvedName)
    }

    @Test
    fun getUsername_notFound_returnsSuccessNull_likeDashj() {
        val source = FakeSource(
            resolveResult = { throw DashSdkError.InternalError("Name 'alice' not found") }
        )
        val queries = queries(source)

        val resource = queries.getUsernameOrNull("alice")

        assertNotNull(resource)
        assertEquals(Status.SUCCESS, resource!!.status)
        assertNull(resource.data)
    }

    @Test
    fun getUsername_sdkFailure_returnsNull_forDashjFallback() {
        val source = FakeSource(resolveResult = { throw DashSdkError.NetworkError("no quorum") })
        assertNull(queries(source).getUsernameOrNull("alice"))

        val malformed = FakeSource(resolveResult = { "garbage{" })
        assertNull(queries(malformed).getUsernameOrNull("alice"))
    }

    @Test
    fun search_prefix_mapsResults_andNormalizesInputs() = runBlocking {
        val source = FakeSource(
            searchResult = {
                """[{"label":"bob","normalizedLabel":"b0b","fullName":"bob.dash",
                     "ownerId":"$identityId","recordsIdentityId":"$identityId"}]"""
            }
        )
        val queries = queries(source)

        val documents = queries.searchDomainDocumentsOrNull("B0b", onlyExactUsername = false, limit = -1)

        assertNotNull(documents)
        assertEquals(1, documents!!.size)
        assertEquals("b0b", DomainDocument(documents[0]).normalizedLabel)
        assertEquals("b0b", source.searchedPrefix)
        // dashj's "-1 = default" becomes the SDK's "0 = default".
        assertEquals(0, source.searchedLimit)
    }

    @Test
    fun search_prefix_positiveLimitPassedThrough() = runBlocking {
        val source = FakeSource(searchResult = { "[]" })
        val queries = queries(source)

        val documents = queries.searchDomainDocumentsOrNull("bob", onlyExactUsername = false, limit = 3)

        assertNotNull(documents)
        assertTrue(documents!!.isEmpty())
        assertEquals(3, source.searchedLimit)
    }

    @Test
    fun search_exact_usesResolve_withDashjSemantics() = runBlocking {
        val source = FakeSource(resolveResult = { """{"identityId":"$identityId"}""" })
        val queries = queries(source)

        val documents = queries.searchDomainDocumentsOrNull("b0b", onlyExactUsername = true, limit = -1)

        assertNotNull(documents)
        assertEquals(1, documents!!.size)
        // Exact mode mirrors names.get: label = query text, normalized derived.
        assertEquals("b0b", DomainDocument(documents[0]).label)
        assertEquals("b0b", DomainDocument(documents[0]).normalizedLabel)
        assertEquals("b0b", source.resolvedName)
    }

    @Test
    fun search_exact_notFound_returnsEmptyList_likeDashj() = runBlocking {
        val source = FakeSource(
            resolveResult = { throw DashSdkError.InternalError("Name 'b0b' not found") }
        )

        val documents = queries(source).searchDomainDocumentsOrNull("b0b", onlyExactUsername = true, limit = -1)

        assertNotNull(documents)
        assertTrue(documents!!.isEmpty())
    }

    @Test
    fun search_sdkFailure_returnsNull_forDashjFallback() = runBlocking {
        val failing = FakeSource(searchResult = { throw DashSdkError.Timeout("dapi timeout") })
        assertNull(queries(failing).searchDomainDocumentsOrNull("bob", false, 100))

        val malformed = FakeSource(searchResult = { """{"not":"an array"}""" })
        assertNull(queries(malformed).searchDomainDocumentsOrNull("bob", false, 100))
    }

    // ── Phase 3e: domain documents by owner (names.getByOwnerId parity) ───

    @Test
    fun byOwner_mapsUsernamesPayload_andPassesExplicitLimit() = runBlocking {
        val source = FakeSource(
            usernamesResult = {
                """[{"label":"bob","normalizedLabel":"b0b","fullName":"bob.dash",
                     "ownerId":"$identityId","recordsIdentityId":"$identityId"}]"""
            }
        )

        val documents = queries(source).getDomainDocumentsByOwnerOrNull(identityId)

        assertNotNull(documents)
        assertEquals(1, documents!!.size)
        val domain = DomainDocument(documents[0])
        assertEquals("bob", domain.label)
        assertEquals(Identifier.from(identityId), domain.dashUniqueIdentityId)
        assertEquals(listOf(identityId), source.usernamesIds)
        // dashj queries run with the 100-document DAPI cap, not the FFI's
        // default of 10.
        assertEquals(100, source.usernamesLimit)
    }

    @Test
    fun byOwner_notFoundOrNoPayload_returnsEmptyList_likeDashj() = runBlocking {
        val notFound = FakeSource(
            usernamesResult = { throw DashSdkError.InternalError("Identity not found") }
        )
        val documents = queries(notFound).getDomainDocumentsByOwnerOrNull(identityId)
        assertNotNull(documents)
        assertTrue(documents!!.isEmpty())

        val noPayload = FakeSource(usernamesResult = { null })
        val empty = queries(noPayload).getDomainDocumentsByOwnerOrNull(identityId)
        assertNotNull(empty)
        assertTrue(empty!!.isEmpty())
    }

    @Test
    fun byOwner_sdkFailure_returnsNull_forDashjFallback() = runBlocking {
        val failing = FakeSource(usernamesResult = { throw DashSdkError.NetworkError("no quorum") })
        assertNull(queries(failing).getDomainDocumentsByOwnerOrNull(identityId))

        val malformed = FakeSource(usernamesResult = { """{"not":"an array"}""" })
        assertNull(queries(malformed).getDomainDocumentsByOwnerOrNull(identityId))

        val missingContract = FakeSource(usernamesResult = { "[]" })
        assertNull(
            queries(missingContract, contractId = null).getDomainDocumentsByOwnerOrNull(identityId)
        )
    }

    // ── Phase 3e: domain documents for id list (names.getList parity) ─────

    @Test
    fun forIdentities_queriesEachId_andAggregatesInOrder() = runBlocking {
        val source = FakeSource(
            usernamesResult = { id ->
                when (id) {
                    identityId ->
                        """[{"label":"alice","normalizedLabel":"a11ce","fullName":"alice.dash",
                             "ownerId":"$identityId"}]"""
                    otherIdentityId ->
                        """[{"label":"bob","normalizedLabel":"b0b","fullName":"bob.dash",
                             "ownerId":"$otherIdentityId"}]"""
                    else -> "[]"
                }
            }
        )

        val ids = listOf(Identifier.from(identityId), Identifier.from(otherIdentityId))
        val documents = queries(source).getDomainDocumentsForIdentitiesOrNull(ids)

        assertNotNull(documents)
        assertEquals(2, documents!!.size)
        assertEquals("a11ce", DomainDocument(documents[0]).normalizedLabel)
        assertEquals("b0b", DomainDocument(documents[1]).normalizedLabel)
        assertEquals(listOf(identityId, otherIdentityId), source.usernamesIds)
    }

    @Test
    fun forIdentities_idWithoutNames_contributesNothing() = runBlocking {
        val source = FakeSource(
            usernamesResult = { id ->
                if (id == identityId) {
                    """[{"label":"alice","normalizedLabel":"a11ce","fullName":"alice.dash",
                         "ownerId":"$identityId"}]"""
                } else {
                    throw DashSdkError.InternalError("Identity not found")
                }
            }
        )

        val ids = listOf(Identifier.from(identityId), Identifier.from(otherIdentityId))
        val documents = queries(source).getDomainDocumentsForIdentitiesOrNull(ids)

        assertNotNull(documents)
        assertEquals(1, documents!!.size)
        assertEquals("alice", DomainDocument(documents[0]).label)
    }

    @Test
    fun forIdentities_anyIdFailing_failsWholeBatch_forDashjFallback() = runBlocking {
        // Partial results would read as deleted usernames downstream, so one
        // bad id must invalidate the whole SDK batch.
        val failing = FakeSource(
            usernamesResult = { id ->
                if (id == identityId) "[]" else throw DashSdkError.Timeout("dapi timeout")
            }
        )
        val ids = listOf(Identifier.from(identityId), Identifier.from(otherIdentityId))
        assertNull(queries(failing).getDomainDocumentsForIdentitiesOrNull(ids))

        val malformed = FakeSource(
            usernamesResult = { id -> if (id == identityId) "[]" else """{"not":"an array"}""" }
        )
        assertNull(queries(malformed).getDomainDocumentsForIdentitiesOrNull(ids))
    }

    @Test
    fun forIdentities_emptyInput_returnsEmpty_withoutSdkCalls() = runBlocking {
        val source = FakeSource()
        val documents = queries(source).getDomainDocumentsForIdentitiesOrNull(emptyList())
        assertNotNull(documents)
        assertTrue(documents!!.isEmpty())
        assertEquals(0, source.calls)
    }
}
