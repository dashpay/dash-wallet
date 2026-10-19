/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.more.connections.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Base58
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.dashj.platform.dapiclient.model.DocumentQuery
import org.dashj.platform.dashpay.callback.WalletSignerCallback
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.*
import org.dashj.platform.sdk.BlockHeight
import org.dashj.platform.sdk.CoreBlockHeight
import org.dashj.platform.sdk.KeyID
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import org.dashj.platform.sdk.callbacks.Signer
import org.dashj.platform.sdk.client.ClientAppDefinition
import org.dashj.platform.sdk.dashsdk
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.text.DateFormat
import java.util.Date

/** One-QR, PV14 grant lifecycle. The mutex covers approval, recovery and revocation. */
internal class AppConnectSession(
    private val service: PlatformService,
    private val walletRepo: PlatformRepo,
    private val identities: IdentityRepository,
    private val config: DashConnectConfig
) {
    companion object {
        const val RESPONSE_CONTRACT = "H8F9mP1BM55TE1ShsxPZHzhyinaMdY9bMmP85mkDhcJJ"
        private const val APP = "app-connect-system"
        private const val TYPE = "loginKeyResponse"
        private const val LOCATOR = "$APP.$TYPE"
        private const val DASHPAY = "Bwr4WHCPz5rFVAD87RqTs3izo4zpzwsEdKPWUT1NS1C7"
        private val SYSTEM_CONTRACTS = setOf(RESPONSE_CONTRACT, DASHPAY,
            "4fJLR2GYTPFdomuTVvNy3VRrvWgvkKPzqehEBpNf2nk6", "rUnsWrFu3PKyRMGk2mxmZVBPbQuZx2qtHeFjURoQevX",
            "GWRSAVFMjXx8HpQFaNJMqBV7MBgMK4br5UESsB4S31Ec", "7CSFGeF4WNzgDmx94zwvHkYaG3Dx4XEe5LFsFgJswLbm",
            "43gujrzZgXqcKBiScLa4T8XTDnRhenR9BLx8GWVHjPxF", "BsjE6tQxG47wffZCRQCovFx5rYrAYYC3rTVRWKro27LA",
            "6voHRaoiPcfmMhbqCA9dixH98xcgPQ9UEcuaXjpVu3LD", "EG7RGfV8fDTayC2FyVr8HwdpJh3fXDbVztcfE94UmN88")
    }
    private val mutex = Mutex()
    private val platform get() = service.platform

    fun scope(): String {
        val wallet = walletRepo.walletApplication.wallet ?: error("Wallet unavailable")
        val network = Constants.NETWORK_PARAMETERS
        val devnet = if (network.id == NetworkParameters.ID_DEVNET) network.devNetName else ""
        return "${network.id}:$devnet:${Sha256Hash.of(wallet.watchingKey.pubKey)}:${identityId()}"
    }

    private fun identityId() = identities.blockchainIdentity?.uniqueIdentifier ?: error("Identity unavailable")
    private fun checkScope(expected: String) = check(scope() == expected) { "Wallet, identity or network changed. Scan again." }
    private fun readIdentity(): Identity = platform.client.getIdentity(identityId().toBuffer(), true) ?: error("Identity not found")
    private fun requestHash(request: DashKeyRequest) = Base58.encode(KeyExchangeCrypto.hash256(
        request.appConnect?.bytes ?: (request.appEphemeralPubKey + request.contractId)
    ))

    suspend fun prepare(request: DashKeyRequest): AppConnectPlan = withContext(Dispatchers.IO) {
        check(Constants.NETWORK_PARAMETERS.id != NetworkParameters.ID_MAINNET) { "App Connect is unavailable on mainnet" }
        check(platform.client.refreshProtocolVersion() >= 14) { "App Connect requires Platform protocol version 14" }
        val scope = scope()
        val identity = readIdentity()
        val blockTime = platform.client.getPlatformTime(identity.id)
        val parsed = request.appConnect
        parsed?.requireFresh(System.currentTimeMillis())
        parsed?.identityHint?.let { check(it.contentEquals(identity.id.toBuffer())) { "QR requests a different identity" } }
        var grants = (parsed?.auth ?: listOf(AuthBinding(0, 2, request.contractId,
            BigInteger("5000000000"), null))).map { grant ->
            AppConnectLimits.clamp(grant, blockTime)
        }
        // A retry must show and use the original approved limits, not a new rolling expiry.
        val prior = config.getGrants().firstOrNull { it.getString("id") == "$scope:${requestHash(request)}" }
        if (prior != null) {
            val saved = decodeKeys(prior).filter { it.purpose == Purpose.AUTHENTICATION }
            val indices = indices(prior, "authIndices")
            check(saved.size == indices.size) { "Stored grant does not match request" }
            grants = grants.mapIndexed { index, grant ->
                val position = indices.sorted().indexOf(index)
                if (position < 0) grant else grant.copy(totalBudget = saved[position].totalBudget, expiresAt = saved[position].expiresAt)
            }
        }
        validateOwnership(request, grants)
        checkScope(scope)
        val text = buildString {
            append("App ID: ${Base58.encode(request.contractId)}\nQR label is not verified branding.\n\n")
            parsed?.callback?.let { append("Return address: ${it.host} (not opened automatically).\n") }
            parsed?.let { append("Requested ${maxOf(0, (System.currentTimeMillis() - it.createdAt) / 1000)} seconds ago.\n") }
            append("Only these application keys are shared. Recovery phrase and master key are never shared. The app cannot transfer your credits, register names, or change identity keys.")
        }
        val encChoices = parsed?.encryption.orEmpty().map {
            "${if (it.purpose == 1) "Encryption" else "Decryption"} key for this app (shared across sessions; omitted if another wallet controls it)."
        }
        AppConnectPlan(requestHash(request), scope, identity.id.toString(), grants, text,
            prior?.let { indices(it, "authIndices") } ?: grants.indices.toSet(), encChoices,
            prior?.let { indices(it, "encIndices") } ?: encChoices.indices.toSet(), prior != null, blockTime)
    }

    private fun validateOwnership(request: DashKeyRequest, grants: List<AuthBinding>) {
        val appId = Identifier.from(request.contractId)
        check(appId.toString() !in SYSTEM_CONTRACTS) { "System contracts cannot be apps" }
        val app = platform.client.getDataContract(request.contractId, true) ?: error("App contract not found")
        check(app.ownerId.toBuffer().any { it != 0.toByte() }) { "System contracts cannot be apps" }
        grants.forEach { grant ->
            val owner = if (grant.kind == 2) platform.client.getContractGroupOwner(Identifier.from(grant.id))
            else platform.client.getDataContract(grant.id, true)?.ownerId
            val profileException = grant.kind == 1 && Base58.encode(grant.id) == DASHPAY && grant.documentType == "profile"
            check(owner == app.ownerId || profileException) { "A requested bound target is not owned by the app" }
        }
        request.appConnect?.encryption?.forEach {
            check(it.contractId.contentEquals(request.contractId)) { "Encryption must be bound to the app contract" }
            check(if (it.purpose == 1) app.requiresIdentityEncryptionBoundedKey else app.requiresIdentityDecryptionBoundedKey) {
                "App contract has not opted into this encryption key"
            }
        }
    }

    suspend fun approve(request: DashKeyRequest, plan: AppConnectPlan): DAppConnection = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkScope(plan.scope)
            check(plan.requestHash == requestHash(request)) { "Approval does not match QR" }
            plan.requireMatches(request)
            check(0 in plan.selectedAuth && plan.selectedAuth.all { it in plan.grants.indices }) { "Primary grant is required" }
            check(plan.selectedEncryption.all { it in request.appConnect?.encryption.orEmpty().indices })
            request.appConnect?.requireFresh(System.currentTimeMillis())
            check(platform.client.refreshProtocolVersion() >= 14)
            validateOwnership(request, plan.grants)
            val wallet = walletRepo.walletApplication.wallet ?: error("Wallet unavailable")
            val keyParameter = walletRepo.getWalletEncryptionKey()
            checkScope(plan.scope)
            request.appConnect?.requireFresh(System.currentTimeMillis())
            val seed = walletRepo.getWalletSeed()?.seedBytes?.copyOf() ?: error("Wallet seed unavailable")
            val privateKeys = mutableListOf<ByteArray>()
            var ephemeral: ByteArray? = null
            try {
                var identity = readIdentity()
                check(identity.id.toString() == plan.identityId) { "Identity changed. Scan again." }
                val time = platform.client.getPlatformTime(identity.id)
                check(plan.grants.all { it.expiresAt!! > time + 3_600_000 }) { "Grant expired. Scan again." }
                val recordId = "${plan.scope}:${plan.requestHash}"
                var record = config.getGrants().firstOrNull { it.getString("id") == recordId }
                check(record?.optString("state") != "disabled") { "This QR has already been revoked. Scan a new one." }
                val keys = mutableListOf<IdentityPublicKey>()
                var nextId = (identity.publicKeys.maxOfOrNull { it.id } ?: -1) + 1
                plan.grants.forEachIndexed { index, grant ->
                    if (index !in plan.selectedAuth) return@forEachIndexed
                    val leaf = KeyExchangeCrypto.sessionRequestLeaf(request.appEphemeralPubKey,
                        if (request.appConnect == null) null else index)
                    val secret = ApplicationKeyDerivation.sessionAuthentication(seed, identity.id.toBuffer(), leaf, false)
                    privateKeys.add(secret)
                    val key = IdentityPublicKey(nextId++, KeyType.ECDSA_HASH160, Purpose.AUTHENTICATION,
                        if (grant.securityLevel == 1) SecurityLevel.CRITICAL else SecurityLevel.HIGH,
                        bounds(grant), KeyExchangeCrypto.hash160(KeyExchangeCrypto.compressedPublicKey(secret)), false,
                        totalBudget = grant.totalBudget, expiresAt = grant.expiresAt, version = 1)
                    keys.add(key)
                }
                request.appConnect?.encryption?.forEachIndexed { index, binding ->
                    if (index !in plan.selectedEncryption) return@forEachIndexed
                    val purpose = if (binding.purpose == 1) Purpose.ENCRYPTION else Purpose.DECRYPTION
                    val secret = ApplicationKeyDerivation.encryption(seed, identity.id.toBuffer(), binding.contractId, purpose, false)
                    val data = KeyExchangeCrypto.compressedPublicKey(secret)
                    val candidates = identity.publicKeys.filter { it.purpose == purpose && it.contractBounds is SingleContractBounds &&
                        it.contractBounds!!.identifier == Identifier.from(binding.contractId) }
                    val newest = candidates.filter { live(it, time) }.maxByOrNull { it.id }
                    val key = when {
                        newest != null && newest.data.contentEquals(data) && newest.type == KeyType.ECDSA_SECP256K1 &&
                            newest.securityLevel == SecurityLevel.MEDIUM && newest.totalBudget == null && newest.expiresAt == null -> newest
                        newest == null && candidates.none { it.data.contentEquals(data) } -> IdentityPublicKey(nextId++,
                            KeyType.ECDSA_SECP256K1, purpose, SecurityLevel.MEDIUM, SingleContractBounds(Identifier.from(binding.contractId)), data, false)
                        else -> null
                    }
                    if (key == null) KeyExchangeCrypto.wipe(secret) else { keys.add(key); privateKeys.add(secret) }
                }
                if (record != null) {
                    val saved = decodeKeys(record)
                    check(saved.size == keys.size && saved.zip(keys).all { (a,b) -> sameKey(a,b, false) }) {
                        "Previous attempt has different grants. Disconnect it before trying again."
                    }
                    keys.clear(); keys.addAll(saved)
                } else {
                    check(keys.filter { it.purpose == Purpose.AUTHENTICATION }.none { added ->
                        identity.publicKeys.any { it.data.contentEquals(added.data) }
                    }) { "This ephemeral request key was already used. Scan a new QR." }
                    val eph = ECKey()
                    ephemeral = eph.privKeyBytes
                    val payload = if (request.appConnect != null) KeyExchangeCrypto.encryptAppConnect(privateKeys,
                        ephemeral!!, request.appEphemeralPubKey, identity.id.toBuffer())
                    else KeyExchangeCrypto.encryptLoginKey(privateKeys.first(), ephemeral!!, request.appEphemeralPubKey)
                    val response = JSONObject().put("hash", Base58.encode(KeyExchangeCrypto.hash160(request.appEphemeralPubKey)))
                        .put("public", Base58.encode(eph.pubKey)).put("payload", Base58.encode(payload))
                    record = JSONObject().put("id", recordId).put("scope", plan.scope).put("app", Base58.encode(request.contractId))
                        .put("connection", request.appConnect?.connectionId?.let { Base58.encode(it) }.orEmpty())
                        .put("label", request.label).put("state", "prepared").put("response", response)
                        .put("updatedAt", System.currentTimeMillis())
                        .put("authIndices", JSONArray(plan.selectedAuth.sorted()))
                        .put("encIndices", JSONArray(plan.selectedEncryption.sorted()))
                        .put("keys", JSONArray(keys.map { JSONObject(it.toJSON()) }))
                    checkScope(plan.scope)
                    config.saveGrant(record)
                }
                val registered = keys.all { expected -> identity.publicKeys.any { sameKey(it, expected) && live(it, time) } }
                val oldRecords = matchingRecords(plan.scope, Base58.encode(request.contractId)).filter {
                    it.getString("id") != recordId && request.appConnect?.connectionId != null &&
                        it.optString("connection") == record.optString("connection") && it.optString("state") != "disabled"
                }
                val disable = oldRecords.flatMap { decodeKeys(it) }.filter { it.purpose == Purpose.AUTHENTICATION }
                    .mapNotNull { old -> identity.publicKeys.firstOrNull { sameKey(it, old) && live(it, time) }?.id }.distinct()
                requireSettled(oldRecords, identity, time)
                WalletSignerCallback(wallet, keyParameter).use { walletSigner ->
                    object : Signer() {
                        override fun sign(publicKey: ByteArray, data: ByteArray): ByteArray {
                            privateKeys.forEach { secret ->
                                val key = ECKey.fromPrivate(secret)
                                if (key.pubKey.contentEquals(publicKey) || key.pubKeyHash.contentEquals(publicKey))
                                    return key.signHash(Sha256Hash.twiceOf(data), null)
                            }
                            return walletSigner.sign(publicKey, data)
                        }
                    }.use { signer ->
                        if (!registered || disable.isNotEmpty()) {
                            val additions = keys.filter { expected -> identity.publicKeys.none { sameKey(it, expected) && live(it, time) } }
                            check(additions.none { addition -> identity.publicKeys.any { it.id == addition.id } }) {
                                "Pending key IDs are occupied. Disconnect and scan a new QR."
                            }
                            val master = signingKey(identity, SecurityLevel.MASTER, time)
                            checkScope(plan.scope)
                            request.appConnect?.requireFresh(System.currentTimeMillis())
                            config.saveGrant(record.put("state", "pending"))
                            checkScope(plan.scope)
                            identity.revision++
                            // On an uncertain result the durable pending record remains, and a retry reads the identity first.
                            dashsdk.platformMobilePutPutIdentityUpdateSdk(platform.rustSdk, identity.toNative(), KeyID(master.id),
                                additions.map { it.toNative() }, disable.map { KeyID(it) }, signer.nativeContext,
                                BigInteger.valueOf(signer.signerCallback)).unwrap()
                        }
                        identity = readIdentity()
                        val confirmedTime = platform.client.getPlatformTime(identity.id)
                        check(keys.all { expected -> identity.publicKeys.any { sameKey(it, expected) && live(it, confirmedTime) } }) {
                            "Grant keys are not confirmed. Response was not published."
                        }
                        check(disable.all { id -> identity.publicKeys.any { it.id == id && it.disabledAt != null } })
                        checkScope(plan.scope)
                        rememberIdentity(identity)
                        config.saveGrant(record.put("state", "confirmed"))
                        oldRecords.forEach { config.saveGrant(it.put("state", "disabled")) }
                        publish(identity, record, walletSigner, plan.scope)
                    }
                }
                checkScope(plan.scope)
                config.saveGrant(record.put("state", "active"))
                DAppConnection(Base58.encode(request.contractId), request.label.ifBlank { "Unknown app" }, "",
                    ConnectionStatus.ACTIVE, System.currentTimeMillis(), plan.scope)
            } finally { KeyExchangeCrypto.wipe(seed); privateKeys.forEach { KeyExchangeCrypto.wipe(it) }; KeyExchangeCrypto.wipe(ephemeral) }
        }
    }

    private fun bounds(grant: AuthBinding): ContractBounds = when (grant.kind) {
        0 -> SingleContractBounds(Identifier.from(grant.id))
        1 -> SingleContractDocumentType(Identifier.from(grant.id), grant.documentType!!)
        else -> ContractGroupBounds(Identifier.from(grant.id))
    }
    private fun rememberIdentity(identity: Identity) {
        identities.blockchainIdentity?.let { current ->
            if (current.uniqueIdentifier == identity.id) current.identity = identity
        }
    }
    private fun live(key: IdentityPublicKey, time: Long) = key.disabledAt == null && (key.expiresAt == null || key.expiresAt!! > time)
    private fun sameKey(a: IdentityPublicKey, b: IdentityPublicKey, compareId: Boolean = true) =
        (!compareId || a.id == b.id) && a.type == b.type && a.purpose == b.purpose && a.securityLevel == b.securityLevel &&
            a.data.contentEquals(b.data) && a.readOnly == b.readOnly && a.totalBudget == b.totalBudget && a.expiresAt == b.expiresAt &&
            a.contractBounds?.toJSON() == b.contractBounds?.toJSON()

    private fun decodeKeys(record: JSONObject): List<IdentityPublicKey> {
        val keys = record.getJSONArray("keys")
        return (0 until keys.length()).map { IdentityPublicKey(jsonMap(keys.getJSONObject(it))) }
    }
    private fun requireSettled(records: List<JSONObject>, identity: Identity, time: Long) {
        check(records.filter { it.optString("state") !in setOf("prepared", "disabled") }.none { record ->
            decodeKeys(record).any { expected -> expected.purpose == Purpose.AUTHENTICATION && live(expected, time) &&
                identity.publicKeys.none { sameKey(it, expected) } }
        }) { "Registration outcome is still unknown. Try again after it confirms; the grant record has been retained." }
    }
    private fun indices(record: JSONObject, name: String): Set<Int> = record.getJSONArray(name).let { values ->
        (0 until values.length()).map { values.getInt(it) }.toSet()
    }
    private fun jsonMap(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith {
        when (val value = json.get(it)) { is JSONObject -> jsonMap(value); JSONObject.NULL -> null; else -> value }
    }
    private suspend fun matchingRecords(scope: String, app: String) = config.getGrants().filter {
        it.getString("scope") == scope && it.getString("app") == app
    }

    private fun register() { platform.apps[APP] = ClientAppDefinition(RESPONSE_CONTRACT) }
    private fun response(record: JSONObject) = record.getJSONObject("response")
    private fun fields(record: JSONObject): Map<String, Any?> = response(record).let {
        mapOf("appEphemeralPubKeyHash" to Base58.decode(it.getString("hash")),
            "walletEphemeralPubKey" to Base58.decode(it.getString("public")), "encryptedPayload" to Base58.decode(it.getString("payload")))
    }
    private fun find(identity: Identity, record: JSONObject): List<Document> {
        register()
        return platform.documents.get(LOCATOR, DocumentQuery.Builder()
            .where("appEphemeralPubKeyHash", "==", Base58.decode(response(record).getString("hash")))
            .where("\$ownerId", "==", identity.id).build())
    }
    private fun exact(doc: Document, record: JSONObject) = fields(record).all { (name, bytes) ->
        (doc.data[name] as? ByteArray)?.contentEquals(bytes as ByteArray) == true
    }
    private fun signingKey(identity: Identity, level: SecurityLevel, time: Long): IdentityPublicKey = identity.publicKeys.firstOrNull {
        it.purpose == Purpose.AUTHENTICATION && it.securityLevel == level &&
            (level != SecurityLevel.HIGH || it.type == KeyType.ECDSA_SECP256K1) && it.contractBounds == null && live(it, time) &&
            walletRepo.authenticationGroupExtension?.identityKeyChain?.findKeyFromPubKey(it.data) != null
    } ?: error("Wallet-controlled unbound $level key unavailable")

    private fun documentKey(identity: Identity): IdentityPublicKey =
        signingKey(identity, SecurityLevel.HIGH, platform.client.getPlatformTime(identity.id))

    private suspend fun publish(identity: Identity, record: JSONObject, signer: Signer, scope: String) {
        register()
        val existing = find(identity, record)
        check(existing.all { exact(it, record) }) { "Conflicting response exists for this QR" }
        // Delete superseded encrypted responses using their complete original values, never replace index-only documents.
        matchingRecords(scope, record.getString("app")).filter { it.getString("id") != record.getString("id") }
            .forEach { old -> deleteResponse(identity, old, signer, scope) }
        if (existing.isNotEmpty()) return
        val doc = platform.documents.create(LOCATOR, identity.id, fields(record).toMutableMap())
        doc.revision = 1
        checkScope(scope)
        dashsdk.platformMobilePutPutDocumentSdk(platform.rustSdk, doc.toNative(), Identifier.from(RESPONSE_CONTRACT).toNative(),
            TYPE, documentKey(identity).toNative(), BlockHeight(10000), CoreBlockHeight(platform.coreBlockHeight),
            signer.nativeContext, BigInteger.valueOf(signer.signerCallback)).unwrap()
        check(find(identity, record).any { exact(it, record) }) { "Response not confirmed. Retry to recover." }
    }
    private fun deleteResponse(identity: Identity, record: JSONObject, signer: Signer, scope: String) {
        find(identity, record).forEach { doc ->
            check(exact(doc, record)) { "Stored response differs from Platform; refusing to delete" }
            doc.type = TYPE
            checkScope(scope)
            dashsdk.deleteDocumentSdk(platform.rustSdk, doc.toNative(), Identifier.from(RESPONSE_CONTRACT).toNative(), TYPE,
                documentKey(identity).toNative(), signer.nativeContext, BigInteger.valueOf(signer.signerCallback)).unwrap()
        }
    }

    suspend fun disconnect(app: String, expectedScope: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = expectedScope
            checkScope(scope)
            val records = matchingRecords(scope, app)
            check(records.isNotEmpty()) { "No revocable grant record for this connection" }
            val wallet = walletRepo.walletApplication.wallet ?: error("Wallet unavailable")
            val keyParameter = walletRepo.getWalletEncryptionKey()
            checkScope(scope)
            var identity = readIdentity()
            val time = platform.client.getPlatformTime(identity.id)
            requireSettled(records, identity, time)
            val disable = records.flatMap { decodeKeys(it) }.filter { it.purpose == Purpose.AUTHENTICATION }
                .mapNotNull { expected -> identity.publicKeys.firstOrNull { sameKey(it, expected) && live(it, time) }?.id }.distinct()
            WalletSignerCallback(wallet, keyParameter).use { signer ->
                if (disable.isNotEmpty()) {
                    val master = signingKey(identity, SecurityLevel.MASTER, time)
                    checkScope(scope)
                    identity.revision++
                    dashsdk.platformMobilePutPutIdentityUpdateSdk(platform.rustSdk, identity.toNative(), KeyID(master.id),
                        emptyList(), disable.map { KeyID(it) }, signer.nativeContext, BigInteger.valueOf(signer.signerCallback)).unwrap()
                    identity = readIdentity()
                    check(disable.all { id -> identity.publicKeys.any { it.id == id && it.disabledAt != null } }) { "Revocation not confirmed" }
                }
                records.forEach { deleteResponse(identity, it, signer, scope) }
            }
            checkScope(scope)
            rememberIdentity(identity)
            records.forEach { config.saveGrant(it.put("state", "disabled")) }
        }
    }

    suspend fun details(app: String, expectedScope: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = expectedScope
            checkScope(scope)
            val identity = readIdentity()
            val time = platform.client.getPlatformTime(identity.id)
            val keys = matchingRecords(scope, app).flatMap { decodeKeys(it) }
                .filter { it.purpose == Purpose.AUTHENTICATION }.distinctBy { it.id }
            val text = keys.joinToString("\n\n") { expected ->
                val current = identity.publicKeys.firstOrNull { sameKey(it, expected) }
                when {
                    current == null -> "Key ${expected.id}: not confirmed on identity"
                    !live(current, time) -> "Key ${expected.id}: disabled or expired"
                    else -> {
                        val remaining = platform.client.getIdentityKeyRemainingBudget(identity.id, current.id)
                        "Key ${current.id}: ${remaining?.let { "${formatDash(it)} DASH remaining" } ?: if (current.totalBudget == null) "No budget limit" else "Remaining budget unavailable"}\n" +
                            "Expires: ${current.expiresAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "No expiry"}"
                    }
                }
            }
            checkScope(scope)
            text.ifEmpty { "This legacy connection has no recorded key IDs. Review its keys on your identity; local removal cannot revoke access." }
        }
    }
}
