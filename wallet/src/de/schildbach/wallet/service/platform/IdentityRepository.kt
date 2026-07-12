package de.schildbach.wallet.service.platform

import com.google.common.base.Stopwatch
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.data.orderBy
import de.schildbach.wallet.database.AppDatabase
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.service.DashSystemService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.PlatformRepo.Companion.TIMESPAN
import de.schildbach.wallet.ui.dashpay.PlatformRepo.Companion.TOP_CONTACT_COUNT
import de.schildbach.wallet.ui.dashpay.UserAlert
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.Companion.UPGRADE_IDENTITY_REQUIRED
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Address
import org.bitcoinj.core.Base58
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.WalletDataProvider
import org.dashj.platform.dapiclient.MaxRetriesReachedException
import org.dashj.platform.dapiclient.NoAvailableAddressesForRetryException
import org.dashj.platform.dapiclient.model.GrpcExceptionInfo
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dashpay.IdentityStatus
import org.dashj.platform.dashpay.UsernameInfo
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.dashj.platform.dashpay.UsernameStatus
import org.dashj.platform.dashpay.callback.WalletSignerCallback
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.sdk.platform.DomainDocument
import org.dashj.platform.sdk.platform.Names
import org.slf4j.LoggerFactory
import java.util.ArrayList
import java.util.Date
import java.util.HashMap
import java.util.HashSet
import java.util.NoSuchElementException
import javax.inject.Inject

interface IdentityRepository {
    val blockchainIdentity: BlockchainIdentity?
    val blockchainIdentityFlow: StateFlow<BlockchainIdentity?>
    //suspend fun addMissingKeys(keyParameter: KeyParameter?): Boolean
    suspend fun getIdentityBalance(): CreditBalanceInfo?
    val hasBlockchainIdentity: Boolean
    suspend fun hasIdentity(): Boolean
    suspend fun hasUsername(): Boolean
    suspend fun getUsername(): String?
    suspend fun loadBlockchainIdentityBaseData(): BlockchainIdentityBaseData
    suspend fun loadBlockchainIdentityData(): BlockchainIdentityData?
    suspend fun doneAndDismiss()
    suspend fun init()
    fun initBlockchainIdentity(blockchainIdentityData: BlockchainIdentityData, wallet: Wallet): BlockchainIdentity
    suspend fun updateBlockchainIdentityData(blockchainIdentityData: BlockchainIdentityData, blockchainIdentity: BlockchainIdentity)
    suspend fun updateBlockchainIdentityData(blockchainIdentityData: BlockchainIdentityData)
    suspend fun updateBlockchainIdentityData()
    suspend fun updateIdentityCreationState(blockchainIdentityData: BlockchainIdentityData,
                                             state: IdentityCreationState,
                                             exception: Throwable? = null)
    suspend fun upgradeIdentity(keyParameter: KeyParameter?): Boolean
    fun getIdentityFromPublicKeyId(): Identity?
    suspend fun clearBlockchainIdentityData()
    suspend fun resetIdentityCreationStateError(blockchainIdentityData: BlockchainIdentityData)
    suspend fun searchUsernames(text: String, onlyExactUsername: Boolean = false, limit: Int = -1): List<UsernameSearchResult>
    suspend fun getUser(username: String): List<UsernameSearchResult>
    suspend fun searchContacts(text: String, orderBy: UsernameSortOrderBy, includeSentPending: Boolean = false): Resource<List<UsernameSearchResult>>
    fun observeContacts(text: String, orderBy: UsernameSortOrderBy, includeSentPending: Boolean = false): Flow<List<UsernameSearchResult>>
    suspend fun updateFrequentContacts()
    suspend fun updateFrequentContacts(newTx: Transaction)
    suspend fun updateDashPayProfile(dashPayProfile: DashPayProfile)
    suspend fun updateDashPayContactRequest(dashPayContactRequest: DashPayContactRequest)
    suspend fun recoverDashPayProfile(blockchainIdentity: BlockchainIdentity)
    suspend fun getNotificationCount(date: Long): Int
    suspend fun getLocalUserProfile(): DashPayProfile?
    suspend fun addInviteUserAlert()
    suspend fun shouldShowAlert(): Boolean
    fun getNextContactAddress(userId: String, accountReference: Int): Address?
    suspend fun clearDatabase(includeInvitations: Boolean)
    fun updateIdentity()
}

class IdentityRepositoryImpl @Inject constructor(
    private val walletApplication: WalletApplication,
    val appDatabase: AppDatabase,
    private val blockchainIdentityDataStorage: BlockchainIdentityConfig,
    private val walletDataProvider: WalletDataProvider,
    private val platformRepo: PlatformRepo,
    private val dashPayConfig: DashPayConfig,
    private val dashSystemService: DashSystemService,
) : IdentityRepository {
    companion object {
        private val log = LoggerFactory.getLogger(IdentityRepository::class.java)
    }

    val authenticationGroupExtension: AuthenticationGroupExtension?
        get() = walletApplication.authenticationGroupExtension

    private val _blockchainIdentityFlow = MutableStateFlow<BlockchainIdentity?>(null)
    override val blockchainIdentityFlow: StateFlow<BlockchainIdentity?> = _blockchainIdentityFlow.asStateFlow()

    private var _blockchainIdentity: BlockchainIdentity?
        get() = _blockchainIdentityFlow.value
        set(value) { _blockchainIdentityFlow.value = value }

    override val blockchainIdentity: BlockchainIdentity?
        get() = _blockchainIdentity

    override val hasBlockchainIdentity: Boolean
        get() = _blockchainIdentity != null
    
    val platform = platformRepo.platform
    private val dashPayContactRequestDao = appDatabase.dashPayContactRequestDao()
    private val dashPayProfileDao = appDatabase.dashPayProfileDao()
    private val invitationsDao = appDatabase.invitationsDao()
    private val userAlertDao = appDatabase.userAlertDao()
    private var hasCheckedIdentityForUpgrade = false

    override suspend fun clearBlockchainIdentityData() {
        blockchainIdentityDataStorage.clear()
        _blockchainIdentity = null
        hasCheckedIdentityForUpgrade = false
    }

    override suspend fun doneAndDismiss() {
        val blockchainIdentityData = blockchainIdentityDataStorage.load()
        if (blockchainIdentityData != null && blockchainIdentityData.creationState == IdentityCreationState.DONE) {
            blockchainIdentityData.creationState = IdentityCreationState.DONE_AND_DISMISS
            blockchainIdentityDataStorage.insert(blockchainIdentityData)
        }
    }

    suspend fun getActiveUsername(): String? {
        return if (_blockchainIdentity != null) {
            _blockchainIdentity!!.currentUsername
        } else {
            val username = blockchainIdentityDataStorage.get(BlockchainIdentityConfig.USERNAME)
            val creationState = blockchainIdentityDataStorage.get(BlockchainIdentityConfig.CREATION_STATE)
            if (creationState == IdentityCreationState.VOTING.name) {
                val usernameSecondary = blockchainIdentityDataStorage.get(BlockchainIdentityConfig.USERNAME_SECONDARY)
                if (usernameSecondary != null &&
                    blockchainIdentityDataStorage.get(BlockchainIdentityConfig.USERNAME_SECONDARY_REGISTRATION_STATUS) == UsernameStatus.CONFIRMED.name) {
                    usernameSecondary
                } else {
                    username
                }
            } else {
                username
            }
        }
    }

    override suspend fun hasIdentity(): Boolean = _blockchainIdentity != null ||
            blockchainIdentityDataStorage.get(BlockchainIdentityConfig.IDENTITY_ID) != null

    override suspend fun hasUsername(): Boolean = (_blockchainIdentity?.currentUsername != null) ||
            blockchainIdentityDataStorage.get(BlockchainIdentityConfig.USERNAME) != null

    override suspend fun getUsername(): String? {
        return if (_blockchainIdentity != null) {
            _blockchainIdentity!!.currentUsername
        } else {
            blockchainIdentityDataStorage.get(BlockchainIdentityConfig.USERNAME)
        }
    }

    @Throws(IllegalStateException::class)
    suspend fun getIdentity(): String {
        return if (_blockchainIdentity != null) {
            _blockchainIdentity!!.uniqueIdString
        } else {
            blockchainIdentityDataStorage.get(BlockchainIdentityConfig.IDENTITY_ID)
                ?: throw IllegalStateException("IdentityId not found")
        }
    }

    override suspend fun getIdentityBalance(): CreditBalanceInfo? = withContext(Dispatchers.IO) {
        try {
            CreditBalanceInfo(platformRepo.platform.client.getIdentityBalance(_blockchainIdentity?.uniqueIdentifier ?: return@withContext null))
        } catch (e: Exception) {
            log.error("Failed to get identity balance", e)
            null
        }
    }

    override suspend fun init() {
        val data = blockchainIdentityDataStorage.load()
        if (data != null) {
            _blockchainIdentity = initBlockchainIdentity(data, walletDataProvider.wallet!!)
            // initializeStateRepository()
        } else {
            _blockchainIdentity = null
            hasCheckedIdentityForUpgrade = false
        }
    }

    override fun initBlockchainIdentity(blockchainIdentityData: BlockchainIdentityData, wallet: Wallet): BlockchainIdentity {
        // previously, we would look up the asset lock transaction, but we don't need to do that
        val watch = Stopwatch.createStarted()
        log.info("loading BlockchainIdentity: starting...")
        val authExt = authenticationGroupExtension
            ?: throw IllegalStateException("AuthenticationGroupExtension is not initialised")
        val blockchainIdentity = BlockchainIdentity(platform.platform, 0, wallet, authExt)
        log.info("loading BlockchainIdentity: {}", watch)
        if (blockchainIdentityData.creationState >= IdentityCreationState.IDENTITY_REGISTERED) {
            blockchainIdentity.apply {
                blockchainIdentityData.userId?.let {
                    uniqueId = Sha256Hash.wrap(Base58.decode(it))
                }
                identity = blockchainIdentityData.identity
            }
            log.info("loading identity ${blockchainIdentityData.userId} == ${_blockchainIdentity?.uniqueIdString}: {}", watch)
        } else {
            log.info("loading identity: {}", watch)
            return blockchainIdentity
        }

        // TODO: needs to check against Platform to see if values exist.  Check after
        // Syncing complete
        log.info("loading identity ${blockchainIdentityData.userId} == ${_blockchainIdentity?.uniqueIdString}: {}", watch)
        return blockchainIdentity.apply {
            primaryUsername = blockchainIdentityData.username
            secondaryUsername = blockchainIdentityData.usernameSecondary
            blockchainIdentityData.username?.let {
                addUsername(it)
            }
            blockchainIdentityData.usernameSecondary?.let {
                addUsername(it)
            }

            registrationStatus = blockchainIdentityData.registrationStatus ?: IdentityStatus.NOT_REGISTERED
            // usernameStatus, usernameSalts are not set if preorder hasn't started
            if (blockchainIdentityData.creationState >= IdentityCreationState.PREORDER_REGISTERING) {
                val usernameStatus = UsernameInfo(
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

            if (blockchainIdentityData.creationState >= IdentityCreationState.PREORDER_SECONDARY_REGISTERING) {
                val usernameStatus = UsernameInfo(
                    blockchainIdentityData.preorderSaltSecondary,
                    blockchainIdentityData.usernameSecondaryStatus ?: UsernameStatus.NOT_PRESENT,
                    blockchainIdentityData.usernameSecondary,
                    null,
                    null
                )
                secondaryUsername ?.let {
                    usernameStatuses[it] = usernameStatus
                }
            }

            creditBalance = blockchainIdentityData.creditBalance ?: Coin.ZERO
            log.info("loading identity: {}", watch)
        }
    }

    override suspend fun loadBlockchainIdentityBaseData(): BlockchainIdentityBaseData {
        return blockchainIdentityDataStorage.loadBase()
    }

    override suspend fun loadBlockchainIdentityData(): BlockchainIdentityData? {
        return blockchainIdentityDataStorage.load()
    }

    override suspend fun resetIdentityCreationStateError(blockchainIdentityData: BlockchainIdentityData) {
        blockchainIdentityDataStorage.updateCreationState(blockchainIdentityData.creationState, null)
        blockchainIdentityData.creationStateErrorMessage = null
    }

    override suspend fun updateIdentityCreationState(blockchainIdentityData: BlockchainIdentityData,
                                                     state: IdentityCreationState,
                                                     exception: Throwable?
    ) {
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
        blockchainIdentityDataStorage.updateCreationState(state, errorMessage)
        blockchainIdentityData.creationState = state
        blockchainIdentityData.creationStateErrorMessage = errorMessage
    }

    override suspend fun updateBlockchainIdentityData(blockchainIdentityData: BlockchainIdentityData) {
        blockchainIdentityDataStorage.insert(blockchainIdentityData)
    }

    override suspend fun updateBlockchainIdentityData(blockchainIdentityData: BlockchainIdentityData, blockchainIdentity: BlockchainIdentity) {
        blockchainIdentityData.apply {
            creditFundingTxId = blockchainIdentity.assetLockTransaction?.txId
            userId = if (blockchainIdentity.registrationStatus == IdentityStatus.REGISTERED)
                blockchainIdentity.uniqueIdString
            else null
            identity = blockchainIdentity.identity
            registrationStatus = blockchainIdentity.registrationStatus
            if (blockchainIdentity.currentUsername != null) {
                username = blockchainIdentity.primaryUsername
                if (blockchainIdentity.registrationStatus == IdentityStatus.REGISTERED) {
                    preorderSalt = blockchainIdentity.saltForUsername(blockchainIdentity.currentUsername!!, false)
                    usernameStatus = blockchainIdentity.statusOfUsername(blockchainIdentity.currentUsername!!)
                }
                val requestStatus = blockchainIdentity.getUsernameRequestStatus(username!!)
                if (requestStatus != UsernameRequestStatus.NONE) {
                    usernameRequested = requestStatus
                }
                val votingStart = blockchainIdentity.getUsernameVotingStart(username!!)
                if (votingStart != -1L) {
                    votingPeriodStart = votingStart
                }

                log.info("creation: blockchainIdentity.secondaryUsername = {}", blockchainIdentity.secondaryUsername)
                blockchainIdentity.secondaryUsername?.let { name ->
                    usernameSecondary = name
                    usernameSecondaryStatus = blockchainIdentity.statusOfUsername(name)
                    log.info("creation: secondary username: {}, usernameSecondaryStatus = {}", name, usernameSecondaryStatus)
                    preorderSaltSecondary = blockchainIdentity.saltForUsername(name, false)
                }
            }
            creditBalance = blockchainIdentity.creditBalance

        }
        updateBlockchainIdentityData(blockchainIdentityData)
    }

    override suspend fun updateBlockchainIdentityData() {
        val identity = _blockchainIdentity ?: return
        blockchainIdentityDataStorage.load()?.let {
            updateBlockchainIdentityData(it, identity)
        }
    }

    /** should be called after loading identity from storage and updating from platform */
    override suspend fun upgradeIdentity(keyParameter: KeyParameter?): Boolean {
        // the only upgrade is to add missing keys
        // always run the upgrade action items the first time or if there is a failure the last time
        // those actions were run
        return if (!hasCheckedIdentityForUpgrade || dashPayConfig.get(UPGRADE_IDENTITY_REQUIRED) == true) {
            hasCheckedIdentityForUpgrade = true
            addMissingKeys(keyParameter)
        } else {
            false
        }
    }

    /** assumes that the blockchainIdentity has been synced against platform */
    private suspend fun addMissingKeys(keyParameter: KeyParameter?): Boolean {
        val identity = _blockchainIdentity
        if (hasBlockchainIdentity && identity != null) {
            walletDataProvider.wallet?.let { wallet ->
                if (!identity.hasTransferKey() || !identity.hasEncryptionKey()) {
                    dashPayConfig.set(UPGRADE_IDENTITY_REQUIRED, true)
                    try {
                        log.info(
                            "one or more identity keys are missing [transfer=${
                                identity.hasTransferKey()
                            }, encryption=${
                                identity.hasEncryptionKey()
                            }]"
                        )
                        val enough = getIdentityBalance()
                        if (enough != null && !enough.isBalanceEmpty()) {
                            val signer = WalletSignerCallback(wallet, keyParameter)
                            identity.addMissingKeys(signer)
                            updateBlockchainIdentityData()
                            dashPayConfig.set(UPGRADE_IDENTITY_REQUIRED, false)
                            return true
                        }
                    } catch (E: Exception) {
                        log.error("failure to add missing keys", E)
                        dashPayConfig.set(UPGRADE_IDENTITY_REQUIRED, true)
                    }
                } else {
                    dashPayConfig.set(UPGRADE_IDENTITY_REQUIRED, false)
                }
            }
        }
        return false
    }

    /**
     * gets all the name documents for usernames starting with text
     *
     * @param text The beginning of a username to search for
     * @return
     */

    @Throws(Exception::class)
    override suspend fun searchUsernames(text: String, onlyExactUsername: Boolean, limit: Int): List<UsernameSearchResult> {
        return withContext(Dispatchers.IO) {
            val identity = _blockchainIdentity ?: throw IllegalStateException("BlockchainIdentity not initialized")
            val userIdString = identity.uniqueIdString
            val userId = identity.uniqueIdentifier

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
            // determine if multiple names belong to the same identity. If so, don't show any non-contested names
            val identifierDocumentMap = hashMapOf<Identifier, ArrayList<DomainDocument>>()
            nameDocuments.forEach { document ->
                val domainDocument = DomainDocument(document)
                val identifier = platformRepo.getIdentityForName(domainDocument)
                if (identifierDocumentMap.contains(identifier)) {
                    identifierDocumentMap[identifier]?.add(domainDocument)
                } else {
                    val newList = arrayListOf(domainDocument)
                    identifierDocumentMap[identifier] = newList
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
                    result.add(platformRepo.getIdentityForName(exactNameDoc))
                }
                result
            } else {
                identifierDocumentMap.keys
            }.toSet().toList()

            val profileById: Map<Identifier, Document> = if (userIds.isNotEmpty()) {
                val profileDocuments = platform.profiles.getList(userIds)
                profileDocuments.associateBy({ it.ownerId }, { it })
            } else {
                log.warn("search usernames: userIdList is empty, though nameDocuments has ${nameDocuments.size} items")
                mapOf()
            }

            // remove non-contested names if there are contested names for the same identity
            val filteredNameDocuments = arrayListOf<DomainDocument>()
            identifierDocumentMap.forEach { (identifier, documents) ->
                if (documents.size == 1) {
                    filteredNameDocuments.addAll(documents)
                } else {
                    val hasContestedNames = documents.any { Names.isUsernameContestable(it.normalizedLabel) }
                    documents.forEach { document ->
                        if (Names.isUsernameContestable(document.normalizedLabel)) {
                            filteredNameDocuments.add(document)
                        } else if (!hasContestedNames) {
                            filteredNameDocuments.add(document)
                        }
                    }
                }
            }

            val toContactDocuments = dashPayContactRequestDao.loadToOthers(userIdString)

            // Get all contact requests where toUserId == userId
            val fromContactDocuments = dashPayContactRequestDao.loadFromOthers(userIdString)

            val usernameSearchResults = ArrayList<UsernameSearchResult>()

            for (nameDoc in filteredNameDocuments) {
                if (nameDoc.dashAliasIdentityId != null) {
                    continue // skip aliases
                }

                //Remove own user document from result
                val nameDocIdentityId = platformRepo.getIdentityForName(nameDoc)
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

                usernameSearchResults.add(
                    UsernameSearchResult(username,
                    dashPayProfile, toContact, fromContact)
                )
            }

            // TODO: this is only needed when Proofs don't sort results
            // This was added in v0.20
            usernameSearchResults.sortBy { Names.normalizeString(it.username) }

            return@withContext usernameSearchResults
        }
    }

    @Throws(Exception::class)
    override suspend fun getUser(username: String): List<UsernameSearchResult> {
        return try {
            searchUsernames(username, true)
        } catch (e: Exception) {
            platformRepo.formatExceptionMessage("get single user failure", e)
            throw e
        }
    }


    /**
     * search the contacts
     *
     * @param text the text to find in usernames and displayNames.  if blank, all contacts are returned
     * @param orderBy the field that is used to sort the list of matching entries in ascending order
     * @return
     */
    override suspend fun searchContacts(text: String, orderBy: UsernameSortOrderBy, includeSentPending: Boolean): Resource<List<UsernameSearchResult>> {
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

            val usernameSearchResults = platformRepo.getFromProfiles(profiles, text.lowercase(), toContactMap, fromContactMap, includeSentPending)
            usernameSearchResults.orderBy(orderBy)

            Resource.success(usernameSearchResults)
        } catch (e: Exception) {
            Resource.error(platformRepo.formatExceptionMessage("search contact request", e), null)
        }
    }

    override fun observeContacts(text: String, orderBy: UsernameSortOrderBy, includeSentPending: Boolean): Flow<List<UsernameSearchResult>> {
        return blockchainIdentityDataStorage.observe()
            .filterNotNull()
            .filter { it.hasUsername && it.userId != null }
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
                        val usernameSearchResults = platformRepo.getFromProfiles(profiles, text.lowercase(), toContactMap, fromContactMap, includeSentPending)
                        usernameSearchResults.orderBy(orderBy)
                        usernameSearchResults
                    }
                }
            }
            .distinctUntilChanged()
    }

    override suspend fun updateFrequentContacts(newTx: Transaction) {
        // since we are accessing the blockchainIdentity object, we better check that it is valid
        // previously, we were using hasUsername() which can return true during a wallet reset
        if (hasBlockchainIdentity && _blockchainIdentity?.getContactForTransaction(newTx) != null) {
            updateFrequentContacts()
        }
    }

    override suspend fun updateFrequentContacts() {
        if (hasIdentity()) {
            val contactRequests = searchContacts("", UsernameSortOrderBy.DATE_ADDED)
            val frequentContacts = when (contactRequests.status) {
                Status.SUCCESS -> {
                    val identity = _blockchainIdentity
                    if (!hasBlockchainIdentity || identity == null) {
                        return
                    }

                    val threeMonthsAgo = Date().time - TIMESPAN

                    val results =
                        getTopContacts(contactRequests.data!!, listOf(), identity, threeMonthsAgo, true)

                    if (results.size < TOP_CONTACT_COUNT) {
                        val moreResults =
                            getTopContacts(contactRequests.data, results, identity, threeMonthsAgo, false)
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

    private fun isUsernameRegistered(): Boolean {
        return _blockchainIdentity != null
    }

    override suspend fun getNotificationCount(date: Long): Int {
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

    /**
     * returns true if:
     *  1. Invites have been not been sent previously
     *  2. Identity Creation is not in progress
     *  3. The balance is high enough
     */

    override suspend fun shouldShowAlert(): Boolean {
        val hasSentInvites = invitationsDao.count() > 0
        val blockchainIdentityData = blockchainIdentityDataStorage.load()
        val noIdentityCreatedOrInProgress = (blockchainIdentityData == null) || blockchainIdentityData.creationState == IdentityCreationState.NONE
        val canAffordIdentityCreation = walletApplication.canAffordIdentityCreation()
        return !noIdentityCreatedOrInProgress && (canAffordIdentityCreation || hasSentInvites)
    }

    override fun getNextContactAddress(userId: String, accountReference: Int): Address? {
        return try {
            _blockchainIdentity?.getContactNextPaymentAddress(Identifier.from(userId), accountReference)
        } catch (e: NullPointerException) {
            log.error("Failed to get contact address due to null key chain", e)
            null
        }
    }

    /**
    This is used by java code, outside of coroutines

    This should not be a suspended method.
     */
    override suspend fun clearDatabase(includeInvitations: Boolean) {
        log.info("clearing databases (includeInvitations = $includeInvitations)")
        dashPayProfileDao.clear()
        dashPayContactRequestDao.clear()
        userAlertDao.clear()
        clearBlockchainIdentityData()
        if (includeInvitations) {
            invitationsDao.clear()
        }
    }

    override fun updateIdentity() {
        _blockchainIdentity?.updateIdentity()
    }

    // current unused
    private suspend fun getContactRequestReport(): String {
        val report = StringBuilder()
        val profiles = dashPayProfileDao.loadAll()
        val profilesById = profiles.associateBy({ it.userId }, { it })
        report.append("Contact Requests (Sent) -----------------\n")
        dashPayContactRequestDao.loadToOthers(_blockchainIdentity?.uniqueIdString ?: return "").forEach {
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
        dashPayContactRequestDao.loadFromOthers(_blockchainIdentity?.uniqueIdString ?: return "").forEach {
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

    override suspend fun addInviteUserAlert() {
        // this alert will be shown or not based on the current balance and will be
        // managed by NotificationsLiveData
        val userAlert = UserAlert(UserAlert.INVITATION_NOTIFICATION_TEXT, UserAlert.INVITATION_NOTIFICATION_ICON)
        userAlertDao.insert(userAlert)
    }

    //Step 6: Recover the DashPay Profile
    override suspend fun recoverDashPayProfile(blockchainIdentity: BlockchainIdentity) {
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

    override suspend fun updateDashPayProfile(dashPayProfile: DashPayProfile) {
        dashPayProfileDao.insert(dashPayProfile)
    }

    override suspend fun updateDashPayContactRequest(dashPayContactRequest: DashPayContactRequest) {
        dashPayContactRequestDao.insert(dashPayContactRequest)
    }

    override suspend fun getLocalUserProfile(): DashPayProfile? {
        val blockchainIdentityBaseData = loadBlockchainIdentityBaseData()
        val userId = blockchainIdentityBaseData.userId ?: return null
        return dashPayProfileDao.loadByUserId(userId)
    }

    override fun getIdentityFromPublicKeyId(): Identity? {
        return try {
            platformRepo.getWalletEncryptionKey()?.let {
                val firstIdentityKey = platformRepo.getBlockchainIdentityKey(0, it) ?: return null
                platform.stateRepository.fetchIdentityFromPubKeyHash(firstIdentityKey.pubKeyHash)
            }
        } catch (e: MaxRetriesReachedException) {
            null
        } catch (e: NoAvailableAddressesForRetryException) {
            null
        }
    }
}