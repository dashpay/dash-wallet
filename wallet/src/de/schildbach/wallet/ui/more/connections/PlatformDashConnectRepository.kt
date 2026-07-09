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

package de.schildbach.wallet.ui.more.connections

import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.more.connections.protocol.DashConnectNetwork
import de.schildbach.wallet.ui.more.connections.protocol.DashConnectUri
import de.schildbach.wallet.ui.more.connections.protocol.DashConnectUriException
import de.schildbach.wallet.ui.more.connections.protocol.DashKeyRequest
import de.schildbach.wallet.ui.more.connections.protocol.DashStRequest
import de.schildbach.wallet.ui.more.connections.protocol.KeyExchangeCrypto
import de.schildbach.wallet.ui.more.connections.protocol.LoginKeyDerivation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Base58
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.document.DocumentCreateTransition
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.identity.IdentityUpdateTransition
import org.dashj.platform.dpp.statetransition.StateTransitionFactory
import org.dashj.platform.sdk.BlockHeight
import org.dashj.platform.sdk.CoreBlockHeight
import org.dashj.platform.sdk.KeyType
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.sdk.SecurityLevel
import org.dashj.platform.sdk.callbacks.Signer
import org.dashj.platform.sdk.client.ClientAppDefinition
import org.dashj.platform.sdk.dashsdk
import org.dashj.platform.dashpay.callback.WalletSignerCallback
import org.dashj.platform.dapiclient.model.DocumentQuery
import org.slf4j.LoggerFactory
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real Dash Platform implementation of [DashConnectRepository] for the Yappr key-exchange login
 * protocol (MO-945). See `protocol/` for the pure, unit-tested crypto and URI parsing.
 *
 * TESTNET ONLY: the key-exchange contract [KEY_EXCHANGE_CONTRACT_ID] is only deployed on testnet.
 * The whole feature is gated on [Constants.IS_TESTNET_BUILD]; on mainnet, [parseQr] and the
 * approve/complete paths reject with a clear message and the UI shows a "testnet only" state.
 */
@Singleton
class PlatformDashConnectRepository @Inject constructor(
    private val platform: PlatformService,
    private val platformRepo: PlatformRepo,
    private val identityRepository: IdentityRepository,
    private val config: DashConnectConfig
) : DashConnectRepository {

    companion object {
        private val log = LoggerFactory.getLogger(PlatformDashConnectRepository::class.java)

        /** loginKeyResponse contract (testnet only — no mainnet deployment). */
        const val KEY_EXCHANGE_CONTRACT_ID = "7UaqHGBJBbRLJ4fUWS45cnud8PPUugJWoGTt1SKwHJ2P"
        private const val APP_NAME = "dash-connect-key-exchange"
        private const val DOCUMENT_TYPE = "loginKeyResponse"
        private const val TYPE_LOCATOR = "$APP_NAME.$DOCUMENT_TYPE"

        // loginKeyResponse fields
        private const val FIELD_CONTRACT_ID = "contractId"
        private const val FIELD_APP_EPH_PUBKEY_HASH = "appEphemeralPubKeyHash"
        private const val FIELD_WALLET_EPH_PUBKEY = "walletEphemeralPubKey"
        private const val FIELD_ENCRYPTED_PAYLOAD = "encryptedPayload"
        private const val FIELD_KEY_INDEX = "keyIndex"
    }

    // ── observe ──────────────────────────────────────────────────────────────────

    override fun observeConnections(): Flow<List<DAppConnection>> =
        config.observeConnections().map { stored ->
            stored
                .map { it.toDAppConnection() }
                .sortedByDescending { it.updatedAt }
        }

    // ── parse ────────────────────────────────────────────────────────────────────

    override suspend fun parseQr(qrContent: String): DashConnectQr {
        checkTestnet()
        val content = qrContent.trim()
        return when {
            DashConnectUri.isKeyUri(content) -> {
                val request = DashConnectUri.parseKeyRequest(content)
                requireNetworkMatch(request.network)
                DashConnectQr.Login(request)
            }
            DashConnectUri.isStUri(content) -> {
                val request = DashConnectUri.parseStRequest(content)
                requireNetworkMatch(request.network)
                DashConnectQr.KeyRegistration(request)
            }
            else -> throw DashConnectUriException("not a DashConnect QR code")
        }
    }

    // ── approve login (QR #1) ────────────────────────────────────────────────────

    override suspend fun approveLogin(request: DashKeyRequest): DAppConnection = withContext(Dispatchers.IO) {
        checkTestnet()
        requireNetworkMatch(request.network)

        val blockchainIdentity = identityRepository.blockchainIdentity
            ?: throw IllegalStateException("blockchain identity not available")
        val identity = blockchainIdentity.identity
            ?: throw IllegalStateException("identity not loaded")
        val identityIdBytes = blockchainIdentity.uniqueIdData
        val keyParameter = platformRepo.getWalletEncryptionKey()

        // 1. deterministic login key from the BLOCKCHAIN_IDENTITY auth chain
        val chainKey = platformRepo.getBlockchainIdentityKey(LoginKeyDerivation.DEFAULT_KEY_INDEX, keyParameter)
            ?: throw IllegalStateException("could not derive authentication chain key")
        val chainKeyPrivateBytes = chainKey.privKeyBytes

        var loginKey: ByteArray? = null
        var walletEphemeralPriv: ByteArray? = null
        try {
            loginKey = LoginKeyDerivation.deriveLoginKey(
                chainKeyPrivateBytes = chainKeyPrivateBytes,
                identityIdBytes = identityIdBytes,
                appContractIdBytes = request.contractId
            )

            // 2. fresh ephemeral keypair
            val walletEphemeral = ECKey()
            walletEphemeralPriv = walletEphemeral.privKeyBytes
            val walletEphemeralPub = walletEphemeral.pubKey // 33-byte compressed

            // 3-5. ECDH → HKDF → AES-256-GCM, yielding a 60-byte payload
            val encryptedPayload = KeyExchangeCrypto.encryptLoginKey(
                loginKey = loginKey,
                walletEphemeralPriv = walletEphemeralPriv,
                appEphemeralPub = request.appEphemeralPubKey
            )
            val appEphemeralPubKeyHash = KeyExchangeCrypto.hash160(request.appEphemeralPubKey)

            // 6. publish (or replace) the loginKeyResponse document
            publishLoginKeyResponse(
                identity = identity,
                appContractIdBytes = request.contractId,
                appEphemeralPubKeyHash = appEphemeralPubKeyHash,
                walletEphemeralPubKey = walletEphemeralPub,
                encryptedPayload = encryptedPayload,
                keyIndex = LoginKeyDerivation.DEFAULT_KEY_INDEX,
                keyParameter = keyParameter
            )
        } finally {
            // 7. wipe sensitive copies
            KeyExchangeCrypto.wipe(loginKey)
            KeyExchangeCrypto.wipe(walletEphemeralPriv)
            KeyExchangeCrypto.wipe(chainKeyPrivateBytes)
        }

        val contractIdBase58 = Base58.encode(request.contractId)
        val stored = StoredConnection(
            contractId = contractIdBase58,
            label = request.label,
            url = "",
            status = ConnectionStatus.ACTIVE.name,
            updatedAt = System.currentTimeMillis()
        )
        config.upsertConnection(stored)
        stored.toDAppConnection()
    }

    /**
     * Creates or replaces the loginKeyResponse document. There is a unique index on
     * ($ownerId, contractId), so on a repeat login we MUST query our own existing document and
     * REPLACE it ($revision + 1) rather than blindly create (which would fail with a
     * duplicate-unique-index error).
     */
    private fun publishLoginKeyResponse(
        identity: Identity,
        appContractIdBytes: ByteArray,
        appEphemeralPubKeyHash: ByteArray,
        walletEphemeralPubKey: ByteArray,
        encryptedPayload: ByteArray,
        keyIndex: Int,
        keyParameter: org.bouncycastle.crypto.params.KeyParameter?
    ) {
        registerContract()

        val fields = mutableMapOf<String, Any?>(
            FIELD_CONTRACT_ID to appContractIdBytes,
            FIELD_APP_EPH_PUBKEY_HASH to appEphemeralPubKeyHash,
            FIELD_WALLET_EPH_PUBKEY to walletEphemeralPubKey,
            FIELD_ENCRYPTED_PAYLOAD to encryptedPayload,
            FIELD_KEY_INDEX to keyIndex.toLong()
        )

        val existing = findOwnLoginKeyResponse(identity.id, appContractIdBytes)
        val signer: Signer = WalletSignerCallback(platformRepo.walletApplication.wallet!!, keyParameter)
        // Documents default to a HIGH security-level authentication key (DPP default).
        val highKey = identity.getFirstPublicKey(SecurityLevel.HIGH)
            ?: error("no HIGH security-level authentication key on identity")

        val document = platform.platform.documents.create(TYPE_LOCATOR, identity.id, fields)
        val contractId = document.dataContractId
            ?: error("loginKeyResponse document has no contract id")

        val result = if (existing == null) {
            document.revision = DocumentCreateTransition.INITIAL_REVISION
            dashsdk.platformMobilePutPutDocumentSdk(
                platform.platform.rustSdk,
                document.toNative(),
                contractId.toNative(),
                document.type,
                highKey.toNative(),
                BlockHeight(10000),
                CoreBlockHeight(platform.platform.coreBlockHeight),
                signer.nativeContext,
                BigInteger.valueOf(signer.signerCallback)
            )
        } else {
            document.revision = existing.revision + 1
            dashsdk.platformMobilePutReplaceDocumentSdk(
                platform.platform.rustSdk,
                document.toNative(),
                contractId.toNative(),
                document.type,
                highKey.toNative(),
                BlockHeight(10000),
                CoreBlockHeight(platform.platform.coreBlockHeight),
                signer.nativeContext,
                BigInteger.valueOf(signer.signerCallback)
            )
        }
        result.unwrap() // throws on Platform error (surface stale-revision etc.)
        log.info("published loginKeyResponse for app ${Base58.encode(appContractIdBytes)} (replace=${existing != null})")
    }

    private fun findOwnLoginKeyResponse(ownerId: Identifier, appContractIdBytes: ByteArray): Document? {
        registerContract()
        val query = DocumentQuery.Builder()
            .where("\$ownerId", "==", ownerId)
            .where(FIELD_CONTRACT_ID, "==", appContractIdBytes)
            .build()
        return platform.platform.documents.get(TYPE_LOCATOR, query).firstOrNull()
    }

    // ── complete key registration (QR #2) ──────────────────────────────────────────

    override suspend fun completeKeyRegistration(request: DashStRequest) = withContext(Dispatchers.IO) {
        checkTestnet()
        requireNetworkMatch(request.network)

        val blockchainIdentity = identityRepository.blockchainIdentity
            ?: throw IllegalStateException("blockchain identity not available")
        val identity = blockchainIdentity.identity
            ?: throw IllegalStateException("identity not loaded")
        val identityIdBytes = blockchainIdentity.uniqueIdData
        val keyParameter = platformRepo.getWalletEncryptionKey()

        // deserialize the transition bytes (structure validated by the SDK factory)
        val factory = StateTransitionFactory(platform.dpp, platform.stateRepository)
        val stateTransition = factory.createFromBuffer(
            request.transitionBytes,
            StateTransitionFactory.Options(true) // skipValidation: we validate ourselves + broadcast validates
        )
        val transition = stateTransition as? IdentityUpdateTransition
            ?: throw DashConnectUriException("dash-st is not an IdentityUpdateTransition")

        validateKeyRegistration(transition, identity, identityIdBytes, keyParameter)

        // sign with the identity MASTER authentication key and broadcast
        val masterPublicKey = blockchainIdentity.getIdentityPublicKeyByPurpose(
            org.dashj.platform.dashpay.BlockchainIdentity.KeyIndexPurpose.MASTER
        ) ?: error("no MASTER authentication key on identity")
        val masterEcKey = blockchainIdentity.getPrivateKeyByPurpose(
            org.dashj.platform.dashpay.BlockchainIdentity.KeyIndexPurpose.MASTER,
            keyParameter
        )

        platform.platform.broadcastStateTransition(transition, identity, masterEcKey, masterPublicKey.id)
        log.info("broadcast dash-st IdentityUpdateTransition for identity ${blockchainIdentity.uniqueIdString}")
    }

    /**
     * Validates the dash-st transition against the protocol rules and STRONGLY verifies that the
     * two added public keys were derived from THIS wallet's login key (mismatch = forged QR).
     */
    private suspend fun validateKeyRegistration(
        transition: IdentityUpdateTransition,
        identity: Identity,
        identityIdBytes: ByteArray,
        keyParameter: org.bouncycastle.crypto.params.KeyParameter?
    ) {
        // identity ownership
        if (!transition.identityId.toBuffer().contentEquals(identity.id.toBuffer())) {
            throw DashConnectUriException("dash-st identityId is not ours")
        }
        // no key disables allowed
        if (!transition.disablePublicKeys.isNullOrEmpty()) {
            throw DashConnectUriException("dash-st must not disable keys")
        }
        // exactly 2 keys: authentication/HIGH/ECDSA_HASH160 and encryption/MEDIUM/ECDSA_SECP256K1
        val addKeys = transition.addPublicKeys.orEmpty()
        if (addKeys.size != 2) {
            throw DashConnectUriException("dash-st must add exactly 2 keys, was ${addKeys.size}")
        }
        val authKey = addKeys.firstOrNull {
            it.purpose == Purpose.AUTHENTICATION && it.securityLevel == SecurityLevel.HIGH && it.type == KeyType.ECDSA_HASH160
        } ?: throw DashConnectUriException("dash-st missing authentication/HIGH/ECDSA_HASH160 key")
        val encKey = addKeys.firstOrNull {
            it.purpose == Purpose.ENCRYPTION && it.securityLevel == SecurityLevel.MEDIUM && it.type == KeyType.ECDSA_SECP256K1
        } ?: throw DashConnectUriException("dash-st missing encryption/MEDIUM/ECDSA_SECP256K1 key")

        // STRONG end-to-end check: re-derive from our login key and match the QR's key data.
        val chainKey = platformRepo.getBlockchainIdentityKey(LoginKeyDerivation.DEFAULT_KEY_INDEX, keyParameter)
            ?: throw IllegalStateException("could not derive authentication chain key")
        val chainKeyPrivateBytes = chainKey.privKeyBytes
        var loginKey: ByteArray? = null
        var authPriv: ByteArray? = null
        var encPriv: ByteArray? = null
        try {
            // The app contract id is not carried in the dash-st transition; it is bound only via
            // the login key. We therefore verify against the contract the wallet is registering for
            // by trying each known connection's contract id until one matches, OR — since the QR is
            // scanned right after the dash-key login for the same app — verify against ALL stored
            // apps. In practice the auth/enc key data uniquely identify the right (identity, app).
            val candidateContractIds = candidateContractIds()
            var matched = false
            for (contractIdBytes in candidateContractIds) {
                loginKey = LoginKeyDerivation.deriveLoginKey(chainKeyPrivateBytes, identityIdBytes, contractIdBytes)
                authPriv = KeyExchangeCrypto.deriveAuthPrivateKey(loginKey, identityIdBytes)
                encPriv = KeyExchangeCrypto.deriveEncryptionPrivateKey(loginKey, identityIdBytes)
                val expectedAuthData = KeyExchangeCrypto.hash160(KeyExchangeCrypto.compressedPublicKey(authPriv))
                val expectedEncData = KeyExchangeCrypto.compressedPublicKey(encPriv)
                if (expectedAuthData.contentEquals(authKey.data) && expectedEncData.contentEquals(encKey.data)) {
                    matched = true
                    break
                }
                KeyExchangeCrypto.wipe(loginKey); KeyExchangeCrypto.wipe(authPriv); KeyExchangeCrypto.wipe(encPriv)
            }
            if (!matched) {
                throw DashConnectUriException("dash-st keys were not derived from this wallet (possible forged QR)")
            }
        } finally {
            KeyExchangeCrypto.wipe(loginKey)
            KeyExchangeCrypto.wipe(authPriv)
            KeyExchangeCrypto.wipe(encPriv)
            KeyExchangeCrypto.wipe(chainKeyPrivateBytes)
        }
    }

    /** Contract ids of apps the wallet is currently connecting to, as raw 32-byte arrays. */
    private suspend fun candidateContractIds(): List<ByteArray> =
        config.getConnections().mapNotNull {
            try {
                Base58.decode(it.contractId).takeIf { bytes -> bytes.size == 32 }
            } catch (ex: Exception) {
                null
            }
        }

    // ── disconnect ───────────────────────────────────────────────────────────────

    override suspend fun disconnect(connectionId: String) {
        config.updateStatus(connectionId, ConnectionStatus.DISCONNECTED.name, System.currentTimeMillis())
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Registers the key-exchange app so [Documents.create]/queries can resolve its contract by id. */
    private fun registerContract() {
        if (!platform.platform.apps.containsKey(APP_NAME)) {
            platform.platform.apps[APP_NAME] = ClientAppDefinition(KEY_EXCHANGE_CONTRACT_ID)
        }
    }

    private fun checkTestnet() {
        if (!Constants.IS_TESTNET_BUILD) {
            throw IllegalStateException("DashConnect key-exchange is only available on testnet")
        }
    }

    private fun requireNetworkMatch(network: DashConnectNetwork) {
        val expected = when (Constants.NETWORK_PARAMETERS.id) {
            NetworkParameters.ID_MAINNET -> DashConnectNetwork.MAINNET
            NetworkParameters.ID_TESTNET -> DashConnectNetwork.TESTNET
            else -> DashConnectNetwork.DEVNET
        }
        if (network != expected) {
            throw DashConnectUriException("QR network $network does not match wallet network $expected")
        }
    }

    private fun StoredConnection.toDAppConnection(): DAppConnection = DAppConnection(
        id = contractId,
        name = label.ifBlank { "Unknown app" },
        url = url,
        status = runCatching { ConnectionStatus.valueOf(status) }.getOrDefault(ConnectionStatus.DISCONNECTED),
        updatedAt = updatedAt
    )
}
