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
import org.dashj.platform.dpp.contract.VerifiedContractGroup
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.*
import org.dashj.platform.dpp.identity.ApplicationKeyDerivation
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
import de.schildbach.wallet.ui.more.connections.AppConnectKeyState.live
import de.schildbach.wallet.ui.more.connections.AppConnectKeyState.sameKey
import de.schildbach.wallet.ui.more.connections.AppConnectKeyState.sameKeyIdentity

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
        request.requireAppConnect().bytes
    ))

    private fun nativeBytes(result: org.dashj.platform.sdk.Result_ok_Vec_u8_err_String): ByteArray = try {
        check(result.error == null) { result.error.orEmpty() }
        checkNotNull(result.ok)
    } finally { result.delete() }

    suspend fun prepareTokenPurchase(request: DashStRequest): TokenPurchaseApproval = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(platform.client.refreshProtocolVersion() >= 14) { "App Connect operations require Platform protocol version 14" }
            val scope = scope()
            val id = "$scope:${Base58.encode(KeyExchangeCrypto.hash256(request.transitionBytes))}"
            check(!config.purchaseWasSubmitted(id)) { "This purchase was already submitted or its outcome is unknown. Check your balances; do not retry it." }
            val identity = readIdentity()
            val summary = nativeBytes(dashsdk.prepareTokenPurchaseSdk(platform.rustSdk, request.transitionBytes, identity.id.toNative()))
            val data = JSONObject(String(summary, Charsets.UTF_8))
            val signingTime = platform.client.getPlatformTime(identity.id)
            val needsKey = runCatching { signingKey(identity, SecurityLevel.CRITICAL, signingTime) }.isFailure
            checkScope(scope)
            TokenPurchaseApproval(request.copy(transitionBytes = request.transitionBytes.copyOf()), scope, id,
                data.getString("ownerId"), data.getString("contractId"), data.getString("tokenId"),
                BigInteger(data.getString("amount")), BigInteger(data.getString("maximumCredits")),
                data.getString("name").replace(Regex("[\\p{Cc}\\p{Cf}]"), "").take(80), data.getInt("decimals"), summary, needsKey)
        }
    }

    suspend fun submitTokenPurchase(approval: TokenPurchaseApproval) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val authorize = coroutineContext.connectionAuthorization()
            checkScope(approval.scope)
            check(!config.purchaseWasSubmitted(approval.requestId)) { "This purchase cannot be submitted again" }
            val wallet = walletRepo.walletApplication.wallet ?: error("Wallet unavailable")
            val keyParameter = walletRepo.getWalletEncryptionKey()
            check(!wallet.isEncrypted || keyParameter != null) { "Wallet password unavailable. Unlock the wallet and try again." }
            authorize()
            checkScope(approval.scope)
            val identity = readIdentity()
            check(identity.id.toString() == approval.ownerId) { "Purchase targets a different identity" }
            val summary = nativeBytes(dashsdk.prepareTokenPurchaseSdk(platform.rustSdk,
                approval.request.transitionBytes, identity.id.toNative()))
            check(summary.contentEquals(approval.summary)) { "Purchase terms or token metadata changed. Scan again." }
            val key = signingKey(identity, SecurityLevel.CRITICAL, platform.client.getPlatformTime(identity.id))
            val chain = (wallet.getKeyChainExtension(org.bitcoinj.wallet.authentication.AuthenticationGroupExtension.EXTENSION_ID)
                as org.bitcoinj.wallet.authentication.AuthenticationGroupExtension).identityKeyChain
                ?: error("Wallet authentication keys unavailable")
            object : Signer() {
                override fun sign(publicKey: ByteArray, data: ByteArray): ByteArray {
                    authorize()
                    checkScope(approval.scope)
                    val walletKey = if (publicKey.size == 20) chain.findKeyFromPubHash(publicKey)
                        else chain.findKeyFromPubKey(publicKey)
                    return checkNotNull(walletKey) { "Purchase signing key is not controlled by this wallet" }
                        .signHash(Sha256Hash.twiceOf(data), keyParameter)
                }
            }.use { signer ->
                val sdk = platform.rustSdk
                val owner = identity.id.toNative()
                val nativeKey = key.toNative()
                val signerContext = signer.nativeContext
                val signerCallback = BigInteger.valueOf(signer.signerCallback)
                val receipt = JSONObject().put("id", approval.requestId).put("scope", approval.scope)
                    .put("ownerId", approval.ownerId).put("contractId", approval.contractId).put("tokenId", approval.tokenId)
                    .put("amount", approval.amount.toString()).put("maximumCredits", approval.maximumCredits.toString())
                    .put("status", "outcomeUnknown").put("updatedAt", System.currentTimeMillis())
                submitWithPurchaseReceipt(config, receipt, preflight = {
                    checkScope(approval.scope)
                    authorize()
                }) {
                    nativeBytes(dashsdk.submitTokenPurchaseSdk(sdk, approval.request.transitionBytes,
                        approval.summary, owner, nativeKey, signerContext, signerCallback))
                }
            }
        }
    }

    suspend fun setupPurchaseSigningKey(approval: TokenPurchaseApproval) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val authorize = coroutineContext.connectionAuthorization()
            checkScope(approval.scope)
            check(approval.requiresSigningKey) { "Purchase signing-key setup was not requested" }
            val wallet = walletRepo.walletApplication.wallet ?: error("Wallet unavailable")
            val keyParameter = walletRepo.getWalletEncryptionKey()
            check(!wallet.isEncrypted || keyParameter != null) { "Wallet password unavailable. Unlock the wallet and try again." }
            authorize()
            val identity = readIdentity()
            check(identity.id.toString() == approval.ownerId) { "Purchase targets a different identity" }
            rememberIdentity(identity)
            checkScope(approval.scope)
            identities.ensureCriticalAuthenticationKey(identity.id, keyParameter) { authorize(); checkScope(approval.scope) }
            awaitGrantConfirmation(read = { authorize(); checkScope(approval.scope); readIdentity() }, confirmed = { current ->
                val time = platform.client.getPlatformTime(current.id)
                runCatching { signingKey(current, SecurityLevel.CRITICAL, time) }.isSuccess
            }, failureMessage = "Wallet purchase signing key is not confirmed. No purchase was submitted.")
            checkScope(approval.scope)
        }
    }

    suspend fun prepare(request: DashKeyRequest): AppConnectPlan = withContext(Dispatchers.IO) {
        val parsed = request.requireAppConnect()
        check(Constants.NETWORK_PARAMETERS.id != NetworkParameters.ID_MAINNET) { "App Connect is unavailable on mainnet" }
        check(platform.client.refreshProtocolVersion() >= 14) { "App Connect requires Platform protocol version 14" }
        val scope = scope()
        val identity = readIdentity()
        val blockTime = platform.client.getPlatformTime(identity.id)
        parsed.requireFresh(System.currentTimeMillis())
        parsed.identityHint?.let { check(it.contentEquals(identity.id.toBuffer())) { "QR requests a different identity" } }
        // A retry must show and use the original approved limits, not a new rolling expiry.
        val prior = config.getGrants().firstOrNull { it.getString("id") == "$scope:${requestHash(request)}" }
        val selectedAuth = prior?.let { indices(it, "authIndices") } ?: parsed.auth.indices.toSet()
        check(0 in selectedAuth && selectedAuth.all { it in parsed.auth.indices }) { "Stored grant does not match request" }
        var grants = parsed.auth.mapIndexed { index, grant ->
            AppConnectLimits.clamp(grant, blockTime, requireUsableExpiry = index in selectedAuth)
        }
        if (prior != null) {
            val saved = decodeKeys(prior).filter { it.purpose == Purpose.AUTHENTICATION }
            check(saved.size == selectedAuth.size) { "Stored grant does not match request" }
            grants = grants.mapIndexed { index, grant ->
                val position = selectedAuth.sorted().indexOf(index)
                if (position < 0) grant else grant.copy(totalBudget = saved[position].totalBudget, expiresAt = saved[position].expiresAt)
            }
        }
        val groupScopes = validateOwnership(request, grants)
        checkScope(scope)
        val text = buildString {
            append("App ID: ${Base58.encode(request.contractId)}\nQR label is not verified branding.\n\n")
            parsed.callback?.let { append("Return address: ${it.host} (not opened automatically).\n") }
            append("Requested ${maxOf(0, (System.currentTimeMillis() - parsed.createdAt) / 1000)} seconds ago.\n")
            append("Only these application keys are shared. Recovery phrase and master key are never shared. The app cannot register names or change identity keys. CRITICAL grants can spend your Platform credits on token purchases within the approved bounds and budgets. Payments credit the purchased token's contract owner, which may differ from the app owner; other credit transfers are not authorized.")
        }
        val encChoices = parsed.encryption.map {
            "${if (it.purpose == 1) "Encryption" else "Decryption"} key for this app (shared across sessions; omitted if another wallet controls it)."
        }
        AppConnectPlan(requestHash(request), scope, identity.id.toString(), grants, text,
            selectedAuth, encChoices,
            prior?.let { indices(it, "encIndices") } ?: encChoices.indices.toSet(), prior != null, blockTime,
            groupScopes = groupScopes, previousGroupScopes = readApprovedGroupScopes(prior))
    }

    private fun validateOwnership(request: DashKeyRequest, grants: List<AuthBinding>): Map<String, VerifiedContractGroup> {
        val groups = mutableMapOf<String, VerifiedContractGroup>()
        val appId = Identifier.from(request.contractId)
        check(appId.toString() !in SYSTEM_CONTRACTS) { "System contracts cannot be apps" }
        val app = platform.client.getDataContract(request.contractId, true) ?: error("App contract not found")
        check(app.ownerId.toBuffer().any { it != 0.toByte() }) { "System contracts cannot be apps" }
        grants.forEach { grant ->
            if (grant.kind == 2) {
                val id = Identifier.from(grant.id)
                val group = groups[id.toString()] ?: platform.client.getContractGroup(id)
                    ?: error("Requested contract group not found")
                check(group.id == id && group.ownerId == app.ownerId) { "Requested group is not owned by the app" }
                groups[id.toString()] = group
            } else {
                val owner = platform.client.getDataContract(grant.id, true)?.ownerId
                val profileException = grant.kind == 1 && Base58.encode(grant.id) == DASHPAY && grant.documentType == "profile"
                check(owner == app.ownerId || profileException) { "A requested bound target is not owned by the app" }
            }
        }
        request.requireAppConnect().encryption.forEach {
            check(it.contractId.contentEquals(request.contractId)) { "Encryption must be bound to the app contract" }
            check(if (it.purpose == 1) app.requiresIdentityEncryptionBoundedKey else app.requiresIdentityDecryptionBoundedKey) {
                "App contract has not opted into this encryption key"
            }
        }
        return groups
    }

    suspend fun approve(request: DashKeyRequest, plan: AppConnectPlan): DAppConnection = withContext(Dispatchers.IO) {
        val parsed = request.requireAppConnect()
        mutex.withLock {
            val authorize = coroutineContext.connectionAuthorization()
            checkScope(plan.scope)
            check(plan.requestHash == requestHash(request)) { "Approval does not match QR" }
            plan.requireMatches(request)
            check(0 in plan.selectedAuth && plan.selectedAuth.all { it in plan.grants.indices }) { "Primary grant is required" }
            check(plan.selectedEncryption.all { it in parsed.encryption.indices })
            parsed.requireFresh(System.currentTimeMillis())
            check(platform.client.refreshProtocolVersion() >= 14)
            val selectedGrants = plan.selectedAuth.sorted().map { plan.grants[it] }
            plan.requireGroupScopes(validateOwnership(request, selectedGrants))
            val recordId = "${plan.scope}:${plan.requestHash}"
            var record = config.getGrants().firstOrNull { it.getString("id") == recordId }
            plan.requireStoredGroupConsent(record)
            check(record?.optString("state") != "disabled" && record?.optBoolean("permanentRevocationConfirmed") != true) {
                "This QR has already been revoked. Scan a new one."
            }
            val wallet = walletRepo.walletApplication.wallet ?: error("Wallet unavailable")
            val keyParameter = walletRepo.getWalletEncryptionKey()
            check(!wallet.isEncrypted || keyParameter != null) { "Wallet password unavailable. Unlock the wallet and try again." }
            authorize()
            checkScope(plan.scope)
            parsed.requireFresh(System.currentTimeMillis())
            val seed = walletRepo.getWalletSeed()?.seedBytes?.copyOf() ?: error("Wallet seed unavailable")
            val privateKeys = mutableListOf<ByteArray>()
            var ephemeral: ByteArray? = null
            try {
                var identity = readIdentity()
                check(identity.id.toString() == plan.identityId) { "Identity changed. Scan again." }
                val time = platform.client.getPlatformTime(identity.id)
                check(selectedGrants.all { it.expiresAt!! > time + 3_600_000 }) { "Grant expired. Scan again." }
                val keys = mutableListOf<IdentityPublicKey>()
                var nextId = (identity.publicKeys.maxOfOrNull { it.id } ?: -1) + 1
                plan.grants.forEachIndexed { index, grant ->
                    if (index !in plan.selectedAuth) return@forEachIndexed
                    val leaf = KeyExchangeCrypto.sessionRequestLeaf(request.appEphemeralPubKey, index)
                    val secret = ApplicationKeyDerivation.sessionAuthentication(seed, identity.id.toBuffer(), leaf, false)
                    privateKeys.add(secret)
                    val key = IdentityPublicKey(nextId++, KeyType.ECDSA_HASH160, Purpose.AUTHENTICATION,
                        if (grant.securityLevel == 1) SecurityLevel.CRITICAL else SecurityLevel.HIGH,
                        bounds(grant), KeyExchangeCrypto.hash160(KeyExchangeCrypto.compressedPublicKey(secret)), false,
                        totalBudget = grant.totalBudget, expiresAt = grant.expiresAt, version = 1)
                    keys.add(key)
                }
                parsed.encryption.forEachIndexed { index, binding ->
                    if (index !in plan.selectedEncryption) return@forEachIndexed
                    val purpose = if (binding.purpose == 1) Purpose.ENCRYPTION else Purpose.DECRYPTION
                    val secret = ApplicationKeyDerivation.encryption(seed, identity.id.toBuffer(), binding.contractId, purpose, false)
                    privateKeys.add(secret)
                    val data = KeyExchangeCrypto.compressedPublicKey(secret)
                    val candidates = identity.publicKeys.filter { it.purpose == purpose && it.contractBounds is SingleContractBounds &&
                        it.contractBounds!!.identifier == Identifier.from(binding.contractId) }
                    val indexedId = platform.client.getCurrentContractKeyId(identity.id, Identifier.from(binding.contractId), purpose)
                    val current = AppConnectKeyState.currentEncryptionKey(indexedId, candidates, data, time)
                    val key = when {
                        current != null -> current
                        indexedId == null && candidates.isEmpty() -> IdentityPublicKey(nextId++,
                            KeyType.ECDSA_SECP256K1, purpose, SecurityLevel.MEDIUM, SingleContractBounds(Identifier.from(binding.contractId)), data, false)
                        else -> null
                    }
                    if (key == null) {
                        privateKeys.removeAt(privateKeys.lastIndex)
                        KeyExchangeCrypto.wipe(secret)
                    } else keys.add(key)
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
                    val payload = KeyExchangeCrypto.encryptAppConnect(privateKeys,
                        ephemeral!!, request.appEphemeralPubKey, identity.id.toBuffer())
                    val response = JSONObject().put("hash", Base58.encode(KeyExchangeCrypto.hash160(request.appEphemeralPubKey)))
                        .put("public", Base58.encode(eph.pubKey)).put("payload", Base58.encode(payload))
                    record = JSONObject().put("id", recordId).put("scope", plan.scope).put("app", Base58.encode(request.contractId))
                        .put("connection", parsed.connectionId?.let { Base58.encode(it) }.orEmpty())
                        .put("label", request.label).put("state", "prepared").put("response", response)
                        .put("updatedAt", System.currentTimeMillis())
                        .put("authIndices", JSONArray(plan.selectedAuth.sorted()))
                        .put("encIndices", JSONArray(plan.selectedEncryption.sorted()))
                        .put("keys", JSONArray(keys.map { JSONObject(it.toJSON()) }))
                        .put("groupScopes", plan.groupScopesJson())
                    checkScope(plan.scope)
                    config.saveGrant(record)
                }
                val registered = keys.all { expected -> identity.publicKeys.any { sameKey(it, expected) && live(it, time) } }
                val oldRecords = matchingRecords(plan.scope, Base58.encode(request.contractId)).filter {
                    it.getString("id") != recordId && parsed.connectionId != null &&
                        it.optString("connection") == record.optString("connection") &&
                        (it.optString("state") != "disabled" || !it.optBoolean("permanentRevocationConfirmed"))
                }
                val disable = AppConnectKeyState.revocableIds(AppConnectKeyState.inspect(
                    oldRecords.flatMap { decodeKeys(it) }, identity.publicKeys, time))
                requireSettled(oldRecords, identity, time)
                WalletSignerCallback(wallet, keyParameter, { authorize(); checkScope(plan.scope) }).use { walletSigner ->
                    object : Signer() {
                        override fun sign(publicKey: ByteArray, data: ByteArray): ByteArray {
                            authorize()
                            checkScope(plan.scope)
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
                            check(additions.size <= 6) { "A grant must fit in one atomic key registration" }
                            checkScope(plan.scope)
                            parsed.requireFresh(System.currentTimeMillis())
                            config.saveGrant(record.withGrantState("pending").put("groupScopes", plan.groupScopesJson()))
                            checkScope(plan.scope)
                            // On an uncertain result the durable pending record remains, and a retry reads the identity first.
                            identity = updateIdentityInBatches(additions, disable,
                                read = { checkScope(plan.scope); readIdentity() }) { current, add, ids ->
                                val master = signingKey(current, SecurityLevel.MASTER, platform.client.getPlatformTime(current.id))
                                checkScope(plan.scope)
                                parsed.requireFresh(System.currentTimeMillis())
                                plan.requireGroupScopes(validateOwnership(request, selectedGrants))
                                checkScope(plan.scope)
                                parsed.requireFresh(System.currentTimeMillis())
                                current.revision++
                                authorize()
                                dashsdk.platformMobilePutPutIdentityUpdateSdk(platform.rustSdk, current.toNative(), KeyID(master.id),
                                    add.map { it.toNative() }, ids.map { KeyID(it) }, signer.nativeContext,
                                    BigInteger.valueOf(signer.signerCallback)).unwrap()
                            }
                        }
                        identity = awaitGrantConfirmation(
                            read = {
                                authorize()
                                checkScope(plan.scope)
                                readIdentity()
                            },
                            confirmed = { current ->
                                val confirmedTime = platform.client.getPlatformTime(current.id)
                                checkScope(plan.scope)
                                keys.all { expected -> current.publicKeys.any { sameKey(it, expected) && live(it, confirmedTime) } } &&
                                    disable.all { id -> current.publicKeys.any { it.id == id && it.disabledAt != null } }
                            }
                        )
                        checkScope(plan.scope)
                        rememberIdentity(identity)
                        config.saveGrant(record.withGrantState("confirmed"))
                        oldRecords.forEach { config.saveGrant(settleConfirmedRevocation(it, null)) }
                        plan.requireGroupScopes(validateOwnership(request, selectedGrants))
                        authorize()
                        checkScope(plan.scope)
                        // Keep the exact scope approved for this response alongside its key set.
                        record.put("groupScopes", plan.groupScopesJson())
                        config.saveGrant(record)
                        publish(identity, record, walletSigner, plan.scope, authorize)
                    }
                }
                checkScope(plan.scope)
                config.saveGrant(record.withGrantState("active"))
                DAppConnection(Base58.encode(request.contractId), unverifiedAppName(request.label, Base58.encode(request.contractId)), "",
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
    private fun decodeKeys(record: JSONObject): List<IdentityPublicKey> {
        val keys = record.getJSONArray("keys")
        return (0 until keys.length()).map { IdentityPublicKey(jsonMap(keys.getJSONObject(it))) }
    }
    private fun needsRegistrationRecovery(record: JSONObject) = record.optString("state") != "prepared" &&
        (record.optString("state") != "disabled" || !record.optBoolean("permanentRevocationConfirmed"))

    private fun requireSettled(records: List<JSONObject>, identity: Identity, time: Long) {
        check(records.filter { needsRegistrationRecovery(it) }.none { record ->
            AppConnectKeyState.unresolved(AppConnectKeyState.inspect(decodeKeys(record), identity.publicKeys, time))
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
            (walletRepo.authenticationGroupExtension?.identityKeyChain?.findKeyFromPubKey(it.data) != null ||
                (level == SecurityLevel.CRITICAL && it.type == KeyType.ECDSA_HASH160 &&
                    walletRepo.authenticationGroupExtension?.identityKeyChain?.findKeyFromPubHash(it.data) != null))
    } ?: error("Wallet-controlled unbound $level key unavailable")

    private fun documentKey(identity: Identity): IdentityPublicKey =
        signingKey(identity, SecurityLevel.HIGH, platform.client.getPlatformTime(identity.id))

    internal suspend fun responsesToDelete(record: JSONObject, scope: String): List<JSONObject> =
        matchingRecords(scope, record.getString("app")).filter {
            it.getString("id") != record.getString("id") && it.optString("state") == "disabled" &&
                it.optBoolean("permanentRevocationConfirmed")
        }

    private suspend fun publish(identity: Identity, record: JSONObject, signer: Signer, scope: String, authorize: () -> Unit) {
        decodeKeys(record).filter { it.purpose == Purpose.ENCRYPTION || it.purpose == Purpose.DECRYPTION }.forEach { key ->
            val indexedId = platform.client.getCurrentContractKeyId(identity.id, key.contractBounds!!.identifier, key.purpose)
            check(indexedId == key.id) { "Current application encryption key changed. Response was not published." }
        }
        register()
        val existing = find(identity, record)
        check(existing.all { exact(it, record) }) { "Conflicting response exists for this QR" }
        // Independent connections may still need their responses. Only confirmed revoked grants are obsolete.
        responsesToDelete(record, scope).forEach { old -> deleteResponse(identity, old, signer, scope, authorize) }
        if (existing.isNotEmpty()) return
        val doc = platform.documents.create(LOCATOR, identity.id, fields(record).toMutableMap())
        doc.revision = 1
        checkScope(scope)
        val key = documentKey(identity)
        authorize()
        dashsdk.platformMobilePutPutDocumentSdk(platform.rustSdk, doc.toNative(), Identifier.from(RESPONSE_CONTRACT).toNative(),
            TYPE, key.toNative(), BlockHeight(10000), CoreBlockHeight(platform.coreBlockHeight),
            signer.nativeContext, BigInteger.valueOf(signer.signerCallback)).unwrap()
        check(find(identity, record).any { exact(it, record) }) { "Response not confirmed. Retry to recover." }
    }
    private fun deleteResponse(identity: Identity, record: JSONObject, signer: Signer, scope: String, authorize: () -> Unit) {
        find(identity, record).forEach { doc ->
            check(exact(doc, record)) { "Stored response differs from Platform; refusing to delete" }
            doc.type = TYPE
            checkScope(scope)
            val key = documentKey(identity)
            authorize()
            dashsdk.deleteDocumentSdk(platform.rustSdk, doc.toNative(), Identifier.from(RESPONSE_CONTRACT).toNative(), TYPE,
                key.toNative(), signer.nativeContext, BigInteger.valueOf(signer.signerCallback)).unwrap()
        }
    }

    suspend fun disconnect(app: String, expectedScope: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = expectedScope
            checkScope(scope)
            val records = matchingRecords(scope, app)
            check(records.isNotEmpty()) { "No revocable grant record for this connection" }
            revokeRecords(records, scope, app)
        }
    }

    suspend fun revokePrepared(request: DashKeyRequest, plan: AppConnectPlan) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkScope(plan.scope)
            check(plan.requestHash == requestHash(request)) { "Approval does not match QR" }
            val record = matchingRecords(plan.scope, Base58.encode(request.contractId))
                .firstOrNull { it.getString("id") == "${plan.scope}:${plan.requestHash}" }
            // Approval may have failed before it durably prepared or submitted any keys.
            if (record != null) revokeRecords(listOf(record), plan.scope)
        }
    }

    private suspend fun revokeRecords(records: List<JSONObject>, scope: String, completeApp: String? = null) {
        val authorize = kotlin.coroutines.coroutineContext.connectionAuthorization()
        val wallet = walletRepo.walletApplication.wallet ?: error("Wallet unavailable")
        val keyParameter = walletRepo.getWalletEncryptionKey()
        check(!wallet.isEncrypted || keyParameter != null) { "Wallet password unavailable. Unlock the wallet and try again." }
        checkScope(scope)
        var identity = readIdentity()
        val time = platform.client.getPlatformTime(identity.id)
        val disable = AppConnectKeyState.revocableIds(AppConnectKeyState.inspect(
            records.flatMap { decodeKeys(it) }, identity.publicKeys, time))
        // Occupying one missing authentication ID prevents that entire atomic registration
        // from ever landing. The replacement key belongs only to this wallet, not the app.
        rememberIdentity(identity)
        val fences = mutableListOf<IdentityPublicKey>()
        records.filter { needsRegistrationRecovery(it) }.forEach { record ->
            val id = AppConnectKeyState.fenceId(AppConnectKeyState.inspect(decodeKeys(record), identity.publicKeys, time))
            if (id != null && fences.none { it.id == id }) {
                fences.add(checkNotNull(identities.blockchainIdentity).createRevocationFenceKey(id, keyParameter, fences))
            }
        }
        WalletSignerCallback(wallet, keyParameter, { authorize(); checkScope(scope) }).use { signer ->
            if (disable.isNotEmpty() || fences.isNotEmpty()) {
                identity = updateIdentityInBatches(fences, disable, read = { checkScope(scope); readIdentity() }) { current, add, ids ->
                    val master = signingKey(current, SecurityLevel.MASTER, platform.client.getPlatformTime(current.id))
                    checkScope(scope)
                    current.revision++
                    authorize()
                    dashsdk.platformMobilePutPutIdentityUpdateSdk(platform.rustSdk, current.toNative(), KeyID(master.id),
                        add.map { it.toNative() }, ids.map { KeyID(it) }, signer.nativeContext, BigInteger.valueOf(signer.signerCallback)).unwrap()
                }
            }
            records.forEach { deleteResponse(identity, it, signer, scope, authorize) }
        }
        checkScope(scope)
        rememberIdentity(identity)
        val confirmedTime = platform.client.getPlatformTime(identity.id)
        val unresolved = records.filter { needsRegistrationRecovery(it) &&
            AppConnectKeyState.unresolved(AppConnectKeyState.inspect(decodeKeys(it), identity.publicKeys, confirmedTime)) }
        val unknown = completeApp?.let { unrecordedAppKeys(identity, it, scope) }
        checkScope(scope)
        records.filter { it !in unresolved }.forEach {
            config.saveGrant(settleConfirmedRevocation(it, unknown))
        }
        check(unresolved.isEmpty()) {
            "Confirmed keys were revoked, but a registration outcome is still unknown. Retry to finish revocation; pending records have been retained."
        }
        check(unknown.isNullOrEmpty()) {
            "Recorded keys were revoked, but other enabled application keys (${unknown?.joinToString()}) remain. " +
                "Local history is incomplete, possibly after wallet recovery. Complete revocation is not confirmed."
        }
    }

    internal suspend fun unrecordedAppKeys(identity: Identity, app: String, scope: String): List<Int> {
        // Other locally recorded apps may legitimately have the same DashPay profile bounds.
        val recorded = config.getGrants().filter { it.getString("scope") == scope }.flatMap { decodeKeys(it) }
        val appId = Identifier.from(app)
        val owner = platform.client.getDataContract(appId.toBuffer(), true)?.ownerId ?: error("App contract not found")
        return AppConnectKeyState.unrecordedKeys(identity.publicKeys, recorded) { key ->
            val target = key.contractBounds!!.identifier
            when (val bounds = key.contractBounds) {
                is ContractGroupBounds -> checkNotNull(platform.client.getContractGroupOwner(target)) {
                    "Contract group ownership could not be established. Complete revocation is not confirmed."
                } == owner
                is SingleContractDocumentType -> target == appId ||
                    (target.toString() == DASHPAY && bounds.documentType == "profile") ||
                    (target.toString() !in SYSTEM_CONTRACTS && contractOwner(target) == owner)
                else -> target == appId || (target.toString() !in SYSTEM_CONTRACTS &&
                    contractOwner(target) == owner)
            }
        }
    }

    private fun contractOwner(target: Identifier): Identifier =
        checkNotNull(platform.client.getDataContract(target.toBuffer(), true)) {
            "Contract ownership could not be established. Complete revocation is not confirmed."
        }.ownerId

    suspend fun details(app: String, expectedScope: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = expectedScope
            checkScope(scope)
            val identity = readIdentity()
            val time = platform.client.getPlatformTime(identity.id)
            val keys = matchingRecords(scope, app).flatMap { decodeKeys(it) }
                .filter { it.purpose == Purpose.AUTHENTICATION }.distinctBy { it.id }
            val text = keys.joinToString("\n\n") { expected ->
                val current = identity.publicKeys.firstOrNull { sameKeyIdentity(it, expected) }
                when {
                    current == null -> "Key ${expected.id}: not confirmed on identity"
                    current.disabledAt != null -> "Key ${expected.id}: permanently disabled"
                    !live(current, time) -> "Key ${expected.id}: expired but still enabled. Revoke access to disable it permanently."
                    else -> {
                        val remaining = try {
                            platform.client.getIdentityKeyRemainingBudget(identity.id, current.id)
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            null
                        }
                        "Key ${current.id}: ${remaining?.let { "${formatDash(it)} DASH remaining" } ?: if (current.totalBudget == null) "No budget limit" else "Remaining budget unavailable"}\n" +
                            "Expires: ${current.expiresAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "No expiry"}"
                    }
                }
            }
            checkScope(scope)
            val historyWarning = try {
                val unknown = unrecordedAppKeys(identity, app, scope)
                if (unknown.isEmpty()) "" else "\n\nLocal history is incomplete. Other enabled application keys: ${unknown.joinToString()}. " +
                    "Revoking recorded keys alone will not revoke all access."
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                "\n\nAdditional authorization ownership checks are unavailable. Complete revocation cannot be verified. " +
                    "Revoke access is still available for recorded keys; incomplete checks will retain the recovery records."
            }
            checkScope(scope)
            text.ifEmpty { "This connection has no recorded key IDs. Review its keys on your identity; local removal cannot revoke access." } + historyWarning
        }
    }
}
