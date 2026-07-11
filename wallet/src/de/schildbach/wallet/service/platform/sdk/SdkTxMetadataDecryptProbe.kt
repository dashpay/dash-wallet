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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.platform.PlatformService
import kotlinx.coroutines.CancellationException
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.wallet.TxMetadata
import org.dashj.platform.wallet.TxMetadataItem
import org.dashj.platform.wallet.WalletUtils
import org.slf4j.LoggerFactory
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam over the Kotlin SDK's encrypted-document fetch
 * ([org.dashfoundation.dashsdk.documents.DocumentTransactions.fetchEncryptedDocuments]
 * via [org.dashfoundation.dashsdk.wallet.PlatformWalletManager]), so the
 * orchestration/verdict logic in [SdkTxMetadataDecryptProbe] is host-JVM
 * unit-testable with fake JSON — the real call needs `libdash_sdk`.
 */
interface TxMetadataDecryptProbeSource {
    /** Same contract as [SdkDashPayWriteSource.boundWalletIdOrNull]. */
    suspend fun boundWalletIdOrNull(): String?

    /** Same contract as [SdkDashPayWriteSource.isIdentityManaged]. */
    suspend fun isIdentityManaged(walletIdHex: String, identityId: ByteArray): Boolean

    /**
     * Fetch + decrypt every encrypted [documentType] document on
     * [contractId] (32 bytes) owned by [ownerId] (32 bytes) updated at or
     * after [sinceMs]. Returns the SDK's JSON array (each element:
     * `{id, ownerId, keyIndex, encryptionKeyIndex, version, updatedAt,
     * payload}` with `payload` = base64 of the DECRYPTED plaintext;
     * documents that fail to decrypt are skipped Rust-side).
     */
    suspend fun fetchEncryptedDocuments(
        walletIdHex: String,
        ownerId: ByteArray,
        contractId: ByteArray,
        documentType: String,
        sinceMs: Long
    ): String
}

/** Production [TxMetadataDecryptProbeSource]: boots the SDK on demand. */
internal class DashSdkTxMetadataDecryptSource(
    private val service: DashSdkService
) : TxMetadataDecryptProbeSource {

    private suspend fun manager(): org.dashfoundation.dashsdk.wallet.PlatformWalletManager {
        service.ensureStarted()
        return checkNotNull(service.walletManagerOrNull()) {
            "SDK wallet manager missing after ensureStarted()"
        }
    }

    override suspend fun boundWalletIdOrNull(): String? =
        manager().wallets.value.keys.singleOrNull()

    override suspend fun isIdentityManaged(walletIdHex: String, identityId: ByteArray): Boolean {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        return wallet.dashpay.syncState(identityId) != null
    }

    override suspend fun fetchEncryptedDocuments(
        walletIdHex: String,
        ownerId: ByteArray,
        contractId: ByteArray,
        documentType: String,
        sinceMs: Long
    ): String {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        return manager.documentTransactions.fetchEncryptedDocuments(
            walletHandle = wallet.handle,
            ownerId = ownerId,
            contractId = contractId,
            documentType = documentType,
            sinceMs = sinceMs
        )
    }
}

/** Verdict of one wire-compat decrypt-proof run ([SdkTxMetadataDecryptProbe]). */
enum class DecryptProofVerdict { PROVEN, PARTIAL, FAILED }

/**
 * Pure mapping for the wire-compat decrypt proof: the SDK's
 * `fetchEncryptedDocuments` JSON → per-document decrypt/parse outcomes,
 * plus the verdict/summary formatting the probe logs and the debug
 * settings item shows. Pure JVM (Gson + the legacy dashj-platform protobuf
 * classes) — covered by host unit tests.
 */
object SdkTxMetadataDecryptMapping {

    /** dashj `Platform.apps` key of the wallet-utils contract. */
    const val APP_NAME = "wallet-utils"

    /**
     * The legacy document type (dashj `TxMetadata` queries the
     * `wallet-utils.txMetadata` type locator — verified from
     * dash-sdk-kotlin 4.0.0-RC2 bytecode).
     */
    const val DOCUMENT_TYPE = "txMetadata"

    /** One fetched document's decrypt-proof outcome. */
    data class DocProofResult(
        val docId: String,
        /** The SDK returned a base64-decodable decrypted payload. */
        val decryptOk: Boolean,
        /** The plaintext parsed as the legacy protobuf `TxMetadataBatch`. */
        val parseOk: Boolean,
        /** Parsed [TxMetadataItem] count; 0 unless [parseOk]. */
        val itemCount: Int
    )

    /**
     * Parse the SDK fetch payload into per-document outcomes, or null when
     * the root is not a JSON array (malformed payload — the probe reports
     * it instead of guessing). Elements that are not objects are counted
     * as fetched-but-failed rather than dropped, so the summary counts
     * never overstate success.
     */
    fun docResults(fetchJson: String): List<DocProofResult>? {
        val root = try {
            JsonParser.parseString(fetchJson)
        } catch (e: Exception) {
            return null
        }
        if (!root.isJsonArray) return null
        return root.asJsonArray.map { element ->
            val obj = element as? JsonObject
                ?: return@map DocProofResult("?", decryptOk = false, parseOk = false, itemCount = 0)
            val docId = (obj.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.asString ?: "?"
            val payload = decodedPayloadOrNull(obj)
                ?: return@map DocProofResult(docId, decryptOk = false, parseOk = false, itemCount = 0)
            val itemCount = parsedItemCountOrNull(payload)
                ?: return@map DocProofResult(docId, decryptOk = true, parseOk = false, itemCount = 0)
            DocProofResult(docId, decryptOk = true, parseOk = true, itemCount = itemCount)
        }
    }

    /**
     * Map the run's counts to the proof verdict:
     * - [DecryptProofVerdict.PROVEN] — the legacy path knows documents
     *   exist (`legacyExpected > 0`) and the SDK fetched+decrypted+parsed
     *   ALL of them;
     * - [DecryptProofVerdict.PARTIAL] — the SDK proved at least one
     *   document but the legacy count is higher or unknown;
     * - [DecryptProofVerdict.FAILED] — the SDK proved nothing (including
     *   the nothing-to-prove case of zero legacy documents).
     */
    fun verdict(sdkParsed: Int, legacyExpected: Int?): DecryptProofVerdict = when {
        legacyExpected != null && legacyExpected > 0 && sdkParsed >= legacyExpected ->
            DecryptProofVerdict.PROVEN
        sdkParsed > 0 -> DecryptProofVerdict.PARTIAL
        else -> DecryptProofVerdict.FAILED
    }

    /** The one-line run summary (logged AND shown as the item subtitle). */
    fun summaryLine(sdkFetched: Int, sdkDecrypted: Int, sdkParsed: Int, legacyExpected: Int?): String =
        "sdkFetched=$sdkFetched sdkDecrypted=$sdkDecrypted sdkParsed=$sdkParsed " +
            "legacyExpected=${legacyExpected ?: "unknown"} " +
            "verdict=${verdict(sdkParsed, legacyExpected)}"

    /** The per-document log line. */
    fun docLine(result: DocProofResult): String =
        "doc ${result.docId.take(8)}… " +
            "decrypt=${if (result.decryptOk) "ok" else "FAIL"} " +
            "parse=${if (result.parseOk) "ok" else "FAIL"} " +
            "items=${result.itemCount}"

    private fun decodedPayloadOrNull(obj: JsonObject): ByteArray? {
        val payload = (obj.get("payload") as? JsonPrimitive)?.takeIf { it.isString }?.asString
            ?: return null
        return try {
            Base64.getDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Parse the decrypted plaintext exactly the way the legacy pipeline
     * does (`TxMetadataDocument.decrypt`'s protobuf branch — the version-1
     * scheme this wallet writes): `WalletUtils.TxMetadataBatch` protobuf,
     * each entry mapped to the [TxMetadataItem] model. Null when it is not
     * a parseable batch.
     */
    private fun parsedItemCountOrNull(payload: ByteArray): Int? = try {
        WalletUtils.TxMetadataBatch.parseFrom(payload).itemsList.map { TxMetadataItem(it) }.size
    } catch (e: Exception) {
        null
    }
}

/**
 * Debug-only wire-compat decrypt proof for the encrypted `txMetadata`
 * pipeline (dashpay/platform#4091): the new Kotlin SDK's
 * `fetchEncryptedDocuments` claims wire compatibility with the legacy
 * `org.dashj.platform` `publishTxMetaData` scheme, but the HD
 * derivation-path prefix of the decryption key could not be pinned from
 * static analysis. This wallet's identity has REAL legacy-written
 * encrypted documents on testnet — if the SDK fetches AND decrypts AND
 * parses them, wire compat is proven end to end.
 *
 * Read-only by construction: one SDK fetch (decrypt happens Rust-side) and
 * one legacy fetch of the same owner's documents for the expected count
 * (`TxMetadata(platform).get(ownerId, 0)` — the exact query
 * `BlockchainIdentity.getTxMetaData` runs, without needing the wallet
 * encryption key since the count doesn't decrypt anything). No documents
 * are created and no wallet state is touched; every failure is contained
 * into the returned status line. Logged under `TxMetaDecryptProof`.
 *
 * Only caller: the debug settings item
 * ([de.schildbach.wallet.ui.more.SettingsViewModel.runTxMetadataDecryptProof]).
 */
@Singleton
class SdkTxMetadataDecryptProbe internal constructor(
    private val source: TxMetadataDecryptProbeSource,
    private val ownIdentityId: suspend () -> String?,
    private val contractId: () -> Identifier?,
    private val legacyDocumentCount: suspend (Identifier) -> Int
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        blockchainIdentityConfig: BlockchainIdentityConfig,
        platformService: PlatformService
    ) : this(
        source = DashSdkTxMetadataDecryptSource(sdkService),
        ownIdentityId = { blockchainIdentityConfig.get(BlockchainIdentityConfig.IDENTITY_ID) },
        contractId = {
            platformService.platform.apps[SdkTxMetadataDecryptMapping.APP_NAME]?.contractId
        },
        legacyDocumentCount = { ownerId ->
            TxMetadata(platformService.platform).get(ownerId, 0).size
        }
    )

    /**
     * Run the proof and return the status line for the settings item
     * subtitle (the summary line on a completed run, a `not run:`/`failed:`
     * line otherwise). Never throws (cancellation excepted) and never
     * mutates any state.
     */
    suspend fun runProof(): String {
        val ownUserId = try {
            ownIdentityId() ?: return notRun("no platform identity on this wallet", null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notRun("identity id lookup failed", t)
        }
        val ownerId = try {
            Identifier.from(ownUserId)
        } catch (e: Exception) {
            return notRun("malformed own identity id", e)
        }
        val contract = try {
            contractId() ?: return notRun("wallet-utils contract id unavailable", null)
        } catch (e: Exception) {
            return notRun("wallet-utils contract id lookup failed", e)
        }

        // Preflights — mirror the SdkDashPayWrites binder pattern so an
        // on-device failure names the exact missing precondition.
        val walletId = try {
            source.boundWalletIdOrNull()
                ?: return notRun("app wallet not bound to the SDK", null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notRun("SDK bootstrap/bind lookup failed", t)
        }
        try {
            if (!source.isIdentityManaged(walletId, ownerId.toBuffer())) {
                return notRun("identity not managed by the SDK wallet", null)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notRun("identity-managed preflight failed", t)
        }

        // The SDK fetch+decrypt under proof.
        val docs = try {
            val fetchJson = source.fetchEncryptedDocuments(
                walletIdHex = walletId,
                ownerId = ownerId.toBuffer(),
                contractId = contract.toBuffer(),
                documentType = SdkTxMetadataDecryptMapping.DOCUMENT_TYPE,
                sinceMs = 0
            )
            SdkTxMetadataDecryptMapping.docResults(fetchJson)
                ?: return failed("SDK fetch returned a malformed payload", null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return failed("SDK fetch failed", t)
        }
        docs.forEach { log.info(SdkTxMetadataDecryptMapping.docLine(it)) }

        // The legacy expected count — contained: an unreachable legacy path
        // downgrades the verdict to PARTIAL/FAILED instead of aborting.
        val legacyExpected = try {
            legacyDocumentCount(ownerId)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("legacy txMetadata fetch failed; expected count unknown", t)
            null
        }

        val summary = SdkTxMetadataDecryptMapping.summaryLine(
            sdkFetched = docs.size,
            sdkDecrypted = docs.count { it.decryptOk },
            sdkParsed = docs.count { it.parseOk },
            legacyExpected = legacyExpected
        )
        log.info(summary)
        return summary
    }

    private fun notRun(reason: String, cause: Throwable?): String {
        log.info("decrypt proof not run ({})", reason, cause)
        return "not run: $reason"
    }

    private fun failed(reason: String, cause: Throwable?): String {
        log.error("decrypt proof failed ({})", reason, cause)
        return "failed: $reason"
    }

    companion object {
        /** Distinctive tag: `adb logcat`-greppable proof trail. */
        private val log = LoggerFactory.getLogger("TxMetaDecryptProof")
    }
}
