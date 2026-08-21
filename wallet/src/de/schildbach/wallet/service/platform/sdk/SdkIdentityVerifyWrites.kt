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
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import org.dashj.platform.dpp.contract.DataContract
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.sdk.platform.Names
import org.dashj.platform.wallet.IdentityVerifyDocument
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam over the Kotlin SDK's GENERIC document-create surface
 * ([org.dashfoundation.dashsdk.documents.DocumentTransactions.create] via
 * [org.dashfoundation.dashsdk.wallet.PlatformWalletManager]), so the
 * flag/preflight/no-double-broadcast orchestration in
 * [SdkIdentityVerifyWrites] is host-JVM unit-testable — the real call needs
 * `libdash_sdk`.
 */
interface SdkIdentityVerifyWriteSource {
    /** Same contract as [SdkDashPayWriteSource.boundWalletIdOrNull]. */
    suspend fun boundWalletIdOrNull(): String?

    /** Same contract as [SdkDashPayWriteSource.isIdentityManaged]. */
    suspend fun isIdentityManaged(walletIdHex: String, identityId: ByteArray): Boolean

    /**
     * Create + broadcast one document of [documentType] on [contractId]
     * (32 bytes) owned by [ownerId] (32 bytes), carrying [propertiesJson],
     * signed Rust-side (the FFI selects an AUTHENTICATION + ECDSA key
     * satisfying the document type's security level from the wallet's
     * IdentityManager). Returns only on confirmed broadcast; throws
     * otherwise.
     */
    suspend fun createDocument(
        walletIdHex: String,
        ownerId: ByteArray,
        contractId: ByteArray,
        documentType: String,
        propertiesJson: String
    )
}

/** Production [SdkIdentityVerifyWriteSource]: boots the SDK on demand. */
internal class DashSdkIdentityVerifyWriteSource(
    private val service: DashSdkService
) : SdkIdentityVerifyWriteSource {

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

    override suspend fun createDocument(
        walletIdHex: String,
        ownerId: ByteArray,
        contractId: ByteArray,
        documentType: String,
        propertiesJson: String
    ) {
        val manager = manager()
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        // The returned confirmed-document JSON is not needed: the caller
        // rebuilds the (fully client-determined) document from its inputs.
        manager.documentTransactions.create(
            walletHandle = wallet.handle,
            ownerId = ownerId,
            contractId = contractId,
            documentType = documentType,
            propertiesJson = propertiesJson,
            signerHandle = manager.signerHandle
        )
    }
}

/**
 * Pure mapping for the `identity-verify.identityVerify` document — the
 * document the wallet publishes to attach a verification link (kept for
 * contested usernames) to its identity, and reads back to show other
 * contenders' links in the voting UI.
 *
 * Shape recovered from the legacy writer (dash-sdk-kotlin 4.0.0-RC2
 * `org.dashj.platform.wallet.IdentityVerify`, verified from bytecode):
 * - contract: the `identity-verify` app of dashj's `Platform.apps`
 *   (testnet `Bhptm3yBDhLkRNt7ofjpwaBHhMUKjDrQoPufKzQaxmpK`, mainnet
 *   `EVKMFboB3QBUa9Jo7PP5bsLyohzUz8zvw5c2gJs1SfcX` — resolved at runtime
 *   from the same `apps` map, never hardcoded here);
 * - document type: `identityVerify`;
 * - data (PLAINTEXT, no encryption/derivation): `normalizedLabel`
 *   (= `Names.normalizeString(username)`), `normalizedParentDomainName`
 *   (= `"dash"`), `url`; revision 1.
 *
 * Everything here is pure JVM (Gson + dashj model classes): no native
 * calls, no Android — covered by host unit tests.
 */
object SdkIdentityVerifyMapping {

    /** dashj `Platform.apps` key of the identity-verify contract. */
    const val APP_NAME = "identity-verify"

    const val DOCUMENT_TYPE = "identityVerify"

    /** Legacy `IdentityVerify.createForDashDomain` fixes the parent domain. */
    const val PARENT_DOMAIN = "dash"

    /**
     * The exact normalization the legacy writer applies to the username
     * (dashj `Names.normalizeString`: lowercase, `o`→`0`, `i`→`1`, `l`→`1`).
     * Delegated — not reimplemented — so it can never drift from what the
     * legacy path broadcast.
     */
    fun normalizedLabel(username: String): String = Names.normalizeString(username)

    /**
     * The rs-sdk-ffi `propertiesJson` for the create call — field-for-field
     * the data map the legacy `IdentityVerify.createDocument` passed to
     * `Documents.create`. Built with Gson so the caller-supplied [url] is
     * JSON-escaped.
     */
    fun propertiesJson(username: String, url: String): String {
        val properties = JsonObject()
        properties.addProperty("normalizedLabel", normalizedLabel(username))
        properties.addProperty("normalizedParentDomainName", PARENT_DOMAIN)
        properties.addProperty("url", url)
        return properties.toString()
    }

    /**
     * A minimal, locally-built identity-verify [DataContract] — just enough
     * for the non-null `Document.dataContract` field of a synthesized
     * document. Never sent anywhere (mirrors
     * [SdkProfileMapping.minimalDashPayContract]).
     */
    fun minimalContract(contractId: Identifier): DataContract = DataContract(
        hashMapOf<String, Any?>(
            "\$id" to contractId.toString(),
            "ownerId" to ByteArray(32),
            "protocolVersion" to 1,
            "version" to 1,
            "documents" to hashMapOf<String, Any?>(DOCUMENT_TYPE to hashMapOf<String, Any?>())
        )
    )

    /**
     * Rebuild the broadcast document as the legacy
     * [IdentityVerifyDocument] the routed caller
     * (`PlatformBroadcastService.broadcastIdentityVerify` →
     * `BroadcastIdentityVerifyWorker`, which reads only `normalizedLabel`
     * and `url`) returns. Every data field of an identityVerify document is
     * client-determined, so the rebuild equals what landed on Platform;
     * only the server-assigned system fields (`$id`, timestamps) are
     * synthetic placeholders, and no routed caller reads them.
     */
    fun syntheticDocument(
        contractId: Identifier,
        ownerId: Identifier,
        username: String,
        url: String
    ): IdentityVerifyDocument {
        val map = hashMapOf<String, Any?>(
            "\$id" to ByteArray(32),
            "\$type" to DOCUMENT_TYPE,
            "\$dataContractId" to contractId.toString(),
            "\$ownerId" to ownerId.toString(),
            "\$revision" to 1L,
            "normalizedLabel" to normalizedLabel(username),
            "normalizedParentDomainName" to PARENT_DOMAIN,
            "url" to url
        )
        return IdentityVerifyDocument(Document(map, minimalContract(contractId)))
    }
}

/**
 * Routes the wallet's identityVerify document WRITE — publishing the
 * verification link for a (contested) username — to the Dash Platform
 * Kotlin SDK's GENERIC document-create API, behind the SAME runtime flag as
 * the other Platform document writes,
 * [DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES] (default OFF; same trust
 * domain, no new flag).
 *
 * Outcome of evaluating dashpay/platform#4088 the light way: the generic
 * `DocumentTransactions.create(walletHandle, ownerId, contractId,
 * documentType, propertiesJson, signerHandle)` expresses this document
 * completely (plaintext fields, no SDK-side derivation), so NO dedicated
 * identityVerify SDK surface is needed.
 *
 * ## Contract (identical to [SdkDashPayWrites] — writes must not double-fire)
 *
 * Every write returns an [SdkWriteResult]:
 * - [SdkWriteResult.NotBroadcast] whenever the SDK path was not or could
 *   not have been used (flag off, SDK bootstrap failure, wallet not bound,
 *   identity not managed, contract id unavailable, malformed inputs, or a
 *   provably pre-broadcast validation error) — the call site then runs the
 *   legacy dashj path unchanged.
 * - [SdkWriteResult.Broadcast] on confirmed broadcast, carrying the rebuilt
 *   [IdentityVerifyDocument] — the call site skips the dashj broadcast.
 * - [SdkWriteResult.Ambiguous] when the failed attempt cannot be proven
 *   pre-broadcast ([classifyBroadcastFailure]) — the call site must throw
 *   and never auto-retry via dashj.
 *
 * ## Divergence from the legacy path (documented, accepted)
 *
 * Legacy `IdentityVerify.create` first queries for an existing document and
 * silently returns it instead of re-broadcasting. The SDK path skips that
 * pre-read (like [SdkDashPayWrites.sendContactRequest] does for its
 * duplicate case): re-publishing an existing link is rejected by Platform
 * and surfaces as an error, exactly as a duplicate contact request would.
 *
 * Routed call site:
 * - [de.schildbach.wallet.service.platform.PlatformDocumentBroadcastService.broadcastIdentityVerify]
 *   (reached via `BroadcastIdentityVerifyWorker`/`Operation` from the
 *   username-request verification UI).
 * NOT routed (stays legacy until the identity-creation flow migrates):
 * - `CreateIdentityService`'s inline `IdentityVerify.createForDashDomain`
 *   during initial contested-username registration.
 */
@Singleton
class SdkIdentityVerifyWrites internal constructor(
    private val source: SdkIdentityVerifyWriteSource,
    private val dashPayConfig: DashPayConfig,
    private val contractId: () -> Identifier?
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        dashPayConfig: DashPayConfig,
        platformService: PlatformService
    ) : this(
        source = DashSdkIdentityVerifyWriteSource(sdkService),
        dashPayConfig = dashPayConfig,
        contractId = {
            platformService.platform.apps[SdkIdentityVerifyMapping.APP_NAME]?.contractId
        }
    )

    /**
     * SDK-path replacement for the broadcast step of
     * `PlatformBroadcastService.broadcastIdentityVerify` (legacy:
     * `platform.identityVerify.createForDashDomain(username, url, identity,
     * signer)`). [ownUserId] is the base58 identity id; the caller has
     * already authenticated the user.
     */
    suspend fun createForDashDomain(
        ownUserId: String,
        username: String,
        url: String
    ): SdkWriteResult<IdentityVerifyDocument> {
        if (!isEnabled()) return SdkWriteResult.NotBroadcast("flag off")
        val ownId = identityBytesOrNull(ownUserId)
            ?: return SdkWriteResult.NotBroadcast("malformed own identity id")
        val contract = contractIdOrNull()
            ?: return notBroadcast("identity-verify contract id unavailable", null)

        // Preflight — nothing has been submitted if any of this fails.
        val walletId = try {
            source.boundWalletIdOrNull()
                ?: return notBroadcast("app wallet not bound to the SDK", null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("SDK bootstrap/bind lookup failed", t)
        }
        try {
            if (!source.isIdentityManaged(walletId, ownId)) {
                return notBroadcast("identity not managed by the SDK wallet", null)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("identity-managed preflight failed", t)
        }

        // The single broadcast attempt.
        return try {
            source.createDocument(
                walletIdHex = walletId,
                ownerId = ownId,
                contractId = contract.toBuffer(),
                documentType = SdkIdentityVerifyMapping.DOCUMENT_TYPE,
                propertiesJson = SdkIdentityVerifyMapping.propertiesJson(username, url)
            )
            SdkWriteResult.Broadcast(
                SdkIdentityVerifyMapping.syntheticDocument(
                    contractId = contract,
                    ownerId = Identifier.from(ownUserId),
                    username = username,
                    url = url
                )
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            when (val classified = classifyBroadcastFailure(t)) {
                is SdkWriteResult.NotBroadcast -> {
                    log.warn("SDK identityVerify create rejected pre-broadcast; falling back to dashj", t)
                    classified
                }
                is SdkWriteResult.Ambiguous -> {
                    log.error(
                        "SDK identityVerify create failed with an outcome that may already be " +
                            "broadcast; surfacing the error WITHOUT retrying via dashj",
                        t
                    )
                    classified
                }
                is SdkWriteResult.Broadcast -> classified // unreachable
            }
        }
    }

    private fun notBroadcast(reason: String, cause: Throwable?): SdkWriteResult.NotBroadcast {
        log.info("SDK identityVerify create not attempted ({}); using dashj", reason, cause)
        return SdkWriteResult.NotBroadcast(reason, cause)
    }

    private fun contractIdOrNull(): Identifier? = try {
        contractId()
    } catch (e: Exception) {
        log.warn("identity-verify contract id lookup failed; keeping dashj path", e)
        null
    }

    private fun identityBytesOrNull(base58: String): ByteArray? = try {
        Identifier.from(base58).toBuffer()
    } catch (e: Exception) {
        null
    }

    private suspend fun isEnabled(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES) == true
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_DASHPAY_WRITES; keeping dashj path", e)
        false
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkIdentityVerifyWrites::class.java)
    }
}
