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
import android.text.format.DateUtils
import com.google.common.base.Preconditions
import com.google.common.base.Stopwatch
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.schildbach.wallet.Constants
import de.schildbach.wallet.Constants.DASH_PAY_FEE_CONTESTED
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.*
import de.schildbach.wallet.database.AppDatabase
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.Invitation
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.SeriousError
import de.schildbach.wallet.livedata.SeriousErrorListener
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.security.SecurityGuardException
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.bitcoinj.core.*
import org.bitcoinj.crypto.IDeterministicKey
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.quorums.InstantSendLock
import org.bitcoinj.wallet.AuthenticationKeyChain
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
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.errors.concensus.basic.identity.InvalidInstantAssetLockProofException
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.toHex
import org.dashj.platform.dpp.voting.Contenders
import org.dashj.platform.sdk.platform.DomainDocument
import org.dashj.platform.sdk.platform.Names
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PlatformRepo @Inject constructor(
    val walletApplication: WalletApplication,
    val blockchainIdentityDataStorage: BlockchainIdentityConfig,
    val appDatabase: AppDatabase,
    val platform: PlatformService,
    val coinJoinConfig: CoinJoinConfig,
    val dashPayConfig: DashPayConfig
) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface PlatformRepoEntryPoint {
        fun provideAppDatabase(): AppDatabase
    }

    companion object {
        private val log = LoggerFactory.getLogger(PlatformRepo::class.java)
        const val TIMESPAN: Long = DateUtils.DAY_IN_MILLIS * 90 // 90 days
        const val TOP_CONTACT_COUNT = 4
    }

    var onIdentityResolved: ((Identity?) -> Unit)? = {}
    private val onSeriousErrorListeneners = arrayListOf<SeriousErrorListener>()

    lateinit var blockchainIdentity: BlockchainIdentity
        private set

    val hasBlockchainIdentity: Boolean
        get() = this::blockchainIdentity.isInitialized

    suspend fun hasIdentity(): Boolean = this::blockchainIdentity.isInitialized ||
            blockchainIdentityDataStorage.get(BlockchainIdentityConfig.IDENTITY_ID) != null

    suspend fun hasUsername(): Boolean = (this::blockchainIdentity.isInitialized && blockchainIdentity.currentUsername != null) ||
            blockchainIdentityDataStorage.get(BlockchainIdentityConfig.USERNAME) != null

    @Throws(IllegalStateException::class)
    suspend fun getIdentity(): String {
        return if (this::blockchainIdentity.isInitialized) {
            blockchainIdentity.uniqueIdString
        } else {
            blockchainIdentityDataStorage.get(BlockchainIdentityConfig.IDENTITY_ID)!!
            blockchainIdentityDataStorage.get(BlockchainIdentityConfig.IDENTITY_ID)
                ?: throw IllegalStateException("IdentityId not found")
        }
    }

    suspend fun getUsername(): String? {
        return if (this::blockchainIdentity.isInitialized) {
            blockchainIdentity.currentUsername
        } else {
            blockchainIdentityDataStorage.get(BlockchainIdentityConfig.USERNAME)
        }
    }

    val authenticationGroupExtension: AuthenticationGroupExtension?
        get() = walletApplication.authenticationGroupExtension
    //getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as? AuthenticationGroupExtension


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
        blockchainIdentityDataStorage.load()?.let {
            blockchainIdentity = initBlockchainIdentity(it, walletApplication.wallet!!)
            initializeStateRepository()
        }
    }

    fun getWalletEncryptionKey(): KeyParameter? {
        return if (walletApplication.wallet!!.isEncrypted) {
            val password = try {
                // always create a SecurityGuard when it is required
                val securityGuard = SecurityGuard.getInstance()
                securityGuard.retrievePassword()
            } catch (e: SecurityGuardException) {
                log.error("There was an error retrieving the wallet password", e)
                analytics.logError(e, "There was an error retrieving the wallet password")
                null
            }
            // Don't bother with DeriveKeyTask here, just call deriveKey
            password?.let { walletApplication.wallet!!.keyCrypter!!.deriveKey(it) }
        } else {
            null
        }
    }

    fun getWalletSeed(): DeterministicSeed? {
        val wallet = walletApplication.wallet!!
        return if (wallet.isEncrypted) {
            val password = try {
                // always create a SecurityGuard when it is required
                val securityGuard = SecurityGuard.getInstance()
                securityGuard.retrievePassword()
            } catch (e: SecurityGuardException) {
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

    fun getUsername(username: String): Resource<Document> {
        return try {
            val nameDocument = platform.names.get(Names.normalizeString(username))
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

    fun getVoteContenders(username: String): Contenders {
        return try {
            platform.names.getVoteContenders(Names.normalizeString(username))
        } catch (e: Exception) {
            Contenders(Optional.empty(), mapOf(), 0, 0)
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
        return withContext(Dispatchers.IO) {
            val userIdString = blockchainIdentity.uniqueIdString
            val userId = blockchainIdentity.uniqueIdentifier

            // Names.search does support retrieving 100 names at a time if retrieveAll = false
            //TODO: Maybe add pagination later? Is very unlikely that a user will scroll past 100 search results
            // Sometimes when onlyExactUsername = true, an exception is thrown here and that results in a crash
            // it is not clear why a search for an existing username results in a failure to find it again.
            val nameDocuments = if (!onlyExactUsername) {
                platform.names.search(text, Names.DEFAULT_PARENT_DOMAIN, retrieveAll = false, limit = limit)
            } else {
                val nameDocument = platform.names.get(text, Names.DEFAULT_PARENT_DOMAIN)
                if (nameDocument != null) {
                    listOf(nameDocument)
                } else {
                    listOf()
                }
            }
            val userIds = if (onlyExactUsername) {
                val result = mutableListOf<Identifier>()
                val exactNameDoc = try {
                    DomainDocument(nameDocuments.first { text == it.data["normalizedLabel"] })
                } catch (e: NoSuchElementException) {
                    null
                }
                if (exactNameDoc != null) {
                    result.add(getIdentityForName(exactNameDoc))
                }
                result
            } else {
                nameDocuments.map { getIdentityForName(DomainDocument(it)) }
            }.toSet().toList()

            val profileById: Map<Identifier, Document> = if (userIds.isNotEmpty()) {
                val profileDocuments = platform.profiles.getList(userIds)
                profileDocuments.associateBy({ it.ownerId }, { it })
            } else {
                log.warn("search usernames: userIdList is empty, though nameDocuments has ${nameDocuments.size} items")
                mapOf()
            }

            val toContactDocuments = dashPayContactRequestDao.loadToOthers(userIdString)

            // Get all contact requests where toUserId == userId
            val fromContactDocuments = dashPayContactRequestDao.loadFromOthers(userIdString)

            val usernameSearchResults = ArrayList<UsernameSearchResult>()

            for (domainDoc in nameDocuments) {
                val nameDoc = DomainDocument(domainDoc)
                if (nameDoc.dashAliasIdentityId != null) {
                    continue // skip aliases
                }

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

                val username = nameDoc.label
                val profileDoc = profileById[nameDocIdentityId]

                val dashPayProfile = if (profileDoc != null)
                    DashPayProfile.fromDocument(profileDoc, username)!!
                else DashPayProfile(nameDocIdentityId.toString(), username)

                usernameSearchResults.add(UsernameSearchResult(username,
                    dashPayProfile, toContact, fromContact))
            }

            // TODO: this is only needed when Proofs don't sort results
            // This was added in v0.20
            usernameSearchResults.sortBy { Names.normalizeString(it.username) }

            return@withContext usernameSearchResults
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
        if (!hasIdentity()) {
            return Resource.success(emptyList())
        }

        return try {
            val userIdList = HashSet<String>()

            val userId = getIdentity()

            val toContactDocuments = dashPayContactRequestDao.loadToOthers(userId)
            val toContactMap = HashMap<String, DashPayContactRequest>()
            toContactDocuments.forEach {
                userIdList.add(it.toUserId)
                toContactMap[it.toUserId] = it
            }
            // Get all contact requests where toUserId == userId, the users who have added me
            val fromContactDocuments = dashPayContactRequestDao.loadFromOthers(userId)
            val fromContactMap = HashMap<String, DashPayContactRequest>()
            fromContactDocuments.forEach {
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

            val usernameSearchResults = getFromProfiles(profiles, text.lowercase(), toContactMap, fromContactMap, includeSentPending)
            usernameSearchResults.orderBy(orderBy)

            Resource.success(usernameSearchResults)
        } catch (e: Exception) {
            Resource.error(formatExceptionMessage("search contact request", e), null)
        }
    }

    fun observeContacts(text: String, orderBy: UsernameSortOrderBy, includeSentPending: Boolean = false): Flow<List<UsernameSearchResult>> {
        return blockchainIdentityDataStorage.observe()
            .filterNotNull()
            .filter { it.creationState >= BlockchainIdentityData.CreationState.DONE }
            .flatMapLatest { identityData ->
                val userId = identityData.userId!!

                // Combine the two contact request flows
                combine(
                    dashPayContactRequestDao.observeToOthers(userId),
                    dashPayContactRequestDao.observeFromOthers(userId)
                ) { toContacts, fromContacts ->
                    val userIdList = HashSet<String>()

                    val toContactMap = HashMap<String, DashPayContactRequest>()
                    toContacts.forEach {
                        userIdList.add(it.toUserId)
                        toContactMap[it.toUserId] = it
                    }

                    val fromContactMap = HashMap<String, DashPayContactRequest>()
                    fromContacts.forEach {
                        userIdList.add(it.userId)
                        if (!fromContactMap.containsKey(it.userId)) {
                            fromContactMap[it.userId] = it
                        } else {
                            val previous = fromContactMap[it.userId]!!
                            if (previous.timestamp > it.timestamp) {
                                fromContactMap[it.userId] = it
                            }
                        }
                    }

                    Triple(userIdList, toContactMap, fromContactMap)
                }.flatMapLatest { (userIdList, toContactMap, fromContactMap) ->
                    dashPayProfileDao.observeByUserIds(userIdList.toList()).map { list ->
                        val profiles = list.associateBy { it.userId }
                        val usernameSearchResults = getFromProfiles(profiles, text.lowercase(), toContactMap, fromContactMap, includeSentPending)
                        usernameSearchResults.orderBy(orderBy)
                        usernameSearchResults
                    }
                }
            }
            .distinctUntilChanged()
    }

    suspend fun updateFrequentContacts(newTx: Transaction) {
        if (hasIdentity() && blockchainIdentity.getContactForTransaction(newTx) != null) {
            updateFrequentContacts()
        }
    }

    suspend fun updateFrequentContacts() {
        if (hasIdentity()) {
            val contactRequests = searchContacts("", UsernameSortOrderBy.DATE_ADDED)
            val frequentContacts = when (contactRequests.status) {
                Status.SUCCESS -> {
                    if (!hasBlockchainIdentity) {
                        return
                    }

                    val threeMonthsAgo = Date().time - TIMESPAN

                    val results =
                        getTopContacts(contactRequests.data!!, listOf(), blockchainIdentity, threeMonthsAgo, true)

                    if (results.size < TOP_CONTACT_COUNT) {
                        val moreResults =
                            getTopContacts(contactRequests.data, results, blockchainIdentity, threeMonthsAgo, false)
                        results.addAll(moreResults)
                    }

                    results
                }

                else -> listOf<UsernameSearchResult>()
            }
            dashPayConfig.set(DashPayConfig.FREQUENT_CONTACTS, frequentContacts.map { it.getIdentity() }.toSet())
        }
    }

    private fun getTopContacts(items: List<UsernameSearchResult>,
                               ignore: List<UsernameSearchResult>,
                               blockchainIdentity: BlockchainIdentity,
                               threeMonthsAgo: Long,
                               sent: Boolean
    ): ArrayList<UsernameSearchResult> {
        val wholeWatch = Stopwatch.createStarted()
        val results = arrayListOf<UsernameSearchResult>()
        val contactScores = hashMapOf<String, Int>()
        val contactIds = arrayListOf<String>()
        // only include fully established contacts
        val contacts = items.filter { it.requestSent && it.requestReceived }

        contacts.forEach {
            val watch = Stopwatch.createStarted()
            val transactions = blockchainIdentity.getContactTransactions(it.fromContactRequest!!.userIdentifier, it.fromContactRequest!!.accountReference)
            var count = 0

            for (tx in transactions) {
                val txValue = tx.getValue(walletApplication.wallet)
                if ((sent && txValue.isNegative) || (!sent && txValue.isPositive)) {
                    if (tx.updateTime.time > threeMonthsAgo) {
                        count++
                    }
                }
            }
            contactScores[it.fromContactRequest!!.userId] = count
            contactIds.add(it.fromContactRequest!!.userId)
        }

        // determine users with top TOP_CONTACT_COUNT non-zero scores
        // if ignore has some items, then find TOP_CONTACT_COUNT - ignore.size
        contactIds.sortByDescending { contactScores[it] }
        var count = 0
        for (id in contactIds) {
            if (contactScores[id] != 0 && ignore.find { it.fromContactRequest!!.userId == id } == null) {
                results.add(items.find { it.fromContactRequest!!.userId == id }!!)
                count++
                if (count == TOP_CONTACT_COUNT - ignore.size)
                    break
            }
        }
        log.info("frequent processing: {}", wholeWatch)
        return results
    }

    private fun getFromProfiles(
        profiles: Map<String, DashPayProfile?>,
        searchText: String,
        toContactMap: Map<String, DashPayContactRequest>,
        fromContactMap: Map<String, DashPayContactRequest>,
        includeSentPending: Boolean
    ): ArrayList<UsernameSearchResult> {
        val usernameSearchResults = ArrayList<UsernameSearchResult>()

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

        return usernameSearchResults
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
        val blockchainIdentityData = blockchainIdentityDataStorage.load()
        val noIdentityCreatedOrInProgress = (blockchainIdentityData == null) || blockchainIdentityData.creationState == BlockchainIdentityData.CreationState.NONE
        val canAffordIdentityCreation = walletApplication.canAffordIdentityCreation()
        return !noIdentityCreatedOrInProgress && (canAffordIdentityCreation || !hasSentInvites)
    }


    suspend fun getNotificationCount(date: Long): Int {
        var count = 0
        if (!isUsernameRegistered()) {
            return 0
        }

        if (Constants.SUPPORTS_INVITES) {
            if (shouldShowAlert()) {
                val alert = userAlertDao.load(date)
                if (alert != null) {
                    count++
                }
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

    //
    // Step 1 is to upgrade the wallet to support authentication keys
    //
    suspend fun addWalletAuthenticationKeysAsync(seed: DeterministicSeed, keyParameter: KeyParameter) {
        withContext(Dispatchers.IO) {
            val wallet = walletApplication.wallet as WalletEx
            // this will initialize any missing key chains
            wallet.initializeCoinJoin(keyParameter, 0)

            var authenticationGroupExtension = AuthenticationGroupExtension(wallet)
            authenticationGroupExtension = wallet.addOrGetExistingExtension(authenticationGroupExtension) as AuthenticationGroupExtension
            authenticationGroupExtension.addEncryptedKeyChains(wallet.params, seed, keyParameter, keyChainTypes)
        }
    }

    //
    // Step 2 is to create the credit funding transaction
    //


    //
    // Step 3: Register the identity
    //
    suspend fun registerIdentityAsync(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter?) {
        withContext(Dispatchers.IO) {
            Context.propagate(walletApplication.wallet!!.context)
            for (i in 0 until 3) {
                try {
                    val timer = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_IDENTITY_CREATE)
                    blockchainIdentity.registerIdentity(keyParameter, true, true)
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
    // Step 3: Find the identity in the case of recovery
    //
    suspend fun recoverIdentityAsync(blockchainIdentity: BlockchainIdentity, creditFundingTransaction: AssetLockTransaction) {
        withContext(Dispatchers.IO) {
            blockchainIdentity.recoverIdentity(creditFundingTransaction)
        }
    }

    suspend fun recoverIdentityAsync(blockchainIdentity: BlockchainIdentity, publicKeyHash: ByteArray) {
        withContext(Dispatchers.IO) {
            blockchainIdentity.registrationStatus = IdentityStatus.UNKNOWN
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
            val set = blockchainIdentity.getUsernamesWithStatus(UsernameStatus.PREORDER_REGISTRATION_PENDING)
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
            blockchainIdentity.registerUsernameDomainsForUsernames(names, keyParameter, false)
            timer.logTiming()
        }
    }

    //
    // Step 5: Verify that the username was registered
    //
    @Deprecated("watch* functions should no longer be used")
    suspend fun isNameRegisteredAsync(blockchainIdentity: BlockchainIdentity) {
        withContext(Dispatchers.IO) {
            val (result, usernames) = blockchainIdentity.watchUsernames(blockchainIdentity.getUsernamesWithStatus(UsernameStatus.REGISTRATION_PENDING), 100, 1000, RetryDelayType.SLOW20)
            if (!result) {
                throw TimeoutException("the usernames: $usernames were not found to be registered in the allotted amount of time")
            }
        }
    }

    //Step 6: Create DashPay Profile
    @Deprecated("Don't need this function when creating an identity")
    suspend fun createDashPayProfile(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter) {
        withContext(Dispatchers.IO) {
            val username = blockchainIdentity.currentUsername!!
            blockchainIdentity.registerProfile(username, "", "", null, null, keyParameter)
        }
    }

    suspend fun loadBlockchainIdentityBaseData(): BlockchainIdentityBaseData? {
        return blockchainIdentityDataStorage.loadBase()
    }

    suspend fun loadBlockchainIdentityData(): BlockchainIdentityData? {
        return blockchainIdentityDataStorage.load()
    }

    fun initBlockchainIdentity(blockchainIdentityData: BlockchainIdentityData, wallet: Wallet): BlockchainIdentity {
        // previously, we would look up the asset lock transaction, but we don't need to do that
        val watch = Stopwatch.createStarted()
        log.info("loading BlockchainIdentity: starting...")
        val authExt = authenticationGroupExtension
            ?: throw IllegalStateException("AuthenticationGroupExtension is not initialised")
        val blockchainIdentity = BlockchainIdentity(platform.platform, 0, wallet, authExt)
        log.info("loading BlockchainIdentity: {}", watch)
        if (blockchainIdentityData.creationState >= BlockchainIdentityData.CreationState.IDENTITY_REGISTERED) {
            blockchainIdentity.apply {
                blockchainIdentityData.userId?.let {
                    uniqueId = Sha256Hash.wrap(Base58.decode(it))
                }
                identity = blockchainIdentityData.identity
            }
            log.info("loading identity ${blockchainIdentityData.userId} == ${if (this::blockchainIdentity.isInitialized) blockchainIdentity.uniqueIdString else null}: {}", watch)
        } else {
            log.info("loading identity: {}", watch)
            return blockchainIdentity
        }

        // TODO: needs to check against Platform to see if values exist.  Check after
        // Syncing complete
        log.info("loading identity ${blockchainIdentityData.userId} == ${if (this::blockchainIdentity.isInitialized) blockchainIdentity.uniqueIdString else null}: {}", watch)
        return blockchainIdentity.apply {
            currentUsername = blockchainIdentityData.username
            registrationStatus = blockchainIdentityData.registrationStatus ?: IdentityStatus.NOT_REGISTERED
            // usernameStatus, usernameSalts are not set if preorder hasn't started
            if (blockchainIdentityData.creationState >= BlockchainIdentityData.CreationState.PREORDER_REGISTERING) {
                var usernameStatus = UsernameInfo(
                    blockchainIdentityData.preorderSalt,
                    blockchainIdentityData.usernameStatus ?: UsernameStatus.NOT_PRESENT,
                    currentUsername,
                    blockchainIdentityData.usernameRequested,
                    blockchainIdentityData.votingPeriodStart
                )
                currentUsername ?.let {
                    usernameStatuses[it] = usernameStatus
                }
            }

            creditBalance = blockchainIdentityData.creditBalance ?: Coin.ZERO
            log.info("loading identity: {}", watch)
        }
    }

    suspend fun updateBlockchainIdentityData(blockchainIdentityData: BlockchainIdentityData, blockchainIdentity: BlockchainIdentity) {
        blockchainIdentityData.apply {
            creditFundingTxId = blockchainIdentity.assetLockTransaction?.txId
            userId = if (blockchainIdentity.registrationStatus == IdentityStatus.REGISTERED)
                blockchainIdentity.uniqueIdString
            else null
            identity = blockchainIdentity.identity
            registrationStatus = blockchainIdentity.registrationStatus
            if (blockchainIdentity.currentUsername != null) {
                username = blockchainIdentity.currentUsername
                if (blockchainIdentity.registrationStatus == IdentityStatus.REGISTERED) {
                    preorderSalt = blockchainIdentity.saltForUsername(blockchainIdentity.currentUsername!!, false)
                    usernameStatus = blockchainIdentity.statusOfUsername(blockchainIdentity.currentUsername!!)
                }
                usernameRequested = blockchainIdentity.getUsernameRequestStatus(username!!)
                votingPeriodStart = blockchainIdentity.getUsernameVotingStart(username!!)
            }
            creditBalance = blockchainIdentity.creditBalance

        }
        updateBlockchainIdentityData(blockchainIdentityData)
    }

    suspend fun resetIdentityCreationStateError(blockchainIdentityData: BlockchainIdentityData) {
        blockchainIdentityDataStorage.updateCreationState(blockchainIdentityData.id, blockchainIdentityData.creationState, null)
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
        blockchainIdentityDataStorage.updateCreationState(blockchainIdentityData.id, state, errorMessage)
        blockchainIdentityData.creationState = state
        blockchainIdentityData.creationStateErrorMessage = errorMessage
    }

    suspend fun updateBlockchainIdentityData(blockchainIdentityData: BlockchainIdentityData) {
        blockchainIdentityDataStorage.insert(blockchainIdentityData)
    }

    /**
     * Updates the dashpay.profile in the database by making a query to Platform
     *
     * @param userId
     * @return true if an update was made, false if not
     */
    suspend fun updateDashPayProfile(userId: String): Boolean {
        try {
            var profileDocument = platform.profiles.get(userId)
            if (profileDocument == null) {
                val identity = platform.identities.get(userId)
                if (identity != null) {
                    profileDocument =
                        platform.profiles.createProfileDocument("", "", "", null, null, identity)
                } else {
                    // there is no existing identity, so do nothing
                    return false
                }
            }
            val nameDocuments = platform.names.getByOwnerId(userId)

            if (nameDocuments.isNotEmpty()) {
                val username = DomainDocument(nameDocuments[0]).label

                val profile = DashPayProfile.fromDocument(profileDocument, username)
                dashPayProfileDao.insert(profile)
                return true
            }
            return false
        } catch (e: Exception) {
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
        val blockchainIdentityData = blockchainIdentityDataStorage.load()
        if (blockchainIdentityData != null && blockchainIdentityData.creationState == BlockchainIdentityData.CreationState.DONE) {
            blockchainIdentityData.creationState = BlockchainIdentityData.CreationState.DONE_AND_DISMISS
            blockchainIdentityDataStorage.insert(blockchainIdentityData)
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
                getUsername()?.let { username ->
                    // recovery will only get the information and place it in the database
                    val profile = blockchainIdentity.getProfile()


                    // blockchainIdentity doesn't yet keep track of the profile, so we will load it
                    // into the database directly
                    val dashPayProfile = if (profile != null)
                        DashPayProfile.fromDocument(profile, username)
                    else
                        DashPayProfile(blockchainIdentity.uniqueIdString, username)
                    updateDashPayProfile(dashPayProfile)
                }
            }
        }
    }

    fun getNextContactAddress(userId: String, accountReference: Int): Address? {
        return try {
            blockchainIdentity.getContactNextPaymentAddress(Identifier.from(userId), accountReference)
        } catch (e: NullPointerException) {
            log.error("Failed to get contact address due to null key chain", e)
            null
        }
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
    fun getIdentityForName(nameDocument: DomainDocument): Identifier {
        // look at the unique identity first, followed by the alias
        return nameDocument.dashUniqueIdentityId ?: nameDocument.dashAliasIdentityId!!
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
        blockchainIdentityDataStorage.clear()
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
        val key = decryptedChain.getKey(index)
        Preconditions.checkState(key.path.last().isHardened)
        return key
    }

    fun getIdentityFromPublicKeyId(): Identity? {
        return try {
            getWalletEncryptionKey()?.let {
                val firstIdentityKey = getBlockchainIdentityKey(0, it) ?: return null
                platform.stateRepository.fetchIdentityFromPubKeyHash(firstIdentityKey.pubKeyHash)
            }
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
            : AssetLockTransaction {
        // dashj Context does not work with coroutines well, so we need to call Context.propogate
        // in each suspend method that uses the dashj Context
        Context.propagate(walletApplication.wallet!!.context)
        val balance = walletApplication.wallet!!.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE)
        val fee = DASH_PAY_FEE_CONTESTED
        val emptyWallet = balance == fee && balance <= (fee + Transaction.MIN_NONDUST_OUTPUT)
        val cftx = blockchainIdentity.createInviteFundingTransaction(
            Constants.DASH_PAY_FEE,
            keyParameter,
            useCoinJoin = coinJoinConfig.getMode() != CoinJoinMode.NONE,
            returnChange = true,
            emptyWallet = emptyWallet
        )
        val invitation = Invitation(cftx.identityId.toStringBase58(), cftx.txId,
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

    private suspend fun sendTransaction(cftx: AssetLockTransaction): Boolean {
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
            val cfTx = AssetLockTransaction(Constants.NETWORK_PARAMETERS, tx)
            val identity = platform.identities.get(cfTx.identityId.toStringBase58())
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
            blockchainIdentityDataStorage.clear()
        }
    }

    fun handleSentAssetLockTransaction(cftx: AssetLockTransaction, blockTimestamp: Long) {
        val extension = authenticationGroupExtension

        if (this::blockchainIdentity.isInitialized && extension != null) {
            GlobalScope.launch(Dispatchers.IO) {
                // Context.getOrCreate(platform.params)
                val isInvite = extension.invitationFundingKeyChain.findKeyFromPubHash(cftx.assetLockPublicKeyId.bytes) != null
                val isTopup = extension.identityTopupKeyChain.findKeyFromPubHash(cftx.assetLockPublicKeyId.bytes) != null
                val isIdentity = extension.identityFundingKeyChain.findKeyFromPubHash(cftx.assetLockPublicKeyId.bytes) != null
                val identityId = cftx.identityId.toStringBase58()
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

    suspend fun getIdentityBalance(): CreditBalanceInfo? {
        return withContext(Dispatchers.IO) {
            try {
                CreditBalanceInfo(platform.client.getIdentityBalance(blockchainIdentity.uniqueIdentifier))
            } catch (e: Exception) {
                log.error("Failed to get identity balance", e)
                null
            }
        }
    }

    suspend fun getIdentityBalance(identifier: Identifier): CreditBalanceInfo {
        return withContext(Dispatchers.IO) {
            CreditBalanceInfo(platform.client.getIdentityBalance(identifier))
        }
    }

    suspend fun addInviteUserAlert() {
        // this alert will be shown or not based on the current balance and will be
        // managed by NotificationsLiveData
        val userAlert = UserAlert(UserAlert.INVITATION_NOTIFICATION_TEXT, UserAlert.INVITATION_NOTIFICATION_ICON)
        userAlertDao.insert(userAlert)
    }
}

fun ArrayList<UsernameSearchResult>.orderBy(orderBy: UsernameSortOrderBy) {
    when (orderBy) {
        UsernameSortOrderBy.DISPLAY_NAME -> this.sortBy {
            if (it.dashPayProfile.displayName.isNotEmpty())
                it.dashPayProfile.displayName.lowercase()
            else it.dashPayProfile.username.lowercase()
        }
        UsernameSortOrderBy.USERNAME -> this.sortBy {
            it.dashPayProfile.username.lowercase()
        }
        UsernameSortOrderBy.DATE_ADDED -> this.sortByDescending {
            it.date
        }
        else -> { /* ignore */ }
        //TODO: sort by last activity or date added
    }
}
