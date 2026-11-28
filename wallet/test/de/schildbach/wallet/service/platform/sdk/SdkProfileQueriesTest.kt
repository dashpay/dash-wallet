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

import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.util.Converters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the Phase 3d profile read facade: the SDK-JSON →
 * dashj profile-`Document` mapping (consumed by
 * [DashPayProfile.fromDocument]) and the flag/fallback orchestration. No
 * native calls — the native query surface is faked via [SdkProfileSource].
 *
 * Fixture JSON mirrors the shape produced by the SDK's FFI layer
 * (`rs-sdk-ffi/src/document/queries/search.rs`): each document is dpp
 * `to_object()` output — `$id`/`$ownerId` base58, `$createdAt`/`$updatedAt`
 * millis, profile properties at the root with byte fields
 * (`avatarHash`/`avatarFingerprint`) base64-encoded. The FFI wraps the array
 * as `{"documents":[…],"total_count":N}`; the JNI docs promise a bare array —
 * both shapes are accepted.
 */
class SdkProfileQueriesTest {

    private val dashPayContractId = "Bwr4WHCPz5rFVAD87RqTs3izo4zpzwsEdKPWUT1NS1C7"
    private val identityId = "5DbLwAxGBzUzo81VewMUwn4b5P4bpv9FNFybi25XB5Bk"
    private val otherIdentityId = Identifier.from(ByteArray(32) { 2 }).toString()

    private val contract = SdkProfileMapping.minimalDashPayContract(Identifier.from(dashPayContractId))

    // 32-byte avatar hash / fingerprint as base64 (what dpp to_object emits).
    private val avatarHashBase64 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val fingerprintBase64 = java.util.Base64.getEncoder().encodeToString(ByteArray(8) { 9 })

    private fun profileJson(
        ownerId: String = identityId,
        displayName: String? = "Alice",
        withAvatarBytes: Boolean = true
    ): String {
        val avatar = if (withAvatarBytes) {
            ""","avatarHash":"$avatarHashBase64","avatarFingerprint":"$fingerprintBase64""""
        } else {
            ""
        }
        val display = displayName?.let { ""","displayName":"$it"""" } ?: ""
        return """
            {"${'$'}id":"$otherIdentityId","${'$'}ownerId":"$ownerId","${'$'}revision":2,
             "${'$'}createdAt":1700000000000,"${'$'}updatedAt":1700000001000
             $display,"publicMessage":"hi","avatarUrl":"https://x/y.png"$avatar}
        """.trimIndent()
    }

    // ── SdkProfileMapping: where clauses ─────────────────────────────────

    @Test
    fun whereClauses_matchDashjProfilesQueries() {
        val ids = listOf(Identifier.from(identityId), Identifier.from(otherIdentityId))
        assertEquals(
            """[{"field":"${'$'}ownerId","operator":"in","value":["$identityId","$otherIdentityId"]}]""",
            SdkProfileMapping.whereOwnerIdIn(ids)
        )
        assertEquals(
            """[{"field":"${'$'}ownerId","operator":"=","value":"$identityId"}]""",
            SdkProfileMapping.whereOwnerIdEquals(Identifier.from(identityId))
        )
        assertEquals(
            """[{"field":"${'$'}ownerId","ascending":true}]""",
            SdkProfileMapping.ORDER_BY_OWNER_ID
        )
    }

    // ── SdkProfileMapping: document mapping ──────────────────────────────

    @Test
    fun profileJson_bareArray_mapsToDocument_consumableByDashPayProfile() {
        val documents = SdkProfileMapping.profileDocumentsFromJson("[${profileJson()}]", contract)

        assertNotNull(documents)
        assertEquals(1, documents!!.size)

        val document = documents[0]
        assertEquals(Identifier.from(identityId), document.ownerId)
        assertEquals(Identifier.from(dashPayContractId), document.dataContractId)
        assertEquals("profile", document.type)
        assertEquals(1700000000000L, document.createdAt)
        assertEquals(1700000001000L, document.updatedAt)

        // The exact consumer on every routed path:
        val profile = DashPayProfile.fromDocument(document, "alice")
        assertEquals(identityId, profile.userId)
        assertEquals("alice", profile.username)
        assertEquals("Alice", profile.displayName)
        assertEquals("hi", profile.publicMessage)
        assertEquals("https://x/y.png", profile.avatarUrl)
        assertArrayEquals(ByteArray(32) { 7 }, profile.avatarHash)
        assertArrayEquals(ByteArray(8) { 9 }, profile.avatarFingerprint)
        assertEquals(1700000000000L, profile.createdAt)
        assertEquals(1700000001000L, profile.updatedAt)
    }

    @Test
    fun profileJson_wrappedObject_isAccepted() {
        val json = """{"documents":[${profileJson()}],"total_count":1}"""

        val documents = SdkProfileMapping.profileDocumentsFromJson(json, contract)

        assertNotNull(documents)
        assertEquals(1, documents!!.size)
        assertEquals(Identifier.from(identityId), documents[0].ownerId)
    }

    @Test
    fun profileJson_minimalFields_mapsToBlankishProfile() {
        // A profile with no optional fields at all (dashj parity: absent map
        // keys → defaults in DashPayProfile.fromDocument).
        val json = """[{"${'$'}ownerId":"$identityId"}]"""

        val documents = SdkProfileMapping.profileDocumentsFromJson(json, contract)!!
        assertEquals(1, documents.size)

        val profile = DashPayProfile.fromDocument(documents[0], "alice")
        assertEquals("", profile.displayName)
        assertEquals("", profile.publicMessage)
        assertEquals("", profile.avatarUrl)
        assertNull(profile.avatarHash)
        assertNull(profile.avatarFingerprint)
        assertEquals(0L, profile.createdAt)
        assertEquals(0L, profile.updatedAt)
    }

    @Test
    fun profileJson_dataNestedProperties_areRead() {
        val json = """
            [{"${'$'}ownerId":"$identityId",
              "data":{"displayName":"Nested","publicMessage":"m","avatarUrl":"u",
                      "avatarHash":"$avatarHashBase64"}}]
        """.trimIndent()

        val documents = SdkProfileMapping.profileDocumentsFromJson(json, contract)!!
        val profile = DashPayProfile.fromDocument(documents[0], "alice")

        assertEquals("Nested", profile.displayName)
        assertEquals("m", profile.publicMessage)
        assertEquals("u", profile.avatarUrl)
        assertArrayEquals(ByteArray(32) { 7 }, profile.avatarHash)
    }

    @Test
    fun profileJson_skipsEntriesWithoutValidOwnerId() {
        val json = """
            [
              ${profileJson()},
              {"displayName":"no-owner"},
              {"${'$'}ownerId":"###bad###","displayName":"bad-owner"},
              "not-an-object"
            ]
        """.trimIndent()

        val documents = SdkProfileMapping.profileDocumentsFromJson(json, contract)

        assertNotNull(documents)
        assertEquals(1, documents!!.size)
        assertEquals(Identifier.from(identityId), documents[0].ownerId)
    }

    @Test
    fun profileJson_malformed_returnsNull() {
        assertNull(SdkProfileMapping.profileDocumentsFromJson("garbage{", contract))
        assertNull(SdkProfileMapping.profileDocumentsFromJson(""""a string"""", contract))
        assertNull(SdkProfileMapping.profileDocumentsFromJson("""{"total_count":0}""", contract))
        assertNull(SdkProfileMapping.profileDocumentsFromJson("""{"documents":{"a":1}}""", contract))
    }

    @Test
    fun profileJson_emptyArray_returnsEmptyList() {
        assertTrue(SdkProfileMapping.profileDocumentsFromJson("[]", contract)!!.isEmpty())
        assertTrue(
            SdkProfileMapping.profileDocumentsFromJson(
                """{"documents":[],"total_count":0}""",
                contract
            )!!.isEmpty()
        )
    }

    @Test
    fun profileJson_documentId_roundTripsWhenPresent() {
        val documents = SdkProfileMapping.profileDocumentsFromJson("[${profileJson()}]", contract)!!
        assertEquals(Identifier.from(otherIdentityId), documents[0].id)
    }

    // ── Facade orchestration ─────────────────────────────────────────────

    private class FakeSource(
        var result: (whereJson: String) -> String? = { null }
    ) : SdkProfileSource {
        var calls = 0
        val whereJsons = mutableListOf<String>()
        val orderByJsons = mutableListOf<String?>()
        val limits = mutableListOf<Int>()

        override suspend fun searchProfiles(whereJson: String, orderByJson: String?, limit: Int): String? {
            calls++
            whereJsons.add(whereJson)
            orderByJsons.add(orderByJson)
            limits.add(limit)
            return result(whereJson)
        }
    }

    private fun config(enabled: Boolean): DashPayConfig = mockk {
        coEvery { get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) } returns enabled
    }

    private fun queries(
        source: FakeSource,
        enabled: Boolean = true,
        contractId: Identifier? = Identifier.from(dashPayContractId)
    ) = SdkProfileQueries(source, config(enabled), { contractId })

    @Test
    fun flagOff_returnsNull_andNeverTouchesSdk() = runBlocking {
        val source = FakeSource()
        val queries = queries(source, enabled = false)

        assertNull(queries.getProfileDocumentOrNull(identityId))
        assertNull(queries.getProfileDocumentsOrNull(listOf(Identifier.from(identityId))))
        assertEquals(0, source.calls)
    }

    @Test
    fun missingContractId_returnsNull_forDashjFallback() = runBlocking {
        val source = FakeSource(result = { "[${profileJson()}]" })
        val queries = queries(source, contractId = null)

        assertNull(queries.getProfileDocumentOrNull(identityId))
        assertNull(queries.getProfileDocumentsOrNull(listOf(Identifier.from(identityId))))
        assertEquals(0, source.calls)
    }

    @Test
    fun getProfile_found_returnsPresentOptional() = runBlocking {
        val source = FakeSource(result = { "[${profileJson()}]" })
        val queries = queries(source)

        val result = queries.getProfileDocumentOrNull(identityId)

        assertNotNull(result)
        assertTrue(result!!.isPresent)
        assertEquals(Identifier.from(identityId), result.get().ownerId)
        // dashj parity: where $ownerId == id, single result.
        assertEquals(
            """[{"field":"${'$'}ownerId","operator":"=","value":"$identityId"}]""",
            source.whereJsons.single()
        )
        assertNull(source.orderByJsons.single())
        assertEquals(1, source.limits.single())
    }

    @Test
    fun getProfile_noProfile_returnsEmptyOptional_likeDashjNull() = runBlocking {
        val emptyArray = FakeSource(result = { "[]" })
        val viaEmpty = queries(emptyArray).getProfileDocumentOrNull(identityId)
        assertNotNull(viaEmpty)
        assertFalse(viaEmpty!!.isPresent)

        val noPayload = FakeSource(result = { null })
        val viaNoData = queries(noPayload).getProfileDocumentOrNull(identityId)
        assertNotNull(viaNoData)
        assertFalse(viaNoData!!.isPresent)
    }

    @Test
    fun getProfile_sdkFailure_returnsNull_forDashjFallback() = runBlocking {
        val failing = FakeSource(result = { throw DashSdkError.NetworkError("no quorum") })
        assertNull(queries(failing).getProfileDocumentOrNull(identityId))

        val malformed = FakeSource(result = { "garbage{" })
        assertNull(queries(malformed).getProfileDocumentOrNull(identityId))
    }

    @Test
    fun getProfiles_mapsBatch_withDashjQueryParity() = runBlocking {
        val source = FakeSource(result = { "[${profileJson()}]" })
        val queries = queries(source)
        val ids = listOf(Identifier.from(identityId), Identifier.from(otherIdentityId))

        val documents = queries.getProfileDocumentsOrNull(ids)

        assertNotNull(documents)
        assertEquals(1, documents!!.size)
        assertEquals(SdkProfileMapping.whereOwnerIdIn(ids), source.whereJsons.single())
        assertEquals(SdkProfileMapping.ORDER_BY_OWNER_ID, source.orderByJsons.single())
        assertEquals(2, source.limits.single())
    }

    @Test
    fun getProfiles_emptyInput_returnsEmpty_withoutQuerying() = runBlocking {
        val source = FakeSource()
        val documents = queries(source).getProfileDocumentsOrNull(emptyList())

        assertNotNull(documents)
        assertTrue(documents!!.isEmpty())
        assertEquals(0, source.calls)
    }

    @Test
    fun getProfiles_chunksBatchesOf100_likeDashjGetList() = runBlocking {
        val ids = (0 until 150).map { i ->
            Identifier.from(ByteArray(32) { (i + 1).toByte() })
        }
        val source = FakeSource(result = { "[]" })

        val documents = queries(source).getProfileDocumentsOrNull(ids)

        assertNotNull(documents)
        assertEquals(2, source.calls)
        assertEquals(listOf(100, 50), source.limits)
        assertTrue(source.whereJsons[0].contains("\"${ids[0]}\""))
        assertTrue(source.whereJsons[1].contains("\"${ids[149]}\""))
        assertFalse(source.whereJsons[1].contains("\"${ids[0]}\""))
    }

    @Test
    fun getProfiles_sdkFailure_returnsNull_forDashjFallback() = runBlocking {
        val failing = FakeSource(result = { throw DashSdkError.Timeout("dapi timeout") })
        assertNull(queries(failing).getProfileDocumentsOrNull(listOf(Identifier.from(identityId))))

        val malformed = FakeSource(result = { """{"not":"documents"}""" })
        assertNull(queries(malformed).getProfileDocumentsOrNull(listOf(Identifier.from(identityId))))
    }

    @Test
    fun avatarBytes_decodeViaConvertersLikeProduction() {
        // Sanity-check the base64 pass-through contract this mapping relies
        // on: dashj's HashUtils/Converters decodes base64 strings.
        assertArrayEquals(ByteArray(32) { 7 }, Converters.fromBase64(avatarHashBase64))
    }
}
