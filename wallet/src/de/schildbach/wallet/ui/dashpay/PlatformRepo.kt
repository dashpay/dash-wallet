/*
 * Copyright 2020 Dash Core Group
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
package de.schildbach.wallet.ui.dashpay

import com.google.common.base.Preconditions
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.RegistrationResource
import de.schildbach.wallet.livedata.RegistrationStep
import de.schildbach.wallet.livedata.Resource
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.crypto.KeyCrypter
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.SendRequest
import org.bouncycastle.crypto.params.KeyParameter
import org.dashevo.dashpay.BlockchainIdentity
import org.dashevo.dpp.document.Document
import org.dashevo.platform.Platform

class PlatformRepo(val walletApplication: WalletApplication) {

    private val platform: Platform = walletApplication.platform

    fun isPlatformAvailable(): Resource<Boolean> {
        // this checks only one random node, but should check several.
        // it is possible that some nodes are not available due to location,
        // firewalls or other reasons
        return try {
            val response = platform.client.getStatus()
            Resource.success(response!!.connections > 0 && response.errors.isBlank() &&
                    Constants.NETWORK_PARAMETERS.getProtocolVersionNum(NetworkParameters.ProtocolVersion.MINIMUM) >= response.protocolVersion)
        } catch(e: Exception) {
            Resource.error(e.localizedMessage, null)
        }
    }

    fun getUsername(username: String): Resource<Document> {
        return try {
            var nameDocument = platform.names.get(username)
            if (nameDocument == null) {
                nameDocument = platform.names.get(username, "")
            }
            Resource.success(nameDocument)
        } catch (e: Exception) {
            Resource.error(e.localizedMessage, null)
        }
    }

    //
    // Step 1 is to upgrade the wallet to support AuthenticationKeyChains
    //
    fun addWalletAuthenticationKeys(seed: DeterministicSeed, keyParameter: KeyParameter?): RegistrationResource<Boolean> {
        val wallet = walletApplication.wallet
        val hasKeys = wallet.hasAuthenticationKeyChains()
        if(!hasKeys) {
            wallet.initializeAuthenticationKeyChains(seed, keyParameter)
            return RegistrationResource.success(RegistrationStep.UPGRADING_WALLET, hasKeys)
        }
        return RegistrationResource.success(RegistrationStep.UPGRADING_WALLET, hasKeys)
    }

    //
    // Step 2 is to create the credit funding transaction
    //
    fun createCreditFundingTransaction(blockchainIdentity: BlockchainIdentity) : RegistrationResource<Boolean> {
        return try {
            blockchainIdentity.sendCreditFundingTransaction(Coin.CENT)
            return RegistrationResource.success(RegistrationStep.CREDIT_FUNDING_TX_SENDING, true)
        } catch (e: Exception) {
            RegistrationResource.error(RegistrationStep.CREDIT_FUNDING_TX_SENDING, e,null)
        }
    }

    fun registerIdentity(blockchainIdentity: BlockchainIdentity): RegistrationResource<Boolean> {
        return try {
            blockchainIdentity.registerIdentity()
            RegistrationResource.success(RegistrationStep.IDENTITY_REGISTERING, true)

        } catch (e: Exception) {
            RegistrationResource.error(RegistrationStep.IDENTITY_REGISTERING, e,null)
        }
    }

    suspend fun isIdentityRegistered(blockchainIdentity: BlockchainIdentity): RegistrationResource<Boolean> {
        return try {
            blockchainIdentity.monitorForBlockchainIdentityWithRetryCount(10, 5000, BlockchainIdentity.RetryDelayType.SLOW20)
            RegistrationResource.success(RegistrationStep.IDENTITY_REGISTERED, true)
        } catch (e: Exception) {
            RegistrationResource.error(RegistrationStep.IDENTITY_REGISTERED, e, false)
        }
    }

    fun preorderName(blockchainIdentity: BlockchainIdentity): RegistrationResource<Boolean> {
        return try {
            val names = blockchainIdentity.getUnregisteredUsernames()
            blockchainIdentity.registerPreorderedSaltedDomainHashesForUsernames(names)
            RegistrationResource.success(RegistrationStep.PREORDER_REGISTERING, true)
        } catch (e: Exception) {
            RegistrationResource.error(RegistrationStep.PREORDER_REGISTERING, e, false)
        }
    }

    fun registerName(blockchainIdentity: BlockchainIdentity): RegistrationResource<Boolean> {
        return try {
            val names = blockchainIdentity.getUnregisteredUsernames()
            blockchainIdentity.registerUsernameDomainsForUsernames(names)
            RegistrationResource.success(RegistrationStep.USERNAME_REGISTERING, true)
        } catch (e: Exception) {
            RegistrationResource.error(RegistrationStep.USERNAME_REGISTERING, e, false)
        }
    }
}