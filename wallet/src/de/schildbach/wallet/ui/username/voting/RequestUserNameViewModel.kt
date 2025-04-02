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
package de.schildbach.wallet.ui.username.voting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.CREATION_STATE
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.IDENTITY_ID
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.USERNAME
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.USERNAME_REQUESTED
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.work.BroadcastIdentityVerifyOperation
import de.schildbach.wallet.ui.username.CreateUsernameArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.script.ScriptPattern
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.sdk.platform.DomainDocument
import org.dashj.platform.sdk.platform.Names
import org.slf4j.LoggerFactory
import javax.inject.Inject
import kotlin.math.max

data class RequestUserNameUIState(
    val usernameVerified: Boolean = false,
    val usernameRequestSubmitting: Boolean = false,
    val usernameRequestSubmitted: Boolean = false,
    val checkingUsername: Boolean = false,
    val usernameCheckSuccess: Boolean = false,
    val usernameSubmittedError: Boolean = false,
    val usernameLengthValid: Boolean = false,
    val usernameCharactersValid: Boolean = false,
    val usernameTooShort: Boolean = true,  // default zero length username
    val usernameContestable: Boolean = false,
    val usernameContested: Boolean = false,
    val usernameExists: Boolean = false,
    val usernameBlocked: Boolean = false,
    val enoughBalance: Boolean = false,
    val usernameNonContestedChars: Boolean = false,
    val usernameNonContestedLength: Boolean = false,
    val votingPeriodStart: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RequestUserNameViewModel @Inject constructor(
    val walletApplication: WalletApplication,
    private val identityConfig: BlockchainIdentityConfig,
    val walletData: WalletDataProvider,
    val platformRepo: PlatformRepo,
    val usernameRequestDao: UsernameRequestDao,
    val coinJoinConfig: CoinJoinConfig,
    val analytics: AnalyticsService,
    val topUpRepository: TopUpRepository
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(RequestUserNameViewModel::class.java)
        private val CONTEST_DOCUMENT_FEE = Coin.valueOf(0, 20).value * 1000
        private val NON_CONTEST_DOCUMENT_FEE = Coin.valueOf(1000000).value * 1000
    }

    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)
    private val _uiState = MutableStateFlow(RequestUserNameUIState())
    val uiState: StateFlow<RequestUserNameUIState> = _uiState.asStateFlow()

    private val _requestedUserNameLink = MutableStateFlow<String?>(null)
    val requestedUserNameLink: StateFlow<String?> = _requestedUserNameLink.asStateFlow()

    var identity: BlockchainIdentityData? = null
    var requestedUserName: String? = null

    private val _identityBalance = MutableStateFlow(0L)
    val identityBalance: StateFlow<Long>
        get() = _identityBalance

    private val _walletBalance = MutableStateFlow(Coin.ZERO)
    val walletBalance: StateFlow<Coin>
        get() = _walletBalance

    private var createUsernameArgs: CreateUsernameArgs? = null
    private val inviteAssetLockTx = MutableStateFlow<AssetLockTransaction?>(null)
    private val _inviteBalance = MutableStateFlow(Coin.ZERO)
    val inviteBalance: StateFlow<Coin>
        get() = _inviteBalance
    private val _isInviteMixed = MutableStateFlow(false)
    val isInviteMixed: StateFlow<Boolean>
        get() = _isInviteMixed
    var invitationNextStep: (() -> Unit)? = null
    fun setCreateUsernameArgs(createUsernameArgs: CreateUsernameArgs?) {
        createUsernameArgs?.let {
            this.createUsernameArgs = it
            viewModelScope.launch {
                getInviteAssetLockTransaction()
            }
        }
    }

    suspend fun isUserNameRequested(): Boolean {
        val hasRequestedName = identityConfig.get(USERNAME).isNullOrEmpty().not()
        val creationState = BlockchainIdentityData.CreationState.valueOf(
            identityConfig.get(CREATION_STATE) ?: BlockchainIdentityData.CreationState.NONE.name
        )
        return hasRequestedName && creationState != BlockchainIdentityData.CreationState.NONE && creationState.ordinal <= BlockchainIdentityData.CreationState.VOTING.ordinal
    }

    suspend fun isUsernameLocked(): Boolean {
        return isUserNameRequested() &&
                UsernameRequestStatus.valueOf(identityConfig.get(USERNAME_REQUESTED)!!) == UsernameRequestStatus.LOCKED
    }

    suspend fun isUsernameLostAfterVoting(): Boolean {
        return isUserNameRequested() &&
                UsernameRequestStatus.valueOf(identityConfig.get(USERNAME_REQUESTED)!!) == UsernameRequestStatus.LOST_VOTE
    }

    suspend fun hasUserCancelledVerification(): Boolean =
        identityConfig.get(BlockchainIdentityConfig.CANCELED_REQUESTED_USERNAME_LINK) ?: false

    fun canAffordNonContestedUsername(): Boolean {
        return when {
            isUsingInvite() -> {
                false
            }

            identity?.userId != null -> {
                val credits = _identityBalance.value
                credits > Constants.DASH_PAY_FEE.value / 10 * 1000
            }

            else -> {
                _walletBalance.value >= Constants.DASH_PAY_FEE
            }
        }
    }

    fun canAffordContestedUsername(): Boolean {
        return if (identity?.userId != null) {
            val credits = _identityBalance.value
            credits > CONTEST_DOCUMENT_FEE
        } else {
            _walletBalance.value >= Constants.DASH_PAY_FEE_CONTESTED
        }
    }

    val myUsernameRequest: Flow<UsernameRequest?>
        get() = _myUsernameRequest
    private val _myUsernameRequest = MutableStateFlow<UsernameRequest?>(null)

    init {
        viewModelScope.launch {
            _requestedUserNameLink.value = withContext(Dispatchers.IO) {
                identityConfig.get(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK)
            }
        }
        identityConfig.observe(IDENTITY_ID)
            .filterNotNull()
            .onEach {
                identity = identityConfig.load()
                _identityBalance.value = identity?.let { identity ->
                    try {
                        platformRepo.getIdentityBalance(Identifier.from(identity.userId)).balance
                    } catch (e: Exception) {
                        // need to try again later
                        -1
                    }
                } ?: 0
                log.info("identity balance: {}", identityBalance)
                if (requestedUserName == null) {
                    requestedUserName = identityConfig.get(USERNAME)
                }
            }
            .flatMapLatest { usernameRequestDao.observeRequest(UsernameRequest.getRequestId(it, requestedUserName ?: "")) }
            .onEach {
                if (it != null) {
                    _myUsernameRequest.value = it
                } else if (requestedUserName != null) {
                    identity?.let { identityData ->
                        _myUsernameRequest.value = UsernameRequest(
                            UsernameRequest.getRequestId(identityData.userId!!, requestedUserName!!),
                            requestedUserName!!,
                            Names.normalizeString(requestedUserName!!),
                            identityData.votingPeriodStart ?: -1L,
                            identityData.userId!!,
                            identityData.verificationLink ?: "",
                            0,
                            0,
                            false
                        )
                    }
                } else {
                    _myUsernameRequest.value = null
                }
            }
            .launchIn(viewModelWorkerScope)

        coinJoinConfig.observeMode()
            .flatMapLatest { coinJoinMode ->
                walletData.observeBalance(
                    if (coinJoinMode == CoinJoinMode.NONE) {
                        Wallet.BalanceType.ESTIMATED_SPENDABLE
                    } else {
                        Wallet.BalanceType.COINJOIN_SPENDABLE
                    }
                )
            }
            .onEach {
                _walletBalance.value = it
            }
            .launchIn(viewModelScope)

        inviteAssetLockTx.onEach {
            _inviteBalance.value = getInvitationAmount()
        }.launchIn(viewModelWorkerScope)
    }

    private fun triggerIdentityCreation(reuseTransaction: Boolean) {
        val username = requestedUserName!!
        val isUsingInvite = isUsingInvite()
        val fromOnboarding = createUsernameArgs?.fromOnboardng ?: false
        when {
            fromOnboarding -> {
                // postpone username creation till later
                walletApplication.configuration.onboardingInviteUsername = requestedUserName
            }
            isUsingInvite && reuseTransaction -> {
                walletApplication.startService(
                    CreateIdentityService.createIntentFromInviteForNewUsername(
                        walletApplication,
                        username
                    )
                )
            }
            isUsingInvite -> {
                walletApplication.startService(
                    CreateIdentityService.createIntentFromInvite(
                        walletApplication,
                        username,
                        createUsernameArgs!!.invite!!
                    )
                )
            }
            reuseTransaction -> {
                walletApplication.startService(
                    CreateIdentityService.createIntentForNewUsername(
                        walletApplication,
                        username
                    )
                )
            }
            else -> {
                walletApplication.startService(
                    CreateIdentityService.createIntent(
                        walletApplication,
                        username
                    )
                )
            }
        }
    }

//    fun triggerIdentityCreationFromInvite(
//        reuseTransaction: Boolean,
//        fromOnboarding: Boolean,
//        inviteLinkData: InvitationLinkData
//    ) {
//        if (reuseTransaction) {
//            walletApplication.startService(CreateIdentityService.createIntentFromInviteForNewUsername(walletApplication, requestedUserName!!))
//        } else {
//            if (fromOnboarding) {
//                walletApplication.configuration.onboardingInviteUsername = requestedUserName
//                return
//            } else {
//                walletApplication.startService(CreateIdentityService.createIntentFromInvite(walletApplication, requestedUserName!!, inviteLinkData))
//            }
//        }
//    }

    fun submit() {
        // Reset ui state for retry if needed
        resetUiForRetrySubmit()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { updateConfig() }
            // send the request / create username, assume not retry
            val reuseTransaction = identity?.let {
                it.usernameRequested == UsernameRequestStatus.LOCKED || it.usernameRequested == UsernameRequestStatus.LOST_VOTE
            } ?: false
            triggerIdentityCreation(reuseTransaction)
        }
        // if call success
        // updateUiForApiSuccess()
        // else if call failed
        // updateUiForApiError()
    }

    fun reset() {
        _uiState.update { RequestUserNameUIState() }
    }

    private fun resetUiForRetrySubmit() {
        _uiState.update {
            it.copy(
                usernameSubmittedError = false,
                usernameRequestSubmitted = false,
                usernameRequestSubmitting = false
            )
        }
    }
    fun setRequestedUserNameLink(link: String) {
        _requestedUserNameLink.value = link
    }

    private suspend fun updateConfig() {
        requestedUserName?.let { name ->
            identityConfig.set(USERNAME, name)
        }
        _requestedUserNameLink.value.let { link ->
            identityConfig.set(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK, link ?: "")
        }
    }

//    private fun updateUiForApiSuccess() {
//        _uiState.update {
//            it.copy(
//                usernameCheckSuccess = true,
//                usernameRequestSubmitting = false,
//                usernameRequestSubmitted = true,
//                usernameSubmittedError = false
//            )
//        }
//    }
//    private fun updateUiForApiError() {
//        _uiState.update { it ->
//            it.copy(
//                usernameCheckSuccess = false,
//                usernameSubmittedError = true
//            )
//        }
//    }

    fun checkUsername(requestedUserName: String?) {
        viewModelScope.launch {
            requestedUserName?.let { username ->
                _uiState.update { it.copy(checkingUsername = true) }
                val usernameSearchResult = withContext(Dispatchers.IO) { platformRepo.getUsername(username) }
                val usernameExists = when (usernameSearchResult.status) {
                    Status.SUCCESS -> {
                        usernameSearchResult.data != null
                    }
                    else -> false
                }
                var usernameContested: Boolean
                var firstCreatedAt = -1L
                val usernameBlocked = withContext(Dispatchers.IO) {
                    val contenders = platformRepo.getVoteContenders(username)
                    usernameContested = contenders.map.isNotEmpty()
                    var maxApprovalVotes = 0
                    firstCreatedAt = try {
                        contenders.map.values.minOf { contender ->
                            val document = contender.serializedDocument?.let {
                                DomainDocument(platformRepo.platform.names.deserialize(it))
                            }
                            maxApprovalVotes = max(contender.votes, maxApprovalVotes)
                            document?.createdAt ?: -1
                        }
                    } catch (e: NoSuchElementException) {
                        -1L
                    }

                    // is the name blocked
                    firstCreatedAt == -1L && contenders.lockVoteTally > maxApprovalVotes
                }
                _uiState.update {
                    it.copy(
                        checkingUsername = false,
                        usernameCheckSuccess = true,
                        usernameSubmittedError = false,
                        usernameContested = usernameContested, usernameExists = usernameExists,
                        usernameBlocked = usernameBlocked,
                        votingPeriodStart = if (firstCreatedAt == -1L) System.currentTimeMillis() else firstCreatedAt
                    )
                }
            }
        }
    }

    fun verify() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                identityConfig.set(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK, _requestedUserNameLink.value ?: "")
                identityConfig.get(IDENTITY_ID)?.let { identityId ->
                    val usernameRequest = usernameRequestDao.getRequest(
                        UsernameRequest.getRequestId(
                            identityId,
                            requestedUserName!!
                        )
                    )
                    usernameRequest!!.link = _requestedUserNameLink.value
                    usernameRequestDao.update(usernameRequest)
                }
            }
            _uiState.update {
                it.copy(
                    usernameVerified = true
                )
            }
        }
    }

    @Deprecated("requests cannot be canceled")
    fun cancelRequest() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                identityConfig.set(BlockchainIdentityConfig.USERNAME, "")
                identityConfig.set(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK, "")
                identityConfig.set(BlockchainIdentityConfig.CANCELED_REQUESTED_USERNAME_LINK, true)
            }
        }
    }

    private fun validateUsernameSize(uname: String): Boolean {
        return uname.length in Constants.USERNAME_MIN_LENGTH..Constants.USERNAME_MAX_LENGTH
    }

    private fun validateNonContestedUsernameSize(uname: String): Boolean {
        return uname.length in Constants.USERNAME_NON_CONTESTED_MIN_LENGTH..Constants.USERNAME_MAX_LENGTH
    }

    private fun validateUsernameCharacters(uname: String): Pair<Boolean, Boolean> {
        val alphaNumHyphenValid = !Regex("[^a-zA-Z0-9\\-]").containsMatchIn(uname)
        val startOrEndWithHyphen = uname.startsWith("-") || uname.endsWith("-")
        return Pair(alphaNumHyphenValid, startOrEndWithHyphen)
    }

    private fun validateNonContestedUsernameCharacters(uname: String): Boolean {
        return Regex("[2-9]").containsMatchIn(uname)
    }

    fun checkUsernameValid(username: String): Boolean {
        val validLength = validateUsernameSize(username)
        val (validCharacters, startOrEndWithHyphen) = validateUsernameCharacters(username)
        val contestable = Names.isUsernameContestable(username)

        val identityBalance = _identityBalance.value
        val walletBalance = _walletBalance.value
        val inviteBalance = _inviteBalance.value
        val enoughBalance = when {
            isUsingInvite() && contestable -> inviteBalance >= Constants.DASH_PAY_FEE_CONTESTED
            isUsingInvite() && !contestable -> inviteBalance >= Constants.DASH_PAY_FEE
            identityBalance > 0L && contestable -> (Coin.valueOf(identityBalance / 1000) + walletBalance) > Coin.valueOf(CONTEST_DOCUMENT_FEE / 1000)
            identityBalance > 0L && !contestable -> (Coin.valueOf(identityBalance / 1000) + walletBalance) > Coin.valueOf(NON_CONTEST_DOCUMENT_FEE / 1000)
            identityBalance == 0L && contestable -> walletBalance >= Constants.DASH_PAY_FEE_CONTESTED
            identityBalance == 0L && !contestable -> walletBalance >= Constants.DASH_PAY_FEE
            else -> false // how can we get here?
        }
        _uiState.update {
            it.copy(
                usernameLengthValid = validLength,
                usernameCharactersValid = validCharacters && !startOrEndWithHyphen,
                usernameContestable = contestable,
                enoughBalance = enoughBalance,
                usernameTooShort = username.isEmpty(),
                usernameSubmittedError = false,
                usernameCheckSuccess = false,
                usernameNonContestedLength = validateNonContestedUsernameSize(username),
                usernameNonContestedChars = validateNonContestedUsernameCharacters(username)
            )
        }
        return validCharacters && validLength
    }

    @Throws(NullPointerException::class)
    fun isUsernameContestable(): Boolean {
        return Names.isUsernameContestable(requestedUserName!!)
    }

    fun publishIdentityVerifyDocument() {
        _requestedUserNameLink.value?.let { url ->
            BroadcastIdentityVerifyOperation(walletApplication).create(
                requestedUserName!!,
                url
            ).enqueue()
        }
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    suspend fun getVotingStartDate(normalizedLabel: String): Long {
        return usernameRequestDao.getRequestsByNormalizedLabel(normalizedLabel).minOf {
            it.createdAt
        }
    }

    fun isUsingInvite(): Boolean = createUsernameArgs?.invite != null

    suspend fun getInviteAssetLockTransaction(): AssetLockTransaction? = withContext(Dispatchers.IO) {
        if (isUsingInvite()) {
            inviteAssetLockTx.value = try {
                topUpRepository.getAssetLockTransaction(createUsernameArgs?.invite!!)
            } catch (e: Exception) {
                log.error("error getting asset lock tx", e)
                null
            }
            isInviteMixed()
        }
        inviteAssetLockTx.value
    }

    fun getInvitationAmount(): Coin {
        return inviteAssetLockTx.value?.let {
            it.assetLockPayload.creditOutputs?.find { transactionOutput ->
                if (ScriptPattern.isP2PKH(transactionOutput.scriptPubKey)) {
                    it.assetLockPublicKey.pubKeyHash.contentEquals(
                        ScriptPattern.extractHashFromP2PKH(transactionOutput.scriptPubKey)
                    )
                } else {
                    false
                }
            }?.value ?: Coin.ZERO
        } ?: Coin.ZERO
    }

    fun isInviteForContestedNames(): Boolean = getInvitationAmount() >= Constants.DASH_PAY_FEE_CONTESTED

    private suspend fun isInviteMixed(): Unit = withContext(Dispatchers.IO) {
        _isInviteMixed.value = inviteAssetLockTx.value?.let {
            topUpRepository.isInvitationMixed(it)
        } ?: false
    }
}
