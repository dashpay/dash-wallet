/*
 * Copyright 2023 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.username

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.ImportedMasternodeKeyDao
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.dao.UsernameVoteDao
import de.schildbach.wallet.database.entity.ImportedMasternodeKey
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.database.entity.UsernameVote
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.work.BroadcastUsernameVotesOperation
import de.schildbach.wallet.ui.username.adapters.UsernameRequestGroupView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Address
import org.bitcoinj.core.AddressFormatException
import org.bitcoinj.core.Base58
import org.bitcoinj.core.DumpedPrivateKey
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.KeyId
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Utils
import org.bitcoinj.evolution.SimplifiedMasternodeListManager
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bitcoinj.wallet.authentication.AuthenticationKeyStatus
import org.bitcoinj.wallet.authentication.AuthenticationKeyUsage
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.voting.ResourceVoteChoice
import org.dashj.platform.sdk.platform.Names
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import kotlin.math.min
import kotlin.random.Random

enum class InvalidKeyType {
    WRONG_NETWORK,
    ADDRESS,
    PUBLIC_KEY_HEX,
    PRIVATE_KEY_HEX,
    NOT_INVALID,
    CHARACTER,
    SHORT,
    CHECKSUM,
    UNKNOWN
}

data class UsernameRequestsUIState(
    val filteredUsernameRequests: List<UsernameRequestGroupView> = listOf(),
    val usernameVotes: Map<String, List<UsernameVote>> = mapOf(),
    val showFirstTimeInfo: Boolean = false,
    val voteSubmitted: Boolean = false,
    val voteCancelled: Boolean = false
)

data class FiltersUIState(
    val sortByOption: UsernameSortOption = UsernameSortOption.defaultOption,
    val typeOption: UsernameTypeOption = UsernameTypeOption.defaultOption,
    val onlyDuplicates: Boolean = true,
    val onlyLinks: Boolean = false
) {
    fun isDefault(): Boolean {
        // typeOption isn't included because we show it in the header
        return sortByOption == UsernameSortOption.DateDescending &&
            !onlyDuplicates &&
            !onlyLinks
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UsernameRequestsViewModel @Inject constructor(
    private val dashPayConfig: DashPayConfig,
    private val usernameRequestDao: UsernameRequestDao,
    private val usernameVoteDao: UsernameVoteDao,
    private val importedMasternodeKeyDao: ImportedMasternodeKeyDao,
    private val platformSyncService: PlatformSyncService,
    private val walletDataProvider: WalletDataProvider,
    private val walletApplication: WalletApplication,
    private val analytics: AnalyticsService
): ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(UsernameRequestsViewModel::class.java)
    }

    private val _currentWorkId = MutableStateFlow("")
    val currentWorkId: StateFlow<String>
        get() = _currentWorkId
    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    private val _uiState = MutableStateFlow(UsernameRequestsUIState())
    val uiState: StateFlow<UsernameRequestsUIState> = _uiState.asStateFlow()

    private val _filterState = MutableStateFlow(FiltersUIState())
    val filterState: StateFlow<FiltersUIState> = _filterState.asStateFlow()

    private val _selectedUsernameRequestId = MutableStateFlow<String?>(null)
    val selectedUsernameRequestId: Flow<UsernameRequest> = _selectedUsernameRequestId
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { id ->
            usernameRequestDao.observeRequest(id)
                .filterNotNull()
                .distinctUntilChanged()
        }

    private val masternodeListManager: SimplifiedMasternodeListManager
        get() = walletDataProvider.wallet!!.context.masternodeListManager

    private val _addedKeys = MutableStateFlow(listOf<ECKey>())
    private val _masternodes =  MutableStateFlow<List<ImportedMasternodeKey>>(listOf())
    val masternodes: StateFlow<List<ImportedMasternodeKey>>
        get() = _masternodes

    private val currentImportedKeys = listOf<ImportedMasternodeKey>()
    private val currentMasternodeKeyUsage = listOf<AuthenticationKeyUsage>()

    val keysAmount: Int
        get() = masternodes.value.size

    init {
        dashPayConfig.observe(DashPayConfig.VOTING_INFO_SHOWN)
            .onEach { isShown -> _uiState.update { it.copy(showFirstTimeInfo = isShown != true) } }
            .launchIn(viewModelScope)


        _filterState.flatMapLatest {
            observeUsernames()
                .map { duplicates ->
                    duplicates.groupBy { it.normalizedLabel }
                        .map { (normalizedUsername, list) ->
                            val sortedList = list.sortAndFilter()
                            val votes = usernameVoteDao.getVotes(normalizedUsername)
                            // display usernames in lower case without 0 and 1 if possible
                            val prettyUsername = when {
                                list.size == 1 -> list[0].username.lowercase()
                                else -> {
                                    list.find {
                                        !(it.username.contains('0') || it.username.contains('1'))
                                    }?.username?.lowercase() ?: list[0].username
                                }
                            }
                            val votingEndDate = if (sortedList.isNotEmpty()) {
                                sortedList.minOf { request -> request.createdAt } + UsernameRequest.VOTING_PERIOD_MILLIS
                            } else {
                                -1L
                            }
                            UsernameRequestGroupView(prettyUsername, sortedList, isExpanded = isExpanded(prettyUsername), votes, votingEndDate)
                        }.filterNot { it.requests.isEmpty() && it.votingEndDate < System.currentTimeMillis() }
                }.map { groupViews -> // Sort the list emitted by the Flow
                    when (_filterState.value.sortByOption) {
                        UsernameSortOption.VotingPeriodSoonest -> groupViews.sortedBy { group ->
                            group.localDate
                        }
                        UsernameSortOption.VotingPeriodLatest -> groupViews.sortedByDescending { group ->
                            group.localDate
                        }
                        UsernameSortOption.VotesAscending -> groupViews.sortedBy { it.requests.sumOf { request -> request.votes } }
                        UsernameSortOption.VotesDescending -> groupViews.sortedByDescending { it.requests.sumOf { request -> request.votes } }
                        UsernameSortOption.DateAscending -> groupViews.sortedBy { group ->
                            group.requests.minOf { request -> request.createdAt }
                        }
                        UsernameSortOption.DateDescending -> groupViews.sortedByDescending { group ->
                            group.requests.minOf { request -> request.createdAt }
                        }
                        else -> groupViews // No sorting applied
                    }
                }
        }.onEach { requests -> _uiState.update { it.copy(filteredUsernameRequests = requests) } }
            .launchIn(viewModelWorkerScope)

        importedMasternodeKeyDao.observeAll()
            .onEach { updateMasternodeKeys(it, currentMasternodeKeyUsage) }
            .launchIn(viewModelScope)

        walletDataProvider.observeAuthenticationKeyUsage()
            .onEach { updateMasternodeKeys(currentImportedKeys, it)}

        viewModelWorkerScope.launch {
            platformSyncService.updateUsernameRequestsWithVotes()
        }
    }

    private suspend fun updateMasternodeKeys(importedKeyList: List<ImportedMasternodeKey>, usage: List<AuthenticationKeyUsage>) {
        val allKeys = arrayListOf<ImportedMasternodeKey>()
        allKeys.addAll(importedKeyList)

        usage.forEach {
            if ((it.type == AuthenticationKeyChain.KeyChainType.MASTERNODE_VOTING ||
                it.type == AuthenticationKeyChain.KeyChainType.MASTERNODE_OWNER) &&
                it.status == AuthenticationKeyStatus.CURRENT) {
                val entries = masternodeListManager.listAtChainTip.getMasternodesByVotingKey(KeyId.fromBytes(it.key.pubKeyHash))
                entries.forEach { masternode ->
                    importedMasternodeKeyDao.insert(
                        ImportedMasternodeKey(
                            masternode.proTxHash,
                            masternode.service.socketAddress.address.hostAddress!!,
                            it.key.privKeyBytes,
                            it.key.pubKey,
                            it.key.pubKeyHash
                        )
                    )
                }
            }
        }
        _masternodes.value = allKeys
    }

    suspend fun setFirstTimeInfoShown() {
        dashPayConfig.set(DashPayConfig.VOTING_INFO_SHOWN, true)
    }

    suspend fun getVotes(username: String): List<UsernameVote> {
        return usernameVoteDao.getVotes(Names.normalizeString(username))
    }

    fun observeVotesCount(username: String): Flow<Int> {
        return usernameVoteDao.observeVotes(Names.normalizeString(username)).flatMapLatest { flowOf(it.size) }
    }

    fun applyFilters(
        sortByOption: UsernameSortOption,
        typeOption: UsernameTypeOption,
        onlyDuplicates: Boolean,
        onlyLinks: Boolean
    ) {
        _filterState.update {
            it.copy(
                sortByOption = sortByOption,
                typeOption = typeOption,
                onlyDuplicates = onlyDuplicates,
                onlyLinks = onlyLinks
            )
        }
    }

    fun selectUsernameRequest(requestId: String) {
        _selectedUsernameRequestId.value = requestId
    }

    fun vote(requestId: String) {
        if (keysAmount == 0) {
            return
        }
        logEvent(AnalyticsConstants.UsernameVoting.VOTE)
        viewModelScope.launch(Dispatchers.IO) {
            val workId = getNextWorkId()
            _currentWorkId.value = workId
            usernameRequestDao.getRequest(requestId)?.let { request ->
                BroadcastUsernameVotesOperation(walletApplication).create(
                    workId,
                    listOf(request.username),
                    listOf(request.normalizedLabel),
                    listOf(ResourceVoteChoice.towardsIdentity(Identifier.from(request.identity))),
                    masternodes.value.map { it.votingPrivateKey },
                    isQuickVoting = false
                ).enqueue()

                _uiState.update { it.copy(voteSubmitted = true) }
            }
        }
    }

    private fun revokeVote(requestId: String) {
        if (keysAmount == 0) {
            return
        }
        logEvent(AnalyticsConstants.UsernameVoting.VOTE_CANCEL)
        viewModelScope.launch(Dispatchers.IO) {
            val workId = getNextWorkId()
            _currentWorkId.value = workId
            usernameRequestDao.getRequest(requestId)?.let { request ->
                BroadcastUsernameVotesOperation(walletApplication).create(
                    workId,
                    listOf(request.username),
                    listOf(request.normalizedLabel),
                    listOf(ResourceVoteChoice.abstain()),
                    masternodes.value.map { it.votingPrivateKey },
                    isQuickVoting = false
                ).enqueue()
                _uiState.update { it.copy(voteCancelled = true) }
            }
        }
    }

    private suspend fun getNextWorkId() = dashPayConfig.getUsernameVoteCounter().toString(16)

    fun voteForAll() {
        if (keysAmount == 0) {
            return
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val workId = getNextWorkId()
                _currentWorkId.value = workId
                val filteredRequests = _uiState.value.filteredUsernameRequests
                // group the filtered requests according to the normalizedLabel
                val requestsByUsername = hashMapOf<String, ArrayList<UsernameRequest>>()
                filteredRequests.flatMap {
                    it.requests
                }.forEach {
                    val requestList = if (!requestsByUsername.contains(it.normalizedLabel)) {
                        val list = arrayListOf<UsernameRequest>()
                        requestsByUsername[it.normalizedLabel] = list
                        list
                    } else {
                        requestsByUsername[it.normalizedLabel]
                    }
                    requestList!!.add(it)
                }

                // choose the first request submitted for each group based on the createdAt timestamp
                val firstRequests = requestsByUsername.map {
                    it.value.minByOrNull { request -> request.createdAt }!!
                }

                val usernames = arrayListOf<String>()
                val normalizedLabels = arrayListOf<String>()
                val voteChoices = arrayListOf<ResourceVoteChoice>()
                // ignore the requests that already have been approved or have no votes remaining
                firstRequests.filterNot {
                    it.isApproved && usernameVoteDao.countVotes(it.normalizedLabel) < UsernameVote.MAX_VOTES
                }.forEach { request ->
                    usernames.add(request.username)
                    normalizedLabels.add(request.normalizedLabel)
                    voteChoices.add(ResourceVoteChoice.towardsIdentity(Identifier.from(request.identity)))
                }

                BroadcastUsernameVotesOperation(walletApplication).create(
                    workId,
                    usernames,
                    normalizedLabels,
                    voteChoices,
                    masternodes.value.map { it.votingPrivateKey },
                    isQuickVoting = true
                ).enqueue()
            }
            _uiState.update { it.copy(voteSubmitted = true) }
        }
    }

    fun voteHandled() {
        _uiState.update { it.copy(voteSubmitted = false, voteCancelled = false) }
    }

    fun verifyKey(key: String): Boolean {
        return getKeyFromWIF(key) != null
    }

    fun verifyMasterVotingKey(key: ECKey): Boolean {
        return masternodeListManager
            .listAtChainTip
            .getMasternodesByVotingKey(KeyId.fromBytes(key.pubKeyHash))
            .isNotEmpty()
    }

    fun getKeyFromWIF(key: String): ECKey? {
        return try {
            DumpedPrivateKey.fromBase58(Constants.NETWORK_PARAMETERS, key).key
        } catch (e: AddressFormatException) {
            null
        }
    }

    suspend fun addKey(key: ECKey) {
        if (!_addedKeys.value.contains(key)) {
            _addedKeys.value += key
        }
        val entries = masternodeListManager.listAtChainTip.getMasternodesByVotingKey(KeyId.fromBytes(key.pubKeyHash))
        entries.forEach {
            importedMasternodeKeyDao.insert(
                ImportedMasternodeKey(
                    it.proTxHash,
                    it.service.socketAddress.address.hostAddress!!,
                    key.privKeyBytes,
                    key.pubKey,
                    key.pubKeyHash
                )
            )
        }
    }

    /**
     * returns true if we have all masternodes for this voting key
     */
    suspend fun hasKey(key: ECKey): Boolean {
        val entries = masternodeListManager.listAtChainTip.getMasternodesByVotingKey(KeyId.fromBytes(key.pubKeyHash))
        val count = entries.count {
            importedMasternodeKeyDao.contains(it.proTxHash)
        }
        return entries.size != count
    }

    private fun observeUsernames(): Flow<List<UsernameRequest>> {
        return if (_filterState.value.onlyDuplicates) {
            usernameRequestDao.observeDuplicates(_filterState.value.onlyLinks)
        } else {
            usernameRequestDao.observeAll(_filterState.value.onlyLinks)
        }
    }

    private fun List<UsernameRequest>.sortAndFilter(): List<UsernameRequest> {
        val sortByOption = _filterState.value.sortByOption
        val sorted = this.sortedWith(
            when (sortByOption) {
                UsernameSortOption.DateAscending -> compareBy { it.createdAt }
                UsernameSortOption.DateDescending -> compareByDescending { it.createdAt }
                UsernameSortOption.VotesAscending -> compareBy { it.votes }
                UsernameSortOption.VotesDescending -> compareByDescending { it.votes }
                UsernameSortOption.VotingPeriodSoonest -> compareBy { it.createdAt }
                UsernameSortOption.VotingPeriodLatest -> compareByDescending { it.createdAt }
            }
        )
        val approvedUsernames = sorted.filter { it.isApproved }.map { it.username }
        return when (_filterState.value.typeOption) {
            UsernameTypeOption.All -> sorted
            UsernameTypeOption.Approved -> sorted.filter { it.isApproved || approvedUsernames.contains(it.username) }
            UsernameTypeOption.NotApproved -> sorted.filter { !it.isApproved && !approvedUsernames.contains(it.username) }
            UsernameTypeOption.HasBlockedVotes -> sorted.filter { it.lockVotes != 0 }
        }
    }

    private fun isExpanded(username: String): Boolean {
        return _uiState.value.filteredUsernameRequests.any { it.username == username && it.isExpanded }
    }

    private var nameCount = 1
    // TODO: remove this when development is completed.

    private fun randomBytes(): ByteArray {
        val uuid = UUID.randomUUID()
        val byteBuffer = ByteBuffer.allocate(16)
        byteBuffer.putLong(uuid.mostSignificantBits)
        byteBuffer.putLong(uuid.leastSignificantBits)
        return Sha256Hash.hash(byteBuffer.array())
    }

    fun prepopulateList() {
        nameCount++
        val now = System.currentTimeMillis()// / 1000
        val names = listOf("John", "doe", "Sarah", "Jane", "jack", "Jill", "Bob")
        val from = 1658290321000L

        viewModelScope.launch {
            var name = names[Random.nextInt(0, min(names.size, nameCount))]
            var identifier = Identifier.from(randomBytes())
            usernameRequestDao.insert(
                UsernameRequest(
                    UsernameRequest.getRequestId(name, identifier.toString()),
                    name,
                    Names.normalizeString(name),
                    Random.nextLong(from, now),
                    identifier.toString(),
                    "https://www.figma.com/file/hh5juOSdGnNNPijJG1NGTi/DashPay%E3%83%BBIn-" +
                        "process%E3%83%BBAndroid?type=design&node-id=752-11735&mode=design&t=zasn6AKlSwb5NuYS-0",
                    Random.nextInt(0, 15),
                    Random.nextInt(0, 1),
                    true
                )
            )
            name = names[Random.nextInt(0, min(names.size, nameCount))]
            identifier = Identifier.from(randomBytes())
            usernameRequestDao.insert(
                UsernameRequest(
                    UsernameRequest.getRequestId(name, identifier.toString()),
                    name,
                    Names.normalizeString(name),
                    Random.nextLong(from, now),
                    identifier.toString(),
                    null,
                    Random.nextInt(0, 15),
                    Random.nextInt(0, 1),
                    true
                )
            )
            name = names[Random.nextInt(0, min(names.size, nameCount))]
            identifier = Identifier.from(randomBytes())
            usernameRequestDao.insert(
                UsernameRequest(
                    UsernameRequest.getRequestId(name, identifier.toString()),
                    name,
                    Names.normalizeString(name),
                    Random.nextLong(from, now),
                    identifier.toString(),
                    null,
                    Random.nextInt(0, 15),
                    Random.nextInt(0, 1),
                    false
                )
            )
            name = names[Random.nextInt(0, min(names.size, nameCount))]
            identifier = Identifier.from(randomBytes())
            usernameRequestDao.insert(
                UsernameRequest(
                    UsernameRequest.getRequestId(name, identifier.toString()),
                    name,
                    Names.normalizeString(name),                    Random.nextLong(from, now),
                    identifier.toString(),
                    "https://twitter.com/ProductHunt/",
                    Random.nextInt(0, 15),
                    Random.nextInt(0, 1),
                    false
                )
            )
            name = names[Random.nextInt(0, min(names.size, nameCount))]
            identifier = Identifier.from(randomBytes())
            usernameRequestDao.insert(
                UsernameRequest(
                    UsernameRequest.getRequestId(name, identifier.toString()),
                    name,
                    Names.normalizeString(name),                    Random.nextLong(from, now),
                    identifier.toString(),
                    null,
                    Random.nextInt(0, 15),
                    Random.nextInt(0, 1),
                    false
                )
            )
            name = names[Random.nextInt(0, min(names.size, nameCount))]
            identifier = Identifier.from(randomBytes())
            usernameRequestDao.insert(
                UsernameRequest(
                    UsernameRequest.getRequestId(name, identifier.toString()),
                    name,
                    Names.normalizeString(name),
                    Random.nextLong(from, now),
                    identifier.toString(),
                    null,
                    Random.nextInt(0, 15),
                    Random.nextInt(0, 1),
                    false
                )
            )
            name = names[Random.nextInt(0, min(names.size, nameCount))]
            identifier = Identifier.from(randomBytes())
            usernameRequestDao.insert(
                UsernameRequest(
                    UsernameRequest.getRequestId(name, identifier.toString()),
                    name,
                    Names.normalizeString(name),
                    Random.nextLong(from, now),
                    identifier.toString(),
                    null,
                    Random.nextInt(0, 15),
                    Random.nextInt(0, 2),
                    false
                )
            )
        }
    }

    fun block(username: String) {
        if (keysAmount == 0) {
            return
        }
        logEvent(AnalyticsConstants.UsernameVoting.BLOCK)
        viewModelScope.launch(Dispatchers.IO) {
            val workId = getNextWorkId()
            _currentWorkId.value = workId
            BroadcastUsernameVotesOperation(walletApplication).create(
                workId,
                listOf(username),
                listOf(username),
                listOf(ResourceVoteChoice.lock()),
                masternodes.value.map { it.votingPrivateKey },
                isQuickVoting = false
            ).enqueue()

            _uiState.update { it.copy(voteSubmitted = true) }
        }
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    fun submitVote(requestId: String, vote: String) {
        when (vote) {
            UsernameVote.APPROVE -> vote(requestId)
            UsernameVote.LOCK -> block(requestId)
            UsernameVote.ABSTAIN -> revokeVote(requestId)
        }
    }

    fun setDontAskAgain() {
        viewModelScope.launch(Dispatchers.IO) {
            dashPayConfig.set(DashPayConfig.KEYS_DONT_ASK_AGAIN, true)
        }
    }

    suspend fun shouldMaybeAskForMoreKeys() = !(dashPayConfig.get(DashPayConfig.KEYS_DONT_ASK_AGAIN) ?: false)

    suspend fun setSecondTimeVoting() {
        dashPayConfig.set(DashPayConfig.FIRST_TIME_VOTING, false)
    }

    suspend fun isFirstTimeVoting() = dashPayConfig.get(DashPayConfig.FIRST_TIME_VOTING) ?: true

    fun invalidKeyType(wifKey: String): InvalidKeyType {
        return try {
            // check if it is a private key hex 64 chars first
            if (wifKey.length == 64) {
                try {
                    Utils.HEX.decode(wifKey)
                    return InvalidKeyType.PRIVATE_KEY_HEX
                } catch (_: Exception) {
                    // swallow
                }
            } else if (wifKey.length == 66) {
                try {
                    Utils.HEX.decode(wifKey)
                    return InvalidKeyType.PUBLIC_KEY_HEX
                } catch (_: Exception) {
                    // swallow
                }
            }
            DumpedPrivateKey.fromBase58(Constants.NETWORK_PARAMETERS, wifKey).key
            InvalidKeyType.NOT_INVALID // shouldn't happen
        } catch (e: AddressFormatException.WrongNetwork) {
            try {
                Address.fromBase58(Constants.NETWORK_PARAMETERS, wifKey)
                InvalidKeyType.ADDRESS
            } catch (e: AddressFormatException) {
                InvalidKeyType.WRONG_NETWORK
            }
        } catch (e: AddressFormatException.InvalidChecksum) {
            try {
                if (Base58.decode(wifKey).size < 39)
                    InvalidKeyType.SHORT
                else InvalidKeyType.CHECKSUM
            } catch (e: AddressFormatException.InvalidCharacter) {
                InvalidKeyType.CHARACTER
            }
        } catch (e: AddressFormatException.InvalidCharacter) {
            try {
                if (Base58.decode(wifKey).size < 32)
                    InvalidKeyType.SHORT
                else InvalidKeyType.CHECKSUM
            } catch (e: AddressFormatException.InvalidCharacter) {
                InvalidKeyType.CHARACTER
            }
        } catch (e: Exception) {
            // is it an address
            try {
                val decodedBytes = Utils.HEX.decode(wifKey)
                if (decodedBytes.size == 33) {
                    InvalidKeyType.PUBLIC_KEY_HEX
                }
                InvalidKeyType.PRIVATE_KEY_HEX
            } catch (e: Exception) {
                InvalidKeyType.UNKNOWN
            }
        }
    }

    fun updateUsernameRequestsWithVotes() {
        viewModelScope.launch(Dispatchers.IO) {
            platformSyncService.updateUsernameRequestsWithVotes()
        }
    }

    fun updateUsernameRequestWithVotes(username: String) {
        viewModelScope.launch(Dispatchers.IO) {
            platformSyncService.updateUsernameRequestWithVotes(username)
        }
    }

    fun removeMasternode(masternodeIp: String) {
        val masternodeList = masternodeListManager.masternodeList
        val masternode = masternodeList.getMNByAddress(InetSocketAddress(masternodeIp, Constants.NETWORK_PARAMETERS.port))
        if (masternode != null) {
            viewModelWorkerScope.launch {
                importedMasternodeKeyDao.remove(masternode.proTxHash)
            }
        }
    }

    /**
     * returns names in [usernames] that have only [votesLeft] remaining votes that can be cast
     */
    suspend fun getUsernamesByVotesLeft(usernames: List<String>, votesLeft: Int): List<String> {
        return usernames.filter { username ->
            usernameVoteDao.countVotes(username) == UsernameVote.MAX_VOTES - votesLeft
        }
    }

    fun voteObserver(workId: String) = BroadcastUsernameVotesOperation.operationStatus(walletApplication, workId, analytics)

    suspend fun isImported(masternode: ImportedMasternodeKey): Boolean {
        return importedMasternodeKeyDao.contains(masternode.proTxHash)
    }

    suspend fun getVotingStartDate(normalizedLabel: String): Long {
        return usernameRequestDao.getRequestsByNormalizedLabel(normalizedLabel).minOf {
            it.createdAt
        }
    }
}
