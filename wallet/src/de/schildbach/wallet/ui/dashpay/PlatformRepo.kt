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
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.*
import de.schildbach.wallet.database.AppDatabase
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.SeriousError
import de.schildbach.wallet.livedata.SeriousErrorListener
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.security.SecurityGuardException
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
import org.dashj.platform.dashpay.*
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.errors.concensus.basic.identity.InvalidInstantAssetLockProofException
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.voting.Contenders
import org.dashj.platform.sdk.platform.DomainDocument
import org.dashj.platform.sdk.platform.Names
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@Singleton
class PlatformRepo @Inject constructor(
    val walletApplication: WalletApplication,
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

    private val onSeriousErrorListeneners = arrayListOf<SeriousErrorListener>()

    val authenticationGroupExtension: AuthenticationGroupExtension?
        get() = walletApplication.authenticationGroupExtension

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

    fun getUsername(username: String): Resource<Document> {
        return try {
            val nameDocument = platform.names.get(Names.normalizeString(username))
            Resource.success(nameDocument)
        } catch (e: Exception) {
            Resource.error(e.localizedMessage!!, null)
        }
    }

    fun getVoteContenders(username: String): Contenders {
        return try {
            val watch = Stopwatch.createStarted()
            val contenders = platform.names.getVoteContenders(Names.normalizeString(username))
            log.info("getVoteContenders took {}", watch)
            contenders
        } catch (e: Exception) {
            Contenders(Optional.empty(), mapOf(), 0, 0)
        }
    }

    fun getFromProfiles(
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
        return formatExceptionMessage(description, e, log)
    }

    fun formatExceptionMessage(description: String, e: Exception, log: Logger): String {
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


    //
    // Step 1 is to upgrade the wallet to support authentication keys
    //
    fun addWalletAuthenticationKeys(seed: DeterministicSeed, keyParameter: KeyParameter) {
        val wallet = walletApplication.wallet as WalletEx
        // this will initialize any missing key chains
        wallet.initializeCoinJoin(keyParameter, 0)

        var authenticationGroupExtension = AuthenticationGroupExtension(wallet)
        authenticationGroupExtension = wallet.addOrGetExistingExtension(authenticationGroupExtension) as AuthenticationGroupExtension
        authenticationGroupExtension.addEncryptedKeyChains(wallet.params, seed, keyParameter, keyChainTypes)
    }

    //
    // Step 2 is to create the credit funding transaction
    //


    //
    // Step 3: Register the identity
    //
    suspend fun registerIdentity(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter?) {
        Context.propagate(walletApplication.wallet!!.context)
        for (i in 0 until 3) {
            try {
                val timer = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_IDENTITY_CREATE)
                blockchainIdentity.registerIdentity(keyParameter, true, true)
                timer.logTiming() // we won't log timing for failed registrations
                return
            } catch (e: InvalidInstantAssetLockProofException) {
                log.info("instantSendLock error: retry registerIdentity again ($i)")
                delay(3000)
            }
        }
        throw InvalidInstantAssetLockProofException("failed after 3 tries")
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
    fun preorderName(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter?, username: String) {
        // val names = blockchainIdentity.getUnregisteredUsernames()
        val timer = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_PREORDER_CREATE)
        blockchainIdentity.registerPreorderedSaltedDomainHashesForUsernames(listOf(username), keyParameter)
        timer.logTiming()
    }

    //
    // Step 5: Register the username
    //
    fun registerName(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter?, username: String) {
        // val names = blockchainIdentity.preorderedUsernames()
        val timer = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_DOMAIN_CREATE)
        blockchainIdentity.registerUsernameDomainsForUsernames(listOf(username), keyParameter, false)
        timer.logTiming()
    }

    //Step 6: Create DashPay Profile
    @Deprecated("Don't need this function when creating an identity")
    suspend fun createDashPayProfile(blockchainIdentity: BlockchainIdentity, keyParameter: KeyParameter) {
        withContext(Dispatchers.IO) {
            val username = blockchainIdentity.currentUsername!!
            blockchainIdentity.registerProfile(username, "", "", null, null, keyParameter)
        }
    }



    suspend fun updateDashPayProfile(dashPayProfile: DashPayProfile) {
        dashPayProfileDao.insert(dashPayProfile)
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

    //
    // Step 5: Find the usernames in the case of recovery
    //
    fun recoverUsernames(blockchainIdentity: BlockchainIdentity) {
        blockchainIdentity.recoverUsernames()
    }

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

    private suspend fun sendTransaction(cftx: AssetLockTransaction): Boolean {
        log.info("Sending credit funding transaction: ${cftx.txId}")
        return suspendCoroutine { continuation ->
            cftx.getConfidence(walletApplication.wallet!!.context).addEventListener(object : TransactionConfidence.Listener {
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

    suspend fun getIdentityBalance(identifier: Identifier): CreditBalanceInfo {
        return withContext(Dispatchers.IO) {
            CreditBalanceInfo(platform.client.getIdentityBalance(identifier))
        }
    }
}
