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
            //We don't check for nullity here because if it's null, it'll be thrown, caputred below
            //and sent as a Resource.error
            val creditFundingTx = wallet.getCreditFundingTransaction(wallet.getTransaction(blockchainIdentity!!.creditFundingTxId))
            val userId = creditFundingTx.creditBurnIdentityIdentifier.toStringBase58()
            // Names.search does support retrieving 100 names at a time if retrieveAll = false
            val nameDocuments = platform.names.search(text, Names.DEFAULT_PARENT_DOMAIN, true)

            val nameDocByUserId = nameDocuments.associateBy({ it.userId }, { it })
            var userIds = arrayListOf<String>()
            for (nameDoc in nameDocuments) {
                userIds.add(nameDoc.userId)
            }
            val dashPayDocuments = Profiles(platform).getList(userIds)

            val toContactDocuments = dashPayContactRequestDaoAsync.loadToOthers(userId)//ContactRequests(platform).get(userId, toUserId = false, retrieveAll = true)
                    ?: ArrayList<DashPayContactRequest>()

            // Get all contact requests where toUserId == userId
            val fromContactDocuments = dashPayContactRequestDaoAsync.loadFromOthers(userId)//ContactRequests(platform).get(userId, toUserId = true, retrieveAll = true)
                    ?: ArrayList<DashPayContactRequest>()

            val usernameSearchResults = ArrayList<UsernameSearchResult>()

            for (doc in nameDocuments) {
                //Remove own user document from result
                if (doc.userId == userId) {
                    continue
                }
                var toContact: DashPayContactRequest? = null
                var fromContact: DashPayContactRequest? = null

                // Determine if any of our contacts match the current name's identity
                if (toContactDocuments.isNotEmpty()) {
                    toContact = toContactDocuments.find { contact ->
                        contact.toUserId == doc.userId
                    }
                }

                // Determine if our identity is someone else's contact
                if (fromContactDocuments.isNotEmpty()) {
                    fromContact = fromContactDocuments.find { contact ->
                        contact.userId == doc.userId
                    }
                }


                // did we download the profile
                val profileDocument = dashPayDocuments.find { it.userId == doc.userId }
                        ?: profiles.createProfileDocument("", "", "", platform.identities.get(doc.userId)!!)

                val profile = DashPayProfile(profileDocument.userId,
                        doc.data["normalizedLabel"] as String,
                        profileDocument.data["displayName"] as String,
                        profileDocument.data["publicMessage"] as String,
                        profileDocument.data["avatarUrl"] as String)

                usernameSearchResults.add(UsernameSearchResult(doc.data["normalizedLabel"] as String,
                        profile, toContact, fromContact))
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

            //dashPayContactRequestDaoAsync.clear()
            //val toContactDocuments1 = ContactRequests(platform).get(userId, toUserId = false, retrieveAll = true)
            var toContactDocuments = dashPayContactRequestDaoAsync.loadToOthers(userId)
            if (toContactDocuments == null || toContactDocuments.isEmpty()) {
                updateContactRequests(userId)
                toContactDocuments = dashPayContactRequestDaoAsync.loadToOthers(userId)
            }
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
                if(!usernameContainsSearchText && searchText != "") {
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

            if (profile != null) {
                val dashPayProfile = DashPayProfile(blockchainIdentity.uniqueIdString,
                        blockchainIdentity.currentUsername!!,
                        profile.data["displayName"] as String,
                        profile.data["publicMessage"] as String,
                        profile.data["avatarUrl"] as String)

                updateDashPayProfile(dashPayProfile)
            }
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
                    username,
                    profile!!.data["displayName"] as String,
                    profile.data["publicMessage"] as String,
                    profile.data["avatarUrl"] as String)

            updateDashPayProfile(dashPayProfile)
        }
    }

    // contacts

    suspend fun updateContactRequests(userId: String) {

        val userIdList = HashSet<String>()

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

        var nameDocuments = HashMap<String, Document>()
        var profileDocuments = HashMap<String, Document?>()

        for (id in userIdList) {
            val nameDocument = platform.names.getByUserId(id)
            nameDocuments[id] = nameDocument[0]

            val profileDocument= profiles.get(id) ?: profiles.createProfileDocument("","",
                    "", platform.identities.get(nameDocument[0].userId)!!)

            profileDocuments[id] = profileDocument

            val profile = DashPayProfile(profileDocument.userId,
                    nameDocument[0].data["normalizedLabel"] as String,
                    profileDocument.data["displayName"] as String,
                    profileDocument.data["publicMessage"] as String,
                    profileDocument.data["avatarUrl"] as String)
            dashPayProfileDaoAsync.insert(profile)

        }

        // lets add more data

        var names = listOf("Lizet (Color Manager)", "Rachel (Dev Manager)", "Tammana (hire me)", "Tammy (Product Manager)", "Alfred Pennyworth", "Serena Kyle", "Batman", "Capt Kirk", "Spock", "", "Deana Troi", "Neelix", "Zephrane Cochrane", "The Tenth Doctor was the Best Doctor, Martha was the best!")
        var usernames = listOf("lizet1993", "rachel4ski", "hellokitty", "oceanbui62", "thebutler", "catwoman", "brucewayne", "jtkirk", "spock", "amanda", "dtroi", "nelix", "warpspeed", "drwho")
        for (i in 0 until names.size) {
            val thisUserId = Sha256Hash.of(names[i].toByteArray()).toStringBase58()
            dashPayProfileDaoAsync.insert(
                    DashPayProfile(thisUserId, usernames[i], names[i], "no public message", ""/*""https://api.adorable.io/avatars/120/${names[i]}"*/))
            dashPayContactRequestDaoAsync.insert(
                    DashPayContactRequest(Entropy.generate(), userId, thisUserId, null, names[0].toByteArray(), 0, 0 , 0.0, false, 0 ))
        }

        names = listOf("Q (The Original)", "Thomas Riker", "Geordi La Forge", "Beverly Crusher", "Capt. Picard")
        usernames = listOf("qcontinuum", "triker", "laforge", "crusher", "jlpicard")
        for (i in 0 until names.size) {
            val thisUserId = Sha256Hash.of(names[i].toByteArray()).toStringBase58()
            dashPayProfileDaoAsync.insert(
                    DashPayProfile(thisUserId, usernames[i], names[i], "no public message", ""/*https://api.adorable.io/avatars/120/${names[i]}"*/))
            dashPayContactRequestDaoAsync.insert(
                    DashPayContactRequest(Entropy.generate(), thisUserId, userId, null, names[0].toByteArray(), 0, 0 , 0.0, false, 0 ))
        }

    }
}