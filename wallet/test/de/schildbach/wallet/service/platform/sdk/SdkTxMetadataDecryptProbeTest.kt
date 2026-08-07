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

import com.google.protobuf.ByteString
import kotlinx.coroutines.test.runTest
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.wallet.WalletUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Host-JVM tests for the wire-compat decrypt-proof probe
 * ([SdkTxMetadataDecryptProbe]): the SDK fetch-payload → per-document
 * mapping (with REAL legacy protobuf `TxMetadataBatch` payloads), the
 * verdict mapping, the summary-line format, and the probe orchestration's
 * preflights + exception containment. The live fetch/decrypt is device-only
 * (`libdash_sdk`) — everything around it is pinned here.
 */
class SdkTxMetadataDecryptProbeTest {

    // ── Fixtures ──────────────────────────────────────────────────────

    private val ownerBase58 = Identifier.from(ByteArray(32) { 7 }).toString()
    private val contractId = Identifier.from(ByteArray(32) { 9 })
    private val docIdA = Identifier.from(ByteArray(32) { 1 }).toString()
    private val docIdB = Identifier.from(ByteArray(32) { 2 }).toString()

    /** A real legacy-scheme plaintext: a protobuf `TxMetadataBatch` of [items] items. */
    private fun batchPayload(items: Int): ByteArray {
        val batch = WalletUtils.TxMetadataBatch.newBuilder()
        repeat(items) { i ->
            batch.addItems(
                WalletUtils.TxMetadataItem.newBuilder()
                    .setTxId(ByteString.copyFrom(ByteArray(32) { i.toByte() }))
                    .setTimestamp(1_700_000_000_000L + i)
                    .setMemo("soak memo $i")
            )
        }
        return batch.build().toByteArray()
    }

    private fun docJson(id: String, payloadBase64: String?): String {
        val payload = payloadBase64?.let { "\"$it\"" } ?: "null"
        return """{"id":"$id","ownerId":"$ownerBase58","keyIndex":2,""" +
            """"encryptionKeyIndex":1,"version":1,"updatedAt":1700000000000,"payload":$payload}"""
    }

    private fun fetchJson(vararg docs: String) = "[${docs.joinToString(",")}]"

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    // ── The fetch-payload → per-document mapping ──────────────────────

    @Test
    fun docResults_realProtobufPayloads_decryptAndParseOk() {
        val results = SdkTxMetadataDecryptMapping.docResults(
            fetchJson(
                docJson(docIdA, base64(batchPayload(3))),
                docJson(docIdB, base64(batchPayload(1)))
            )
        )!!

        assertEquals(2, results.size)
        assertEquals(
            SdkTxMetadataDecryptMapping.DocProofResult(docIdA, decryptOk = true, parseOk = true, itemCount = 3),
            results[0]
        )
        assertEquals(
            SdkTxMetadataDecryptMapping.DocProofResult(docIdB, decryptOk = true, parseOk = true, itemCount = 1),
            results[1]
        )
    }

    @Test
    fun docResults_missingOrUndecodablePayload_isADecryptFailure() {
        val results = SdkTxMetadataDecryptMapping.docResults(
            fetchJson(
                docJson(docIdA, null), // payload absent
                docJson(docIdB, "!!!not-base64!!!")
            )
        )!!

        assertEquals(2, results.size)
        results.forEach {
            assertFalse(it.decryptOk)
            assertFalse(it.parseOk)
            assertEquals(0, it.itemCount)
        }
    }

    @Test
    fun docResults_nonProtobufPlaintext_isAParseFailure() {
        // 0xFF is not a valid protobuf tag — decodes from base64, fails parse.
        val results = SdkTxMetadataDecryptMapping.docResults(
            fetchJson(docJson(docIdA, base64(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))))
        )!!

        assertEquals(1, results.size)
        assertTrue(results[0].decryptOk)
        assertFalse(results[0].parseOk)
        assertEquals(0, results[0].itemCount)
    }

    @Test
    fun docResults_emptyArray_isEmpty_andMalformedRootsAreNull() {
        assertEquals(emptyList<SdkTxMetadataDecryptMapping.DocProofResult>(), SdkTxMetadataDecryptMapping.docResults("[]"))
        assertNull(SdkTxMetadataDecryptMapping.docResults("""{"documents":[]}"""))
        assertNull(SdkTxMetadataDecryptMapping.docResults("not json"))
    }

    // ── The verdict mapping ───────────────────────────────────────────

    @Test
    fun verdict_allLegacyDocsParsed_isProven() {
        assertEquals(DecryptProofVerdict.PROVEN, SdkTxMetadataDecryptMapping.verdict(sdkParsed = 3, legacyExpected = 3))
        assertEquals(DecryptProofVerdict.PROVEN, SdkTxMetadataDecryptMapping.verdict(sdkParsed = 4, legacyExpected = 3))
    }

    @Test
    fun verdict_someButNotAllOrUnknownLegacy_isPartial() {
        assertEquals(DecryptProofVerdict.PARTIAL, SdkTxMetadataDecryptMapping.verdict(sdkParsed = 2, legacyExpected = 3))
        assertEquals(DecryptProofVerdict.PARTIAL, SdkTxMetadataDecryptMapping.verdict(sdkParsed = 2, legacyExpected = null))
        assertEquals(DecryptProofVerdict.PARTIAL, SdkTxMetadataDecryptMapping.verdict(sdkParsed = 2, legacyExpected = 0))
    }

    @Test
    fun verdict_nothingParsed_isFailed_includingTheNothingToProveCase() {
        assertEquals(DecryptProofVerdict.FAILED, SdkTxMetadataDecryptMapping.verdict(sdkParsed = 0, legacyExpected = 3))
        assertEquals(DecryptProofVerdict.FAILED, SdkTxMetadataDecryptMapping.verdict(sdkParsed = 0, legacyExpected = 0))
        assertEquals(DecryptProofVerdict.FAILED, SdkTxMetadataDecryptMapping.verdict(sdkParsed = 0, legacyExpected = null))
    }

    // ── The log-line formats ──────────────────────────────────────────

    @Test
    fun summaryLine_carriesAllCounts_andTheVerdict() {
        assertEquals(
            "sdkFetched=3 sdkDecrypted=3 sdkParsed=3 legacyExpected=3 verdict=PROVEN",
            SdkTxMetadataDecryptMapping.summaryLine(sdkFetched = 3, sdkDecrypted = 3, sdkParsed = 3, legacyExpected = 3)
        )
        assertEquals(
            "sdkFetched=3 sdkDecrypted=2 sdkParsed=1 legacyExpected=3 verdict=PARTIAL",
            SdkTxMetadataDecryptMapping.summaryLine(sdkFetched = 3, sdkDecrypted = 2, sdkParsed = 1, legacyExpected = 3)
        )
        assertEquals(
            "sdkFetched=0 sdkDecrypted=0 sdkParsed=0 legacyExpected=unknown verdict=FAILED",
            SdkTxMetadataDecryptMapping.summaryLine(sdkFetched = 0, sdkDecrypted = 0, sdkParsed = 0, legacyExpected = null)
        )
    }

    @Test
    fun docLine_flagsDecryptAndParseOutcomes() {
        assertEquals(
            "doc ${docIdA.take(8)}… decrypt=ok parse=ok items=3",
            SdkTxMetadataDecryptMapping.docLine(
                SdkTxMetadataDecryptMapping.DocProofResult(docIdA, decryptOk = true, parseOk = true, itemCount = 3)
            )
        )
        assertEquals(
            "doc ${docIdB.take(8)}… decrypt=ok parse=FAIL items=0",
            SdkTxMetadataDecryptMapping.docLine(
                SdkTxMetadataDecryptMapping.DocProofResult(docIdB, decryptOk = true, parseOk = false, itemCount = 0)
            )
        )
    }

    // ── The probe orchestration ───────────────────────────────────────

    private class FakeSource(
        var walletId: String? = "wallet-id",
        var identityManaged: Boolean = true,
        var fetchResult: () -> String = { "[]" }
    ) : TxMetadataDecryptProbeSource {
        var fetchedSinceMs: Long? = null
        var fetchedDocumentType: String? = null
        var fetchedOwnerId: ByteArray? = null
        var fetchedContractId: ByteArray? = null

        override suspend fun boundWalletIdOrNull(): String? = walletId

        override suspend fun isIdentityManaged(walletIdHex: String, identityId: ByteArray) =
            identityManaged

        override suspend fun fetchEncryptedDocuments(
            walletIdHex: String,
            ownerId: ByteArray,
            contractId: ByteArray,
            documentType: String,
            sinceMs: Long
        ): String {
            fetchedOwnerId = ownerId
            fetchedContractId = contractId
            fetchedDocumentType = documentType
            fetchedSinceMs = sinceMs
            return fetchResult()
        }
    }

    private fun probe(
        source: FakeSource,
        identityId: String? = ownerBase58,
        contract: Identifier? = contractId,
        legacyCount: suspend (Identifier) -> Int = { 0 }
    ) = SdkTxMetadataDecryptProbe(
        source = source,
        ownIdentityId = { identityId },
        contractId = { contract },
        legacyDocumentCount = legacyCount
    )

    @Test
    fun runProof_allLegacyDocsDecryptAndParse_reportsProven() = runTest {
        val source = FakeSource(
            fetchResult = {
                fetchJson(
                    docJson(docIdA, base64(batchPayload(2))),
                    docJson(docIdB, base64(batchPayload(5)))
                )
            }
        )
        var countedOwner: Identifier? = null
        val status = probe(source, legacyCount = { owner -> countedOwner = owner; 2 }).runProof()

        assertEquals("sdkFetched=2 sdkDecrypted=2 sdkParsed=2 legacyExpected=2 verdict=PROVEN", status)
        // The fetch queried the legacy locator: own identity, wallet-utils
        // contract, txMetadata type, since the epoch.
        assertEquals(Identifier.from(ownerBase58), Identifier.from(source.fetchedOwnerId!!))
        assertEquals(contractId, Identifier.from(source.fetchedContractId!!))
        assertEquals("txMetadata", source.fetchedDocumentType)
        assertEquals(0L, source.fetchedSinceMs)
        assertEquals(Identifier.from(ownerBase58), countedOwner)
    }

    @Test
    fun runProof_someDocsFailToParse_reportsPartialCounts() = runTest {
        val source = FakeSource(
            fetchResult = {
                fetchJson(
                    docJson(docIdA, base64(batchPayload(1))),
                    docJson(docIdB, base64(byteArrayOf(0xFF.toByte()))) // decrypts, won't parse
                )
            }
        )
        val status = probe(source, legacyCount = { 2 }).runProof()

        assertEquals("sdkFetched=2 sdkDecrypted=2 sdkParsed=1 legacyExpected=2 verdict=PARTIAL", status)
    }

    @Test
    fun runProof_legacyFetchFailure_isContained_asUnknownExpectedCount() = runTest {
        val source = FakeSource(fetchResult = { fetchJson(docJson(docIdA, base64(batchPayload(1)))) })
        val status = probe(source, legacyCount = { error("DAPI unreachable") }).runProof()

        assertEquals("sdkFetched=1 sdkDecrypted=1 sdkParsed=1 legacyExpected=unknown verdict=PARTIAL", status)
    }

    @Test
    fun runProof_preflightFailures_nameTheMissingPrecondition_withoutFetching() = runTest {
        assertEquals(
            "not run: no platform identity on this wallet",
            probe(FakeSource(), identityId = null).runProof()
        )
        assertEquals(
            "not run: wallet-utils contract id unavailable",
            probe(FakeSource(), contract = null).runProof()
        )
        assertEquals(
            "not run: app wallet not bound to the SDK",
            probe(FakeSource(walletId = null)).runProof()
        )
        val unmanaged = FakeSource(identityManaged = false)
        assertEquals(
            "not run: identity not managed by the SDK wallet",
            probe(unmanaged).runProof()
        )
        assertNull(unmanaged.fetchedSinceMs) // no preflight failure reaches the fetch
    }

    @Test
    fun runProof_fetchFailureAndMalformedPayload_areContained() = runTest {
        assertEquals(
            "failed: SDK fetch failed",
            probe(FakeSource(fetchResult = { error("FFI error") })).runProof()
        )
        assertEquals(
            "failed: SDK fetch returned a malformed payload",
            probe(FakeSource(fetchResult = { """{"documents":[]}""" })).runProof()
        )
    }
}
