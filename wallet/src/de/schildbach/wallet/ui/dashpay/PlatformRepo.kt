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
import de.schildbach.wallet.service.platform.IdentityKeyChainBackfill
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.service.platform.sdk.SdkProfileQueries
import de.schildbach.wallet.service.platform.sdk.SdkUsernameQueries
import de.schildbach.wallet.service.platform.sdk.SdkVotingQueries
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
import org.bitcoinj.crypto.ChildNumber
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
    val dashPayConfig: DashPayConfig,
    private val sdkUsernameQueries: SdkUsernameQueries,
    private val sdkVotingQueries: SdkVotingQueries,
    private val sdkProfileQueries: SdkProfileQueries,
    private val identityCreationStatus: IdentityCreationStatusHolder
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

        /**
         * How long a `registerIdentity` call may run before the
         * home-screen tile hints that the network is catching up —
         * see [registerIdentityWithSlowRegistrationHint]. A healthy
         * registration completes within seconds; dashj's internal
         * chain-lock retry waits ~2.5 minutes per attempt.
         */
        const val REGISTRATION_SLOW_HINT_DELAY_MS = 35_000L
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
        // Phase 3c (docs/kotlin-sdk-migration-plan.md): Kotlin-SDK read path
        // behind USE_KOTLIN_SDK_DPNS_READS (default off). Returns null when
        // the flag is off or on ANY SDK-path failure, falling through to the
        // unchanged dashj-platform path below.
        sdkUsernameQueries.getUsernameOrNull(username)?.let { return it }
        return try {
            val nameDocument = platform.names.get(Names.normalizeString(username))
            Resource.success(nameDocument)
        } catch (e: Exception) {
            Resource.error(e.localizedMessage!!, null)
        }
    }

    fun getVoteContenders(username: String): Contenders {
        return try {
            getVoteContendersOrThrow(username)
        } catch (e: Exception) {
            Contenders(Optional.empty(), mapOf(), 0, 0)
        }
    }

    /**
     * [getVoteContenders] with failures PROPAGATED instead of collapsed
     * into "no contenders" — for the availability check, which must fail
     * CLOSED (an empty result and a failed read mean different things
     * there: the latter must never enable the request button).
     */
    fun getVoteContendersOrThrow(username: String): Contenders {
        // Phase 3d (docs/kotlin-sdk-migration-plan.md): Kotlin-SDK read path
        // behind USE_KOTLIN_SDK_DPNS_READS (default off). Returns null when
        // the flag is off or on ANY SDK-path failure, falling through to the
        // unchanged dashj-platform path below.
        sdkVotingQueries.getVoteContendersOrNull(username)?.let { return it }
        val watch = Stopwatch.createStarted()
        val contenders = platform.names.getVoteContenders(Names.normalizeString(username))
        log.info("getVoteContenders took {}", watch)
        return contenders
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
                registerIdentityWithSlowRegistrationHint(blockchainIdentity, keyParameter)
                timer.logTiming() // we won't log timing for failed registrations
                identityCreationStatus.clear()
                return
            } catch (e: InvalidInstantAssetLockProofException) {
                // Per-attempt hint: no IS lock on the funding tx (yet) —
                // the tile shows "waiting for network confirmation" while
                // we wait out the retry delay.
                identityCreationStatus.setHint(identityRetryStatusHint(e))
                log.info("instantSendLock error: retry registerIdentity again ($i)")
                delay(3000)
            }
        }
        throw InvalidInstantAssetLockProofException("failed after 3 tries")
    }

    /**
     * Runs the (blocking) dashj registration with a status watchdog.
     *
     * dashj's `BlockchainIdentity.registerIdentity` retries the
     * chain-lock proof INTERNALLY — on "Asset Lock proof core chain
     * height N is higher than the current consensus core height M" it
     * waits for the next local block and tries again, swallowing every
     * per-attempt throwable, so app code never sees an error while it
     * loops (live incident: ~10 minutes of invisible retries). The only
     * app-side observable is duration, so once the call outlives
     * [REGISTRATION_SLOW_HINT_DELAY_MS] (a normal registration completes
     * in seconds; each dashj retry waits a whole ~2.5 min block) the
     * home-screen tile hint flips to "waiting for the network to catch
     * up".
     */
    private suspend fun registerIdentityWithSlowRegistrationHint(
        blockchainIdentity: BlockchainIdentity,
        keyParameter: KeyParameter?
    ) = coroutineScope {
        val slowHintWatchdog = launch {
            delay(REGISTRATION_SLOW_HINT_DELAY_MS)
            log.info("identity registration still running after {}ms — surfacing catch-up hint",
                REGISTRATION_SLOW_HINT_DELAY_MS)
            identityCreationStatus.setHint(RetryStatusHint.CORE_HEIGHT_LAG)
        }
        try {
            blockchainIdentity.registerIdentity(keyParameter, true, true)
        } finally {
            slowHintWatchdog.cancel()
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
            // Phase 3d (docs/kotlin-sdk-migration-plan.md): Kotlin-SDK read
            // path behind USE_KOTLIN_SDK_DPNS_READS (default off). A null
            // result means "flag off or SDK path failed" — fall through to
            // the unchanged dashj-platform query; Optional.empty() is the
            // SDK's definitive "no profile" (dashj parity: get returns null).
            val sdkProfileDocument = sdkProfileQueries.getProfileDocumentOrNull(userId)
            var profileDocument = if (sdkProfileDocument != null) {
                sdkProfileDocument.orElse(null)
            } else {
                platform.profiles.get(userId)
            }
            if (profileDocument == null) {
                // Tolerant fetch: this identity may be the wallet's own (or a
                // peer's) contract-bound-key identity, whose shape the legacy
                // CBOR cache cannot serialize; getIdentity recovers it uncached.
                val identity = platform.getIdentity(userId)
                if (identity != null) {
                    profileDocument =
                        platform.profiles.createProfileDocument("", "", "", null, null, identity)
                } else {
                    // there is no existing identity, so do nothing
                    return false
                }
            }
            // Phase 3e: domain documents by owner via the Kotlin SDK behind
            // the same read flag. Null = flag off or SDK path failed — fall
            // through to the unchanged dashj-platform query.
            val nameDocuments = sdkUsernameQueries.getDomainDocumentsByOwnerOrNull(userId)
                ?: platform.names.getByOwnerId(userId)

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

    /**
     * Ensures the BLOCKCHAIN_IDENTITY authentication key chain has ISSUED every key that
     * [identity] registered from that chain, so that legacy dashj-platform signing
     * (`WalletSignerCallback.sign` → `AuthenticationKeyChain.findKeyFromPubKey`) can resolve
     * the private key. The identity chain has lookahead 0, so only issued keys are findable
     * by public key.
     *
     * Identities created by the legacy dashj flow issue their keys at registration and this is
     * a no-op for them. Identities created by the Kotlin SDK (canonical 4-key set, derivation
     * index == keyId) arrive via the restore path with zero issued keys, and without this
     * backfill every legacy-path signature (contact request send/accept, profile
     * create/update) fails with "signer callback returned 0".
     *
     * The import mechanics mirror the encrypted-wallet fallback inside the legacy
     * `BlockchainIdentity.privateKeyAtIndex`: decrypt the chain's watching (account) key,
     * derive the hardened child, re-encrypt it against the watching key and add it via
     * `AuthenticationGroupExtension.addNewKey` (which imports it into the chain's basic key
     * chain and persists the wallet).
     *
     * Never throws; failures are logged and the number of keys issued so far is returned.
     *
     * @return the number of keys that were newly issued (0 if nothing was missing)
     */
    fun ensureIdentityChainKeys(identity: Identity?, keyParameter: KeyParameter?): Int {
        identity ?: return 0
        var issued = 0
        try {
            val authExtension = authenticationGroupExtension ?: return 0
            val identityChain = authExtension.getKeyChain(
                AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY
            ) ?: return 0
            val wallet = walletApplication.wallet ?: return 0

            val watchingKey = identityChain.watchingKey
            val decryptedParent = if (wallet.isEncrypted) {
                val keyCrypter = wallet.keyCrypter ?: return 0
                if (keyParameter == null) {
                    log.warn("ensureIdentityChainKeys: wallet is encrypted but no key was provided")
                    return 0
                }
                watchingKey.decrypt(keyCrypter, keyParameter) as IDeterministicKey
            } else {
                watchingKey
            }

            val derivedKeys = hashMapOf<Int, IDeterministicKey>()
            fun derive(index: Int): IDeterministicKey = derivedKeys.getOrPut(index) {
                decryptedParent.deriveChildKey(ChildNumber(index, true))
            }

            val indexes = IdentityKeyChainBackfill.indexesToIssue(
                identity.publicKeys.map { IdentityKeyChainBackfill.IdentityKeyRef(it.id, it.data) },
                derivePublicKey = { index ->
                    try {
                        derive(index).pubKey
                    } catch (e: Exception) {
                        log.warn("ensureIdentityChainKeys: cannot derive identity key at index $index", e)
                        null
                    }
                },
                isIssued = { pubKey -> identityChain.findKeyFromPubKey(pubKey) != null }
            )

            indexes.forEach { index ->
                var key = derive(index)
                if (wallet.isEncrypted) {
                    key = key.encrypt(wallet.keyCrypter, keyParameter, watchingKey)
                }
                authExtension.addNewKey(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY, key)
                issued++
            }
            if (issued > 0) {
                log.info(
                    "ensureIdentityChainKeys: issued {} missing identity chain key(s) at index(es) {} " +
                        "for identity {}",
                    issued,
                    indexes,
                    identity.id
                )
            }
        } catch (e: Exception) {
            log.error("ensureIdentityChainKeys: failed after issuing $issued key(s)", e)
        }
        return issued
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
