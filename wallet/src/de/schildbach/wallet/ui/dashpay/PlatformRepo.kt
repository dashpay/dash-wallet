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

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.google.common.base.Preconditions
import com.google.common.base.Stopwatch
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors.fromApplication
import dagger.hilt.components.SingletonComponent
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.*
import de.schildbach.wallet.database.AppDatabase
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.Invitation
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.SeriousError
import de.schildbach.wallet.livedata.SeriousErrorListener
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.security.SecurityGuard
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.bitcoinj.coinjoin.CoinJoinClientManager
import org.bitcoinj.coinjoin.CoinJoinClientOptions
import org.bitcoinj.coinjoin.PoolStatus
import org.bitcoinj.coinjoin.listeners.MixingCompleteListener
import org.bitcoinj.coinjoin.utils.ProTxToOutpoint
import org.bitcoinj.core.*
import org.bitcoinj.crypto.IDeterministicKey
import org.bitcoinj.evolution.CreditFundingTransaction
import org.bitcoinj.quorums.InstantSendLock
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bitcoinj.wallet.WalletEx
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import org.dashj.platform.dapiclient.MaxRetriesReachedException
import org.dashj.platform.dapiclient.NoAvailableAddressesForRetryException
import org.dashj.platform.dapiclient.model.GrpcExceptionInfo
import org.dashj.platform.dashpay.*
import org.dashj.platform.dashpay.BlockchainIdentity.Companion.BLOCKCHAIN_USERNAME_SALT
import org.dashj.platform.dashpay.BlockchainIdentity.Companion.BLOCKCHAIN_USERNAME_STATUS
import org.dashj.platform.dashpay.BlockchainIdentity.Companion.BLOCKCHAIN_USERNAME_UNIQUE
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.errors.concensus.basic.identity.InvalidInstantAssetLockProofException
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.identity.IdentityPublicKey
import org.dashj.platform.dpp.toHex
import org.dashj.platform.sdk.platform.DomainDocument
import org.dashj.platform.sdk.platform.Names
import org.dashj.platform.sdk.platform.Platform
import org.dashj.platform.sdk.platform.multicall.MulticallQuery
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class PlatformRepo private constructor(val walletApplication: WalletApplication) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface PlatformRepoEntryPoint {
        fun provideAppDatabase(): AppDatabase
    }

    companion object {
        private val log = LoggerFactory.getLogger(PlatformRepo::class.java)

        private val platformRepoInstance = PlatformRepo(WalletApplication.getInstance())

        @JvmStatic
        @Deprecated("Inject instead")
        fun getInstance(): PlatformRepo {
            return platformRepoInstance
        }
    }

    var onIdentityResolved: ((Identity?) -> Unit)? = {}
    private val onSeriousErrorListeneners = arrayListOf<SeriousErrorListener>()

    val platform = Platform(Constants.NETWORK_PARAMETERS)
    val profiles = Profiles(platform)
    val contactRequests = ContactRequests(platform)

    lateinit var blockchainIdentity: BlockchainIdentity
        private set

    val hasIdentity: Boolean
        get() = this::blockchainIdentity.isInitialized

    var authenticationGroupExtension: AuthenticationGroupExtension? = null
        private set

    private val entryPoint = fromApplication(walletApplication, PlatformRepoEntryPoint::class.java)
    private val appDatabase = entryPoint.provideAppDatabase()
    private val blockchainIdentityDataDao = appDatabase.blockchainIdentityDataDao()
    private val dashPayProfileDao = appDatabase.dashPayProfileDao()
    private val dashPayContactRequestDao = appDatabase.dashPayContactRequestDao()
    private val invitationsDao = appDatabase.invitationsDao()
    private val userAlertDao = appDatabase.userAlertDao()

    private val backgroundThread = HandlerThread("background", Process.THREAD_PRIORITY_BACKGROUND)
    private val backgroundHandler: Handler

    private val analytics: AnalyticsService by lazy {
        walletApplication.analyticsService
    }

    private val keyChainTypes = EnumSet.of(
        AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY,
        AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_FUNDING,
        AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_TOPUP,
        AuthenticationKeyChain.KeyChainType.INVITATION_FUNDING
    )

    init {
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    suspend fun init() {
        authenticationGroupExtension = walletApplication.wallet?.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
        blockchainIdentityDataDao.load()?.let {
            blockchainIdentity = initBlockchainIdentity(it, walletApplication.wallet!!)
            platformRepoInstance.initializeStateRepository()
        }
    }

    fun getWalletEncryptionKey(): KeyParameter? {
        return if (walletApplication.wallet!!.isEncrypted) {
            val password = try {
                // always create a SecurityGuard when it is required
                val securityGuard = SecurityGuard()
                securityGuard.retrievePassword()
            } catch (e: IllegalArgumentException) {
                log.error("There was an error retrieving the wallet password", e)
                analytics.logError(e, "There was an error retrieving the wallet password")
                null
            }
            // Don't bother with DeriveKeyTask here, just call deriveKey
            walletApplication.wallet!!.keyCrypter!!.deriveKey(password)
        } else {
            null
        }
    }

    fun getWalletSeed(): DeterministicSeed? {
        val wallet = walletApplication.wallet!!
        return if (wallet.isEncrypted) {
            val password = try {
                // always create a SecurityGuard when it is required
                val securityGuard = SecurityGuard()
                securityGuard.retrievePassword()
            } catch (e: IllegalArgumentException) {
                log.error("There was an error retrieving the wallet password", e)
                analytics.logError(e, "There was an error retrieving the wallet password")
                null
            }
            // Don't bother with DeriveKeyTask here, just call deriveKey
            val encryptionKey = wallet.keyCrypter!!.deriveKey(password)
            wallet.keyChainSeed.decrypt(wallet.keyCrypter, "", encryptionKey)
        } else {
            null
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

        if (this::blockchainIdentity.isInitialized && blockchainIdentity.isRegistered()) {
            val identityId = blockchainIdentity.uniqueIdString
            platform.stateRepository.addValidIdentity(Identifier.from(identityId))

            // load all id's of users who have sent us a contact request
            dashPayContactRequestDao.loadFromOthers(identityId).forEach {
                platform.stateRepository.addValidIdentity(it.userIdentifier)
            }

            // load all id's of users for whom we have profiles
            dashPayProfileDao.loadAll().forEach {
                platform.stateRepository.addValidIdentity(it.userIdentifier)
            }

            platform.stateRepository.storeIdentity(blockchainIdentity.identity!!)
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

        val profileById: Map<Identifier, Document> = if (userIds.isNotEmpty()) {
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

        // TODO: this is only needed when Proofs don't sort results
        // This was added in v0.20
        usernameSearchResults.sortBy { it.username }

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

    fun formatExceptionMessage(description: String, e: Exception): String {
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

    /**
     * returns true if:
     *  1. Invites have been not been sent previously
     *  2. Identity Creation is not in progress
     *  3. The balance is high enough
     */

    suspend fun shouldShowAlert(): Boolean {
        val hasSentInvites = invitationsDao.count() > 0
        val blockchainIdentityData = blockchainIdentityDataDao.load()
        val noIdentityCreatedOrInProgress = (blockchainIdentityData == null) || blockchainIdentityData.creationState == BlockchainIdentityData.CreationState.NONE
        val canAffordIdentityCreation = walletApplication.canAffordIdentityCreation()
        return !noIdentityCreatedOrInProgress && (canAffordIdentityCreation || !hasSentInvites)
    }


    suspend fun getNotificationCount(date: Long): Int {
        var count = 0
        if (!platformRepoInstance.isUsernameRegistered()) {
            return 0
        }

        if (shouldShowAlert()) {
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

    private fun isUsernameRegistered(): Boolean {
        return this::blockchainIdentity.isInitialized
    }

/*
    @Throws(Exception::class)
    suspend fun sendContactRequest(toUserId: String): DashPayContactRequest {
        if (walletApplication.wallet!!.isEncrypted) {
            // always create a SecurityGuard when it is required
            val securityGuard = SecurityGuard()
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
        val timer = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_CONTACT_REQUEST_SEND)
        val cr = contactRequests.create(blockchainIdentity, potentialContactIdentity!!, encryptionKey)
        timer.logTiming()
        log.info("contact request sent")

        // add our receiving from this contact keychain if it doesn't exist
        val contact = EvolutionContact(blockchainIdentity.uniqueIdString, toUserId)

        if (!walletApplication.wallet!!.hasReceivingKeyChain(contact)) {
            Context.propagate(walletApplication.wallet!!.context)
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
*/

    //
    // Step 1 is to upgrade the wallet to support authentication keys
    //
    suspend fun addWalletAuthenticationKeysAsync(seed: DeterministicSeed, keyParameter: KeyParameter) {
        withContext(Dispatchers.IO) {
            val wallet = walletApplication.wallet as WalletEx
            // this will initialize any missing key chains
            wallet.initializeCoinJoin(keyParameter)

            var authenticationGroupExtension = AuthenticationGroupExtension(wallet)
            authenticationGroupExtension = wallet.addOrGetExistingExtension(authenticationGroupExtension) as AuthenticationGroupExtension
            authenticationGroupExtension.addEncryptedKeyChains(wallet.params, seed, keyParameter, keyChainTypes)
            this@PlatformRepo.authenticationGroupExtension = authenticationGroupExtension
        }
    }

    //
    // Step 2 is to create the credit funding transaction
    //
    suspend fun createCreditFundingTransactionAsync(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter?, useCoinJoin: Boolean) {
        withContext(Dispatchers.IO) {
            Context.propagate(walletApplication.wallet!!.context)
            val cftx = blockchainIdentity.createCreditFundingTransaction(Constants.DASH_PAY_FEE, keyParameter, useCoinJoin)
            blockchainIdentity.initializeCreditFundingTransaction(cftx)
        }
    }

    //
    // Step 2 is to obtain the credit funding transaction for invites
    //
    suspend fun obtainCreditFundingTransactionAsync(blockchainIdentity: BlockchainIdentity, invite: InvitationLinkData) {
        withContext(Dispatchers.IO) {
            Context.propagate(walletApplication.wallet!!.context)
            var cftxData = platform.client.getTransaction(invite.cftx)
            //TODO: remove when iOS uses big endian
            if (cftxData == null)
                cftxData = platform.client.getTransaction(Sha256Hash.wrap(invite.cftx).reversedBytes.toHex())
            val cftx = CreditFundingTransaction(platform.params, cftxData!!.transaction)
            val privateKey = DumpedPrivateKey.fromBase58(platform.params, invite.privateKey).key
            cftx.setCreditBurnPublicKeyAndIndex(privateKey, 0)

            // TODO: when all instantsend locks are deterministic, we don't need the catch block
            val instantSendLock = try {
                InstantSendLock(platform.params, Utils.HEX.decode(invite.instantSendLock), InstantSendLock.ISDLOCK_VERSION)
            } catch (e: Exception) {
                InstantSendLock(platform.params, Utils.HEX.decode(invite.instantSendLock), InstantSendLock.ISLOCK_VERSION)
            }

            cftx.confidence.setInstantSendLock(instantSendLock)
            blockchainIdentity.initializeCreditFundingTransaction(cftx)
        }
    }

    //
    // Step 3: Register the identity
    //
    suspend fun registerIdentityAsync(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter?) {
        withContext(Dispatchers.IO) {
            Context.getOrCreate(walletApplication.wallet!!.params)
            for (i in 0 until 3) {
                try {
                    val timer = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_IDENTITY_CREATE)
                    blockchainIdentity.registerIdentity(keyParameter)
                    timer.logTiming() // we won't log timing for failed registrations
                    return@withContext
                } catch (e: InvalidInstantAssetLockProofException) {
                    log.info("instantSendLock error: retry registerIdentity again ($i)")
                    delay(3000)
                }
            }
            throw InvalidInstantAssetLockProofException("failed after 3 tries")
        }
    }

    //
    // Step 3: Verify that the identity is registered
    //
    @Deprecated("watch* functions should no longer be used")
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
            val timer = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_PREORDER_CREATE)
            blockchainIdentity.registerPreorderedSaltedDomainHashesForUsernames(names, keyParameter)
            timer.logTiming()
        }
    }

    //
    // Step 4: Verify that the username was preordered
    //
    @Deprecated("watch* functions should no longer be used")
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
            val timer = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_DOMAIN_CREATE)
            blockchainIdentity.registerUsernameDomainsForUsernames(names, keyParameter)
            timer.logTiming()
        }
    }

    //
    // Step 5: Verify that the username was registered
    //
    @Deprecated("watch* functions should no longer be used")
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
            // the blockchain is synced past the point when the credit funding tx was found
            BlockchainIdentity(
                platform,
                creditFundingTransaction,
                wallet,
                authenticationGroupExtension!!,
                blockchainIdentityData.identity
            )
        } else {
            // the blockchain is not synced
            val blockchainIdentity = BlockchainIdentity(platform, 0, wallet, authenticationGroupExtension!!)
            if (blockchainIdentityData.creationState >= BlockchainIdentityData.CreationState.IDENTITY_REGISTERED) {
                blockchainIdentity.apply {
                    uniqueId = Sha256Hash.wrap(Base58.decode(blockchainIdentityData.userId))
                    identity = blockchainIdentityData.identity
                }
            } else {
                return blockchainIdentity
            }
            blockchainIdentity
        }
        return blockchainIdentity.apply {
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
                    ?: IdentityPublicKey.Type.ECDSA_SECP256K1
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
                val exceptionInfo = GrpcExceptionInfo(this).exception
                message += exceptionInfo
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
        try {
            var profileDocument = profiles.get(userId)
            if (profileDocument == null) {
                val identity = platform.identities.get(userId)
                if (identity != null) {
                    profileDocument =
                        profiles.createProfileDocument("", "", "", null, null, identity)
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
        } catch (e: StatusRuntimeException) {
            formatExceptionMessage("update profile failure", e)
            return false
        }
    }

    suspend fun updateDashPayProfile(dashPayProfile: DashPayProfile) {
        dashPayProfileDao.insert(dashPayProfile)
    }

    suspend fun updateDashPayContactRequest(dashPayContactRequest: DashPayContactRequest) {
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

    var counterForReport = 0

    fun addSeriousErrorListener(listener: SeriousErrorListener) {
        onSeriousErrorListeneners.add(listener)
    }

    fun removeSeriousErrorListener(listener: SeriousErrorListener) {
        onSeriousErrorListeneners.remove(listener)
    }

    fun fireSeriousErrorListeners(error: SeriousError) {
        for (listener in onSeriousErrorListeneners) {
            listener.onSeriousError(Resource.success(error))
        }
    }

    /**
     * obtains the identity associated with the username (domain document)
     * @throws NullPointerException if neither the unique id or alias exists
     */
    fun getIdentityForName(nameDocument: Document): Identifier {
        val domainDocument = DomainDocument(nameDocument)

        // look at the unique identity first, followed by the alias
        return domainDocument.dashUniqueIdentityId ?: domainDocument.dashAliasIdentityId!!
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
            val receivedContactRequest = dashPayContactRequestDao.loadToOthers(userId).firstOrNull()
            val sentContactRequest = dashPayContactRequestDao.loadFromOthers(userId).firstOrNull()
            UsernameSearchResult(this.username, this, sentContactRequest, receivedContactRequest)
        }
    }



    /**
    This is used by java code, outside of coroutines

    This should not be a suspended method.
     */
    suspend fun clearDatabase(includeInvitations: Boolean) {
        log.info("clearing databases (includeInvitations = $includeInvitations)")
        blockchainIdentityDataDao.clear()
        dashPayProfileDao.clear()
        dashPayContactRequestDao.clear()
        userAlertDao.clear()
        if (includeInvitations) {
            invitationsDao.clear()
        }
    }

    fun getBlockchainIdentityKey(index: Int, keyParameter: KeyParameter?): IDeterministicKey? {
        val authenticationChain = authenticationGroupExtension?.getKeyChain(
            AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY
        ) ?: return null

        // decrypt keychain
        val decryptedChain = if (walletApplication.wallet!!.isEncrypted) {
            authenticationChain.toDecrypted(keyParameter)
        } else {
            authenticationChain
        }
        val key = decryptedChain.getKey(index) // watchingKey
        Preconditions.checkState(key.path.last().isHardened)
        return key

    }

    fun getIdentityFromPublicKeyId(): Identity? {
        val encryptionKey = getWalletEncryptionKey()
        val firstIdentityKey = getBlockchainIdentityKey(0, encryptionKey) ?: return null

        return try {
            platform.stateRepository.fetchIdentityFromPubKeyHash(firstIdentityKey.pubKeyHash)
        } catch (e: MaxRetriesReachedException) {
            null
        } catch (e: NoAvailableAddressesForRetryException) {
            null
        }
    }

    fun observeProfileByUserId(userId: String): Flow<DashPayProfile?> {
        return dashPayProfileDao.observeByUserId(userId).distinctUntilChanged()
    }

    suspend fun loadProfileByUserId(userId: String): DashPayProfile? {
        return dashPayProfileDao.loadByUserId(userId)
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
        // dashj Context does not work with coroutines well, so we need to call Context.propogate
        // in each suspend method that uses the dashj Context
        Context.propagate(walletApplication.wallet!!.context)
        val cftx = blockchainIdentity.createInviteFundingTransaction(Constants.DASH_PAY_FEE, keyParameter, useCoinJoin = false)
        val invitation = Invitation(cftx.creditBurnIdentityIdentifier.toStringBase58(), cftx.txId,
                System.currentTimeMillis())
        // update database
        updateInvitation(invitation)

        sendTransaction(cftx)
        //update database
        invitation.sentAt = System.currentTimeMillis()
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
     * validates an invite
     *
     * @return Returns true if it is valid, false if the invite has been used.
     *
     * @throws Exception if the invite is invalid
     */

    fun validateInvitation(invite: InvitationLinkData): Boolean {
        val stopWatch = Stopwatch.createStarted()
        var tx = platform.client.getTransaction(invite.cftx)
        log.info("validateInvitation: obtaining transaction info took $stopWatch")
        //TODO: remove when iOS uses big endian
        if (tx == null) {
            tx =
                platform.client.getTransaction(Sha256Hash.wrap(invite.cftx).reversedBytes.toHex())
        }
        if (tx != null) {
            val cfTx = CreditFundingTransaction(Constants.NETWORK_PARAMETERS, tx.transaction)
            val identity = platform.identities.get(cfTx.creditBurnIdentityIdentifier.toStringBase58())
            if (identity == null) {
                // determine if the invite has enough credits
                if (cfTx.lockedOutput.value < Constants.DASH_PAY_INVITE_MIN) {
                    val reason = "Invite does not have enough credits ${cfTx.lockedOutput.value} < ${Constants.DASH_PAY_INVITE_MIN}"
                    log.warn(reason)
                    log.info("validateInvitation took $stopWatch")
                    throw InsufficientMoneyException(cfTx.lockedOutput.value, reason)
                }
                return try {
                    DumpedPrivateKey.fromBase58(Constants.NETWORK_PARAMETERS, invite.privateKey)
                    // TODO: when all instantsend locks are deterministic, we don't need the catch block
                    try {
                        InstantSendLock(
                            Constants.NETWORK_PARAMETERS,
                            Utils.HEX.decode(invite.instantSendLock),
                            InstantSendLock.ISDLOCK_VERSION
                        )
                    } catch (e: Exception) {
                        InstantSendLock(
                            Constants.NETWORK_PARAMETERS,
                            Utils.HEX.decode(invite.instantSendLock),
                            InstantSendLock.ISLOCK_VERSION
                        )
                    }
                    log.info("Invite is valid and took $stopWatch")
                    true
                } catch (e: AddressFormatException.WrongNetwork) {
                    log.warn("Invite has private key from wrong network: $e and took $stopWatch")
                    throw e
                } catch (e: AddressFormatException) {
                    log.warn("Invite has invalid private key: $e and took $stopWatch")
                    throw e
                } catch (e: Exception) {
                    log.warn("Invite has invalid instantSendLock: $e and took $stopWatch")
                    throw e
                }
            } else {
                log.warn("Invitation has been used: ${identity.id} and took $stopWatch")
                return false
            }
        }
        log.warn("Invitation uses an invalid transaction ${invite.cftx} and took $stopWatch")
        throw IllegalArgumentException("Invitation uses an invalid transaction ${invite.cftx}")
    }

    fun clearBlockchainIdentityData() {
        GlobalScope.launch(Dispatchers.IO) {
            blockchainIdentityDataDao.clear()
        }
    }

    fun handleSentCreditFundingTransaction(cftx: CreditFundingTransaction, blockTimestamp: Long) {
        val extension = authenticationGroupExtension

        if (this::blockchainIdentity.isInitialized && extension != null) {
            GlobalScope.launch(Dispatchers.IO) {
                // Context.getOrCreate(platform.params)
                val isInvite = extension.invitationFundingKeyChain.findKeyFromPubHash(cftx.creditBurnPublicKeyId.bytes) != null
                val isTopup = extension.identityTopupKeyChain.findKeyFromPubHash(cftx.creditBurnPublicKeyId.bytes) != null
                val isIdentity = extension.identityFundingKeyChain.findKeyFromPubHash(cftx.creditBurnPublicKeyId.bytes) != null
                val identityId = cftx.creditBurnIdentityIdentifier.toStringBase58()
                if (isInvite && !isTopup && !isIdentity && invitationsDao.loadByUserId(identityId) == null) {
                    // this is not in our database
                    val invite = Invitation(
                        identityId,
                        cftx.txId,
                        blockTimestamp,
                        "",
                        blockTimestamp,
                        0
                    )

                    // profile information here
                    try {
                        if (updateDashPayProfile(identityId)) {
                            val profile = dashPayProfileDao.loadByUserId(identityId)
                            invite.acceptedAt = profile?.createdAt
                                    ?: -1 // it was accepted in the past, use profile creation as the default
                        }
                    } catch (e: NullPointerException) {
                        // swallow, the identity was not found for this invite
                    } catch (e: MaxRetriesReachedException) {
                        // swallow, the profile could not be retrieved
                        // the invite status update function should be able to try again
                    }
                    invitationsDao.insert(invite)
                }
            }
        }
    }

    // current unused
    private suspend fun getContactRequestReport(): String {
        val report = StringBuilder()
        val profiles = dashPayProfileDao.loadAll()
        val profilesById = profiles.associateBy({ it.userId }, { it })
        report.append("Contact Requests (Sent) -----------------\n")
        dashPayContactRequestDao.loadToOthers(blockchainIdentity.uniqueIdString).forEach {
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
        dashPayContactRequestDao.loadFromOthers(blockchainIdentity.uniqueIdString).forEach {
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