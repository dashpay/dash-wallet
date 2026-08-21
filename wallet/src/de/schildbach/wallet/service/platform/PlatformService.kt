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
import org.dashj.platform.sdk.platform.PlatformStateRepository
import org.dashj.platform.wallet.IdentityVerify
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * Provides Platform Services, dpp
 */

interface PlatformService {
    val dpp: DashPlatformProtocol
    val platform: Platform
    val stateRepository: PlatformStateRepository
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

fun <T> platformLazy(initializer: () -> T): Lazy<T?> {
    return object : Lazy<T?> {
        private var _value: Any? = UNINITIALIZED
        override val value: T?
            get() {
                if (_value === UNINITIALIZED) {
                    _value = if (Constants.SUPPORTS_PLATFORM) {
                        initializer.invoke()
                    } else {
                        null
                    }
                }
                @Suppress("UNCHECKED_CAST")
                return _value as T?
            }

        override fun isInitialized() = _value !== UNINITIALIZED
    }
}

private object UNINITIALIZED

class PlatformServiceImplementation @Inject constructor(
    val walletDataProvider: WalletData
) : PlatformService {
    // none of the following should be initialized if platform is not supported
    private val _platform by platformLazy { Platform(Constants.NETWORK_PARAMETERS) }
    override val platform: Platform
        get() = _platform!!
    private val _profiles by platformLazy { Profiles(platform) }
    private val _contactRequests by platformLazy {  ContactRequests(platform) }
    private val _identityVerify by platformLazy {  IdentityVerify(platform) }
    override val profiles
        get() = _profiles!!
    override val contactRequests
        get() = _contactRequests!!
    override val identityVerify
        get() = _identityVerify!!
    override val dpp: DashPlatformProtocol by lazy { platform.dpp }
    override val stateRepository: PlatformStateRepository by lazy { platform.stateRepository }
    override val identities: Identities by lazy { platform.identities }
    override val names: Names by lazy { platform.names }
    override val client: DapiClient by lazy { platform.client }
    override val params: NetworkParameters = Constants.NETWORK_PARAMETERS
    private lateinit var masternodeListManager: SimplifiedMasternodeListManager

    /** One-shot flag so an unwired quorum lookup logs once, not per call. */
    @Volatile
    private var loggedUnwiredQuorumLookup = false
    companion object {
        private val log = LoggerFactory.getLogger(PlatformServiceImplementation::class.java)
    }
    init {
        val contextProvider = object : ContextProvider() {
            override fun getQuorumPublicKey(
                quorumType: Int,
                quorumHashBytes: ByteArray?,
                coreChainLockedHeight: Int
            ): ByteArray? {
                // Post-cutover (Phase 5d) the dashj engine may be held, and
                // until BlockchainServiceImpl wires the SDK-sourced quorum
                // manager this field is uninitialized. A thrown exception
                // here crosses a JNI boundary into the rust DAPI client —
                // observed live as lateinit crash spam plus banned DAPI
                // addresses — so degrade to "not found" instead (contained,
                // logged once).
                if (!::masternodeListManager.isInitialized) {
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
                    masternodeListManager.getQuorumListAtTip(
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
        _platform?.client?.contextProvider = contextProvider
    }

    override fun hasApp(app: String): Boolean {
        return platform.hasApp(app)
    }

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

    override fun setMasternodeListManager(masternodeListManager: SimplifiedMasternodeListManager) {
        this.masternodeListManager = masternodeListManager
        platform.setMasternodeListManager(masternodeListManager)
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
