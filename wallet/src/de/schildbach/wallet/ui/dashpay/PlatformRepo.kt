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
import de.schildbach.wallet.data.BlockchainIdentityBaseData
import de.schildbach.wallet.data.BlockchainIdentityData
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.evolution.CreditFundingTransaction
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.bouncycastle.crypto.params.KeyParameter
import org.dashevo.dapiclient.model.DocumentQuery
import org.dashevo.dashpay.BlockchainIdentity
import org.dashevo.dashpay.BlockchainIdentity.Companion.BLOCKCHAIN_USERNAME_SALT
import org.dashevo.dashpay.BlockchainIdentity.Companion.BLOCKCHAIN_USERNAME_STATUS
import org.dashevo.dashpay.ContactRequests
import org.dashevo.dashpay.Profiles
import org.dashevo.dpp.document.Document
import org.dashevo.dpp.identity.Identity
import org.dashevo.dpp.identity.IdentityPublicKey
import org.dashevo.platform.Names
import org.dashevo.platform.Platform
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeoutException

class PlatformRepo(val walletApplication: WalletApplication) {

    companion object {
        private val log = LoggerFactory.getLogger(PlatformRepo::class.java)
    }

    private val platform: Platform = walletApplication.platform

    private val blockchainIdentityDataDaoAsync = AppDatabase.getAppDatabase().blockchainIdentityDataDaoAsync()
    private val dashPayProfileDaoAsync = AppDatabase.getAppDatabase().dashPayProfileDaoAsync()


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

    /**
     * gets all the name documents for usernames starting with text
     *
     * @param text The beginning of a username to search for
     * @param userId The current userId for which to search contacts
     * @return
     */
    fun searchUsernames(text: String, userId: String): Resource<List<UsernameSearchResult>> {
        return try {
            // Names.search does support retrieving 100 names at a time if retrieveAll = false
            val nameDocuments = platform.names.search(text, Names.DEFAULT_PARENT_DOMAIN, true)

            val nameDocByUserId = nameDocuments.associateBy({ it.userId }, { it })
            var userIds = arrayListOf<String>()
            for (nameDoc in nameDocuments) {
                userIds.add(nameDoc.userId)
            }
            val dashPayDocuments = Profiles(platform).getList(userIds)

            // TODO: Replace this Platform call with a query into the local database
            val toContactDocuments = ContactRequests(platform).get(userId, toUserId = false, retrieveAll = true)

            // Get all contact requests where toUserId == userId
            val fromContactDocuments = ContactRequests(platform).get(userId, toUserId = true, retrieveAll = true)

            val usernameSearchResults = ArrayList<UsernameSearchResult>()

            // TODO: Replace this loop that processed DPP with a loop that processes the results
            // from the database query
            for (doc in dashPayDocuments) {
                var toContact: Document? = null
                var fromContact: Document? = null

                // Determine if any of our contacts match the current name's identity
                if (toContactDocuments.isNotEmpty()) {
                    toContact = toContactDocuments.find { contact ->
                        if (contact.data.containsKey("toUserId"))
                            contact.data["toUserId"] == doc.userId
                        else false
                    }
                }

                // Determine if our identity is someone else's contact
                if (fromContactDocuments.isNotEmpty()) {
                    fromContact = fromContactDocuments.find { contact ->
                        contact.userId == doc.userId
                    }
                }

                val nameDoc = nameDocByUserId[doc.userId]
                if (nameDoc != null) {
                    usernameSearchResults.add(UsernameSearchResult(nameDoc.data["normalizedLabel"] as String,
                            doc, nameDoc, toContact, fromContact))
                }
            }

            Resource.success(usernameSearchResults)
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
    // Step 3: Find the identity in the case of recovery
    //
    suspend fun recoverIdentityAsync(blockchainIdentity: BlockchainIdentity, creditFundingTransaction: CreditFundingTransaction) {
        withContext(Dispatchers.IO) {
            blockchainIdentity.recoverIdentity(creditFundingTransaction)
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

    //Step 6: Create DashPay Profile
    suspend fun createDashPayProfile(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter) {
        withContext(Dispatchers.IO) {
            val username = blockchainIdentity.currentUsername!!
            blockchainIdentity.registerProfile(username, "Hello, I'm ${username}. I was created by the Android Wallet", null, keyParameter)
        }
    }

    //
    // Step 6: Verify that the profile was registered
    //
    suspend fun verifyProfileCreatedAsync(blockchainIdentity: BlockchainIdentity) {
        withContext(Dispatchers.IO) {
            val profile = blockchainIdentity.watchProfile(10, 5000, BlockchainIdentity.RetryDelayType.SLOW20)
                    ?: throw TimeoutException("the profile was not found to be created in the allotted amount of time")

            val dashPayProfile = DashPayProfile(blockchainIdentity.uniqueIdString,
                    profile.data["displayName"] as String,
                    profile.data["publicMessage"] as String,
                    profile.data["avatarUrl"] as String)

            updateDashPayProfile(dashPayProfile)
        }
    }

    suspend fun loadBlockchainIdentityBaseData(): BlockchainIdentityBaseData? {
        return blockchainIdentityDataDaoAsync.loadBase()
    }

    suspend fun loadBlockchainIdentityData(): BlockchainIdentityData? {
        return blockchainIdentityDataDaoAsync.load()
    }

    fun initBlockchainIdentity(blockchainIdentityData: BlockchainIdentityData, wallet: Wallet): BlockchainIdentity {
        val creditFundingTransaction = blockchainIdentityData.findCreditFundingTransaction(wallet)
        if (creditFundingTransaction != null) {
            return BlockchainIdentity(Identity.IdentityType.USER, creditFundingTransaction, wallet).apply {
                currentUsername = blockchainIdentityData.username
                registrationStatus = blockchainIdentityData.registrationStatus!!
                val usernameStatus = HashMap<String, Any>()
                if (blockchainIdentityData.preorderSalt != null) {
                    usernameStatus[BLOCKCHAIN_USERNAME_SALT] = blockchainIdentityData.preorderSalt!!
                }
                if (blockchainIdentityData.usernameStatus != null) {
                    usernameStatus[BLOCKCHAIN_USERNAME_STATUS] = blockchainIdentityData.usernameStatus!!
                }
                usernameStatuses[currentUsername!!] = usernameStatus

                creditBalance = blockchainIdentityData.creditBalance ?: Coin.ZERO
                activeKeyCount = blockchainIdentityData.activeKeyCount ?: 0
                totalKeyCount = blockchainIdentityData.totalKeyCount ?: 0
                keysCreated = blockchainIdentityData.keysCreated ?: 0
                currentMainKeyIndex = blockchainIdentityData.currentMainKeyIndex ?: 0
                currentMainKeyType = blockchainIdentityData.currentMainKeyType
                        ?: IdentityPublicKey.TYPES.ECDSA_SECP256K1
            }
        }
        return BlockchainIdentity(Identity.IdentityType.USER, 0, wallet)
    }

    suspend fun updateBlockchainIdentityData(blockchainIdentityData: BlockchainIdentityData, blockchainIdentity: BlockchainIdentity) {
        blockchainIdentityData.apply {
            creditFundingTxId = blockchainIdentity.creditFundingTransaction?.txId
            registrationStatus = blockchainIdentity.registrationStatus
            if (blockchainIdentity.currentUsername != null) {
                username = blockchainIdentity.currentUsername
                if (blockchainIdentity.registrationStatus == BlockchainIdentity.RegistrationStatus.REGISTERED) {
                    preorderSalt = blockchainIdentity.saltForUsername(blockchainIdentity.currentUsername!!, false)
                    usernameStatus = blockchainIdentity.statusOfUsername(blockchainIdentity.currentUsername!!)
                }
            }
            creditBalance = blockchainIdentity.creditBalance
            activeKeyCount = blockchainIdentity.activeKeyCount
            totalKeyCount = blockchainIdentity.totalKeyCount
            keysCreated = blockchainIdentity.keysCreated
            currentMainKeyIndex = blockchainIdentity.currentMainKeyIndex
            currentMainKeyType = blockchainIdentity.currentMainKeyType
        }
        updateBlockchainIdentityData(blockchainIdentityData)
    }

    suspend fun resetCreationStateError(blockchainIdentityData: BlockchainIdentityData) {
        blockchainIdentityDataDaoAsync.updateCreationState(blockchainIdentityData.id, blockchainIdentityData.creationState, null)
        blockchainIdentityData.creationStateErrorMessage = null
    }

    suspend fun updateCreationState(blockchainIdentityData: BlockchainIdentityData,
                                    state: BlockchainIdentityData.CreationState,
                                    exception: Throwable? = null) {
        val errorMessage = exception?.run { "${exception.javaClass.simpleName}: ${exception.message}" }
        if (errorMessage == null) {
            log.info("updating creation state {}", state)
        } else {
            log.info("updating creation state {} ({})", state, errorMessage)
        }
        blockchainIdentityDataDaoAsync.updateCreationState(blockchainIdentityData.id, state, errorMessage)
        blockchainIdentityData.creationState = state
        blockchainIdentityData.creationStateErrorMessage = errorMessage
    }

    suspend fun updateBlockchainIdentityData(blockchainIdentityData: BlockchainIdentityData) {
        blockchainIdentityDataDaoAsync.insert(blockchainIdentityData)
    }

    suspend fun updateDashPayProfile(dashPayProfile: DashPayProfile) {
        dashPayProfileDaoAsync.insert(dashPayProfile)
    }

    suspend fun doneAndDismiss() {
        val blockchainIdentityData = blockchainIdentityDataDaoAsync.load()
        if (blockchainIdentityData != null && blockchainIdentityData.creationState == BlockchainIdentityData.CreationState.DONE) {
            blockchainIdentityData.creationState = BlockchainIdentityData.CreationState.DONE_AND_DISMISS
            blockchainIdentityDataDaoAsync.insert(blockchainIdentityData)
        }
    }

    //
    // Step 5: Find the usernames in the case of recovery
    //
    suspend fun recoverUsernamesAsync(blockchainIdentity: BlockchainIdentity) {
        withContext(Dispatchers.IO) {
            blockchainIdentity.recoverUsernames()
        }
    }

    //Step 6: Recover the DashPay Profile
    suspend fun recoverDashPayProfile(blockchainIdentity: BlockchainIdentity) {
        withContext(Dispatchers.IO) {
            val username = blockchainIdentity.currentUsername!!
            // recovery will only get the information and place it in the database
            val profile = blockchainIdentity.getProfile() ?: return@withContext

            // blockchainIdentity doesn't yet keep track of the profile, so we will load it
            // into the database directly
            val dashPayProfile = DashPayProfile(blockchainIdentity.uniqueIdString,
                    profile!!.data["displayName"] as String,
                    profile.data["publicMessage"] as String,
                    profile.data["avatarUrl"] as String)

            updateDashPayProfile(dashPayProfile)
        }
    }
}