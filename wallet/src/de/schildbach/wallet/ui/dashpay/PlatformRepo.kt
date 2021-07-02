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
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import com.google.common.base.Preconditions
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
import de.schildbach.wallet.ui.dashpay.CreateIdentityService.Companion.createIntentForRestore
import de.schildbach.wallet.ui.security.SecurityGuard
import de.schildbach.wallet.ui.send.DeriveKeyTask
import de.schildbach.wallet.util.canAffordIdentityCreation
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.*
import org.bitcoinj.core.*
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.evolution.CreditFundingTransaction
import org.bitcoinj.evolution.EvolutionContact
import org.bitcoinj.quorums.InstantSendLock
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.bouncycastle.crypto.params.KeyParameter
import org.dashevo.dapiclient.model.GrpcExceptionInfo
import org.dashevo.dashpay.*
import org.dashevo.dashpay.BlockchainIdentity.Companion.BLOCKCHAIN_USERNAME_SALT
import org.dashevo.dashpay.BlockchainIdentity.Companion.BLOCKCHAIN_USERNAME_STATUS
import org.dashevo.dashpay.BlockchainIdentity.Companion.BLOCKCHAIN_USERNAME_UNIQUE
import org.dashevo.dpp.document.Document
import org.dashevo.dpp.errors.InvalidIdentityAssetLockProofError
import org.dashevo.dpp.identifier.Identifier
import org.dashevo.dpp.identity.Identity
import org.dashevo.dpp.identity.IdentityPublicKey
import org.dashevo.dpp.toHexString
import org.dashevo.platform.Names
import org.dashevo.platform.Platform
import org.dashevo.platform.multicall.MulticallQuery
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
            platformRepoInstance.initGlobal()
        }

        @JvmStatic
        fun getInstance(): PlatformRepo {
            return platformRepoInstance
        }
    }

    private val onContactsUpdatedListeners = arrayListOf<OnContactsUpdated>()
    private val onPreBlockContactListeners = arrayListOf<OnPreBlockProgressListener>()

    private val updatingContacts = AtomicBoolean(false)
    private val preDownloadBlocks = AtomicBoolean(false)
    private var preDownloadBlocksFuture: SettableFuture<Boolean>? = null

    val platform = Platform(Constants.NETWORK_PARAMETERS)
    private val profiles = Profiles(platform)
    private val contactRequests = ContactRequests(platform)

    private val blockchainIdentityDataDao = AppDatabase.getAppDatabase().blockchainIdentityDataDao()
    private val dashPayProfileDao = AppDatabase.getAppDatabase().dashPayProfileDao()
    private val dashPayContactRequestDao = AppDatabase.getAppDatabase().dashPayContactRequestDao()
    private val invitationsDao = AppDatabase.getAppDatabase().invitationsDao()
    private val userAlertDao = AppDatabase.getAppDatabase().userAlertDao()

    // Async
    private val blockchainIdentityDataDaoAsync = AppDatabase.getAppDatabase().blockchainIdentityDataDaoAsync()
    private val dashPayProfileDaoAsync = AppDatabase.getAppDatabase().dashPayProfileDaoAsync()
    private val dashPayContactRequestDaoAsync = AppDatabase.getAppDatabase().dashPayContactRequestDaoAsync()
    private val invitationsDaoAsync = AppDatabase.getAppDatabase().invitationsDaoAsync()
    private val userAlertDaoAsync = AppDatabase.getAppDatabase().userAlertDaoAsync()

    private val securityGuard = SecurityGuard()
    private lateinit var blockchainIdentity: BlockchainIdentity

    private val backgroundThread = HandlerThread("background", Process.THREAD_PRIORITY_BACKGROUND)
    private val backgroundHandler: Handler

    private var mainHandler: Handler = Handler(walletApplication.mainLooper)
    private lateinit var platformSyncJob: Job

    private var lastPreBlockStage: PreBlockStage = PreBlockStage.None

    init {
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    fun initGlobal() {
        platformSyncJob = GlobalScope.launch {
            init()
        }
    }

    suspend fun init() {
        blockchainIdentityDataDao.load()?.let {
            blockchainIdentity = initBlockchainIdentity(it, walletApplication.wallet)
            platformRepoInstance.initializeStateRepository()
            platformSyncJob = GlobalScope.launch {
                log.info("Starting the platform sync job")
                while (isActive) {
                    platformRepoInstance.updateContactRequests()
                    delay(UPDATE_TIMER_DELAY)
                }
            }
        }
    }

    fun resume() {
        if (!platformSyncJob.isActive && this::blockchainIdentity.isInitialized) {
            platformSyncJob = GlobalScope.launch {
                log.info("Resuming the platform sync job")
                while (isActive) {
                    platformRepoInstance.updateContactRequests()
                    delay(UPDATE_TIMER_DELAY)
                }
            }
        }
    }

    fun shutdown() {
        if (this::blockchainIdentity.isInitialized) {
            Preconditions.checkState(platformSyncJob.isActive)
            log.info("Shutting down the platform sync job")
            platformSyncJob.cancel("shutdown the platform sync")
        }
    }

    /**
     * This method looks at all items in the database tables
     * that have existing identites and saves them for future use.
     *
     * Sometimes Platform Nodes return IdentityNotFound Errors and
     * this list is used to determine if that node should be banned
     */
    private suspend fun initializeStateRepository() {
        // load our id

        if (this::blockchainIdentity.isInitialized && blockchainIdentity.registered) {
            val identityId = blockchainIdentity.uniqueIdString
            platform.stateRepository.addValidIdentity(Identifier.from(identityId))

            //load all id's of users who have sent us a contact request
            dashPayContactRequestDao.loadFromOthers(identityId)?.forEach {
                platform.stateRepository.addValidIdentity(it.userIdentifier)
            }

            // load all id's of users for whom we have profiles
            dashPayProfileDao.loadAll().forEach {
                platform.stateRepository.addValidIdentity(it.userIdentifier)
            }
        }
    }

    fun getBlockchainIdentity(): BlockchainIdentity? {
        return if (this::blockchainIdentity.isInitialized) {
            this.blockchainIdentity
        } else {
            null
        }
    }

    /**
     * Calls Platform.check() three times asynchronously
     *
     * @return true if platform is available
     */
    suspend fun isPlatformAvailable(): Resource<Boolean> {
        return withContext(Dispatchers.IO) {
            var success = 0
            val checks = arrayListOf<Deferred<Boolean>>()
            for (i in 0 until 3) {
                checks.add(async { platform.check() })
            }

            for (check in checks) {
                success += if (check.await()) 1 else 0
            }

            return@withContext if (success >= 2) {
                Resource.success(true)
            } else {
                Resource.error("Platform is not available")
            }
        }
    }

    fun getUsername(username: String): Resource<Document> {
        return try {
            val nameDocument = platform.names.get(username)
            Resource.success(nameDocument)
        } catch (e: Exception) {
            Resource.error(e.localizedMessage!!, null)
        }
    }

    @Throws(Exception::class)
    suspend fun getUser(username: String): List<UsernameSearchResult> {
        return try {
            searchUsernames(username, true)
        } catch (e: Exception) {
            formatExceptionMessage("get single user failure", e)
            throw e
        }
    }

    /**
     * gets all the name documents for usernames starting with text
     *
     * @param text The beginning of a username to search for
     * @return
     */

    @Throws(Exception::class)
    suspend fun searchUsernames(text: String, onlyExactUsername: Boolean = false, limit: Int = -1): List<UsernameSearchResult> {
        val userIdString = blockchainIdentity.uniqueIdString
        val userId = blockchainIdentity.uniqueIdentifier

        // Names.search does support retrieving 100 names at a time if retrieveAll = false
        //TODO: Maybe add pagination later? Is very unlikely that a user will scroll past 100 search results
        // Sometimes when onlyExactUsername = true, an exception is thrown here and that results in a crash
        // it is not clear why a search for an existing username results in a failure to find it again.
        val nameDocuments = if (!onlyExactUsername) {
            platform.names.search(text, Names.DEFAULT_PARENT_DOMAIN, retrieveAll = false, limit = limit)
        } else {
            val nameDocument = platform.names.get(text, Names.DEFAULT_PARENT_DOMAIN, MulticallQuery.Companion.CallType.UNTIL_FOUND)
            if (nameDocument != null) {
                listOf(nameDocument)
            } else {
                listOf()
            }
        }
        val userIds = if (onlyExactUsername) {
            val result = mutableListOf<Identifier>()
            val exactNameDoc = try {
                nameDocuments.first { text == it.data["normalizedLabel"] }
            } catch (e: NoSuchElementException) {
                null
            }
            if (exactNameDoc != null) {
                result.add(getIdentityForName(exactNameDoc))
            }
            result
        } else {
            nameDocuments.map { getIdentityForName(it) }
        }

        var profileById: Map<Identifier, Document> = if (userIds.isNotEmpty()) {
            val profileDocuments = profiles.getList(userIds)
            profileDocuments.associateBy({ it.ownerId }, { it })
        } else {
            log.warn("search usernames: userIdList is empty, though nameDocuments has ${nameDocuments.size} items")
            mapOf()
        }

        val toContactDocuments = dashPayContactRequestDao.loadToOthers(userIdString)
                ?: arrayListOf()

        // Get all contact requests where toUserId == userId
        val fromContactDocuments = dashPayContactRequestDao.loadFromOthers(userIdString)
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
                    contact.toUserIdentifier == nameDocIdentityId
                }
            }

            // Determine if our identity is someone else's contact
            if (fromContactDocuments.isNotEmpty()) {
                fromContact = fromContactDocuments.find { contact ->
                    contact.userIdentifier == nameDocIdentityId
                }
            }

            val username = nameDoc.data["normalizedLabel"] as String
            val profileDoc = profileById[nameDocIdentityId]

            val dashPayProfile = if (profileDoc != null)
                DashPayProfile.fromDocument(profileDoc, username)!!
            else DashPayProfile(nameDocIdentityId.toString(), username)

            usernameSearchResults.add(UsernameSearchResult(nameDoc.data["normalizedLabel"] as String,
                    dashPayProfile, toContact, fromContact))
        }

        return usernameSearchResults
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
            val userIdList = HashSet<String>()

            val userId = blockchainIdentity.uniqueIdString

            val toContactDocuments = dashPayContactRequestDao.loadToOthers(userId)
            val toContactMap = HashMap<String, DashPayContactRequest>()
            toContactDocuments!!.forEach {
                userIdList.add(it.toUserId)
                toContactMap[it.toUserId] = it
            }
            // Get all contact requests where toUserId == userId, the users who have added me
            val fromContactDocuments = dashPayContactRequestDao.loadFromOthers(userId)
            val fromContactMap = HashMap<String, DashPayContactRequest>()
            fromContactDocuments!!.forEach {
                userIdList.add(it.userId)

                // It is possible for a contact to send multiple requests that differ by account
                // or by version.  Currently we will ignore all but the first based on the timestamp
                // TODO: choose the contactRequest based on the ContactInfo.accountRef value
                // for this contact
                if (!fromContactMap.containsKey(it.userId)) {
                    fromContactMap[it.userId] = it
                } else {
                    val previous = fromContactMap[it.userId]!!
                    if (previous.timestamp > it.timestamp) {
                        fromContactMap[it.userId] = it
                    }
                }
            }

            val profiles = HashMap<String, DashPayProfile?>(userIdList.size)
            for (user in userIdList) {
                val profile = dashPayProfileDao.loadByUserId(user)
                profiles[user] = profile
            }

            val usernameSearchResults = ArrayList<UsernameSearchResult>()
            val searchText = text.toLowerCase()

            for (profile in profiles) {
                if (profile.value == null) {
                    // this happens occasionally when calling this method just after sending contact request
                    // It occurs when calling NotificationsForUserLiveData.onContactsUpdated() after
                    // sending contact request (even after adding long delay).
                    continue
                }

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
                val toContact: DashPayContactRequest? = toContactMap[profile.value!!.userId]

                // Determine if I am this identity's contact
                val fromContact: DashPayContactRequest? = fromContactMap[profile.value!!.userId]

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
                else -> {
                    // ignore
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
        log.error("$description: $msg", e)
        if (e is StatusRuntimeException) {
            log.error("---> ${e.trailers}")
        }
        return msg
    }

    suspend fun shouldShowAlert(): Boolean {
        val hasSentInvites = invitationsDao.count() > 0
        val blockchainIdentityData = blockchainIdentityDataDao.load()
        val noIdentityCreatedOrInProgress = (blockchainIdentityData == null) || blockchainIdentityData.creationState == BlockchainIdentityData.CreationState.NONE
        val canAffordIdentityCreation = walletApplication.wallet.canAffordIdentityCreation()
        return !noIdentityCreatedOrInProgress && (canAffordIdentityCreation || hasSentInvites)
    }


    suspend fun getNotificationCount(date: Long): Int {
        var count = 0
        // Developer Mode Feature
        if (walletApplication.configuration.developerMode && shouldShowAlert()) {
            val alert = userAlertDao.load(date)
            if (alert != null) {
                count++
            }
        }
        val results = searchContacts("", UsernameSortOrderBy.DATE_ADDED)
        if (results.status == Status.SUCCESS) {
            val list = results.data ?: return 0
            list.forEach { if (it.date >= date) ++count }
            log.info("New contacts at ${Date(date)} = $count - getNotificationCount")
        }
        return count
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

    @Throws(Exception::class)
    suspend fun sendContactRequest(toUserId: String): DashPayContactRequest {
        if (walletApplication.wallet.isEncrypted) {
            val password = securityGuard.retrievePassword()
            // Don't bother with DeriveKeyTask here, just call deriveKey
            val encryptionKey = walletApplication.wallet!!.keyCrypter!!.deriveKey(password)
            return sendContactRequest(toUserId, encryptionKey)
        }
        throw IllegalStateException("sendContactRequest doesn't support non-encrypted wallets")
    }

    @Throws(Exception::class)
    suspend fun sendContactRequest(toUserId: String, encryptionKey: KeyParameter): DashPayContactRequest {
        val potentialContactIdentity = platform.identities.get(toUserId)
        log.info("potential contact identity: $potentialContactIdentity")

        //Create Contact Request
        val cr = contactRequests.create(blockchainIdentity, potentialContactIdentity!!, encryptionKey)
        log.info("contact request sent")

        //Verify that the Contact Request was seen on the network
        //val cr = contactRequests.watchContactRequest(Identifier.from(this.blockchainIdentity.uniqueId.bytes),
        //        Identifier.from(toUserId), 100, 500, RetryDelayType.LINEAR)

        // add our receiving from this contact keychain if it doesn't exist
        val contact = EvolutionContact(blockchainIdentity.uniqueIdString, toUserId)

        if (!walletApplication.wallet.hasReceivingKeyChain(contact)) {
            Context.propagate(walletApplication.wallet.context)
            blockchainIdentity.addPaymentKeyChainFromContact(potentialContactIdentity, cr, encryptionKey)

            // update bloom filters now on main thread
            mainHandler.post {
                updateBloomFilters()
            }
        }

        log.info("contact request: $cr")
        val dashPayContactRequest = DashPayContactRequest.fromDocument(cr!!)
        updateDashPayContactRequest(dashPayContactRequest) //update the database since the cr was accepted
        updateDashPayProfile(toUserId) // update the profile
        fireContactsUpdatedListeners() // trigger listeners
        return dashPayContactRequest
    }

    @Throws(Exception::class)
    suspend fun broadcastUpdatedProfile(dashPayProfile: DashPayProfile, encryptionKey: KeyParameter): DashPayProfile {
        log.info("broadcast profile")

        val displayName = if (dashPayProfile.displayName.isNotEmpty()) dashPayProfile.displayName else null
        val publicMessage = if (dashPayProfile.publicMessage.isNotEmpty()) dashPayProfile.publicMessage else null
        val avatarUrl = if (dashPayProfile.avatarUrl.isNotEmpty()) dashPayProfile.avatarUrl else null

        //Create Contact Request
        val createdProfile = if (dashPayProfile.createdAt == 0L) {
            blockchainIdentity.registerProfile(displayName,
                    publicMessage,
                    avatarUrl,
                    dashPayProfile.avatarHash,
                    dashPayProfile.avatarFingerprint,
                    encryptionKey)
        } else {
            blockchainIdentity.updateProfile(displayName,
                    publicMessage,
                    avatarUrl,
                    dashPayProfile.avatarHash,
                    dashPayProfile.avatarFingerprint,
                    encryptionKey)
        }
        log.info("profile broadcast")

        //Verify that the Contact Request was seen on the network
        val updatedProfile = blockchainIdentity.watchProfile(100, 5000, RetryDelayType.LINEAR)

        if (createdProfile != updatedProfile) {
            log.warn("Created profile doesn't match profile from network $createdProfile != $updatedProfile")
        }

        log.info("updated profile: $updatedProfile")
        if (updatedProfile != null) {
            val updatedDashPayProfile = DashPayProfile.fromDocument(updatedProfile, dashPayProfile.username)
            updateDashPayProfile(updatedDashPayProfile!!) //update the database since the cr was accepted
            return updatedDashPayProfile
        } else {
            throw TimeoutException("timeout when updating profile")
        }
    }

    //
    // Step 1 is to upgrade the wallet to support authentication keys
    //
    suspend fun addWalletAuthenticationKeysAsync(seed: DeterministicSeed, keyParameter: KeyParameter?) {
        withContext(Dispatchers.IO) {
            val wallet = walletApplication.wallet
            // this will initialize any missing key chains
            wallet.initializeAuthenticationKeyChains(seed, keyParameter)
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
    // Step 2 is to obtain the credit funding transaction for invites
    //
    suspend fun obtainCreditFundingTransactionAsync(blockchainIdentity: BlockchainIdentity, invite: InvitationLinkData) {
        withContext(Dispatchers.IO) {
            Context.propagate(walletApplication.wallet.context)
            var cftxData = platform.client.getTransaction(invite.cftx)
            //TODO: remove when iOS uses big endian
            if (cftxData == null)
                cftxData = platform.client.getTransaction(Sha256Hash.wrap(invite.cftx).reversedBytes.toHexString())
            val cftx = CreditFundingTransaction(platform.params, cftxData!!.toByteArray())
            val privateKey = DumpedPrivateKey.fromBase58(platform.params, invite.privateKey).key
            cftx.setCreditBurnPublicKeyAndIndex(privateKey, 0)

            val instantSendLock = InstantSendLock(platform.params, Utils.HEX.decode(invite.instantSendLock))

            cftx.confidence.setInstantSendLock(instantSendLock)
            blockchainIdentity.initializeCreditFundingTransaction(cftx)
        }
    }

    //
    // Step 3: Register the identity
    //
    suspend fun registerIdentityAsync(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter?) {
        withContext(Dispatchers.IO) {
            Context.getOrCreate(walletApplication.wallet.params)
            for (i in 0 until 3) {
                try {
                    blockchainIdentity.registerIdentity(keyParameter)
                    return@withContext
                } catch (e: InvalidIdentityAssetLockProofError) {
                    log.info("instantSendLock error: retry registerIdentity again ($i)")
                    delay(3000)
                }
            }
            throw InvalidIdentityAssetLockProofError("failed after 3 tries")
        }
    }

    //
    // Step 3: Verify that the identity is registered
    //
    suspend fun verifyIdentityRegisteredAsync(blockchainIdentity: BlockchainIdentity) {
        withContext(Dispatchers.IO) {
            blockchainIdentity.watchIdentity(100, 1000, RetryDelayType.SLOW20)
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

    suspend fun recoverIdentityAsync(blockchainIdentity: BlockchainIdentity, publicKeyHash: ByteArray) {
        withContext(Dispatchers.IO) {
            blockchainIdentity.recoverIdentity(publicKeyHash)
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
            val (result, usernames) = blockchainIdentity.watchPreorder(saltedDomainHashes, 100, 1000, RetryDelayType.SLOW20)
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
            val (result, usernames) = blockchainIdentity.watchUsernames(blockchainIdentity.getUsernamesWithStatus(BlockchainIdentity.UsernameStatus.REGISTRATION_PENDING), 100, 1000, RetryDelayType.SLOW20)
            if (!result) {
                throw TimeoutException("the usernames: $usernames were not found to be registered in the allotted amount of time")
            }
        }
    }

    //Step 6: Create DashPay Profile
    suspend fun createDashPayProfile(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter) {
        withContext(Dispatchers.IO) {
            val username = blockchainIdentity.currentUsername!!
            blockchainIdentity.registerProfile(username, "", "", null, null, keyParameter)
        }
    }

    suspend fun loadBlockchainIdentityBaseData(): BlockchainIdentityBaseData? {
        return blockchainIdentityDataDao.loadBase()
    }

    suspend fun loadBlockchainIdentityData(): BlockchainIdentityData? {
        return blockchainIdentityDataDao.load()
    }

    fun initBlockchainIdentity(blockchainIdentityData: BlockchainIdentityData, wallet: Wallet): BlockchainIdentity {
        val creditFundingTransaction = blockchainIdentityData.findCreditFundingTransaction(wallet)
        val blockchainIdentity = if (creditFundingTransaction != null) {
            BlockchainIdentity(platform, creditFundingTransaction, wallet, blockchainIdentityData.identity)
        } else {
            val blockchainIdentity = BlockchainIdentity(platform, 0, wallet)
            if (blockchainIdentityData.creationState >= BlockchainIdentityData.CreationState.DONE) {
                blockchainIdentity.apply {
                    uniqueId = Sha256Hash.wrap(Base58.decode(blockchainIdentityData.userId))
                }
            } else {
                return blockchainIdentity
            }
            blockchainIdentity
        }
        return blockchainIdentity.apply {
            identity = if (blockchainIdentityData.identity != null)
                blockchainIdentityData.identity
            else platform.identities.get(uniqueIdString)
            currentUsername = blockchainIdentityData.username
            registrationStatus = blockchainIdentityData.registrationStatus!!
            val usernameStatus = HashMap<String, Any>()
            // usernameStatus, usernameSalts are not set if preorder hasn't started
            if (blockchainIdentityData.creationState >= BlockchainIdentityData.CreationState.PREORDER_REGISTERING) {
                if (blockchainIdentityData.preorderSalt != null) {
                    usernameStatus[BLOCKCHAIN_USERNAME_SALT] = blockchainIdentityData.preorderSalt!!
                    usernameSalts[currentUsername!!] = blockchainIdentityData.preorderSalt!!
                }
                if (blockchainIdentityData.usernameStatus != null) {
                    usernameStatus[BLOCKCHAIN_USERNAME_STATUS] = blockchainIdentityData.usernameStatus!!
                }
                usernameStatus[BLOCKCHAIN_USERNAME_UNIQUE] = true
                usernameStatuses[currentUsername!!] = usernameStatus
            }

            creditBalance = blockchainIdentityData.creditBalance ?: Coin.ZERO
            activeKeyCount = blockchainIdentityData.activeKeyCount ?: 0
            totalKeyCount = blockchainIdentityData.totalKeyCount ?: 0
            keysCreated = blockchainIdentityData.keysCreated ?: 0
            currentMainKeyIndex = blockchainIdentityData.currentMainKeyIndex ?: 0
            currentMainKeyType = blockchainIdentityData.currentMainKeyType
                    ?: IdentityPublicKey.TYPES.ECDSA_SECP256K1
        }
    }

    suspend fun updateBlockchainIdentityData(blockchainIdentityData: BlockchainIdentityData, blockchainIdentity: BlockchainIdentity) {
        blockchainIdentityData.apply {
            creditFundingTxId = blockchainIdentity.creditFundingTransaction?.txId
            userId = if (blockchainIdentity.registrationStatus == BlockchainIdentity.RegistrationStatus.REGISTERED)
                blockchainIdentity.uniqueIdString
            else null
            identity = blockchainIdentity.identity
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

    suspend fun resetIdentityCreationStateError(blockchainIdentityData: BlockchainIdentityData) {
        blockchainIdentityDataDao.updateCreationState(blockchainIdentityData.id, blockchainIdentityData.creationState, null)
        blockchainIdentityData.creationStateErrorMessage = null
    }

    suspend fun updateIdentityCreationState(blockchainIdentityData: BlockchainIdentityData,
                                            state: BlockchainIdentityData.CreationState,
                                            exception: Throwable? = null) {
        val errorMessage = exception?.run {
            var message = "${exception.javaClass.simpleName}: ${exception.message}"
            if (this is StatusRuntimeException) {
                val exceptionInfo = GrpcExceptionInfo(this)
                if (exceptionInfo.errors.isNotEmpty() && exceptionInfo.errors.first().containsKey("name")) {
                    message += " ${exceptionInfo.errors.first()["name"]}"
                }
            }
            message
        }
        if (errorMessage == null) {
            log.info("updating creation state {}", state)
        } else {
            log.info("updating creation state {} ({})", state, errorMessage)
        }
        blockchainIdentityDataDao.updateCreationState(blockchainIdentityData.id, state, errorMessage)
        blockchainIdentityData.creationState = state
        blockchainIdentityData.creationStateErrorMessage = errorMessage
    }

    suspend fun updateBlockchainIdentityData(blockchainIdentityData: BlockchainIdentityData) {
        blockchainIdentityDataDao.insert(blockchainIdentityData)
    }

    /**
     * Updates the dashpay.profile in the database by making a query to Platform
     *
     * @param userId
     * @return true if an update was made, false if not
     */
    suspend fun updateDashPayProfile(userId: String): Boolean {
        var profileDocument = profiles.get(userId)
        if (profileDocument == null) {
            val identity = platform.identities.get(userId)
            if (identity != null) {
                profileDocument = profiles.createProfileDocument("", "", "", null, null, identity)
            } else {
                // there is no existing identity, so do nothing
                return false
            }
        }
        val nameDocuments = platform.names.getByOwnerId(userId)

        if (nameDocuments.isNotEmpty()) {
            val username = nameDocuments[0].data["normalizedLabel"] as String

            val profile = DashPayProfile.fromDocument(profileDocument, username)
            dashPayProfileDao.insert(profile!!)
            return true
        }
        return false
    }

    suspend fun updateDashPayProfile(dashPayProfile: DashPayProfile) {
        dashPayProfileDao.insert(dashPayProfile)
    }

    private suspend fun updateDashPayContactRequest(dashPayContactRequest: DashPayContactRequest) {
        dashPayContactRequestDao.insert(dashPayContactRequest)
    }

    suspend fun doneAndDismiss() {
        val blockchainIdentityData = blockchainIdentityDataDao.load()
        if (blockchainIdentityData != null && blockchainIdentityData.creationState == BlockchainIdentityData.CreationState.DONE) {
            blockchainIdentityData.creationState = BlockchainIdentityData.CreationState.DONE_AND_DISMISS
            blockchainIdentityDataDao.insert(blockchainIdentityData)
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
                val profile = blockchainIdentity.getProfile()


                // blockchainIdentity doesn't yet keep track of the profile, so we will load it
                // into the database directly
                val dashPayProfile = if (profile != null)
                    DashPayProfile.fromDocument(profile, username)
                else
                    DashPayProfile(blockchainIdentity.uniqueIdString, blockchainIdentity.currentUsername!!)
                updateDashPayProfile(dashPayProfile!!)
            }
        }
    }

    fun getNextContactAddress(userId: String, accountReference: Int): Address {
        return blockchainIdentity.getContactNextPaymentAddress(Identifier.from(userId), accountReference)
    }

    fun updateSyncStatus(stage: PreBlockStage) {
        if (stage == PreBlockStage.Starting && lastPreBlockStage != PreBlockStage.None) {
            log.debug("skipping ${stage.name} because an identity was restored")
            return
        }
        if (preDownloadBlocks.get()) {
            firePreBlockProgressListeners(stage)
            lastPreBlockStage = stage
        } else {
            log.debug("skipping ${stage.name} because PREBLOCKS is OFF")
        }
    }

    private fun checkAndAddSentRequest(userId: String, contactRequest: ContactRequest, encryptionKey: KeyParameter? = null): Boolean {
        val contact = EvolutionContact(userId, contactRequest.toUserId.toString())
        try {
            if (!walletApplication.wallet.hasReceivingKeyChain(contact)) {
                log.info("adding accepted/send request to wallet: ${contactRequest.toUserId}")
                val contactIdentity = platform.identities.get(contactRequest.toUserId)
                var myEncryptionKey = encryptionKey
                if (encryptionKey == null && walletApplication.wallet.isEncrypted) {
                    val password = securityGuard.retrievePassword()
                    // Don't bother with DeriveKeyTask here, just call deriveKey
                    myEncryptionKey = walletApplication.wallet!!.keyCrypter!!.deriveKey(password)
                }
                blockchainIdentity.addPaymentKeyChainFromContact(contactIdentity!!, contactRequest, myEncryptionKey!!)
                return true
            }
        } catch (e: KeyCrypterException) {
            // we can't send payments to this contact due to an invalid encryptedPublicKey
            log.info("ContactRequest: error ${e.message}", e)
        } catch (e: Exception) {
            formatExceptionMessage("check and add sent requests: error", e)
        }
        return false
    }

    private fun checkAndAddReceivedRequest(userId: String, contactRequest: ContactRequest, encryptionKey: KeyParameter? = null): Boolean {
        // add the sending to contact keychain if it doesn't exist
        val contact = EvolutionContact(userId, 0, contactRequest.ownerId.toString(), contactRequest.accountReference)
        try {
            if (!walletApplication.wallet.hasSendingKeyChain(contact)) {
                log.info("adding received request: ${contactRequest.ownerId} to wallet")
                val contactIdentity = platform.identities.get(contactRequest.ownerId)
                var myEncryptionKey = encryptionKey
                if (encryptionKey == null && walletApplication.wallet.isEncrypted) {
                    val password = securityGuard.retrievePassword()
                    // Don't bother with DeriveKeyTask here, just call deriveKey
                    myEncryptionKey = walletApplication.wallet!!.keyCrypter!!.deriveKey(password)
                }
                blockchainIdentity.addContactPaymentKeyChain(contactIdentity!!, contactRequest.document, myEncryptionKey!!)
                return true
            }
        } catch (e: KeyCrypterException) {
            // we can't send payments to this contact due to an invalid encryptedPublicKey
            log.info("ContactRequest: error ${e.message}", e)
        } catch (e: Exception) {
            formatExceptionMessage("check and add received requests: error", e)
        }
        return false
    }

    var counterForReport = 0;

    /**
     * updateContactRequests will fetch new Contact Requests from the network
     * and verify that we have all requests and profiles in the local database
     *
     * This method should not use blockchainIdentity because in some cases
     * when the app starts, it has not yet been initialized
     */
    suspend fun updateContactRequests() {

        // only allow this method to execute once at a time
        if (updatingContacts.get()) {
            log.info("updateContactRequests is already running")
            return
        }

        if (!platform.hasApp("dashpay")) {
            log.info("update contacts not completed because there is no dashpay contract")
            return
        }

        val blockchainIdentityData = blockchainIdentityDataDao.load() ?: return
        if (blockchainIdentityData.creationState < BlockchainIdentityData.CreationState.DONE) {
            log.info("update contacts not completed username registration/recovery is not complete")
            return
        }

        if (blockchainIdentityData.username == null || blockchainIdentityData.userId == null) {
            return // this is here because the wallet is being reset without removing blockchainIdentityData
        }

        try {
            val userId = blockchainIdentityData.userId!!

            val userIdList = HashSet<String>()
            val watch = Stopwatch.createStarted()
            var addedContact = false
            Context.propagate(walletApplication.wallet.context)
            var encryptionKey: KeyParameter? = null

            var lastContactRequestTime = if (dashPayContactRequestDao.countAllRequests() > 0) {
                val lastTimeStamp = dashPayContactRequestDao.getLastTimestamp()
                // if the last contact request was received in the past 10 minutes, then query for
                // contact requests that are 10 minutes before it.  If the last contact request was
                // more than 10 minutes ago, then query all contact requests that came after it.
                if (lastTimeStamp < System.currentTimeMillis() - DateUtils.MINUTE_IN_MILLIS * 10)
                    lastTimeStamp
                else lastTimeStamp - DateUtils.MINUTE_IN_MILLIS * 10
            } else 0L

            updatingContacts.set(true)
            updateSyncStatus(PreBlockStage.Starting)
            updateSyncStatus(PreBlockStage.Initialization)
            checkDatabaseIntegrity(userId)

            updateSyncStatus(PreBlockStage.FixMissingProfiles)

            // Get all out our contact requests
            val toContactDocuments = contactRequests.get(userId, toUserId = false, afterTime = lastContactRequestTime, retrieveAll = true)
            toContactDocuments.forEach {

                val contactRequest = ContactRequest(it)
                log.info("found accepted/sent request: ${contactRequest.toUserId}")
                val dashPayContactRequest = DashPayContactRequest.fromDocument(contactRequest)
                if (!dashPayContactRequestDao.exists(dashPayContactRequest.userId, dashPayContactRequest.toUserId, contactRequest.accountReference)) {
                    log.info("adding accepted/send request to database: ${contactRequest.toUserId}")
                    userIdList.add(dashPayContactRequest.toUserId)
                    dashPayContactRequestDao.insert(dashPayContactRequest)

                    // add our receiving from this contact keychain if it doesn't exist
                    addedContact = addedContact || checkAndAddSentRequest(userId, contactRequest)
                    /*val contact = EvolutionContact(userId, dashPayContactRequest.toUserId)
                    try {
                        if (!walletApplication.wallet.hasReceivingKeyChain(contact)) {
                            log.info("adding accepted/send request to wallet: ${contactRequest.toUserId}")
                            val contactIdentity = platform.identities.get(contactRequest.toUserId)
                            if (encryptionKey == null && walletApplication.wallet.isEncrypted) {
                                val password = securityGuard.retrievePassword()
                                // Don't bother with DeriveKeyTask here, just call deriveKey
                                encryptionKey = walletApplication.wallet!!.keyCrypter!!.deriveKey(password)
                            }
                            blockchainIdentity.addPaymentKeyChainFromContact(contactIdentity!!, contactRequest, encryptionKey!!)
                            addedContact = true
                        }
                    } catch (e: KeyCrypterException) {
                        // we can't send payments to this contact due to an invalid encryptedPublicKey
                        log.info("ContactRequest: error ${e.message}")
                    }*/
                }
            }
            updateSyncStatus(PreBlockStage.GetReceivedRequests)
            // Get all contact requests where toUserId == userId, the users who have added me
            val fromContactDocuments = contactRequests.get(userId, toUserId = true, afterTime = lastContactRequestTime, retrieveAll = true)
            fromContactDocuments.forEach {
                val dashPayContactRequest = DashPayContactRequest.fromDocument(it)
                val contactRequest = ContactRequest(it)
                log.info("found received request: ${dashPayContactRequest.userId}")
                platform.stateRepository.addValidIdentity(dashPayContactRequest.userIdentifier)
                if (!dashPayContactRequestDao.exists(dashPayContactRequest.userId, dashPayContactRequest.toUserId, dashPayContactRequest.accountReference)) {
                    log.info("adding received request: ${dashPayContactRequest.userId} to database")
                    userIdList.add(dashPayContactRequest.userId)
                    dashPayContactRequestDao.insert(dashPayContactRequest)

                    // add the sending to contact keychain if it doesn't exist

                    addedContact = addedContact || checkAndAddReceivedRequest(userId, contactRequest)

                    /*val contact = EvolutionContact(userId, 0, dashPayContactRequest.userId, contactRequest.accountReference)
                    try {
                        if (!walletApplication.wallet.hasSendingKeyChain(contact)) {
                            log.info("adding received request: ${dashPayContactRequest.userId} to wallet")
                            val contactIdentity = platform.identities.get(dashPayContactRequest.userId)
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
                     */
                }
            }
            updateSyncStatus(PreBlockStage.GetSentRequests)

            // If new keychains were added to the wallet, then update the bloom filters
            if (addedContact) {
                mainHandler.post {
                    updateBloomFilters()
                }
            }

            //obtain profiles from new contacts
            if (userIdList.isNotEmpty()) {
                updateContactProfiles(userIdList.toList(), 0L)
            }
            updateSyncStatus(PreBlockStage.GetNewProfiles)

            // fetch updated invitations
            updateInvitations()

            // fetch updated profiles from the network
            updateContactProfiles(userId, lastContactRequestTime)

            updateSyncStatus(PreBlockStage.GetUpdatedProfiles)

            // fire listeners if there were new contacts
            if (addedContact) {
                fireContactsUpdatedListeners()
            }

            updateSyncStatus(PreBlockStage.Complete)

            log.info("updating contacts and profiles took $watch")
        } catch (e: Exception) {
            log.error(formatExceptionMessage("error updating contacts", e))
        } finally {
            updatingContacts.set(false)
            if (preDownloadBlocks.get()) {
                finishPreBlockDownload()
            }

            counterForReport++
            if (counterForReport % 8 == 0) {
                // record the report to the logs every 2 minutes
                log.info(platform.client.reportNetworkStatus())
            }
        }
    }

    private fun finishPreBlockDownload() {
        log.info("PreDownloadBlocks: complete")
        if (walletApplication.configuration.areNotificationsDisabled()) {
            // this will enable notifications, since platform information has been synced
            walletApplication.configuration.lastSeenNotificationTime = System.currentTimeMillis()
        }
        preDownloadBlocksFuture?.set(true)
        preDownloadBlocks.set(false)
    }

    private fun updateBloomFilters() {
        if (platformSyncJob.isActive) {
            val intent = Intent(
                BlockchainService.ACTION_RESET_BLOOMFILTERS, null, walletApplication,
                BlockchainServiceImpl::class.java
            )
            walletApplication.startService(intent)
        }
    }

    /**
     * Fetches updated profiles associated with contacts of userId after lastContactRequestTime
     */
    private suspend fun updateContactProfiles(userId: String, lastContactRequestTime: Long) {
        val watch = Stopwatch.createStarted()
        val userIdSet = hashSetOf<String>()

        val toContactDocuments = dashPayContactRequestDao.loadToOthers(userId)
        toContactDocuments!!.forEach {
            userIdSet.add(it.toUserId)
        }
        val fromContactDocuments = dashPayContactRequestDao.loadFromOthers(userId)
        fromContactDocuments!!.forEach {
            userIdSet.add(it.userId)
        }

        invitationsDao.loadAll().forEach {
            userIdSet.add(it.userId)
        }

        // Also add our ownerId to get our profile, in case it was updated on a different device
        userIdSet.add(userId)

        updateContactProfiles(userIdSet.toList(), lastContactRequestTime)
        log.info("updating contacts and profiles took $watch")
    }

    /**
     * Fetches updated profiles of users in userIdList after lastContactRequestTime
     *
     * if lastContactRequestTime is 0, then all profiles are retrieved
     *
     * This does not handle the case if userIdList.size > 100
     */
    private suspend fun updateContactProfiles(userIdList: List<String>, lastContactRequestTime: Long, checkingIntegrity: Boolean = false) {
        try {
            if (userIdList.isNotEmpty()) {
                val identifierList = userIdList.map { Identifier.from(it) }
                val profileDocuments = profiles.getList(identifierList, lastContactRequestTime) //only handles 100 userIds
                val profileById = profileDocuments.associateBy({ it.ownerId }, { it })

                val nameDocuments = platform.names.getList(identifierList)
                val nameById = nameDocuments.associateBy({ getIdentityForName(it) }, { it })

                for (id in profileById.keys) {
                    if (nameById.containsKey(id)) {
                        val nameDocument = nameById[id] // what happens if there is no username for the identity? crash
                        val username = nameDocument!!.data["normalizedLabel"] as String
                        val identityId = getIdentityForName(nameDocument)

                        val profileDocument = profileById[id]

                        val profile = if (profileDocument != null)
                            DashPayProfile.fromDocument(profileDocument, username)
                        else DashPayProfile(identityId.toString(), username)

                        dashPayProfileDao.insert(profile!!)
                        if (checkingIntegrity) {
                            log.info("check database integrity: adding missing profile $username:$id")
                        }
                    } else {
                        log.info("domain document for $id could not be found, though a profile exists")
                    }
                }

                // add a blank profile for any identity that is still missing a profile
                if (lastContactRequestTime == 0L) {
                    val remainingMissingProfiles = userIdList.filter { !profileById.containsKey(Identifier.from(it)) }
                    for (identityId in remainingMissingProfiles) {
                        val nameDocument = nameById[Identifier.from(identityId)]
                        // what happens if there is no username for the identity? crash
                        if (nameDocument != null) {
                            val username = nameDocument.data["normalizedLabel"] as String
                            val identityIdForName = getIdentityForName(nameDocument)
                            dashPayProfileDao.insert(DashPayProfile(identityIdForName.toString(), username))
                        } else {
                            log.info("no username found for $identityId")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            formatExceptionMessage("update contact profiles", e)
        }
    }

    // This will check for missing profiles, download them and update the database
    private suspend fun checkDatabaseIntegrity(userId: String) {
        val watch = Stopwatch.createStarted()
        log.info("check database integrity: starting")

        try {
            val userIdList = HashSet<String>()
            val missingProfiles = HashSet<String>()

            val toContactDocuments = dashPayContactRequestDao.loadToOthers(userId)
            val toContactMap = HashMap<String, DashPayContactRequest>()
            var addedContactRequests = false
            toContactDocuments!!.forEach {
                userIdList.add(it.toUserId)
                toContactMap[it.toUserId] = it

                // check to see if wallet has this contact request's keys
                val added = checkAndAddSentRequest(userId, it.toContactRequest(platform))
                if (added) {
                    log.warn("check database integrity: added sent $it to wallet since it was missing.  Transactions may also be missing")
                    addedContactRequests = true
                }
            }
            // Get all contact requests where toUserId == userId, the users who have added me
            val fromContactDocuments = dashPayContactRequestDao.loadFromOthers(userId)
            val fromContactMap = HashMap<String, DashPayContactRequest>()
            fromContactDocuments!!.forEach {
                userIdList.add(it.userId)
                fromContactMap[it.userId] = it

                // check to see if wallet has this contact request's keys
                val added = checkAndAddReceivedRequest(userId, it.toContactRequest(platform))
                if (added) {
                    log.warn("check database integrity: added received $it to wallet since it was missing")
                    addedContactRequests = true
                }
            }

            // If new keychains were added to the wallet, then update the bloom filters
            if (addedContactRequests) {
                mainHandler.post {
                    updateBloomFilters()
                }
            }

            for (user in userIdList) {
                val profile = dashPayProfileDao.loadByUserId(user)
                if (profile == null) {
                    missingProfiles.add(user)
                }
            }

            if (missingProfiles.isNotEmpty()) {
                updateContactProfiles(missingProfiles.toList(), 0, true)
            }
        } catch (e: Exception) {
            formatExceptionMessage("check database integrity", e)
        } finally {
            log.info("check database integrity complete in $watch")
        }
    }

    fun addContactsUpdatedListener(listener: OnContactsUpdated) {
        onContactsUpdatedListeners.add(listener)
    }

    fun removeContactsUpdatedListener(listener: OnContactsUpdated?) {
        onContactsUpdatedListeners.remove(listener)
    }

    private fun fireContactsUpdatedListeners() {
        for (listener in onContactsUpdatedListeners) {
            listener.onContactsUpdated()
        }
    }

    fun addPreBlockProgressListener(listener: OnPreBlockProgressListener) {
        onPreBlockContactListeners.add(listener)
    }

    fun removePreBlockProgressListener(listener: OnPreBlockProgressListener) {
        onPreBlockContactListeners.remove(listener)
    }

    private fun firePreBlockProgressListeners(stage: PreBlockStage) {
        for (listener in onPreBlockContactListeners) {
            listener.onPreBlockProgressUpdated(stage)
        }
    }

    fun getIdentityForName(nameDocument: Document): Identifier {
        val records = nameDocument.data["records"] as Map<*, *>
        return Identifier.from(records["dashUniqueIdentityId"])
    }

    suspend fun getLocalUserProfile(): DashPayProfile? {
        val blockchainIdentityBaseData = loadBlockchainIdentityBaseData()!!
        return dashPayProfileDao.loadByUserId(blockchainIdentityBaseData.userId!!)
    }

    suspend fun getLocalUserDataByUsername(username: String): UsernameSearchResult? {
        log.info("requesting local user data for $username")
        val profile = dashPayProfileDao.loadByUsername(username)
        return loadContactRequestsAndReturn(profile)
    }

    suspend fun getLocalUserDataByUserId(userId: String): UsernameSearchResult? {
        log.info("requesting local user data for $userId")
        val profile = dashPayProfileDao.loadByUserId(userId)
        return loadContactRequestsAndReturn(profile)
    }

    suspend fun loadContactRequestsAndReturn(profile: DashPayProfile?): UsernameSearchResult? {
        return profile?.run {
            log.info("successfully obtained local user data for $profile")
            val receivedContactRequest = dashPayContactRequestDao.loadToOthers(userId)?.firstOrNull()
            val sentContactRequest = dashPayContactRequestDao.loadFromOthers(userId)?.firstOrNull()
            UsernameSearchResult(this.username, this, sentContactRequest, receivedContactRequest)
        }
    }

    /**
     * Called before DashJ starts synchronizing the blockchain
     */
    fun preBlockDownload(future: SettableFuture<Boolean>) {
        GlobalScope.launch(Dispatchers.IO) {
            preDownloadBlocks.set(true)
            lastPreBlockStage = PreBlockStage.None
            preDownloadBlocksFuture = future
            log.info("PreDownloadBlocks: starting")

            //first check to see if there is a blockchain identity
            if (blockchainIdentityDataDao.load() == null) {
                log.info("PreDownloadBlocks: checking for existing associated identity")

                val identity = getIdentityFromPublicKeyId()
                if (identity != null) {
                    log.info("PreDownloadBlocks: initiate recovery of existing identity ${identity.id.toString()}")
                    ContextCompat.startForegroundService(walletApplication, createIntentForRestore(walletApplication, identity.id.toBuffer()))
                    return@launch
                } else {
                    log.info("PreDownloadBlocks: no existing identity found")
                    // resume Sync process, since there is no Platform data to sync
                    finishPreBlockDownload()
                }
            }

            // update contacts, profiles and other platform data
            else if (!updatingContacts.get()) {
                updateContactRequests()
            }
        }
    }

    /**
    This is used by java code, outside of coroutines

    This should not be a suspended method.
     */
    fun clearDatabase(includeInvitations: Boolean) {
        blockchainIdentityDataDaoAsync.clear()
        dashPayProfileDaoAsync.clear()
        dashPayContactRequestDaoAsync.clear()
        userAlertDaoAsync.clear()
        if (includeInvitations) {
            invitationsDaoAsync.clear()
        }
    }

    fun getIdentityFromPublicKeyId(): Identity? {
        val blockchainIdentityKeyChain = walletApplication.wallet.blockchainIdentityKeyChain
                ?: return null
        val fundingKey = blockchainIdentityKeyChain.watchingKey
        val identityBytes = platform.client.getIdentityByFirstPublicKey(fundingKey.pubKeyHash)
        return if (identityBytes != null) {
            platform.dpp.identity.createFromBuffer(identityBytes.toByteArray())
        } else null
    }

    fun loadProfileByUserId(userId: String): LiveData<DashPayProfile?> {
        return dashPayProfileDaoAsync.loadByUserIdDistinct(userId)
    }

    /**
     * adds a dash pay profile to the database if it is not present
     * or updates it the dashPayProfile is newer
     *
     * @param dashPayProfile
     */
    suspend fun addOrUpdateDashPayProfile(dashPayProfile: DashPayProfile) {
        val currentProfile = dashPayProfileDao.loadByUserId(dashPayProfile.userId)
        if (currentProfile == null || (currentProfile.updatedAt < dashPayProfile.updatedAt)) {
            updateDashPayProfile(dashPayProfile)
        }
    }

    //
    // Step 2 is to create the credit funding transaction
    //
    suspend fun createInviteFundingTransactionAsync(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter?)
            : CreditFundingTransaction {
        Context.propagate(walletApplication.wallet.context)
        val cftx = blockchainIdentity.createInviteFundingTransaction(Constants.DASH_PAY_FEE, keyParameter)
        val invitation = Invitation(cftx.creditBurnIdentityIdentifier.toStringBase58(), cftx.txId,
                System.currentTimeMillis())
        // update database
        updateInvitation(invitation)

        sendTransaction(cftx)
        //update database
        invitation.sentAt = System.currentTimeMillis();
        updateInvitation(invitation)
        return cftx
    }

    suspend fun updateInvitation(invitation: Invitation) {
        invitationsDao.insert(invitation)
    }

    suspend fun getInvitation(userId: String): Invitation? {
        return invitationsDao.loadByUserId(userId)
    }

    private suspend fun sendTransaction(cftx: CreditFundingTransaction): Boolean {
        log.info("Sending credit funding transaction: ${cftx.txId}")
        return suspendCoroutine { continuation ->
            cftx.confidence.addEventListener(object : TransactionConfidence.Listener {
                override fun onConfidenceChanged(confidence: TransactionConfidence?, reason: TransactionConfidence.Listener.ChangeReason?) {
                    when (reason) {
                        // If this transaction is in a block, then it has been sent successfully
                        TransactionConfidence.Listener.ChangeReason.DEPTH -> {
                            // TODO: a chainlock is needed to accompany the block information
                            // to provide sufficient proof
                        }
                        // If this transaction is InstantSend Locked, then it has been sent successfully
                        TransactionConfidence.Listener.ChangeReason.IX_TYPE -> {
                            // TODO: allow for received (IX_REQUEST) instantsend locks
                            // until the bug related to instantsend lock verification is fixed.
                            if (confidence!!.isTransactionLocked || confidence.ixType == TransactionConfidence.IXType.IX_REQUEST) {
                                confidence.removeEventListener(this)
                                continuation.resumeWith(Result.success(true))
                            }
                        }
                        // If this transaction has been seen by more than 1 peer, then it has been sent successfully
                        TransactionConfidence.Listener.ChangeReason.SEEN_PEERS -> {
                            // being seen by other peers is no longer sufficient proof
                        }
                        // If this transaction was rejected, then it was not sent successfully
                        TransactionConfidence.Listener.ChangeReason.REJECT -> {
                            if (confidence!!.hasRejections() && confidence.rejections.size >= 1) {
                                confidence.removeEventListener(this)
                                log.info("Error sending ${cftx.txId}: ${confidence.rejectedTransactionException.rejectMessage.reasonString}")
                                continuation.resumeWithException(confidence.rejectedTransactionException)
                            }
                        }
                        TransactionConfidence.Listener.ChangeReason.TYPE -> {
                            if (confidence!!.hasErrors()) {
                                confidence.removeEventListener(this)
                                val code = when (confidence.confidenceType) {
                                    TransactionConfidence.ConfidenceType.DEAD -> RejectMessage.RejectCode.INVALID
                                    TransactionConfidence.ConfidenceType.IN_CONFLICT -> RejectMessage.RejectCode.DUPLICATE
                                    else -> RejectMessage.RejectCode.OTHER
                                }
                                val rejectMessage = RejectMessage(Constants.NETWORK_PARAMETERS, code, confidence.transactionHash,
                                        "Credit funding transaction is dead or double-spent", "cftx-dead-or-double-spent")
                                log.info("Error sending ${cftx.txId}: ${rejectMessage.reasonString}")
                                continuation.resumeWithException(RejectedTransactionException(cftx, rejectMessage))
                            }
                        }
                        else -> {
                            // ignore
                        }
                    }
                }
            })
            walletApplication.broadcastTransaction(cftx)
        }
    }

    /**
     * Updates invitation status
     */
    private suspend fun updateInvitations() {
        val invitations = invitationsDao.loadAll()
        for (invitation in invitations) {
            if (invitation.acceptedAt == 0L) {
                val identity = platform.identities.get(invitation.userId)
                if (identity != null) {
                    updateDashPayProfile(identity.id.toString())
                    invitation.acceptedAt = System.currentTimeMillis()
                    updateInvitation(invitation)
                }
            }
        }
    }

    /**
     * validates an invite
     *
     * @return Returns true if it is valid, false if the invite has been used.
     *
     * @throws Exception if the invite is invalid
     */

    fun validateInvitation(invite: InvitationLinkData): Boolean {
        var tx = platform.client.getTransaction(invite.cftx)
        //TODO: remove when iOS uses big endian
        if (tx == null)
            tx = platform.client.getTransaction(Sha256Hash.wrap(invite.cftx).reversedBytes.toHexString())
        if (tx != null) {
            val cfTx = CreditFundingTransaction(Constants.NETWORK_PARAMETERS, tx.toByteArray())
            val identity = platform.identities.get(cfTx.creditBurnIdentityIdentifier.toStringBase58())
            if (identity == null) {
                // determine if the invite has enough credits
                if (cfTx.lockedOutput.value < Constants.DASH_PAY_INVITE_MIN) {
                    val reason = "Invite does not have enough credits ${cfTx.lockedOutput.value} < ${Constants.DASH_PAY_INVITE_MIN}"
                    log.warn(reason);
                    throw InsufficientMoneyException(cfTx.lockedOutput.value, reason)
                }
                return try {
                    DumpedPrivateKey.fromBase58(Constants.NETWORK_PARAMETERS, invite.privateKey)
                    InstantSendLock(Constants.NETWORK_PARAMETERS, Utils.HEX.decode(invite.instantSendLock))
                    log.info("Invite is valid")
                    true
                } catch (e: AddressFormatException.WrongNetwork) {
                    log.warn("Invite has private key from wrong network: $e")
                    throw e
                } catch (e: AddressFormatException) {
                    log.warn("Invite has invalid private key: $e")
                    throw e
                } catch (e: Exception) {
                    log.warn("Invite has invalid instantSendLock: $e")
                    throw e
                }
            } else {
                log.warn("Invitation has been used: ${identity.id}")
                return false
            }
        }
        log.warn("Invitation uses an invalid transaction ${invite.cftx}")
        throw IllegalArgumentException("Invitation uses an invalid transaction ${invite.cftx}")
    }

    fun clearBlockchainData() {
        GlobalScope.launch(Dispatchers.IO) {
            blockchainIdentityDataDao.clear()
        }
    }

    fun handleSentCreditFundingTransaction(cftx: CreditFundingTransaction, blockTimestamp: Long) {
        if (this::blockchainIdentity.isInitialized) {
            GlobalScope.launch(Dispatchers.IO) {
                //Context.getOrCreate(platform.params)
                val isInvite = walletApplication.wallet.invitationFundingKeyChain.findKeyFromPubHash(cftx.creditBurnPublicKeyId.bytes) != null
                val isTopup = walletApplication.wallet.blockchainIdentityTopupKeyChain.findKeyFromPubHash(cftx.creditBurnPublicKeyId.bytes) != null
                val isIdentity = walletApplication.wallet.blockchainIdentityFundingKeyChain.findKeyFromPubHash(cftx.creditBurnPublicKeyId.bytes) != null
                val identityId = cftx.creditBurnIdentityIdentifier.toStringBase58();
                if (isInvite && !isTopup && !isIdentity && invitationsDao.loadByUserId(identityId) == null) {
                    // this is not in our database
                    val invite = Invitation(identityId, cftx.txId, blockTimestamp,
                            "", blockTimestamp, 0)

                    // profile information here
                    try {
                        if (updateDashPayProfile(identityId)) {
                            val profile = dashPayProfileDao.loadByUserId(identityId)
                            invite.acceptedAt = profile?.createdAt
                                    ?: -1 // it was accepted in the past, use profile creation as the default
                        }
                    } catch (e: NullPointerException) {
                        // swallow, the identity was not found for this invite
                    }
                    invitationsDao.insert(invite)
                }
            }
        }
    }

    // current unused
    private suspend fun getContactRequestReport(): String {
        val report = StringBuilder();
        val profiles = dashPayProfileDao.loadAll()
        val profilesById = profiles.associateBy({ it.userId }, { it })
        report.append("Contact Requests (Sent) -----------------\n")
        dashPayContactRequestDao.loadToOthers(blockchainIdentity.uniqueIdString)?.forEach {
            val fromProfile = profilesById[it.userId]
            report.append(it.userId)
            if (fromProfile != null) {
                report.append("(").append(fromProfile.username).append(")")
            }
            report.append(" -> ").append(it.toUserId)
            val toProfile = profilesById[it.toUserId]
            if (toProfile != null) {
                report.append("(").append(toProfile.username).append(")")
            }
            report.append("\n")
        }
        report.append("Contact Requests (Received) -----------------\n")
        dashPayContactRequestDao.loadFromOthers(blockchainIdentity.uniqueIdString)?.forEach {
            val fromProfile = profilesById[it.userId]
            report.append(it.userId)
            if (fromProfile != null) {
                report.append("(").append(fromProfile).append(")")
            }
            report.append(" -> ").append(it.toUserId)
            val toProfile = profilesById[it.toUserId]
            if (toProfile != null) {
                report.append("(").append(toProfile.username).append(")")
            }
            report.append("\n")
        }
        return report.toString()
    }
}