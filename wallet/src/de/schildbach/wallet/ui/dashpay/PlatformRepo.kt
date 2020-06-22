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

import com.google.common.base.Stopwatch
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainIdentityBaseData
import de.schildbach.wallet.data.BlockchainIdentityData
import de.schildbach.wallet.data.DashPayContactRequest
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.evolution.CreditFundingTransaction
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.bouncycastle.crypto.params.KeyParameter
import org.dashevo.dashpay.BlockchainIdentity
import org.dashevo.dashpay.BlockchainIdentity.Companion.BLOCKCHAIN_USERNAME_SALT
import org.dashevo.dashpay.BlockchainIdentity.Companion.BLOCKCHAIN_USERNAME_STATUS
import org.dashevo.dashpay.ContactRequests
import org.dashevo.dashpay.Profiles
import org.dashevo.dpp.document.Document
import org.dashevo.dpp.identity.Identity
import org.dashevo.dpp.identity.IdentityPublicKey
import org.dashevo.dpp.util.Entropy
import org.dashevo.dpp.util.HashUtils
import org.dashevo.platform.Names
import org.dashevo.platform.Platform
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeoutException

class PlatformRepo(val walletApplication: WalletApplication) {

    companion object {
        private val log = LoggerFactory.getLogger(PlatformRepo::class.java)
    }

    private val platform: Platform = walletApplication.platform
    private val profiles = Profiles(platform)

    private val blockchainIdentityDataDaoAsync = AppDatabase.getAppDatabase().blockchainIdentityDataDaoAsync()
    private val dashPayProfileDaoAsync = AppDatabase.getAppDatabase().dashPayProfileDaoAsync()
    private val dashPayContactRequestDaoAsync = AppDatabase.getAppDatabase().dashPayContactRequestDaoAsync()


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
     * @return
     */
    suspend fun searchUsernames(text: String): Resource<List<UsernameSearchResult>> {
        return try {
            val wallet = walletApplication.wallet
            val blockchainIdentity = blockchainIdentityDataDaoAsync.load()
            //We don't check for nullity here because if it's null, it'll be thrown, captured below
            //and sent as a Resource.error
            val creditFundingTx = wallet.getCreditFundingTransaction(wallet.getTransaction(blockchainIdentity!!.creditFundingTxId))
            val userId = creditFundingTx.creditBurnIdentityIdentifier.toStringBase58()
            // Names.search does support retrieving 100 names at a time if retrieveAll = false
            //TODO: Maybe add pagination later? Is very unlikely that a user will scroll past 100 search results
            val nameDocuments = platform.names.search(text, Names.DEFAULT_PARENT_DOMAIN, false)

            val userIds = nameDocuments.map { it.userId }

            val profileDocuments = Profiles(platform).getList(userIds)
            val profileById = profileDocuments.associateBy({ it.userId }, { it })

            val toContactDocuments = dashPayContactRequestDaoAsync.loadToOthers(userId)
                    ?: arrayListOf()

            // Get all contact requests where toUserId == userId
            val fromContactDocuments = dashPayContactRequestDaoAsync.loadFromOthers(userId)
                    ?: arrayListOf()

            val usernameSearchResults = ArrayList<UsernameSearchResult>()

            for (nameDoc in nameDocuments) {
                //Remove own user document from result
                if (nameDoc.userId == userId) {
                    continue
                }
                var toContact: DashPayContactRequest? = null
                var fromContact: DashPayContactRequest? = null

                // Determine if any of our contacts match the current name's identity
                if (toContactDocuments.isNotEmpty()) {
                    toContact = toContactDocuments.find { contact ->
                        contact.toUserId == nameDoc.userId
                    }
                }

                // Determine if our identity is someone else's contact
                if (fromContactDocuments.isNotEmpty()) {
                    fromContact = fromContactDocuments.find { contact ->
                        contact.userId == nameDoc.userId
                    }
                }

                val username = nameDoc.data["normalizedLabel"] as String
                val profileDoc = profileById[nameDoc.userId]

                val dashPayProfile = if (profileDoc != null)
                    DashPayProfile.fromDocument(profileDoc, username)!!
                else DashPayProfile(nameDoc.userId, username)

                usernameSearchResults.add(UsernameSearchResult(nameDoc.data["normalizedLabel"] as String,
                        dashPayProfile, toContact, fromContact))
            }

            Resource.success(usernameSearchResults)
        } catch (e: Exception) {
            Resource.error(e.localizedMessage, null)
        }
    }

    /**
     * search the contacts
     *
     * @param text the text to find in usernames and displayNames.  if blank, all contacts are returned
     * @param orderBy the field that is used to sort the list of matching entries in ascending order
     * @return
     */
    suspend fun searchContacts(text: String, orderBy: UsernameSortOrderBy): Resource<List<UsernameSearchResult>> {
        return try {
            // TODO: Replace this Platform call with a query into the local database
            val userIdList = HashSet<String>()

            val wallet = walletApplication.wallet
            val blockchainIdentity = blockchainIdentityDataDaoAsync.load()
            val creditFundingTx = wallet.getCreditFundingTransaction(wallet.getTransaction(blockchainIdentity!!.creditFundingTxId))
            val userId = creditFundingTx.creditBurnIdentityIdentifier.toStringBase58()

            var toContactDocuments = dashPayContactRequestDaoAsync.loadToOthers(userId)
            val toContactMap = HashMap<String, DashPayContactRequest>()
            toContactDocuments!!.forEach {
                userIdList.add(it.toUserId)
                toContactMap[it.toUserId] = it
            }
            // Get all contact requests where toUserId == userId, the users who have added me
            val fromContactDocuments = dashPayContactRequestDaoAsync.loadFromOthers(userId)
            val fromContactMap = HashMap<String, DashPayContactRequest>()
            fromContactDocuments!!.forEach {
                userIdList.add(it.userId)
                fromContactMap[it.userId] = it
            }

            val profiles = HashMap<String, DashPayProfile?>(userIdList.size)
            for (user in userIdList) {
                val profile = dashPayProfileDaoAsync.load(user)
                profiles[user] = profile
            }

            val usernameSearchResults = ArrayList<UsernameSearchResult>()
            val searchText = text.toLowerCase()

            for (profile in profiles) {
                var toContact: DashPayContactRequest? = null
                var fromContact: DashPayContactRequest? = null

                // find matches where the text matches part of the username or displayName
                // if the text is blank, match everything
                val username = profile.value!!.username
                val displayName = profile.value!!.displayName
                val usernameContainsSearchText = username.findLastAnyOf(listOf(searchText), ignoreCase = true) != null ||
                        displayName.findLastAnyOf(listOf(searchText), ignoreCase = true) != null
                if (!usernameContainsSearchText && searchText != "") {
                    continue
                }

                // Determine if this identity is our contact
                toContact = toContactMap[profile.value!!.userId]

                // Determine if I am this identity's contact
                fromContact = fromContactMap[profile.value!!.userId]

                usernameSearchResults.add(UsernameSearchResult(profile.value!!.username,
                        profile.value!!, toContact, fromContact))
            }
            when (orderBy) {
                UsernameSortOrderBy.DISPLAY_NAME -> usernameSearchResults.sortBy {
                    if (it.dashPayProfile.displayName.isNotEmpty())
                        it.dashPayProfile.displayName.toLowerCase()
                    else it.dashPayProfile.username.toLowerCase()
                }
                UsernameSortOrderBy.USERNAME -> usernameSearchResults.sortBy { it.dashPayProfile.username.toLowerCase() }
                //TODO: sort by last activity or date added
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
            blockchainIdentity.registerProfile(username, "", "", keyParameter)
        }
    }

    //
    // Step 6: Verify that the profile was registered
    //
    suspend fun verifyProfileCreatedAsync(blockchainIdentity: BlockchainIdentity) {
        withContext(Dispatchers.IO) {
            val profile = blockchainIdentity.watchProfile(10, 5000, BlockchainIdentity.RetryDelayType.SLOW20)
                    ?: throw TimeoutException("the profile was not found to be created in the allotted amount of time")

            val dashPayProfile = DashPayProfile.fromDocument(profile, blockchainIdentity.currentUsername!!)

            updateDashPayProfile(dashPayProfile!!)
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

    private suspend fun updateDashPayProfile(dashPayProfile: DashPayProfile) {
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
            val dashPayProfile = DashPayProfile.fromDocument(profile, username)
            updateDashPayProfile(dashPayProfile!!)
        }
    }

    // contacts

    suspend fun updateContactRequests(userId: String) {

        val userIdList = HashSet<String>()
        val watch = Stopwatch.createStarted()

        //TODO: remove this when removing the fake contact list
        dashPayContactRequestDaoAsync.clear()
        dashPayProfileDaoAsync.clear()

        try {

            // Get all out our contact requests
            val toContactDocuments = ContactRequests(platform).get(userId, toUserId = false, retrieveAll = true)
            toContactDocuments.forEach {
                val toUserId = it.data["toUserId"] as String
                userIdList.add(toUserId)
                val privateData = if (it.data.containsKey("privateData"))
                    HashUtils.byteArrayFromString(it.data["privateData"] as String)
                else null
                val contactRequest = DashPayContactRequest(it.entropy, it.userId, it.data["toUserId"] as String,
                        privateData,
                        HashUtils.byteArrayFromString(it.data["encryptedPublicKey"] as String),
                        it.data["senderKeyIndex"] as Int,
                        it.data["recipientKeyIndex"] as Int,
                        it.data["timestamp"] as Double, false, 0)
                dashPayContactRequestDaoAsync.insert(contactRequest)
            }
            // Get all contact requests where toUserId == userId, the users who have added me
            val fromContactDocuments = ContactRequests(platform).get(userId, toUserId = true, retrieveAll = true)
            fromContactDocuments.forEach {
                userIdList.add(it.userId)
                val privateData = if (it.data.containsKey("privateData"))
                    HashUtils.byteArrayFromString(it.data["privateData"] as String)
                else null
                val contactRequest = DashPayContactRequest(it.entropy, it.userId, it.data["toUserId"] as String,
                        privateData,
                        HashUtils.byteArrayFromString(it.data["encryptedPublicKey"] as String),
                        it.data["senderKeyIndex"] as Int,
                        it.data["recipientKeyIndex"] as Int,
                        it.data["timestamp"] as Double, false, 0)
                dashPayContactRequestDaoAsync.insert(contactRequest)
            }

            val profileDocuments = Profiles(platform).getList(userIdList.toList()) //only handles 100 userIds
            val profileById = profileDocuments.associateBy({ it.userId }, { it })

            val nameDocuments = platform.names.getList(userIdList.toList())
            val nameById = nameDocuments.associateBy({ it.userId }, { it })

            for (id in userIdList) {
                val nameDocument = nameById[id] // what happens if there is no username for the identity? crash
                val username = nameDocument!!.data["normalizedLabel"] as String

                val profileDocument = profileById[id] ?: profiles.createProfileDocument("", "",
                        "", platform.identities.get(nameDocument!!.userId)!!)

                val profile = DashPayProfile.fromDocument(profileDocument, username)
                dashPayProfileDaoAsync.insert(profile!!)
            }

            // lets add more data

            val ourRequests = listOf(listOf("dashpayer", "FW2BGfVdTLgGWGkJRjC838MPpEcL2cSfkNkwao8ooxm5"),
                    listOf("realdashpay", "CUH2fD5nf8Pm1G6rv3oj4ivFjLoPkzyEW8VMuNmJ27e2"),
                    listOf("test10", "k6yBwjYdJkwDEY1yVC7ZzgTwA9CtPtBAcMFTHX7R53p"),
                    listOf("5dfs", "5quW27UcW1kbMJR8s4qMrFX32sNuBQ4LxYjfuM1ECQP3"),
                    listOf("dalek", "6pk9geGeDwvGwmFxEVHDSBd1dQhuhALp3pe3cpNKipBx"),
                    listOf("rockstar", "7G3BeUyLcncJQaBgUn4k9t5WtH4DRbjDTHHyGpY1e1nm"),
                    listOf("cyberman", "6hQvAB5fmk9S7SXA9NeMNRQNPkgnNtzweNKow26gqXCz"),
                    listOf("cable", "2fXFCQrwhxSPZLrTxrsA9wfiH33Yr7EAbLZe2QceMBw7"),
                    listOf("demouser11", "8UbMuDixbjCPz2wYWMwauDuNSTJRajHrQ1PjNSjabR7U"),
                    listOf("dashdemo1", "EhezCM1jSkErP7xUzSZChjAfT9dBzGpVUCAPoiFE8FMX"),
                    listOf("demodash1", "VrNsS7JPg8watdqu7iPrQrJqCm2EZgAMyKSqTcpSpXQ"),
                    listOf("tomcat", "79rrczQfjkeFh6bYdnwJj6FpAsw7hvqgPweGwPHz98QR"),
                    listOf("jerry", "Cfw2ATTFqPA86ryLepcZFD6cM1uP3G5FmNuUxqmNNeGE"),
                    listOf("mikeeee4", "9UBX4QKREkmFJbZFD3taJr3cCHwMDvgZ5ToTTzFaYf3L"),
                    listOf("shaggy", "DeEk46CZy27kCiPK52J8LbTAg6EiQiF6JJrVrhFn1i71"),
                    listOf("scooby", "9fQhNtj3iUZjumT2VA3V8t88MwkNqSWcLUPfCLyBiiRK"),
                    listOf("dashhelpline", "DXShMkwAp5Jvvx5KuAuv4spdxyVYpxh2LSRoSYMP4cao"),
                    listOf("kpopstar", "7daTP2h8bGSe1YTp9rRy2JjYg4AZyenpLoUXHUpHKexi"),
                    listOf("dashdemo3", "6vNR3edCoiKeYd4WVGVw567esopi1uVtKkFtNN4N9jEk"),
                    listOf("dashdemo4", "DqYydjVXnxyPPrJULWwvxMNZJnB85MkVcGfSPp9CWrGS"),
                    listOf("dashdemo4", "DqYydjVXnxyPPrJULWwvxMNZJnB85MkVcGfSPp9CWrGS"),
                    listOf("eric10", "4WgYVkpHZraSmUWZqk2u5uyx3uwXUf9GaJ7BTij82pcv"),
                    listOf("deathmetalstar", "Dxui3pnYYRCpNahx5UTHghWWn2LW4aUgaEnZ3UsDTGfB"),
                    listOf("rapstar", "FYrjvdRcS2t3rqhK8o51xcdiXJSde85K8SmdL9pbuetD"),
                    listOf("kelly", "9iLtJdDWAxkZ3QdX9PuMGtJKDdjGewoWM1fkb1nHNC1t"),
                    listOf("kelly1", "2U3rT8XAMrwM7mmWTqb6hm7KvKE5x2dW3UgfFMLXqDMp"),
                    listOf("kelly5", "Fuhw2gBvvmZ89TR3tpMbx5zSfxGe7ZDjLcvPs4QyPs33"),
                    listOf("kelly6", "H9VzPiwgKu93v6fS6u1r11bLnLEYdfwtX6R4vBsZGa9x"),
                    listOf("kelly7", "3qU6K65Hm2a8KhMHdBgRDTeXqNj96RPDAZFThWzXBc34"),
                    listOf("samb", "HkxSLmmLGCTT1oVSdtzekG5DhJiZUfhVphkpA1S3yeRY"),
                    listOf("samb1", "GivG6LETNmBECvtq2qnTpfDQb4PiXTRW8GvtPWCMht3K"),
                    listOf("qwe1", "GEeqNGNi5uLk23F58QtQfX62X1b8Yv2iW9XJWbHs5MuY"),
                    listOf("asd1", "HX9mLCxZ6LFvPy71pvzwymzWzu9KmThxeJ1VV2d1jtyo"),
                    listOf("samb2", "D4MhE1HNhZexFswGK5zeceTRuk3rcSVKeTC9hMZ8x1NY"),
                    listOf("popstar", "CdoB8VH3YwZBhVchEhhZzik4Chx61EtBDxh8qV1AWM9X"),
                    listOf("rapstar1", "6vPVM3Dy1f6TKvoaFgZRUSLSQtUdJsqAEXuLShJJB9jz"),
                    listOf("samb3", "5txbSagBKpArZwHorruZ61SEFvF6zsYYE1cZfJvtkEvV"),
                    listOf("yw5", "6VKsEgnUtba6PMcRub8ydNrtZZE1bLzNHHDfRJmb5cUj"),
                    listOf("ynb", "CsvgFtcSiC3yzPrW9txFa4bPfGKEdykMWMfuNZoSdXbu"),
                    listOf("laforge", "DJW7iaL8SxKWWepiBMU922sHbtPgCJtqocZeUgGDha6B"),
                    listOf("crusher", "DJW7iaL8SxKWWepiBMU922sHbtPgCJtqocZeUgGDha6B"),
                    listOf("wesley", "DJW7iaL8SxKWWepiBMU922sHbtPgCJtqocZeUgGDha6B"),
                    listOf("locutus", "DJW7iaL8SxKWWepiBMU922sHbtPgCJtqocZeUgGDha6B"),
                    listOf("sydney", "7wtTHcTSSfF8zeua9tCTqgaa4yC5zwYB1QpBpkUTLn3u"),
                    listOf("cooper", "2NS54HYRpNyM5p3sdwKt4bNUkQM2yCWbtDTiagw3XXVe"),
                    listOf("tricia", "EAFFW5uvbVLNNXysSNCmQYrPJcMFoxjhMBD4bp5AcYrZ"),
                    listOf("cookie", "HHgHazrgQBBA1qpgRXpRwkSLLj9nPezpSA2qvC7d4vMC"),
                    listOf("hugh", "DJW7iaL8SxKWWepiBMU922sHbtPgCJtqocZeUgGDha6B"),
                    listOf("tomasz", "CvmhjWgDREb4qMGDUQRyZz3TEYLaf1dFnnxvcAqjVhSr")
            )

            val theirRequests = listOf(
                    listOf("asd1", "HX9mLCxZ6LFvPy71pvzwymzWzu9KmThxeJ1VV2d1jtyo"),
                    listOf("samb2", "D4MhE1HNhZexFswGK5zeceTRuk3rcSVKeTC9hMZ8x1NY"),
                    listOf("popstar", "CdoB8VH3YwZBhVchEhhZzik4Chx61EtBDxh8qV1AWM9X"),
                    listOf("rapstar1", "6vPVM3Dy1f6TKvoaFgZRUSLSQtUdJsqAEXuLShJJB9jz"),
                    listOf("yw5", "6VKsEgnUtba6PMcRub8ydNrtZZE1bLzNHHDfRJmb5cUj"),
                    listOf("ynb", "CsvgFtcSiC3yzPrW9txFa4bPfGKEdykMWMfuNZoSdXbu"),
                    listOf("laforge", "DJW7iaL8SxKWWepiBMU922sHbtPgCJtqocZeUgGDha6B"),
                    listOf("crusher", "DJW7iaL8SxKWWepiBMU922sHbtPgCJtqocZeUgGDha6B"),
                    listOf("odo", "DJW7iaL8SxKWWepiBMU922sHbtPgCJtqocZeUgGDha6B"),
                    listOf("morn", "DJW7iaL8SxKWWepiBMU922sHbtPgCJtqocZeUgGDha6B"),
                    listOf("bento", "4ZmGNg65BWbW2S471gEhxR3QTjT6ohnRM4Dz8iSSPFpw"),
                    listOf("john420", "4TRcDT6rkBwEoNcHRZLTfVwX9zUCTk9iBreVrsttQ5dB"),
                    listOf("john421", "9uJupoiiBRD8xmGDor6ZkMyqs6ANh7aaytyQauzH8BQy"),
                    listOf("sambb8", "6HwNjBRfeLiz6onNBZKbuQ3BPVwgEqVaYTCGcataCTJX"),
                    listOf("sambbb", "FXP8vfHYxjjbQ7o99Hio1gcRMAiXYuNcKP4c3mDjnqeq"),
                    listOf("john422", "8EvPRm5idvVkdioWvT1m6eKwUoWxxCyQUAH2P8rysyxB"),
                    listOf("tomasz", "CvmhjWgDREb4qMGDUQRyZz3TEYLaf1dFnnxvcAqjVhSr"),
                    listOf("john443", "AHyrB7y3KAKKotUh1CtTnPzRH5Ac3hSUze3JNempjA2y"),
                    listOf("ericthebowler", "7NYPJKDHxo6mwgsvRMDLHSVr5KkGWVVNMtRAr6XXCuWj"),
                    listOf("username", "5nt3CMHZfTvdReAfdvc7QensZGD17R9VoCA62UVqbaed"),
                    listOf("ericthejanitor", "39mm4r82cQgkXrmznn5bEAEgAaqww4HMk2fB34Ce5PzB"),
                    listOf("ericthemanager", "3bj8YeYhfzgToYa2mmiz4rBckN16tjg3gGwsCzj7h5o4"),
                    listOf("usernametomasz", "6kBLamnAgjT8hZbMoKRXhB9kyhHhJopbXhFk2NTGDcFv"),
                    listOf("tomasz30", "FfZyLhF8NQjSGUteFHcz4bnhijpDY9ds89y6F7zyjpet"),
                    listOf("samb001", "5ABfg81tMakD7vagnkCBjDpiajmfK2FVRajzm8NZnx9C"),
                    listOf("ericthedoctor", "5UpPbXqU4P3Rfh1xDq9i7VEW6xb3pQUzjENeoH5hk6pJ"),
                    listOf("erictheengineer", "APNJwjJgWNN7e7y6pgi1MdhG2kCbNAMqzUJ4ZPQNpyxJ"),
                    listOf("samb002", "47uwukQwyTncmRzJxjMv4ctj5gXH8x4zci4bkaHmWHWC"),
                    listOf("yahoo123", "BoxpvQxdBGaqaCS8zbLVJSeybXREvY8dckR2z4bs34LR"),
                    listOf("brian", "6rNKpoi9CB9EbRr5eqjXZtyeCV7ASvNJRFkMGPT3uXbP"),
                    listOf("coroutinetomasz", "2PiFtsgkQ34NahQtbGXoSQF91N9qryjQ21X9fQSoi8iV"),
                    listOf("ironman", "CZjkem35rHnyMDUubELCZkSikD3g5oThTxjvX7uPkCZP"),
                    listOf("samsbbq", "4EKMpjTCkdWpHtV76MMAEbrtkuSPDnjMBk5NAoVtTsoE"),
                    listOf("briantheproductowner", "F1i9Cgju69hL2ZbfwoMowN7jxnht5ZA4BFnmbzU2X5mx"),
                    listOf("sydney", "7wtTHcTSSfF8zeua9tCTqgaa4yC5zwYB1QpBpkUTLn3u"),
                    listOf("cooper", "2NS54HYRpNyM5p3sdwKt4bNUkQM2yCWbtDTiagw3XXVe"),
                    listOf("tricia", "EAFFW5uvbVLNNXysSNCmQYrPJcMFoxjhMBD4bp5AcYrZ"),
                    listOf("cookie", "HHgHazrgQBBA1qpgRXpRwkSLLj9nPezpSA2qvC7d4vMC"),
                    listOf("ericthetester", "CTme6NxrHk5apwK4oL8YkpFuDFGAEZnBxpEH8VvnuZLU"),
                    listOf("tomhardy", "EC7XnT36mGxkL8eHGhaQqRqoMKbgU81HLC72xbC2zodq"),
                    listOf("patrickstewart", "2SNALrffcea8b5nDRnrVpkJHceGfPs2c6rveCyB3YCDE"),
                    listOf("minerforlife", "3in7MggSpWUcP1tEa7qvFyLAJVGg5AQF9udxBRGoJoaP"),
                    listOf("sambarboza", "8N6hxeCxV9T2hvcV71ZCUZdA9cBZZVogNiYLefX4wgcG"),
                    listOf("babyyoda", "A9kMxyQx65HeuNgigvsHZL3ez1fw6njsB56QtwNiQMM9"),
                    listOf("kyloren", "4snmYHmK4qxDgaySi9SBnsgTFjUFgCrmDpKQWk5PxCwT"),
                    listOf("kyle", "ELasJfWo9uPCkpqjr6k2ycnqY7wWsEQGgktGiFfhxRpN"),
                    listOf("luciusmalfoy", "6psoTRU6WYXNTFD4BpqHHL7D9XubGou34BtcpAT1g1Wu"),
                    listOf("hagrid", "9JFyW6zhNzRSuQbHmQ71k4YMyE339XEdetKjWPK13Aof")
            )

            for (l in ourRequests) {
                dashPayProfileDaoAsync.insert(DashPayProfile(l[1], l[0]))
                dashPayContactRequestDaoAsync.insert(
                        DashPayContactRequest(Entropy.generate(), userId, l[1], null, l[1].toByteArray(), 0, 0, 0.0, false, 0))
            }

            for (l in theirRequests) {
                dashPayProfileDaoAsync.insert(DashPayProfile(l[1], l[0]))
                dashPayContactRequestDaoAsync.insert(
                        DashPayContactRequest(Entropy.generate(), l[1], userId, null, l[1].toByteArray(), 0, 0, 0.0, false, 0))
            }

            var names = listOf("Lizet Michaelson", "Rachel Sanderson", "Tamanna Smith", "Tammy Oceanography", "Alfred Pennyworth", "Serena Kyle", "Batman", "Capt Kirk", "Spock", "", "Deanna Troi", "Neelix", "Zephrane Cochrane", "The Tenth Doctor was the Best Doctor, Martha was the best!")
            var usernames = listOf("lizet1993", "rachel4ski", "tsmith", "marinebio", "thebutler", "catwoman", "brucewayne", "jtkirk", "spock", "amanda", "dtroi", "nelix", "warpspeed", "drwho")
            for (i in names.indices) {
                val thisUserId = Sha256Hash.of(names[i].toByteArray()).toStringBase58()
                dashPayProfileDaoAsync.insert(DashPayProfile(thisUserId, usernames[i], names[i], "", ""))
                dashPayContactRequestDaoAsync.insert(
                        DashPayContactRequest(Entropy.generate(), userId, thisUserId, null, names[0].toByteArray(), 0, 0, 0.0, false, 0))
                dashPayContactRequestDaoAsync.insert(
                        DashPayContactRequest(Entropy.generate(), thisUserId, userId, null, names[0].toByteArray(), 0, 0, 0.0, false, 0))

            }

            names = listOf("Q (The Original)", "Thomas Riker", "Geordi La Forge", "Beverly Crusher", "Capt. Picard")
            usernames = listOf("qcontinuum", "triker", "laforge", "crusher", "jlpicard")
            for (i in names.indices) {
                val thisUserId = Sha256Hash.of(names[i].toByteArray()).toStringBase58()
                dashPayProfileDaoAsync.insert(DashPayProfile(thisUserId, usernames[i], names[i], "", ""))
                dashPayContactRequestDaoAsync.insert(
                        DashPayContactRequest(Entropy.generate(), thisUserId, userId, null, names[0].toByteArray(), 0, 0, 0.0, false, 0))
            }

            log.info("updating contacts and profiles took $watch")
        } catch (e: Exception) {
            log.error("error updating contacts: ${e.message}")
        }
    }
}