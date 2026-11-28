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
import org.dashj.platform.sdk.platform.Names
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the identityVerify write facade — the
 * dashpay/platform#4088 "light way": the wallet's identityVerify document
 * built for and routed through the Kotlin SDK's GENERIC document-create
 * API. No native calls; the SDK surface is faked via
 * [SdkIdentityVerifyWriteSource].
 *
 * The mapping tests lock the broadcast JSON to the LEGACY document shape
 * (dash-sdk-kotlin 4.0.0-RC2 `IdentityVerify.createDocument`, verified from
 * bytecode): data = { normalizedLabel = Names.normalizeString(username),
 * normalizedParentDomainName = "dash", url } on the `identity-verify`
 * contract's `identityVerify` type, revision 1, no other fields.
 */
class SdkIdentityVerifyWritesTest {

    private val ownUserId = "5DbLwAxGBzUzo81VewMUwn4b5P4bpv9FNFybi25XB5Bk"

    // The testnet identity-verify contract id (only used as an opaque
    // 32-byte identifier fixture here; production resolves it at runtime
    // from dashj's Platform.apps).
    private val contractIdBase58 = "Bhptm3yBDhLkRNt7ofjpwaBHhMUKjDrQoPufKzQaxmpK"
    private val contractId = Identifier.from(contractIdBase58)
    private val walletId = "ab".repeat(32)
    private val username = "WilliamOslo"
    private val url = "https://example.com/proof?user=1"

    // ── Mapping: field-for-field equality with the legacy document ───────

    @Test
    fun normalizedLabel_matchesLegacyNamesNormalization() {
        // Legacy createForDashDomain normalizes via Names.normalizeString:
        // lowercase, o→0, i→1, l→1.
        assertEquals("he110", SdkIdentityVerifyMapping.normalizedLabel("HELLO"))
        assertEquals("0110", SdkIdentityVerifyMapping.normalizedLabel("Olio"))
        assertEquals("w1111am0s10", SdkIdentityVerifyMapping.normalizedLabel("WilliamOslo"))
        // Exact delegation — can never drift from the legacy writer.
        for (name in listOf("Alice-42", "b0b", "XyZ")) {
            assertEquals(Names.normalizeString(name), SdkIdentityVerifyMapping.normalizedLabel(name))
        }
    }

    @Test
    fun propertiesJson_equalsLegacyDocumentData_fieldForField() {
        val json = JsonParser.parseString(
            SdkIdentityVerifyMapping.propertiesJson(username, url)
        ).asJsonObject

        // Exactly the legacy data map: three fields, nothing else.
        assertEquals(setOf("normalizedLabel", "normalizedParentDomainName", "url"), json.keySet())
        assertEquals(Names.normalizeString(username), json.get("normalizedLabel").asString)
        assertEquals("dash", json.get("normalizedParentDomainName").asString)
        assertEquals(url, json.get("url").asString)
    }

    @Test
    fun propertiesJson_escapesUrl() {
        val trickyUrl = """https://example.com/a"b\c?d=e&f="g""""
        val json = JsonParser.parseString(
            SdkIdentityVerifyMapping.propertiesJson("alice", trickyUrl)
        ).asJsonObject
        assertEquals(trickyUrl, json.get("url").asString)
    }

    @Test
    fun syntheticDocument_matchesLegacyIdentityVerifyDocument() {
        val ownerId = Identifier.from(ownUserId)
        val document = SdkIdentityVerifyMapping.syntheticDocument(contractId, ownerId, username, url)

        // The legacy IdentityVerifyDocument getters, field for field.
        assertEquals(Names.normalizeString(username), document.normalizedLabel)
        assertEquals("dash", document.normalizedParentDomainName)
        assertEquals(url, document.url)
        // Legacy createDocument: identity-verify.identityVerify, revision 1,
        // owned by the identity.
        assertEquals("identityVerify", document.document.type)
        assertEquals(contractId, document.document.dataContractId)
        assertEquals(ownerId, document.document.ownerId)
        assertEquals(1L, document.document.revision)
    }

    // ── Orchestration (mirrors SdkDashPayWritesTest) ─────────────────────

    private class FakeSource(
        var boundWalletId: () -> String? = { null },
        var identityManaged: (String, ByteArray) -> Boolean = { _, _ -> false },
        var onCreateDocument: () -> Unit = {}
    ) : SdkIdentityVerifyWriteSource {
        var boundCalls = 0
        var managedCalls = 0
        var broadcastCalls = 0
        var lastWalletId: String? = null
        var lastOwnerId: ByteArray? = null
        var lastContractId: ByteArray? = null
        var lastDocumentType: String? = null
        var lastPropertiesJson: String? = null

        override suspend fun boundWalletIdOrNull(): String? {
            boundCalls++
            return boundWalletId()
        }

        override suspend fun isIdentityManaged(walletIdHex: String, identityId: ByteArray): Boolean {
            managedCalls++
            return identityManaged(walletIdHex, identityId)
        }

        override suspend fun createDocument(
            walletIdHex: String,
            ownerId: ByteArray,
            contractId: ByteArray,
            documentType: String,
            propertiesJson: String
        ) {
            broadcastCalls++
            lastWalletId = walletIdHex
            lastOwnerId = ownerId
            lastContractId = contractId
            lastDocumentType = documentType
            lastPropertiesJson = propertiesJson
            onCreateDocument()
        }
    }

    private fun config(enabled: Boolean?): DashPayConfig = mockk {
        if (enabled == null) {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES) } throws
                IllegalStateException("datastore unavailable")
        } else {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES) } returns enabled
        }
    }

    private fun readySource() = FakeSource(
        boundWalletId = { walletId },
        identityManaged = { _, _ -> true }
    )

    private fun writes(
        source: FakeSource,
        enabled: Boolean? = true,
        contract: Identifier? = contractId
    ) = SdkIdentityVerifyWrites(source, config(enabled)) { contract }

    @Test
    fun flagOff_isNotBroadcast_andNeverTouchesSdk() = runBlocking {
        val source = readySource()
        val result = writes(source, enabled = false).createForDashDomain(ownUserId, username, url)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.boundCalls + source.managedCalls + source.broadcastCalls)
    }

    @Test
    fun flagReadFailure_isNotBroadcast_andNeverTouchesSdk() = runBlocking {
        val source = readySource()
        val result = writes(source, enabled = null).createForDashDomain(ownUserId, username, url)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.boundCalls + source.managedCalls + source.broadcastCalls)
    }

    @Test
    fun malformedOwnIdentityId_isNotBroadcast_withoutSdkCalls() = runBlocking {
        val source = readySource()
        val result = writes(source).createForDashDomain("not-base58!!", username, url)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.boundCalls + source.managedCalls + source.broadcastCalls)
    }

    @Test
    fun missingContractId_isNotBroadcast_withoutSdkCalls() = runBlocking {
        val source = readySource()
        val result = writes(source, contract = null).createForDashDomain(ownUserId, username, url)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.boundCalls + source.managedCalls + source.broadcastCalls)
    }

    @Test
    fun contractIdLookupFailure_isNotBroadcast_withoutSdkCalls() = runBlocking {
        val source = readySource()
        val writes = SdkIdentityVerifyWrites(source, config(true)) {
            throw IllegalStateException("platform not initialized")
        }
        val result = writes.createForDashDomain(ownUserId, username, url)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.boundCalls + source.managedCalls + source.broadcastCalls)
    }

    @Test
    fun walletNotBound_isNotBroadcast_withoutBroadcastAttempt() = runBlocking {
        val source = FakeSource(boundWalletId = { null })
        val result = writes(source).createForDashDomain(ownUserId, username, url)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun boundLookupFailure_isNotBroadcast_withoutBroadcastAttempt() = runBlocking {
        val source = FakeSource(boundWalletId = { throw IllegalStateException("bootstrap failed") })
        val result = writes(source).createForDashDomain(ownUserId, username, url)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun identityNotManaged_isNotBroadcast_withoutBroadcastAttempt() = runBlocking {
        val source = FakeSource(
            boundWalletId = { walletId },
            identityManaged = { _, _ -> false }
        )
        val result = writes(source).createForDashDomain(ownUserId, username, url)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(1, source.managedCalls)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun identityManagedCheckFailure_isNotBroadcast_withoutBroadcastAttempt() = runBlocking {
        val source = FakeSource(
            boundWalletId = { walletId },
            identityManaged = { _, _ -> throw DashSdkError.InternalError("snapshot failed") }
        )
        val result = writes(source).createForDashDomain(ownUserId, username, url)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun success_isBroadcast_withLegacyEquivalentDocumentAndMappedArgs() = runBlocking {
        val source = readySource()
        val result = writes(source).createForDashDomain(ownUserId, username, url)

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(1, source.broadcastCalls)
        // Generic-API targeting: the identity-verify contract, the
        // identityVerify type, our identity as owner.
        assertEquals(walletId, source.lastWalletId)
        assertArrayEquals(Identifier.from(ownUserId).toBuffer(), source.lastOwnerId)
        assertArrayEquals(contractId.toBuffer(), source.lastContractId)
        assertEquals("identityVerify", source.lastDocumentType)
        assertEquals(
            SdkIdentityVerifyMapping.propertiesJson(username, url),
            source.lastPropertiesJson
        )
        // The Broadcast value is the legacy-shaped document the worker reads.
        val document = (result as SdkWriteResult.Broadcast).value
        assertEquals(Names.normalizeString(username), document.normalizedLabel)
        assertEquals(url, document.url)
    }

    @Test
    fun broadcastValidationFailure_isNotBroadcast_dashjFallbackSafe() = runBlocking {
        val source = readySource().apply {
            onCreateDocument = { throw DashSdkError.InvalidParameter("bad properties") }
        }
        val result = writes(source).createForDashDomain(ownUserId, username, url)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(1, source.broadcastCalls)
    }

    @Test
    fun broadcastNetworkFailure_isAmbiguous_noDashjFallback() = runBlocking {
        val source = readySource().apply {
            onCreateDocument = { throw DashSdkError.NetworkError("conn dropped mid-submit") }
        }
        val result = writes(source).createForDashDomain(ownUserId, username, url)

        assertTrue(result is SdkWriteResult.Ambiguous)
        assertEquals(1, source.broadcastCalls)
    }

    @Test
    fun everyOutcome_makesAtMostOneBroadcastAttempt() = runBlocking {
        val outcomes = listOf<() -> Unit>(
            { },
            { throw DashSdkError.InvalidParameter("x") },
            { throw DashSdkError.NetworkError("x") },
            { throw RuntimeException("x") }
        )
        for (outcome in outcomes) {
            val source = readySource().apply { onCreateDocument = outcome }
            writes(source).createForDashDomain(ownUserId, username, url)
            assertEquals(1, source.broadcastCalls)
        }
    }
}
