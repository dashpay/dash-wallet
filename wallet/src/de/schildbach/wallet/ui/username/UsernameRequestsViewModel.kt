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
import com.google.api.services.drive.model.User
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bitcoinj.core.AddressFormatException
import org.bitcoinj.core.Base58
import org.bitcoinj.core.DumpedPrivateKey
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.KeyId
import org.bitcoinj.core.Utils
import org.bitcoinj.evolution.Masternode
import org.bitcoinj.evolution.SimplifiedMasternodeListManager
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bitcoinj.wallet.authentication.AuthenticationKeyStatus
import org.bitcoinj.wallet.authentication.AuthenticationKeyUsage
import org.dash.wallet.common.WalletDataProvider
import org.dashj.platform.sdk.platform.Names
import java.util.UUID
import javax.inject.Inject
import kotlin.math.min
import kotlin.random.Random

data class UsernameRequestsUIState(
    val filteredUsernameRequests: List<UsernameRequestGroupView> = listOf(),
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
    private val walletDataProvider: WalletDataProvider
): ViewModel() {
    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    private val _uiState = MutableStateFlow(UsernameRequestsUIState())
    val uiState: StateFlow<UsernameRequestsUIState> = _uiState.asStateFlow()

    private val _filterState = MutableStateFlow(FiltersUIState())
    val filterState: StateFlow<FiltersUIState> = _filterState.asStateFlow()

    private val _selectedUsernameRequestId = MutableStateFlow<String?>(null)
    val selectedUsernameRequest: Flow<UsernameRequest> = _selectedUsernameRequestId
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { id ->
            usernameRequestDao.observeRequest(id)
                .filterNotNull()
                .distinctUntilChanged()
        }

    private val masternodeListManager: SimplifiedMasternodeListManager
        get() = walletDataProvider.wallet!!.context.masternodeListManager

    // TODO: remove this
    private val _addedKeys = MutableStateFlow(listOf<ECKey>())
//    val masternodes: Flow<List<Masternode>> = _addedKeys.map {
//        val masternodesForKeys = arrayListOf<Masternode>()
//        it.forEach { key ->
//            val entries = masternodeListManager.listAtChainTip.getMasternodesByVotingKey(KeyId.fromBytes(key.pubKeyHash))
//            masternodesForKeys.addAll(entries)
//        }
//        masternodesForKeys
//    }

    private val _masternodes =  MutableStateFlow<List<ImportedMasternodeKey>>(listOf())
    val masternodes: StateFlow<List<ImportedMasternodeKey>>// = importedMasternodeKeyDao.observeAll()
        get() = _masternodes
//        .onEach {
//            val masternodesForKeys = arrayListOf<Masternode>()
//            it.forEach { key ->
//                val entries = masternodeListManager.listAtChainTip.getMasternodesByVotingKey(KeyId.fromBytes(key.pubKeyHash))
//                masternodesForKeys.addAll(entries)
//            }
//            masternodesForKeys
//        }
//        .launchIn(viewModelScope)
//    }
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
                    duplicates.groupBy { it.username }
                        .map { (username, list) ->
                            val sortedList = list.sortAndFilter()
                            val votes = usernameVoteDao.getVotes(username)
                            UsernameRequestGroupView(username, sortedList, isExpanded = isExpanded(username), votes)
                        }.filterNot { it.requests.isEmpty() }
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

        //val authenticationGroupExtension = walletDataProvider.wallet!!.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension

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

        viewModelScope.launch {
            usernameRequestDao.getRequest(requestId)?.let { request ->
                usernameRequestDao.update(request.copy(votes = request.votes + keysAmount, isApproved = true))
                _uiState.update { it.copy(voteSubmitted = true) }
                usernameVoteDao.insert(
                    UsernameVote(
                        request.username,
                        request.identity,
                        UsernameVote.APPROVE
                    )
                )
            }
        }
    }

    fun revokeVote(requestId: String) {
        if (keysAmount == 0) {
            return
        }

        viewModelScope.launch {
            usernameRequestDao.getRequest(requestId)?.let { request ->
                usernameRequestDao.update(request.copy(votes = request.votes - keysAmount, isApproved = false))
                _uiState.update { it.copy(voteCancelled = true) }
                usernameVoteDao.insert(
                    UsernameVote(
                        request.username,
                        request.identity,
                        UsernameVote.ABSTAIN
                    )
                )
            }
        }
    }

    fun voteForAll() {
        if (keysAmount == 0) {
            return
        }

        viewModelScope.launch {
            val filteredRequests = _uiState.value.filteredUsernameRequests
            val requestIds = filteredRequests.flatMap {
                it.requests
            }.filterNot {
                it.isApproved
            }.map {
                it.requestId
            }
            usernameRequestDao.voteForRequests(
                requestIds,
                keysAmount
            )
            _uiState.update { it.copy(voteSubmitted = true) }
        }
    }

    fun voteHandled() {
        _uiState.update { it.copy(voteSubmitted = false, voteCancelled = false) }
    }

    fun verifyKey(key: String): Boolean {
        return getKeyFromWIF(key) != null
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
            }
        )
        val approvedUsernames = sorted.filter { it.isApproved }.map { it.username }
        return when (_filterState.value.typeOption) {
            UsernameTypeOption.All -> sorted
            UsernameTypeOption.Approved -> sorted.filter { it.isApproved || approvedUsernames.contains(it.username) }
            UsernameTypeOption.NotApproved -> sorted.filter { !it.isApproved && !approvedUsernames.contains(it.username) }
        }
    }

    private fun isExpanded(username: String): Boolean {
        return _uiState.value.filteredUsernameRequests.any { it.username == username && it.isExpanded }
    }

    private var nameCount = 1
    fun prepopulateList() {
        nameCount++
        val now = System.currentTimeMillis()// / 1000
        val names = listOf("John", "doe", "Sarah", "Jane", "jack", "Jill", "Bob")
        val from = 1658290321000L

        viewModelScope.launch {
            var name = names[Random.nextInt(0, min(names.size, nameCount))]
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    name,
                    Names.normalizeString(name),
                    Random.nextLong(from, now),
                    Base58.encode(UUID.randomUUID().toString().toByteArray()),
                    "https://www.figma.com/file/hh5juOSdGnNNPijJG1NGTi/DashPay%E3%83%BBIn-" +
                        "process%E3%83%BBAndroid?type=design&node-id=752-11735&mode=design&t=zasn6AKlSwb5NuYS-0",
                    Random.nextInt(0, 15),
                    Random.nextInt(0, 15),
                    true
                )
            )
            name = names[Random.nextInt(0, min(names.size, nameCount))]
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    name,
                    Names.normalizeString(name),
                    Random.nextLong(from, now),
                    Base58.encode(UUID.randomUUID().toString().toByteArray()),
                    null,
                    Random.nextInt(0, 15),
                    Random.nextInt(0, 15),
                    true
                )
            )
            name = names[Random.nextInt(0, min(names.size, nameCount))]
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    name,
                    Names.normalizeString(name),
                    Random.nextLong(from, now),
                    Base58.encode(UUID.randomUUID().toString().toByteArray()),
                    null,
                    Random.nextInt(0, 15),
                    Random.nextInt(0, 15),
                    false
                )
            )
            name = names[Random.nextInt(0, min(names.size, nameCount))]
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    name,
                    Names.normalizeString(name),                    Random.nextLong(from, now),
                    Base58.encode(UUID.randomUUID().toString().toByteArray()),
                    "https://twitter.com/ProductHunt/",
                    Random.nextInt(0, 15),
                    Random.nextInt(0, 15),
                    false
                )
            )
            name = names[Random.nextInt(0, min(names.size, nameCount))]
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    name,
                    Names.normalizeString(name),                    Random.nextLong(from, now),
                    Base58.encode(UUID.randomUUID().toString().toByteArray()),
                    null,
                    Random.nextInt(0, 15),
                    Random.nextInt(0, 15),
                    false
                )
            )
            name = names[Random.nextInt(0, min(names.size, nameCount))]
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    name,
                    Names.normalizeString(name),
                    Random.nextLong(from, now),
                    Base58.encode(UUID.randomUUID().toString().toByteArray()),
                    null,
                    Random.nextInt(0, 15),
                    Random.nextInt(0, 15),
                    false
                )
            )
            name = names[Random.nextInt(0, min(names.size, nameCount))]
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    name,
                    Names.normalizeString(name),
                    Random.nextLong(from, now),
                    Base58.encode(UUID.randomUUID().toString().toByteArray()),
                    null,
                    Random.nextInt(0, 15),
                    Random.nextInt(0, 15),
                    false
                )
            )
        }
    }

    fun block(requestId: String) {
        if (keysAmount == 0) {
            return
        }

        viewModelScope.launch {
            usernameRequestDao.getRequest(requestId)?.let { request ->
                usernameRequestDao.update(request.copy(lockVotes = request.lockVotes + keysAmount, isApproved = true))
                _uiState.update { it.copy(voteSubmitted = true) }
                usernameVoteDao.insert(
                    UsernameVote(
                        request.username,
                        request.identity,
                        UsernameVote.LOCK
                    )
                )
            }
        }
    }
}
