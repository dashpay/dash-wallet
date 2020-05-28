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

import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainIdentityData
import de.schildbach.wallet.livedata.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.bouncycastle.crypto.params.KeyParameter
import org.dashevo.dashpay.BlockchainIdentity
import org.dashevo.dpp.document.Document
import org.dashevo.dpp.identity.Identity
import org.dashevo.dpp.identity.IdentityPublicKey
import org.dashevo.platform.Names
import org.dashevo.platform.Platform
import java.util.concurrent.TimeoutException

class PlatformRepo(val walletApplication: WalletApplication) {

    private val platform: Platform = walletApplication.platform

    private val blockchainIdentityDataDaoAsync = AppDatabase.getAppDatabase().blockchainIdentityDataDaoAsync()

    fun isPlatformAvailable(): Resource<Boolean> {
        // this checks only one random node, but should check several.
        // it is possible that some nodes are not available due to location,
        // firewalls or other reasons
        return try {
            val response = platform.client.getStatus()
            Resource.success(response!!.connections > 0 && response.errors.isBlank() &&
                    Constants.NETWORK_PARAMETERS.getProtocolVersionNum(NetworkParameters.ProtocolVersion.MINIMUM) >= response.protocolVersion)
        } catch (e: Exception) {
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
    // Step 1 is to upgrade the wallet to support authentication keys
    //
    suspend fun addWalletAuthenticationKeysAsync(seed: DeterministicSeed, keyParameter: KeyParameter?) {
        withContext(Dispatchers.IO) {
            val wallet = walletApplication.wallet
            val hasKeys = wallet.hasAuthenticationKeyChains()
            if (!hasKeys) {
                wallet.initializeAuthenticationKeyChains(seed, keyParameter)
            }
        }
    }

    //
    // Step 2 is to create the credit funding transaction
    //
    suspend fun createCreditFundingTransactionAsync(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter?) {
        withContext(Dispatchers.IO) {
            Context.propagate(walletApplication.wallet.context)
            val cftx = blockchainIdentity.createCreditFundingTransaction(Coin.CENT, keyParameter)
            blockchainIdentity.initializeCreditFundingTransaction(cftx)
        }
    }

    //
    // Step 3: Register the identity
    //
    suspend fun registerIdentityAsync(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter?) {
        withContext(Dispatchers.IO) {
            blockchainIdentity.registerIdentity(keyParameter)
        }
    }

    //
    // Step 3: Verify that the identity is registered
    //
    suspend fun verifyIdentityRegisteredAsync(blockchainIdentity: BlockchainIdentity) {
        withContext(Dispatchers.IO) {
            blockchainIdentity.watchIdentity(10, 5000, BlockchainIdentity.RetryDelayType.SLOW20)
                    ?: throw TimeoutException("the identity was not found to be registered in the allotted amount of time")
        }
    }

    //
    // Step 4: Preorder the username
    //
    suspend fun preorderNameAsync(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter?) {
        withContext(Dispatchers.IO) {
            val names = blockchainIdentity.getUnregisteredUsernames()
            blockchainIdentity.registerPreorderedSaltedDomainHashesForUsernames(names, keyParameter)
        }
    }

    //
    // Step 4: Verify that the username was preordered
    //
    suspend fun isNamePreorderedAsync(blockchainIdentity: BlockchainIdentity) {
        withContext(Dispatchers.IO) {
            val set = blockchainIdentity.getUsernamesWithStatus(BlockchainIdentity.UsernameStatus.PREORDER_REGISTRATION_PENDING)
            val saltedDomainHashes = blockchainIdentity.saltedDomainHashesForUsernames(set)
            val (result, usernames) = blockchainIdentity.watchPreorder(saltedDomainHashes, 10, 5000, BlockchainIdentity.RetryDelayType.SLOW20)
            if (!result) {
                throw TimeoutException("the usernames: $usernames were not found to be preordered in the allotted amount of time")
            }
        }
    }

    //
    // Step 5: Register the username
    //
    suspend fun registerNameAsync(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter?) {
        withContext(Dispatchers.IO) {
            val names = blockchainIdentity.preorderedUsernames()
            blockchainIdentity.registerUsernameDomainsForUsernames(names, keyParameter)
        }
    }

    //
    // Step 5: Verify that the username was registered
    //
    suspend fun isNameRegisteredAsync(blockchainIdentity: BlockchainIdentity) {
        withContext(Dispatchers.IO) {
            val (result, usernames) = blockchainIdentity.watchUsernames(blockchainIdentity.getUsernamesWithStatus(BlockchainIdentity.UsernameStatus.REGISTRATION_PENDING), 10, 5000, BlockchainIdentity.RetryDelayType.SLOW20)
            if (!result) {
                throw TimeoutException("the usernames: $usernames were not found to be registered in the allotted amount of time")
            }
        }
    }

    suspend fun initBlockchainIdentityData(username: String): BlockchainIdentityData {
        return blockchainIdentityDataDaoAsync.load()
                ?: BlockchainIdentityData(BlockchainIdentityData.State.UPGRADING_WALLET, false, username)
    }

    fun initBlockchainIdentity(blockchainIdentityData: BlockchainIdentityData, wallet: Wallet): BlockchainIdentity {
        if (blockchainIdentityData.creditFundingTxId != null) {
            val creditFundingTx = wallet.getTransaction(blockchainIdentityData.creditFundingTxId)
            if (creditFundingTx != null) {
                val creditFundingTransaction = wallet.getCreditFundingTransaction(creditFundingTx)
                return BlockchainIdentity(Identity.IdentityType.USER, creditFundingTransaction, wallet).apply {
                    currentUsername = blockchainIdentityData.username
                    // should we load `preorderSalt` somehow?
                    registrationStatus = blockchainIdentityData.registrationStatus!!
                    // should we load `usernameStatus` somehow?
                    // should we load `domain` somehow?
                    creditBalance = blockchainIdentityData.creditBalance ?: Coin.ZERO
                    activeKeyCount = blockchainIdentityData.activeKeyCount ?: 0
                    totalKeyCount = blockchainIdentityData.totalKeyCount ?: 0
                    keysCreated = blockchainIdentityData.keysCreated ?: 0
                    currentMainKeyIndex = blockchainIdentityData.currentMainKeyIndex ?: 0
                    currentMainKeyType = blockchainIdentityData.currentMainKeyType
                            ?: IdentityPublicKey.TYPES.ECDSA_SECP256K1
                }
            }
        }
        return BlockchainIdentity(Identity.IdentityType.USER, 0, wallet)
    }

    suspend fun updateBlockchainIdentityData(blockchainIdentityData: BlockchainIdentityData, blockchainIdentity: BlockchainIdentity) {
        blockchainIdentityData.apply {
            if (blockchainIdentity.creditFundingTransaction != null) {
                creditFundingTxId = blockchainIdentity.creditFundingTransaction!!.txId
            }
            registrationStatus = blockchainIdentity.registrationStatus
            if (blockchainIdentity.currentUsername != null &&
                    blockchainIdentity.registrationStatus == BlockchainIdentity.RegistrationStatus.REGISTERED) {
                domain = Names.DEFAULT_PARENT_DOMAIN
                username = blockchainIdentity.currentUsername
                preorderSalt = blockchainIdentity.saltForUsername(blockchainIdentity.currentUsername!!, false)
                usernameStatus = blockchainIdentity.statusOfUsername(blockchainIdentity.currentUsername!!)

                // should we load `preorderSalt` somehow?
                registrationStatus = blockchainIdentity.registrationStatus
                // should we load `usernameStatus` somehow?
                // should we load `domain` somehow?
                creditBalance = blockchainIdentity.creditBalance
                activeKeyCount = blockchainIdentity.activeKeyCount
                totalKeyCount = blockchainIdentity.totalKeyCount
                keysCreated = blockchainIdentity.keysCreated
                currentMainKeyIndex = blockchainIdentity.currentMainKeyIndex
                currentMainKeyType = blockchainIdentity.currentMainKeyType
            }
        }
        blockchainIdentityDataDaoAsync.insert(blockchainIdentityData)
    }
}