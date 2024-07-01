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
import de.schildbach.wallet.livedata.Resource
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.evolution.SimplifiedMasternodeListManager
import org.bitcoinj.quorums.LLMQParameters
import org.dash.wallet.common.WalletDataProvider
import org.dashj.platform.dapiclient.DapiClient
import org.dashj.platform.dashpay.ContactRequests
import org.dashj.platform.dashpay.Profiles
import org.dashj.platform.dpp.DashPlatformProtocol
import org.dashj.platform.dpp.toHex
import org.dashj.platform.sdk.callbacks.ContextProvider
import org.dashj.platform.sdk.platform.Identities
import org.dashj.platform.sdk.platform.Names
import org.dashj.platform.sdk.platform.Platform
import org.dashj.platform.sdk.platform.PlatformStateRepository
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
    val client: DapiClient
    val params: NetworkParameters

    suspend fun isPlatformAvailable(): Boolean
    fun hasApp(app: String): Boolean
    fun setMasternodeListManager(masternodeListManager: SimplifiedMasternodeListManager)
}

class PlatformServiceImplementation @Inject constructor(
    walletDataProvider: WalletDataProvider
) : PlatformService {
    override val platform = Platform(Constants.NETWORK_PARAMETERS)
    override val profiles = Profiles(platform)
    override val contactRequests = ContactRequests(platform)
    override val dpp: DashPlatformProtocol = platform.dpp
    override val stateRepository: PlatformStateRepository = platform.stateRepository
    override val identities: Identities = platform.identities
    override val names: Names = platform.names
    override val client: DapiClient = platform.client
    override val params: NetworkParameters = platform.params

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
                val quorumHash = Sha256Hash.wrap(quorumHashBytes)
                var quorumPublicKey: ByteArray? = null
                log.info("searching for quorum: $quorumType, $quorumHash, $coreChainLockedHeight")
                Context.propagate(walletDataProvider.wallet!!.context)
                Context.get().masternodeListManager.getQuorumListAtTip(
                    LLMQParameters.LLMQType.fromValue(
                        quorumType
                    )
                ).forEachQuorum(true) {
                    if (it.llmqType.value == quorumType && it.quorumHash == quorumHash) {
                        quorumPublicKey = it.quorumPublicKey.serialize(false)
                    }
                }
                log.info("searching for quorum: result: ${quorumPublicKey?.toHex()}")
                return quorumPublicKey
            }

            override fun getDataContract(identifier: org.dashj.platform.sdk.Identifier?): ByteArray {
                TODO("Not yet implemented")
            }
        }
        platform.client.contextProvider = contextProvider
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

            return@withContext success >= 2
            //return@withContext Resource.success(true)
        }
    }

    override fun setMasternodeListManager(masternodeListManager: SimplifiedMasternodeListManager) {
        platform.setMasternodeListManager(masternodeListManager)
    }
}
