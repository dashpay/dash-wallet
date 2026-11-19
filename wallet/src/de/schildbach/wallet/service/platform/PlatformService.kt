/*
 * Copyright 2023 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.service.platform

import de.schildbach.wallet.Constants
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.evolution.SimplifiedMasternodeListManager
import org.bitcoinj.quorums.LLMQParameters
import de.schildbach.wallet.data.WalletData
import org.dashj.platform.dapiclient.DapiClient
import org.dashj.platform.dashpay.ContactRequests
import org.dashj.platform.dashpay.Profiles
import org.dashj.platform.dpp.DashPlatformProtocol
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.toHex
import org.dashj.platform.sdk.callbacks.ContextProvider
import org.dashj.platform.sdk.platform.Identities
import org.dashj.platform.sdk.platform.Names
import org.dashj.platform.sdk.platform.Platform
import org.dashj.platform.wallet.IdentityVerify
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * Provides Platform Services, dpp
 */

interface PlatformService {
    val dpp: DashPlatformProtocol
    val platform: Platform
    val identities: Identities
    val names: Names
    val profiles: Profiles
    val contactRequests: ContactRequests
    val identityVerify: IdentityVerify
    val client: DapiClient
    val params: NetworkParameters

    suspend fun isPlatformAvailable(): Boolean
    fun hasApp(app: String): Boolean
    fun setMasternodeListManager(masternodeListManager: SimplifiedMasternodeListManager)

    /**
     * Fetch the identity for [userId], tolerating the legacy dashj identity
     * cache being unable to serialize an identity that carries contract-bound
     * keys. Plain [identities].get fetches the identity then caches it via
     * PlatformStateRepository.storeIdentity, whose CBOR serializer has no
     * converter for a key's `SingleContractDocumentType` bound and throws
     * `IllegalArgumentException("No converter for ...")` — AFTER the fetch
     * succeeded but BEFORE the identity is returned, so every caller of the
     * plain get loses a perfectly-good identity.
     *
     * This affects any v4.1-era identity whose keys carry contract bounds,
     * INCLUDING the wallet's own newly-registered 6-key identities (keys 4/5
     * carry `SingleContractDocumentType(dashpay, "contactRequest")`). On that
     * specific failure this refetches via the cache-bypassing DapiClient path
     * so no Platform flow is aborted by a purely-local cache limitation. See
     * [fetchIdentityToleratingCacheError].
     *
     * Prefer this over `identities.get` at every site that can encounter such
     * an identity (profile synthesis, invitations, top-ups, contact requests).
     */
    fun getIdentity(userId: Identifier): Identity?

    /** String-keyed overload of [getIdentity]; see that method's contract. */
    fun getIdentity(userId: String): Identity?

    /**
     * Contact-request entry point for [getIdentity]. Kept as a distinctly named
     * alias so the accept/receive contact-request flow reads clearly; carries
     * the same cache tolerance. See [getIdentity].
     */
    /**
     * Fetch the identity for [userId], tolerating the legacy dashj identity
     * cache being unable to serialize a v4.1-platform identity (e.g. an iOS
     * username). [identities].get fetches the identity then caches it via
     * PlatformStateRepository.storeIdentity, whose CBOR serializer throws
     * `IllegalArgumentException("No converter for ...")` on a v4.1 identity
     * shape — after the fetch succeeded but before the identity is returned,
     * so the caller loses it. On that specific failure this refetches via the
     * cache-bypassing DapiClient path so accept/receive contact-request flows
     * are not aborted by a purely-local cache limitation. See
     * [fetchIdentityToleratingCacheError].
     */
    fun getContactIdentity(userId: Identifier): Identity?
}

/**
 * [PlatformService.hasApp]'s decision: a name [registry] knows is answered from it when Platform
 * is supported, without calling [legacyHasApp] (which constructs the legacy client); every other
 * case calls [legacyHasApp], so an unknown name and an unsupported device behave as before.
 */
internal fun resolveHasApp(
    app: String,
    platformSupported: Boolean,
    registry: SystemContractIds,
    legacyHasApp: (String) -> Boolean
): Boolean {
    val registered = if (platformSupported) registry.isRegistered(app) else null
    return registered ?: legacyHasApp(app)
}

/**
 * The legacy `org.dashj.platform` client is constructed on its first genuine use, not when the
 * service is injected: its constructor loads `libsdklib`.
 *
 * [creationLock] guards creating and publishing [legacyClient] and storing and applying
 * [masternodeListManager], so concurrent first callers get one client, and the context provider,
 * the latest manager and the Sakura address handling are attached before the client is visible.
 * Code run under the lock touches only the client being wired (constructor, field setters,
 * address-provider replacement): nothing that calls back into this service or blocks on the
 * network. The JNI quorum callback reads the `@Volatile` manager without the lock.
 */
class PlatformServiceImplementation internal constructor(
    val walletDataProvider: WalletData,
    private val systemContractIds: SystemContractIds,
    private val platformSupported: Boolean,
    private val clientFactory: (NetworkParameters) -> Platform
) : PlatformService {

    @Inject
    constructor(walletDataProvider: WalletData, systemContractIds: SystemContractIds) : this(
        walletDataProvider,
        systemContractIds,
        Constants.SUPPORTS_PLATFORM,
        ::createNetworkPlatform
    )

    private val creationLock = Any()

    @Volatile
    private var legacyClient: Platform? = null

    /** Latest manager supplied (latest wins); read by the JNI quorum callback without the lock. */
    @Volatile
    private var masternodeListManager: SimplifiedMasternodeListManager? = null

    /**
     * The legacy client, created on the first call (null when Platform is not supported). A
     * failing constructor or wiring publishes nothing and propagates; the next call retries.
     */
    private fun legacyClientOrNull(): Platform? {
        if (!platformSupported) return null
        legacyClient?.let { return it }
        synchronized(creationLock) {
            legacyClient?.let { return it }
            val created = clientFactory(params)
            created.client.contextProvider = quorumContextProvider
            masternodeListManager?.let { applyMasternodeListManager(created, it) }
            legacyClient = created
            return created
        }
    }

    // none of the following should be initialized if platform is not supported
    override val platform: Platform
        get() = legacyClientOrNull()!!
    // Synchronized lazies over [platform]: each wrapper is built once, on the one client. A
    // throwing initializer leaves the lazy uninitialized, so the next read runs it again.
    private val _profiles by lazy { if (platformSupported) Profiles(platform) else null }
    private val _contactRequests by lazy { if (platformSupported) ContactRequests(platform) else null }
    private val _identityVerify by lazy { if (platformSupported) IdentityVerify(platform) else null }
    override val profiles
        get() = _profiles!!
    override val contactRequests
        get() = _contactRequests!!
    override val identityVerify
        get() = _identityVerify!!
    override val dpp: DashPlatformProtocol by lazy { platform.dpp }
    override val identities: Identities by lazy { platform.identities }
    override val names: Names by lazy { platform.names }
    override val client: DapiClient by lazy { platform.client }
    override val params: NetworkParameters = Constants.NETWORK_PARAMETERS

    /** One-shot flag so an unwired quorum lookup logs once, not per call. */
    @Volatile
    private var loggedUnwiredQuorumLookup = false
    companion object {
        private val log = LoggerFactory.getLogger(PlatformServiceImplementation::class.java)
    }

    private val quorumContextProvider = object : ContextProvider() {
        override fun getQuorumPublicKey(
            quorumType: Int,
            quorumHashBytes: ByteArray?,
            coreChainLockedHeight: Int
        ): ByteArray? {
            // Post-cutover (Phase 5d) the dashj engine may be held, and
            // until BlockchainServiceImpl wires the SDK-sourced quorum
            // manager none is set. A thrown exception here crosses a JNI
            // boundary into the rust DAPI client — observed live as
            // lateinit crash spam plus banned DAPI addresses — so degrade
            // to "not found" instead (contained, logged once).
            val manager = masternodeListManager
            if (manager == null) {
                if (!loggedUnwiredQuorumLookup) {
                    loggedUnwiredQuorumLookup = true
                    log.warn(
                        "quorum lookup before a masternode list manager is wired " +
                            "(cutover holding dashj?) — returning not-found until " +
                            "setMasternodeListManager runs"
                    )
                }
                return null
            }
            return try {
                val quorumHash = Sha256Hash.wrap(quorumHashBytes)
                var quorumPublicKey: ByteArray? = null
                log.info("searching for quorum: $quorumType, $quorumHash, $coreChainLockedHeight")
                walletDataProvider.wallet?.context?.let { Context.propagate(it) }
                manager.getQuorumListAtTip(
                    LLMQParameters.LLMQType.fromValue(
                        quorumType
                    )
                ).forEachQuorum(true) {
                    if (it.llmqType.value == quorumType && it.quorumHash == quorumHash) {
                        quorumPublicKey = it.quorumPublicKey.serialize(false)
                    }
                }
                log.info("searching for quorum: result: ${quorumPublicKey?.toHex()}")
                quorumPublicKey
            } catch (e: Exception) {
                // Never throw across the JNI callback boundary.
                log.warn("quorum lookup failed: {}", e.toString())
                null
            }
        }

        override fun getDataContract(identifier: org.dashj.platform.sdk.Identifier?): ByteArray {
            TODO("Not yet implemented")
        }
    }

    /**
     * Whether [app] has a contract registered on this network. A name the wallet-owned
     * [SystemContractIds] registry knows is answered from it without constructing the legacy
     * `Platform` client (same answer as the legacy `Platform.hasApp`, whose constructor registers
     * those names). Any other name (a custom app a legacy caller registered at runtime, such as
     * DashConnect's) still asks the legacy client. When Platform is unsupported on this device the
     * legacy getter is used for every name, keeping its existing failure.
     */
    override fun hasApp(app: String): Boolean =
        resolveHasApp(app, Constants.SUPPORTS_PLATFORM, systemContractIds) { platform.hasApp(it) }

    /**
     * Calls Platform.check() three times asynchronously
     *
     * @return true if platform is available
     */
    override suspend fun isPlatformAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            var success = 0
            val checks = arrayListOf<Deferred<Boolean>>()
            for (i in 0 until 3) {
                checks.add(async {
                    try {
                        platform.check()
                    } catch (e: Exception) {
                        return@async false
                    }
                })
            }

            for (check in checks) {
                success += if (check.await()) 1 else 0
            }
            log.info("platform available $success of 3: ${ success > 2}")
            return@withContext success >= 2
        }
    }

    /**
     * Stores [masternodeListManager] (latest wins) without constructing the legacy client, and
     * applies it to the client if one exists; a client created later gets it at creation.
     */
    override fun setMasternodeListManager(masternodeListManager: SimplifiedMasternodeListManager) {
        synchronized(creationLock) {
            this.masternodeListManager = masternodeListManager
            legacyClient?.let { applyMasternodeListManager(it, masternodeListManager) }
        }
    }

    /** Runs under [creationLock]. */
    private fun applyMasternodeListManager(client: Platform, manager: SimplifiedMasternodeListManager) {
        val configuredAddresses = client.client.dapiAddressListProvider
        client.setMasternodeListManager(manager)
        if (params is de.schildbach.wallet.network.SakuraDevNetParams) {
            // Keep Sakura's explicit HTTPS ports; the SDK's legacy discovery replaces them.
            client.client.dapiAddressListProvider = configuredAddresses
        }
    }

    override fun getIdentity(userId: Identifier): Identity? =
        fetchIdentityToleratingCacheError(
            cachedGet = { identities.get(userId) },
            cacheBypassingFetch = {
                // The identity WAS fetched; only the legacy in-memory CBOR
                // cache write rejected its contract-bound-key shape. Refetch
                // straight from the DAPI client (mirrors
                // PlatformStateRepository.fetchIdentity's own
                // client.getIdentity(id.toBuffer(), true) call), which never
                // touches storeIdentity, so the identity is recovered uncached.
                log.warn(
                    "identity {} fetched but the legacy CBOR cache rejected its " +
                        "contract-bound-key shape; bypassing the cache",
                    userId
                )
                client.getIdentity(userId.toBuffer(), true)
            }
        )

    override fun getIdentity(userId: String): Identity? =
        getIdentity(Identifier.from(userId))

    override fun getContactIdentity(userId: Identifier): Identity? =
        getIdentity(userId)
}
