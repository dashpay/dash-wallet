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

import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.google.common.base.Stopwatch
import com.google.common.util.concurrent.SettableFuture
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.*
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.service.BlockchainService
import de.schildbach.wallet.service.BlockchainServiceImpl
import de.schildbach.wallet.ui.security.SecurityGuard
import de.schildbach.wallet.ui.send.DeriveKeyTask
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.*
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.evolution.CreditFundingTransaction
import org.bitcoinj.evolution.EvolutionContact
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.bouncycastle.crypto.params.KeyParameter
import org.dashevo.dashpay.BlockchainIdentity
import org.dashevo.dashpay.BlockchainIdentity.Companion.BLOCKCHAIN_USERNAME_SALT
import org.dashevo.dashpay.BlockchainIdentity.Companion.BLOCKCHAIN_USERNAME_STATUS
import org.dashevo.dashpay.ContactRequests
import org.dashevo.dashpay.Profiles
import org.dashevo.dashpay.RetryDelayType
import org.dashevo.dpp.document.Document
import org.dashevo.dpp.identity.IdentityPublicKey
import org.dashevo.platform.Names
import org.dashevo.platform.Platform
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class PlatformRepo private constructor(val walletApplication: WalletApplication) {

    companion object {
        private val log = LoggerFactory.getLogger(PlatformRepo::class.java)

        const val UPDATE_TIMER_DELAY = 15000L // 15 seconds

        private lateinit var platformRepoInstance: PlatformRepo

        @JvmStatic
        fun initPlatformRepo(walletApplication: WalletApplication) {
            platformRepoInstance = PlatformRepo(walletApplication)
            platformRepoInstance.init()
        }

        @JvmStatic
        fun getInstance(): PlatformRepo {
            return platformRepoInstance
        }
    }

    private val onContactsUpdatedListeners = arrayListOf<OnContactsUpdated>()
    private val updatingContacts = AtomicBoolean(false)
    private val preDownloadBlocks = AtomicBoolean(true)
    private var preDownloadBlocksFuture: SettableFuture<Boolean>? = null

    val platform = Platform(Constants.NETWORK_PARAMETERS)
    private val profiles = Profiles(platform)

    private val blockchainIdentityDataDaoAsync = AppDatabase.getAppDatabase().blockchainIdentityDataDaoAsync()
    private val dashPayProfileDaoAsync = AppDatabase.getAppDatabase().dashPayProfileDaoAsync()
    private val dashPayContactRequestDaoAsync = AppDatabase.getAppDatabase().dashPayContactRequestDaoAsync()

    private val securityGuard = SecurityGuard()
    private lateinit var blockchainIdentity: BlockchainIdentity

    private val backgroundThread = HandlerThread("background", Process.THREAD_PRIORITY_BACKGROUND)
    private val backgroundHandler: Handler

    init {
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    fun init() {
        GlobalScope.launch {
            blockchainIdentityDataDaoAsync.load()?.let {
                blockchainIdentity = initBlockchainIdentity(it, walletApplication.wallet)
                while (isActive) {
                    log.info("Timer: Update contacts")
                    platformRepoInstance.updateContactRequests()
                    delay(UPDATE_TIMER_DELAY)
                }
            }
        }
    }

    fun getBlockchainIdentity(): BlockchainIdentity? {
        return if (this::blockchainIdentity.isInitialized) {
            this.blockchainIdentity;
        } else {
            null
        }
    }

    fun isPlatformAvailable(): Resource<Boolean> {
        // this checks only one random node, but should check several.
        // it is possible that some nodes are not available due to location,
        // firewalls or other reasons
        return try {
            if (Constants.NETWORK_PARAMETERS.id.contains("mobile")) {
                // Something is wrong with getStatus() or the nodes only return success about 10-20% of time
                // on the mobile 0.11 devnet
                platform.client.getBlockByHeight(100)
                Resource.success(true)
            } else {
                val response = platform.client.getStatus()
                Resource.success(response!!.connections > 0 && response.errors.isBlank() &&
                        Constants.NETWORK_PARAMETERS.getProtocolVersionNum(NetworkParameters.ProtocolVersion.MINIMUM) <= response.protocolVersion)
            }
        } catch (e: Exception) {
            try {
                // use getBlockByHeight instead of getStatus in case of failure
                platform.client.getBlockByHeight(100)
                Resource.success(true)
            } catch (e: Exception) {
                Resource.error(e.localizedMessage, null)
            }
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
            val blockchainIdentityData = blockchainIdentityDataDaoAsync.load()!!
            //We don't check for nullity here because if it's null, it'll be thrown, captured below
            //and sent as a Resource.error
            val creditFundingTx = wallet.getCreditFundingTransaction(wallet.getTransaction(blockchainIdentityData!!.creditFundingTxId))
            val userId = creditFundingTx.creditBurnIdentityIdentifier.toStringBase58()
            // Names.search does support retrieving 100 names at a time if retrieveAll = false
            //TODO: Maybe add pagination later? Is very unlikely that a user will scroll past 100 search results
            val nameDocuments = platform.names.search(text, Names.DEFAULT_PARENT_DOMAIN, false)

            val userIds = nameDocuments.map { getIdentityForName(it) }

            val profileDocuments = Profiles(platform).getList(userIds)
            val profileById = profileDocuments.associateBy({ it.ownerId }, { it })

            val toContactDocuments = dashPayContactRequestDaoAsync.loadToOthers(userId)
                    ?: arrayListOf()

            // Get all contact requests where toUserId == userId
            val fromContactDocuments = dashPayContactRequestDaoAsync.loadFromOthers(userId)
                    ?: arrayListOf()

            val usernameSearchResults = ArrayList<UsernameSearchResult>()

            for (nameDoc in nameDocuments) {
                //Remove own user document from result
                val nameDocIdentityId = getIdentityForName(nameDoc)
                if (nameDocIdentityId == userId) {
                    continue
                }
                var toContact: DashPayContactRequest? = null
                var fromContact: DashPayContactRequest? = null

                // Determine if any of our contacts match the current name's identity
                if (toContactDocuments.isNotEmpty()) {
                    toContact = toContactDocuments.find { contact ->
                        contact.toUserId == nameDocIdentityId
                    }
                }

                // Determine if our identity is someone else's contact
                if (fromContactDocuments.isNotEmpty()) {
                    fromContact = fromContactDocuments.find { contact ->
                        contact.userId == nameDocIdentityId
                    }
                }

                val username = nameDoc.data["normalizedLabel"] as String
                val profileDoc = profileById[nameDocIdentityId]

                val dashPayProfile = if (profileDoc != null)
                    DashPayProfile.fromDocument(profileDoc, username)!!
                else DashPayProfile(nameDocIdentityId, username)

                usernameSearchResults.add(UsernameSearchResult(nameDoc.data["normalizedLabel"] as String,
                        dashPayProfile, toContact, fromContact))
            }

            Resource.success(usernameSearchResults)
        } catch (e: Exception) {
            Resource.error(formatExceptionMessage("search usernames", e), null)
        }
    }

    /**
     * search the contacts
     *
     * @param text the text to find in usernames and displayNames.  if blank, all contacts are returned
     * @param orderBy the field that is used to sort the list of matching entries in ascending order
     * @return
     */
    suspend fun searchContacts(text: String, orderBy: UsernameSortOrderBy, includeSentPending: Boolean = false): Resource<List<UsernameSearchResult>> {
        return try {
            // TODO: Replace this Platform call with a query into the local database
            val userIdList = HashSet<String>()

            val wallet = walletApplication.wallet
            val blockchainIdentity = blockchainIdentityDataDaoAsync.load()
                    ?: return Resource.error("search contacts: no blockchain identity")
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

                val usernameSearchResult = UsernameSearchResult(profile.value!!.username,
                        profile.value!!, toContact, fromContact)

                if (usernameSearchResult.requestReceived || (includeSentPending && usernameSearchResult.requestSent))
                    usernameSearchResults.add(usernameSearchResult)
            }
            when (orderBy) {
                UsernameSortOrderBy.DISPLAY_NAME -> usernameSearchResults.sortBy {
                    if (it.dashPayProfile.displayName.isNotEmpty())
                        it.dashPayProfile.displayName.toLowerCase()
                    else it.dashPayProfile.username.toLowerCase()
                }
                UsernameSortOrderBy.USERNAME -> usernameSearchResults.sortBy {
                    it.dashPayProfile.username.toLowerCase()
                }
                UsernameSortOrderBy.DATE_ADDED -> usernameSearchResults.sortByDescending {
                    it.date
                }
                //TODO: sort by last activity or date added
            }
            Resource.success(usernameSearchResults)
        } catch (e: Exception) {
            Resource.error(formatExceptionMessage("search contact request", e), null)
        }
    }

    private fun formatExceptionMessage(description: String, e: Exception): String {
        var msg = if (e.localizedMessage != null) {
            e.localizedMessage
        } else {
            e.message
        }
        if (msg == null) {
            msg = "Unknown error - ${e.javaClass.simpleName}"
        }
        log.error("$description: $msg")
        if (e is StatusRuntimeException) {
            log.error("---> ${e.trailers}")
        }
        log.error(msg)
        e.printStackTrace()
        return msg
    }

    suspend fun getNotificationCount(date: Long): Int {
        val results = searchContacts("", UsernameSortOrderBy.DATE_ADDED)
        return if (results.status == Status.SUCCESS) {
            val list = results.data ?: return 0
            var count = 0
            list.forEach { if (it.date >= date) ++count }
            log.info("New contacts at ${Date(date)} = $count - getNotificationCount")
            count
        } else {
            -1
        }
    }

    /**
     *  Wraps callbacks of DeriveKeyTask as Coroutine
     */
    private suspend fun deriveEncryptionKey(handler: Handler, wallet: Wallet, password: String): KeyParameter {
        return suspendCoroutine { continuation ->
            object : DeriveKeyTask(handler, walletApplication.scryptIterationsTarget()) {

                override fun onSuccess(encryptionKey: KeyParameter, wasChanged: Boolean) {
                    continuation.resume(encryptionKey)
                }

                override fun onFailure(ex: KeyCrypterException?) {
                    continuation.resumeWithException(ex as Throwable)
                }

            }.deriveKey(wallet, password)
        }
    }

    suspend fun sendContactRequest(toUserId: String, encryptionKey: KeyParameter): Resource<DashPayContactRequest> {
        return try {
            Context.propagate(walletApplication.wallet.context)
            val potentialContactIdentity = platform.identities.get(toUserId)
            log.info("potential contact identity: $potentialContactIdentity")

            //Create Contact Request
            val contactRequests = ContactRequests(platform)
            contactRequests.create(blockchainIdentity, potentialContactIdentity!!, encryptionKey)
            log.info("contact request sent")

            //Verify that the Contact Request was seen on the network
            val cr = contactRequests.watchContactRequest(this.blockchainIdentity.uniqueIdString,
                    toUserId, 100, 500, RetryDelayType.LINEAR)

            // add our receiving from this contact keychain if it doesn't exist
            val contact = EvolutionContact(blockchainIdentity.uniqueIdString, toUserId)
            var encryptionKey: KeyParameter? = null
            try {
                if (!walletApplication.wallet.hasReceivingKeyChain(contact)) {
                    val contactIdentity = platform.identities.get(toUserId)
                    if (walletApplication.wallet.isEncrypted) {
                        val password = securityGuard.retrievePassword()
                        encryptionKey = walletApplication.wallet!!.keyCrypter!!.deriveKey(password)
                    }
                    blockchainIdentity.addPaymentKeyChainFromContact(contactIdentity!!, cr!!, encryptionKey!!)

                    // update bloom filters now
                    val intent = Intent(BlockchainService.ACTION_RESET_BLOOMFILTERS, null, walletApplication,
                            BlockchainServiceImpl::class.java)
                    walletApplication.startService(intent)
                }
            } catch (e: KeyCrypterException) {
                // we can't send payments to this contact due to an invalid encryptedPublicKey
                log.info("ContactRequest: error ${e.message}")
            }

            log.info("contact request: $cr")
            val dashPayContactRequest = DashPayContactRequest.fromDocument(cr!!)
            updateDashPayContactRequest(dashPayContactRequest) //update the database since the cr was accepted
            Resource.success(dashPayContactRequest)
        } catch (e: Exception) {
            log.error(e.localizedMessage)
            Resource.error(formatExceptionMessage("send contact request", e))
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
            blockchainIdentity.watchIdentity(10, 5000, RetryDelayType.SLOW20)
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
            val (result, usernames) = blockchainIdentity.watchPreorder(saltedDomainHashes, 10, 5000, RetryDelayType.SLOW20)
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
            val (result, usernames) = blockchainIdentity.watchUsernames(blockchainIdentity.getUsernamesWithStatus(BlockchainIdentity.UsernameStatus.REGISTRATION_PENDING), 10, 5000, RetryDelayType.SLOW20)
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
            val profile = blockchainIdentity.watchProfile(10, 5000, RetryDelayType.SLOW20)
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
            return BlockchainIdentity(platform, creditFundingTransaction, wallet).apply {
                identity = platform.identities.get(uniqueIdString)
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
        return BlockchainIdentity(platform, 0, wallet)
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

    private suspend fun updateDashPayContactRequest(dashPayContactRequest: DashPayContactRequest) {
        dashPayContactRequestDaoAsync.insert(dashPayContactRequest)
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
            if (platform.hasApp("dashpay")) {
                val username = blockchainIdentity.currentUsername!!
                // recovery will only get the information and place it in the database
                val profile = blockchainIdentity.getProfile() ?: return@withContext

                // blockchainIdentity doesn't yet keep track of the profile, so we will load it
                // into the database directly
                val dashPayProfile = DashPayProfile.fromDocument(profile, username)
                updateDashPayProfile(dashPayProfile!!)
            }
        }
    }

    fun getNextContactAddress(userId: String): Address {
        return blockchainIdentity.getContactNextPaymentAddress(userId)
    }

    // contacts
    suspend fun updateContactRequests() {
        try {
            // only allow this method to execute once at a time
            if (updatingContacts.get()) {
                log.info("updateContactRequests is already running")
                return
            }

            if (!platform.hasApp("dashpay")) {
                return
            }

            val blockchainIdentityData = blockchainIdentityDataDaoAsync.load() ?: return
            if (blockchainIdentityData.creationState < BlockchainIdentityData.CreationState.DONE) {
                return
            }
            val userId = blockchainIdentityData!!.getIdentity(walletApplication.wallet) ?: return
            if (blockchainIdentityData.username == null) {
                return // this is here because the wallet is being reset without removing blockchainIdentityData
            }

            val userIdList = HashSet<String>()
            val watch = Stopwatch.createStarted()
            var addedContact = false
            Context.propagate(walletApplication.wallet.context)
            var encryptionKey: KeyParameter? = null

            var lastContactRequestTime = if (dashPayContactRequestDaoAsync.countAllRequests() > 0)
                dashPayContactRequestDaoAsync.getLastTimestamp()
            else 0L

            updatingContacts.set(true)
            // Get all out our contact requests
            val toContactDocuments = ContactRequests(platform).get(userId, toUserId = false, afterTime = lastContactRequestTime, retrieveAll = true)
            toContactDocuments.forEach {
                val contactRequest = DashPayContactRequest.fromDocument(it)
                userIdList.add(contactRequest.toUserId)
                dashPayContactRequestDaoAsync.insert(contactRequest)

                // add our receiving from this contact keychain if it doesn't exist
                val contact = EvolutionContact(userId, contactRequest.toUserId)
                try {
                    if (!walletApplication.wallet.hasReceivingKeyChain(contact)) {
                        val contactIdentity = platform.identities.get(contactRequest.toUserId)
                        if (encryptionKey == null && walletApplication.wallet.isEncrypted) {
                            val password = securityGuard.retrievePassword()
                            // Don't bother with DeriveKeyTask here, just call deriveKey
                            encryptionKey = walletApplication.wallet!!.keyCrypter!!.deriveKey(password)
                        }
                        blockchainIdentity.addPaymentKeyChainFromContact(contactIdentity!!, it, encryptionKey!!)
                        addedContact = true
                    }
                } catch (e: KeyCrypterException) {
                    // we can't send payments to this contact due to an invalid encryptedPublicKey
                    log.info("ContactRequest: error ${e.message}")
                }
            }
            // Get all contact requests where toUserId == userId, the users who have added me
            val fromContactDocuments = ContactRequests(platform).get(userId, toUserId = true, afterTime = lastContactRequestTime, retrieveAll = true)
            fromContactDocuments.forEach {
                val contactRequest = DashPayContactRequest.fromDocument(it)
                userIdList.add(contactRequest.userId)
                dashPayContactRequestDaoAsync.insert(contactRequest)

                // add the sending to contact keychain if it doesn't exist
                val contact = EvolutionContact(userId, contactRequest.userId)
                try {
                    if (!walletApplication.wallet.hasSendingKeyChain(contact)) {
                        val contactIdentity = platform.identities.get(contactRequest.userId)
                        if (encryptionKey == null && walletApplication.wallet.isEncrypted) {
                            val password = securityGuard.retrievePassword()
                            // Don't bother with DeriveKeyTask here, just call deriveKey
                            encryptionKey = walletApplication.wallet!!.keyCrypter!!.deriveKey(password)
                        }
                        blockchainIdentity.addContactPaymentKeyChain(contactIdentity!!, it, encryptionKey!!)
                        addedContact = true
                    }
                } catch (e: KeyCrypterException) {
                    // we can't send payments to this contact due to an invalid encryptedPublicKey
                    log.info("ContactRequest: error ${e.message}")
                }
            }

            if (userIdList.isNotEmpty()) {
                val profileDocuments = Profiles(platform).getList(userIdList.toList()) //only handles 100 userIds
                val profileById = profileDocuments.associateBy({ it.ownerId }, { it })

                val nameDocuments = platform.names.getList(userIdList.toList())
                val nameById = nameDocuments.associateBy({ getIdentityForName(it) }, { it })

                for (id in userIdList) {
                    val nameDocument = nameById[id] // what happens if there is no username for the identity? crash
                    val username = nameDocument!!.data["normalizedLabel"] as String
                    val identityId = getIdentityForName(nameDocument)

                    val profileDocument = profileById[id] ?: profiles.createProfileDocument("", "",
                            "", platform.identities.get(identityId)!!)

                    val profile = DashPayProfile.fromDocument(profileDocument, username)
                    dashPayProfileDaoAsync.insert(profile!!)
                }
            }

            // fire listeners if there were new contacts
            if (fromContactDocuments.isNotEmpty() || toContactDocuments.isNotEmpty()) {
                fireContactsUpdatedListeners()
            }

            // If new keychains were added to the wallet, then update the bloom filters
            if (addedContact) {
                val intent = Intent(BlockchainService.ACTION_RESET_BLOOMFILTERS, null, walletApplication,
                        BlockchainServiceImpl::class.java)
                walletApplication.startService(intent)
            }
            log.info("updating contacts and profiles took $watch")
        } catch (e: Exception) {
            log.error(formatExceptionMessage("error updating contacts", e))
        } finally {
            updatingContacts.set(false)
            if (preDownloadBlocks.get()) {
                log.info("PreDownloadBlocks: complete")
                preDownloadBlocksFuture?.set(true)
                preDownloadBlocks.set(false)
            }
        }
    }

    fun addContactsUpdatedListener(listener: OnContactsUpdated) {
        onContactsUpdatedListeners.add(listener)
    }

    fun removeContactsUpdatedListener(listener: OnContactsUpdated?) {
        onContactsUpdatedListeners.remove(listener)
    }

    private fun fireContactsUpdatedListeners() {
        backgroundHandler.post {
            for (listener in onContactsUpdatedListeners)
                listener.onContactsUpdated()
        }
    }

    fun getIdentityForName(nameDocument: Document): String {
        val records = nameDocument.data["records"] as Map<String, Any?>
        return records["dashIdentity"] as String
    }

    suspend fun getLocalUsernameSearchResult(userId: String): UsernameSearchResult {
        val profile = dashPayProfileDaoAsync.load(userId)
        val receivedContactRequest = dashPayContactRequestDaoAsync.loadToOthers(userId)?.let { it[0] }
        val sentContactRequest = dashPayContactRequestDaoAsync.loadFromOthers(userId)?.let { it[0] }

        return UsernameSearchResult(profile!!.username, profile, sentContactRequest, receivedContactRequest)
    }

    /**
     * Called before DashJ starts synchronizing the blockchain
     */
    fun preBlockDownload(future: SettableFuture<Boolean>) {
        GlobalScope.launch(Dispatchers.IO) {
            preDownloadBlocks.set(true)
            preDownloadBlocksFuture = future
            log.info("PreDownloadBlocks: starting")
            if(!updatingContacts.get()) {
                updateContactRequests()
            }
        }
    }
}