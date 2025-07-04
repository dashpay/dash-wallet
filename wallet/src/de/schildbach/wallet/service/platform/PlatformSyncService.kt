/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.service.platform

import android.app.ActivityManager
import android.content.Intent
import android.text.format.DateUtils
import com.google.common.base.Preconditions
import com.google.common.base.Stopwatch
import com.google.common.util.concurrent.SettableFuture
import com.google.zxing.BarcodeFormat
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.dao.TransactionMetadataChangeCacheDao
import de.schildbach.wallet.database.dao.TransactionMetadataDocumentDao
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.dao.UsernameVoteDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.TransactionMetadataCacheItem
import de.schildbach.wallet.database.entity.TransactionMetadataDocument
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.livedata.SeriousError
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.BlockchainService
import de.schildbach.wallet.service.BlockchainServiceImpl
import de.schildbach.wallet.service.platform.work.RestoreIdentityOperation
import de.schildbach.wallet.ui.dashpay.OnContactsUpdated
import de.schildbach.wallet.ui.dashpay.OnPreBlockProgressListener
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.PreBlockStage
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.more.TxMetadataSaveFrequency
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.coinjoin.utils.CoinJoinTransactionType
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.evolution.EvolutionContact
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.transactions.TransactionCategory
import org.dash.wallet.common.transactions.filters.TransactionFilter
import org.dash.wallet.common.util.TickerFlow
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dashj.platform.contracts.wallet.TxMetadataDocument
import org.dashj.platform.dashpay.ContactRequest
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.voting.ContestedDocumentResourceVotePoll
import org.dashj.platform.sdk.platform.DomainDocument
import org.dashj.platform.wallet.IdentityVerify
import org.dashj.platform.wallet.TxMetadataItem
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MarkerFactory
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

interface PlatformSyncService {
    fun init()
    suspend fun initSync(runFirstUpdateBlocking: Boolean = false)
    fun resume()
    suspend fun shutdown()

    fun updateSyncStatus(stage: PreBlockStage)
    fun preBlockDownload(future: SettableFuture<Boolean>)

    suspend fun updateContactRequests(initialSync: Boolean = false)
    fun postUpdateBloomFilters()
    suspend fun updateUsernameRequestsWithVotes()
    suspend fun updateUsernameRequestWithVotes(username: String)
    suspend fun checkUsernameVotingStatus()

    fun addContactsUpdatedListener(listener: OnContactsUpdated)
    fun removeContactsUpdatedListener(listener: OnContactsUpdated?)
    fun fireContactsUpdatedListeners()

    suspend fun triggerPreBlockDownloadComplete()

    fun addPreBlockProgressListener(listener: OnPreBlockProgressListener)
    fun removePreBlockProgressListener(listener: OnPreBlockProgressListener)

    suspend fun clearDatabases()
    suspend fun getUnsavedTransactions(): Pair<List<Transaction>, Long>
    suspend fun hasPendingTxMetadataToSave(): Boolean
}

class PlatformSynchronizationService @Inject constructor(
    private val platform: PlatformService,
    private val platformRepo: PlatformRepo,
    private val analytics: AnalyticsService,
    private val config: DashPayConfig,
    private val walletApplication: WalletApplication,
    private val transactionMetadataProvider: TransactionMetadataProvider,
    private val transactionMetadataChangeCacheDao: TransactionMetadataChangeCacheDao,
    private val transactionMetadataDocumentDao: TransactionMetadataDocumentDao,
    private val blockchainIdentityDataDao: BlockchainIdentityConfig,
    private val dashPayProfileDao: DashPayProfileDao,
    private val dashPayContactRequestDao: DashPayContactRequestDao,
    private val dashPayConfig: DashPayConfig,
    private val giftCardDao: GiftCardDao,
    private val invitationsDao: InvitationsDao,
    private val usernameRequestDao: UsernameRequestDao,
    private val usernameVoteDao: UsernameVoteDao,
    private val identityConfig: BlockchainIdentityConfig,
    private val topUpRepository: TopUpRepository,
    private val walletDataProvider: WalletDataProvider,
) : PlatformSyncService {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(PlatformSynchronizationService::class.java)
        private val random = Random(System.currentTimeMillis())

        val UPDATE_TIMER_DELAY = 15.seconds
        val PUSH_PERIOD = if (BuildConfig.DEBUG) 3.minutes else 3.hours
        val WEEKLY_PUSH_PERIOD = 7.days
        val CUTOFF_MIN = if (BuildConfig.DEBUG) 3.minutes else 3.hours
        val CUTOFF_MAX = if (BuildConfig.DEBUG) 6.minutes else 6.hours
        private val PUBLISH = MarkerFactory.getMarker("PUBLISH")
    }

    private var platformSyncJob: Job? = null
    private var txMetadataJob: Job? = null
    private val updatingContacts = AtomicBoolean(false)
    private val preDownloadBlocks = AtomicBoolean(false)
    private var preDownloadBlocksFuture: SettableFuture<Boolean>? = null

    private val onContactsUpdatedListeners = arrayListOf<OnContactsUpdated>()
    private val onPreBlockContactListeners = arrayListOf<OnPreBlockProgressListener>()
    private var lastPreBlockStage: PreBlockStage = PreBlockStage.None
    // TODO: cancel these on shutdown?
    private val syncJob = SupervisorJob()
    private val syncScope = CoroutineScope(Dispatchers.IO + syncJob)

    override fun init() {
        syncScope.launch { platformRepo.init() }
        log.info("Starting the platform sync job")
    }

    override fun resume() {
        // This method may not be required.  initSync must be called by PreBlockDownload handler
    }

    override suspend fun initSync(runFirstUpdateBlocking: Boolean) {
        if (runFirstUpdateBlocking) {
            updateContactRequests(true)
        }
        platformSyncJob = TickerFlow(UPDATE_TIMER_DELAY)
            .onEach { updateContactRequests() }
            .launchIn(syncScope)

        txMetadataJob = TickerFlow(PUSH_PERIOD)
            .onEach {
                maybePublishChangeCache()
            }
            .launchIn(syncScope)
    }

    private suspend fun maybePublishChangeCache() {
        val saveSettings = dashPayConfig.getTransactionMetadataSettings()
        if (!saveSettings.saveToNetwork) {
            return
        }
        val lastPush = config.get(DashPayConfig.LAST_METADATA_PUSH) ?: 0
        // maybe we don't need this
        // val lastTransactionTime = transactionMetadataChangeCacheDao.lastTransactionTime()
        val txIds = transactionMetadataChangeCacheDao.getAllTransactionIds()
        val now = System.currentTimeMillis()
        val everythingBeforeTimestamp = random.nextLong(
            now - CUTOFF_MAX.inWholeMilliseconds,
            now - CUTOFF_MIN.inWholeMilliseconds
        ) // Choose cutoff time between 3 and 6 hours ago

        // ensure that CUTOFF_MIN has elapsed since one or more tx timestamps with new metadata
        val timeStamps = txIds.map {
            transactionMetadataProvider.getTransactionMetadata(it)?.timestamp ?: Long.MAX_VALUE
        }.sortedByDescending { it }

        var newDataItems = txIds.size
        var newEverythingBeforeTimestamp = everythingBeforeTimestamp
        for (timestamp in timeStamps) {
            if (timestamp < everythingBeforeTimestamp) {
                newEverythingBeforeTimestamp = timestamp + 1
                break
            } else {
                newDataItems--
            }
        }
        log.info("maybe publish $newDataItems of ${txIds.size} with timestamps ${timeStamps.map { Date(it) } } < ${Date(newEverythingBeforeTimestamp)}")

        // determine how many transactions meet the cut off time
        // val newDataItems = transactionMetadataChangeCacheDao.countTransactions(newEverythingBeforeTimestamp)

        val meetsSaveFrequency = when (saveSettings.saveFrequency) {
            TxMetadataSaveFrequency.afterTenTransactions -> newDataItems >= 10
            TxMetadataSaveFrequency.afterEveryTransaction -> newDataItems >= 1
            TxMetadataSaveFrequency.oncePerWeek -> lastPush < now - WEEKLY_PUSH_PERIOD.inWholeMilliseconds && newDataItems >= 1
        }
        // publish no more frequently than every 3 hours
        val shouldPushToNetwork = (lastPush < now - PUSH_PERIOD.inWholeMilliseconds)
        if (shouldPushToNetwork && meetsSaveFrequency) {
            log.info("maybe publish meets requirements")
            publishChangeCache(newEverythingBeforeTimestamp, saveAll = false)
        } else {
            log.info("last platform push was less than $CUTOFF_MIN ago, skipping")
        }
    }

    override suspend fun shutdown() {
        if (platformSyncJob != null && platformRepo.hasBlockchainIdentity) {
            Preconditions.checkState(platformSyncJob!!.isActive)
            log.info("Shutting down the platform sync job")
            syncScope.coroutineContext.cancelChildren(CancellationException("shutdown the platform sync"))
            platformSyncJob!!.cancel(null)
            platformSyncJob = null
        }
        if (txMetadataJob != null && platformRepo.hasIdentity()) {
            if (txMetadataJob!!.isActive) {
                log.info("Shutting down the txmetdata publish job")
                syncScope.coroutineContext.cancelChildren(CancellationException("shutdown the platform sync"))
                txMetadataJob!!.cancel(null)
            }
            txMetadataJob = null
        }
    }

    var counterForReport = 0

    /**
     * updateContactRequests will fetch new Contact Requests from the network
     * and verify that we have all requests and profiles in the local database
     *
     * This method should not use blockchainIdentity because in some cases
     * when the app starts, it has not yet been initialized
     */
    override suspend fun updateContactRequests(initialSync: Boolean) {

        // if there is no wallet or identity, then skip the remaining steps of the update
        if (!platformRepo.hasBlockchainIdentity || walletApplication.wallet == null) {
            return
        }

        // only allow this method to execute once at a time
        if (updatingContacts.get()) {
            log.info("updateContactRequests is already running: {}", lastPreBlockStage)
            return
        }

        if (!platform.hasApp("dashpay")) {
            log.info("update contacts not completed because there is no dashpay contract")
            return
        }

        try {
            val blockchainIdentityData = blockchainIdentityDataDao.load() ?: return
            if (blockchainIdentityData.creationState < BlockchainIdentityData.CreationState.DONE) {
                // Is the Voting Period complete?
                if (blockchainIdentityData.creationState == BlockchainIdentityData.CreationState.VOTING) {
                    val timeWindow = UsernameRequest.VOTING_PERIOD_MILLIS
                    val votingPeriodStart = blockchainIdentityData.votingPeriodStart ?: 0L
                    if (System.currentTimeMillis() - votingPeriodStart >= timeWindow) {
                        val resource = platformRepo.getUsername(blockchainIdentityData.username!!)
                        if (resource.status == Status.SUCCESS && resource.data != null) {
                            val domainDocument = DomainDocument(resource.data)
                            if (domainDocument.dashUniqueIdentityId == blockchainIdentityData.identity?.id) {
                                blockchainIdentityData.creationState =
                                    BlockchainIdentityData.CreationState.DONE_AND_DISMISS
                                platformRepo.updateBlockchainIdentityData(blockchainIdentityData)
                            }
                        }
                    }
                }
                log.info("update contacts not completed username registration/recovery is not complete")
                // if username creation or request is not complete, then allow the sync process to finish
                if (preDownloadBlocks.get()) {
                    finishPreBlockDownload()
                }
                return
            }

            if (blockchainIdentityData.username == null || blockchainIdentityData.userId == null) {
                return // this is here because the wallet is being reset without removing blockchainIdentityData
            }

            val userId = blockchainIdentityData.userId!!

            val userIdList = HashSet<String>()
            val watch = Stopwatch.createStarted()
            var addedContact = false
            Context.propagate(platformRepo.walletApplication.wallet!!.context)

            val lastContactRequestTime = if (dashPayContactRequestDao.countAllRequests() > 0) {
                val lastTimeStamp = dashPayContactRequestDao.getLastTimestamp()
                // if the last contact request was received in the past 10 minutes, then query for
                // contact requests that are 10 minutes before it.  If the last contact request was
                // more than 10 minutes ago, then query all contact requests that came after it.
                if (lastTimeStamp < System.currentTimeMillis() - DateUtils.MINUTE_IN_MILLIS * 10) {
                    lastTimeStamp
                } else {
                    lastTimeStamp - DateUtils.MINUTE_IN_MILLIS * 10
                }
            } else {
                0L
            }

            updatingContacts.set(true)
            updateSyncStatus(PreBlockStage.Starting)
            updateSyncStatus(PreBlockStage.Initialization)
            checkDatabaseIntegrity(userId)

            updateSyncStatus(PreBlockStage.FixMissingProfiles)

            // Get all out our contact requests
            val toContactDocuments = platform.contactRequests.get(
                userId,
                toUserId = false,
                afterTime = lastContactRequestTime,
                retrieveAll = true
            )
            toContactDocuments.forEach {
                val contactRequest = ContactRequest(it)
                log.info("found accepted/sent request: ${contactRequest.toUserId}")
                val dashPayContactRequest = DashPayContactRequest.fromDocument(contactRequest)
                if (!dashPayContactRequestDao.exists(
                        dashPayContactRequest.userId,
                        dashPayContactRequest.toUserId,
                        contactRequest.accountReference
                    )
                ) {
                    log.info("adding accepted/send request to database: ${contactRequest.toUserId}")
                    userIdList.add(dashPayContactRequest.toUserId)
                    dashPayContactRequestDao.insert(dashPayContactRequest)

                    // add our receiving from this contact keychain if it doesn't exist
                    addedContact = checkAndAddSentRequest(userId, contactRequest) || addedContact
                    log.info("contactRequest: added sent request from ${contactRequest.toUserId}")
                }
            }
            updateSyncStatus(PreBlockStage.GetReceivedRequests)
            // Get all contact requests where toUserId == userId, the users who have added me
            val fromContactDocuments = platform.contactRequests.get(
                userId,
                toUserId = true,
                afterTime = lastContactRequestTime,
                retrieveAll = true
            )
            fromContactDocuments.forEach {
                val dashPayContactRequest = DashPayContactRequest.fromDocument(it)
                val contactRequest = ContactRequest(it)
                log.info("found received request: ${dashPayContactRequest.userId}")
                platform.stateRepository.addValidIdentity(dashPayContactRequest.userIdentifier)
                if (!dashPayContactRequestDao.exists(
                        dashPayContactRequest.userId,
                        dashPayContactRequest.toUserId,
                        dashPayContactRequest.accountReference
                    )
                ) {
                    log.info("adding received request: ${dashPayContactRequest.userId} to database")
                    userIdList.add(dashPayContactRequest.userId)
                    dashPayContactRequestDao.insert(dashPayContactRequest)

                    // add the sending to contact keychain if it doesn't exist
                    addedContact = checkAndAddReceivedRequest(userId, contactRequest) || addedContact
                    log.info("contactRequest: added received request from ${contactRequest.ownerId}")
                }
            }
            updateSyncStatus(PreBlockStage.GetSentRequests)

            if (!initialSync) {
                // If new keychains were added to the wallet, then update the bloom filters
                if (addedContact) {
                    postUpdateBloomFilters()
                }

                // obtain profiles from new contacts
                if (userIdList.isNotEmpty()) {
                    updateContactProfiles(userIdList.toList(), 0L)
                }

                updateSyncStatus(PreBlockStage.GetNewProfiles)

                coroutineScope {
                    awaitAll(
                        // fetch updated invitations
                        async { updateInvitations() },
                        // fetch updated transaction metadata
                        async { updateTransactionMetadata() },  // TODO: this is skipped in VOTING state, but shouldn't be
                        // fetch updated profiles from the network
                        async { updateContactProfiles(userId, lastContactRequestTime) },
                        // check for unused topups
                        async { checkTopUps() }
                    )
                }

                updateSyncStatus(PreBlockStage.GetUpdatedProfiles)
            }
            // fire listeners if there were new contacts
            if (addedContact) {
                fireContactsUpdatedListeners()
            }

            updateSyncStatus(PreBlockStage.Complete)

            log.info("updating contacts and profiles took $watch")
        } catch (_: CancellationException) {
            log.info("updating contacts canceled")
        } catch (e: Exception) {
            log.error(platformRepo.formatExceptionMessage("error updating contacts", e))
        } finally {
            updatingContacts.set(false)

            counterForReport++
            if (counterForReport % 8 == 0) {
                // record the report to the logs every 2 minutes
                log.info(platform.client.reportNetworkStatus())
            }
        }
        // This needs to be here to ensure that the pre-block download stage always completes
        // This block used to be the above finally block, but was moved here to fix some issues
        if (preDownloadBlocks.get()) {
            finishPreBlockDownload()
        }
    }

    override fun updateSyncStatus(stage: PreBlockStage) {
        log.info("updateSyncStatus: ${stage.name}")
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

    private fun checkAndAddSentRequest(
        userId: String,
        contactRequest: ContactRequest,
        encryptionKey: KeyParameter? = null
    ): Boolean {
        val contact = EvolutionContact(userId, contactRequest.toUserId.toString())
        try {
            if (!platformRepo.walletApplication.wallet!!.hasReceivingKeyChain(contact)) {
                Context.propagate(walletApplication.wallet!!.context)
                log.info("adding accepted/send request to wallet: ${contactRequest.toUserId}")
                val contactIdentity = platform.identities.get(contactRequest.toUserId)
                var myEncryptionKey = encryptionKey
                if (encryptionKey == null && platformRepo.walletApplication.wallet!!.isEncrypted) {
                    val password = try {
                        // always create a SecurityGuard when it is required
                        val securityGuard = SecurityGuard()
                        securityGuard.retrievePassword()
                    } catch (e: IllegalArgumentException) {
                        log.error("There was an error retrieving the wallet password", e)
                        analytics.logError(e, "There was an error retrieving the wallet password")
                        platformRepo.fireSeriousErrorListeners(SeriousError.MissingEncryptionIV)
                        null
                    } ?: return false
                    // Don't bother with DeriveKeyTask here, just call deriveKey
                    myEncryptionKey =
                        platformRepo.walletApplication.wallet!!.keyCrypter!!.deriveKey(password)
                }
                platformRepo.blockchainIdentity.addPaymentKeyChainFromContact(
                    contactIdentity!!,
                    contactRequest,
                    myEncryptionKey!!
                )
                return true
            }
        } catch (e: KeyCrypterException) {
            // we can't send payments to this contact due to an invalid encryptedPublicKey
            log.info("ContactRequest: error ${e.message}", e)
        } catch (e: Exception) {
            platformRepo.formatExceptionMessage("check and add sent requests: error", e)
        }
        return false
    }

    private fun checkAndAddReceivedRequest(
        userId: String,
        contactRequest: ContactRequest,
        encryptionKey: KeyParameter? = null
    ): Boolean {
        // add the sending to contact keychain if it doesn't exist
        val contact = EvolutionContact(
            userId,
            0,
            contactRequest.ownerId.toString(),
            contactRequest.accountReference
        )
        try {
            if (!platformRepo.walletApplication.wallet!!.hasSendingKeyChain(contact)) {
                log.info("adding received request: ${contactRequest.ownerId} to wallet")
                val contactIdentity = platform.identities.get(contactRequest.ownerId)
                var myEncryptionKey = encryptionKey
                if (encryptionKey == null && platformRepo.walletApplication.wallet!!.isEncrypted) {
                    val password = try {
                        // always create a SecurityGuard when it is required
                        val securityGuard = SecurityGuard()
                        securityGuard.retrievePassword()
                    } catch (e: IllegalArgumentException) {
                        log.error("There was an error retrieving the wallet password", e)
                        analytics.logError(e, "There was an error retrieving the wallet password")
                        platformRepo.fireSeriousErrorListeners(SeriousError.MissingEncryptionIV)
                        null
                    } ?: return false
                    // Don't bother with DeriveKeyTask here, just call deriveKey
                    myEncryptionKey =
                        platformRepo.walletApplication.wallet!!.keyCrypter!!.deriveKey(password)
                }
                platformRepo.blockchainIdentity.addPaymentKeyChainToContact(
                    contactIdentity!!,
                    contactRequest,
                    myEncryptionKey!!
                )
                return true
            }
        } catch (e: KeyCrypterException) {
            // we can't send payments to this contact due to an invalid encryptedPublicKey
            log.info("ContactRequest: error ${e.message}", e)
        } catch (e: Exception) {
            platformRepo.formatExceptionMessage("check and add received requests: error", e)
        }
        return false
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
                    platformRepo.updateDashPayProfile(identity.id.toString())
                    invitation.acceptedAt = System.currentTimeMillis()
                    platformRepo.updateInvitation(invitation)
                }
            }
        }
    }

    /**
     * Fetches updated profiles associated with contacts of userId after lastContactRequestTime
     */
    private suspend fun updateContactProfiles(userId: String, lastContactRequestTime: Long) {
        val watch = Stopwatch.createStarted()
        val userIdSet = hashSetOf<String>()

        val toContactDocuments = dashPayContactRequestDao.loadToOthers(userId)
        toContactDocuments.forEach {
            userIdSet.add(it.toUserId)
        }
        val fromContactDocuments = dashPayContactRequestDao.loadFromOthers(userId)
        fromContactDocuments.forEach {
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
    private suspend fun updateContactProfiles(
        userIdList: List<String>,
        lastContactRequestTime: Long,
        checkingIntegrity: Boolean = false
    ) {
        try {
            if (userIdList.isNotEmpty()) {
                val identifierList = userIdList.map { Identifier.from(it) }
                val profileDocuments = platform.profiles.getList(
                    identifierList,
                    lastContactRequestTime
                )
                val profileById = profileDocuments.associateBy({ it.ownerId }, { it })

                val nameDocuments = platform.names.getList(identifierList).map { DomainDocument(it) }
                val nameById =
                    nameDocuments.associateBy({ platformRepo.getIdentityForName(it) }, { it })

                for (id in profileById.keys) {
                    if (nameById.containsKey(id)) {
                        val nameDocument = nameById[id]!! // what happens if there is no username for the identity? crash
                        val username = nameDocument.label
                        val identityId = platformRepo.getIdentityForName(nameDocument)

                        val profileDocument = profileById[id]

                        val profile = if (profileDocument != null) {
                            DashPayProfile.fromDocument(profileDocument, username)
                        } else {
                            DashPayProfile(identityId.toString(), username)
                        }

                        dashPayProfileDao.insert(profile)
                        if (checkingIntegrity) {
                            log.info("check database integrity: adding missing profile $username:$id")
                        }
                    } else {
                        log.info("domain document for $id could not be found, though a profile exists")
                    }
                }

                // add a blank profile for any identity that is still missing a profile
                if (lastContactRequestTime == 0L) {
                    val remainingMissingProfiles = userIdList.filter {
                        !profileById.containsKey(
                            Identifier.from(it)
                        )
                    }
                    for (identityId in remainingMissingProfiles) {
                        val nameDocument = nameById[Identifier.from(identityId)]
                        // what happens if there is no username for the identity? crash
                        if (nameDocument != null) {
                            val username = nameDocument.label
                            val identityIdForName = platformRepo.getIdentityForName(nameDocument)
                            dashPayProfileDao.insert(
                                DashPayProfile(
                                    identityIdForName.toString(),
                                    username
                                )
                            )
                        } else {
                            log.info("no username found for $identityId")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            platformRepo.formatExceptionMessage("update contact profiles", e)
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
            toContactDocuments.forEach {
                userIdList.add(it.toUserId)
                toContactMap[it.toUserId] = it

                // check to see if wallet has this contact request's keys
                val added = checkAndAddSentRequest(userId, it.toContactRequest(platform.platform))
                if (added) {
                    log.warn(
                        "check database integrity: added sent $it to wallet since it was missing.  " +
                            "Transactions may also be missing"
                    )
                    addedContactRequests = true
                }
            }
            // Get all contact requests where toUserId == userId, the users who have added me
            val fromContactDocuments = dashPayContactRequestDao.loadFromOthers(userId)
            val fromContactMap = HashMap<String, DashPayContactRequest>()
            fromContactDocuments.forEach {
                userIdList.add(it.userId)
                fromContactMap[it.userId] = it

                // check to see if wallet has this contact request's keys
                val added = checkAndAddReceivedRequest(userId, it.toContactRequest(platform.platform))
                if (added) {
                    log.warn("check database integrity: added received $it to wallet since it was missing")
                    addedContactRequests = true
                }
            }

            // If new keychains were added to the wallet, then update the bloom filters
            if (addedContactRequests) {
                postUpdateBloomFilters()
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
            platformRepo.formatExceptionMessage("check database integrity", e)
        } finally {
            log.info("check database integrity complete in $watch")
        }
    }

    override fun postUpdateBloomFilters() {
        MainScope().launch {
            updateBloomFilters()
        }
    }

    private suspend fun updateTransactionMetadata() {
        if (!Constants.SUPPORTS_TXMETADATA) {
            return
        }
        val watch = Stopwatch.createStarted()
        val myEncryptionKey = platformRepo.getWalletEncryptionKey()

        val lastTxMetadataRequestTime = if (transactionMetadataDocumentDao.countAllRequests() > 0) {
            val lastTimeStamp = transactionMetadataDocumentDao.getLastTimestamp()
            // if the last txmetadata document was received in the past 10 minutes, then query for
            // documents that are 10 minutes before it.  If the tx metadata documented was
            // more than 10 minutes ago, then query all metadata documents that came after it.
            if (lastTimeStamp < System.currentTimeMillis() - DateUtils.MINUTE_IN_MILLIS * 10) {
                lastTimeStamp
            } else {
                lastTimeStamp - DateUtils.MINUTE_IN_MILLIS * 10
            }
        } else {
            0L
        }

        log.info("fetching TxMetadataDocuments from {}", lastTxMetadataRequestTime)

        val items = platformRepo.blockchainIdentity
            .getTxMetaData(lastTxMetadataRequestTime, myEncryptionKey)

        if (items.isEmpty()) {
            return
        }

        // val lastItem = items.keys.last()
        // lastItem.createdAt?.let {
        //    configuration.txMetadataUpdateTime = it
        // }
        log.info("processing TxMetadataDocuments: {}", items.toString())

        items.forEach { (doc, list) ->
            if (transactionMetadataDocumentDao.count(doc.id) == 0) {
                val timestamp = doc.updatedAt!!
                log.info("processing TxMetadata: ${doc.id} with ${list.size} items")
                list.forEach { metadata ->
                    if (metadata.isNotEmpty()) {
                        val cachedItems = transactionMetadataChangeCacheDao.findAfter(
                            Sha256Hash.wrap(metadata.txId),
                            timestamp
                        )
                        log.info(
                            "processing TxMetadata: found ${cachedItems.size} related items in this document"
                        )

                        // what if the updates from platform are older

                        // if not change the main table

                        // we need to find a new way -- how can we know that we should change something?
                        // should we save to the DB table?
                        val txIdAsHash = Sha256Hash.wrap(metadata.txId)
                        val metadataDocumentRecord = TransactionMetadataDocument(
                            doc.id,
                            doc.updatedAt!!,
                            txIdAsHash
                        )
                        val updatedMetadata = TransactionMetadata(txIdAsHash, 0, Coin.ZERO, TransactionCategory.Invalid)
                        var iconUrl: String? = null
                        val giftCard = GiftCard(txIdAsHash)

                        metadata.timestamp?.let { timestamp ->
                            metadataDocumentRecord.sentTimestamp = timestamp
                            log.info("processing TxMetadata: sent time stamp")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.sentTimestamp != null && it.sentTimestamp != timestamp
                                } == null
                            ) {
                                log.info("processing TxMetadata: service change: changing timestamp")
                                updatedMetadata.timestamp = timestamp
                            }
                        }
                        metadata.service?.let { service ->
                            metadataDocumentRecord.service = service
                            log.info("processing TxMetadata: service change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.service != null && it.service != service
                                } == null
                            ) {
                                log.info("processing TxMetadata: service change: changing service")
                                updatedMetadata.service = service
                            }
                        }
                        metadata.memo?.let { memo ->
                            metadataDocumentRecord.memo = memo
                            log.info(
                                "processing TxMetadata: memo change: {}",
                                cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.memo != null && it.memo != memo
                                }
                            )
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.memo != null && it.memo != memo
                                } == null
                            ) {
                                log.info("processing TxMetadata: memo change: changing memo")
                                updatedMetadata.memo = memo
                            }
                        }
                        metadata.taxCategory?.let { taxCategoryAsString ->
                            TaxCategory.fromValue(taxCategoryAsString)?.let { taxCategory ->
                                metadataDocumentRecord.taxCategory = taxCategory
                                log.info("processing TxMetadata: tax category change")
                                if (cachedItems.find {
                                        it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                            it.taxCategory != null && it.taxCategory?.name != taxCategoryAsString
                                    } == null
                                ) {
                                    log.info("processing TxMetadata: tax category change: changing category")
                                    updatedMetadata.taxCategory = taxCategory
                                }
                            }
                        }
                        if (metadata.exchangeRate != null && metadata.currencyCode != null) {
                            metadataDocumentRecord.rate = metadata.exchangeRate
                            metadataDocumentRecord.currencyCode = metadata.currencyCode

                            val prevItem = cachedItems.find {
                                it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                    it.currencyCode != null && it.rate != null &&
                                    (
                                        it.currencyCode != metadata.currencyCode ||
                                            it.rate != metadata.exchangeRate.toString()
                                        )
                            }
                            log.info("processing TxMetadata: exchange rate change change: $prevItem")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.currencyCode != null && it.rate != null &&
                                        (
                                            it.currencyCode != metadata.currencyCode ||
                                                it.rate != metadata.exchangeRate.toString()
                                            )
                                } == null
                            ) {
                                log.info("processing TxMetadata: exchange rate change change: setting rate")
                                updatedMetadata.currencyCode = metadata.currencyCode
                                updatedMetadata.rate = metadata.exchangeRate.toString()
                            }
                        }
                        metadata.customIconUrl?.let { url ->
                            metadataDocumentRecord.customIconUrl = url
                            log.info("processing TxMetadata: custom icon url change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.customIconUrl != null && it.customIconUrl != url
                                } == null
                            ) {
                                log.info("processing TxMetadata: custom icon url change: changing icon")
                                iconUrl = url
                            }
                        }
                        metadata.giftCardNumber?.let { number ->
                            metadataDocumentRecord.giftCardNumber = number
                            log.info("processing TxMetadata: gift card number change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.giftCardNumber != null && it.giftCardNumber != number
                                } == null
                            ) {
                                log.info("processing TxMetadata: gift card number change: changing number")
                                giftCard.number = number
                            }
                        }
                        metadata.giftCardPin?.let { pin ->
                            metadataDocumentRecord.giftCardPin = pin
                            log.info("processing TxMetadata: gift card pin change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.giftCardPin != null && it.giftCardPin != pin
                                } == null
                            ) {
                                log.info("processing TxMetadata: gift card pin change: changing pin")
                                giftCard.pin = pin
                            }
                        }
                        metadata.merchantName?.let { name ->
                            metadataDocumentRecord.merchantName = name
                            log.info("processing TxMetadata: merchant name change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.merchantName != null && it.merchantName != name
                                } == null
                            ) {
                                log.info("processing TxMetadata: merchant name change: changing name")
                                giftCard.merchantName = name
                            }
                        }
                        metadata.originalPrice?.let { price ->
                            metadataDocumentRecord.originalPrice = price
                            log.info("processing TxMetadata: gift card price change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.createdAt!! &&
                                        it.originalPrice != null && it.originalPrice != price
                                } == null
                            ) {
                                log.info("processing TxMetadata: gift card price change: changing price")
                                giftCard.price = price
                            }
                        }
                        metadata.barcodeValue?.let { barcodeValue ->
                            metadataDocumentRecord.barcodeValue = barcodeValue
                            log.info("processing TxMetadata: barcode value change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.barcodeValue != null && it.barcodeValue != barcodeValue
                                } == null
                            ) {
                                log.info("processing TxMetadata: barcode value change: changing value")
                                giftCard.barcodeValue = barcodeValue
                            }
                        }
                        metadata.barcodeFormat?.let { barcodeFormat ->
                            metadataDocumentRecord.barcodeFormat = barcodeFormat
                            log.info("processing TxMetadata: barcode format change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.barcodeFormat != null && it.barcodeFormat != barcodeFormat
                                } == null
                            ) {
                                log.info("processing TxMetadata: barcode value change: changing value")
                                try {
                                    giftCard.barcodeFormat = BarcodeFormat.valueOf(barcodeFormat)
                                } catch (e: IllegalArgumentException) {
                                    log.warn("Invalid barcode format: {}", barcodeFormat, e)
                                }
                            }
                        }
                        metadata.merchantUrl?.let { url ->
                            metadataDocumentRecord.merchantUrl = url
                            log.info("processing TxMetadata: merchant url change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.merchantUrl != null && it.merchantUrl != url
                                } == null
                            ) {
                                log.info("processing TxMetadata: merchant url change: changing url")
                                giftCard.merchantUrl = url
                            }
                        }

                        log.info("syncing metadata with platform updates: $updatedMetadata")
                        transactionMetadataProvider.syncPlatformMetadata(txIdAsHash, updatedMetadata, giftCard, iconUrl)
                        log.info("adding TxMetadataItem: {}", metadata)
                        transactionMetadataDocumentDao.insert(metadataDocumentRecord)
                    } else {
                        log.info("not adding TxMetadataItem: {} since it is empty", metadata)
                    }
                }

                // configuration.txMetadataUpdateTime = doc.createdAt!!
            } else {
                log.info("TxMetadataDocument:  this item already exists ${doc.id}")
            }
        }

        log.info("fetching ${items.size} tx metadata items in $watch")
    }

    private suspend fun publishTransactionMetadata(
        txMetadataItems: List<TransactionMetadataCacheItem>,
        progressListener: (suspend (Int) -> Unit)? = null
    ): Int {
        if (!platformRepo.hasBlockchainIdentity) {
            return 0
        }
        progressListener?.invoke(0)
        log.info(PUBLISH, txMetadataItems.joinToString("\n") { it.toString() })
        val metadataList = txMetadataItems.map {
            TxMetadataItem(
                it.txId.bytes,
                it.sentTimestamp,
                it.memo,
                it.rate?.toDouble(),
                it.currencyCode,
                it.taxCategory?.name?.lowercase(),
                it.service,
                it.customIconUrl,
                it.giftCardNumber,
                it.giftCardPin,
                it.merchantName,
                it.originalPrice,
                it.barcodeValue,
                it.barcodeFormat,
                it.merchantUrl
            )
        }
        progressListener?.invoke(10)
        val walletEncryptionKey = platformRepo.getWalletEncryptionKey()
        val keyIndex = transactionMetadataChangeCacheDao.count() + transactionMetadataDocumentDao.countAllRequests()
        platformRepo.blockchainIdentity.publishTxMetaData(
            metadataList,
            walletEncryptionKey,
            keyIndex,
            TxMetadataDocument.VERSION_PROTOBUF
        ) { progress ->
            syncScope.launch(Dispatchers.IO) {
                progressListener?.invoke(10 + progress * 90 / 100)
            }
        }
        return txMetadataItems.size
    }

    private fun mergeTransactionMetadataDocuments(txId: Sha256Hash, docs: List<TransactionMetadataDocument>): TransactionMetadataCacheItem {
        return TransactionMetadataCacheItem(
            cacheTimestamp = docs.lastOrNull()?.timestamp ?: 0,
            txId = txId,
            sentTimestamp = docs.lastOrNull { it.sentTimestamp != null }?.sentTimestamp,
            taxCategory = docs.lastOrNull { it.taxCategory != null }?.taxCategory,
            currencyCode = docs.lastOrNull { it.currencyCode != null }?.currencyCode,
            rate = docs.lastOrNull { it.rate != null }?.rate.toString(),
            memo = docs.lastOrNull { it.memo != null }?.memo,
            service = docs.lastOrNull { it.service != null }?.service,
            customIconUrl = docs.lastOrNull { it.customIconUrl != null }?.customIconUrl,
            giftCardNumber = docs.lastOrNull { it.giftCardNumber != null }?.giftCardNumber,
            giftCardPin = docs.lastOrNull { it.giftCardPin != null }?.giftCardPin,
            merchantName = docs.lastOrNull { it.merchantName != null }?.merchantName,
            originalPrice = docs.lastOrNull { it.originalPrice != null }?.originalPrice,
            barcodeValue = docs.lastOrNull { it.barcodeValue != null }?.barcodeValue,
            barcodeFormat = docs.lastOrNull { it.barcodeFormat != null }?.barcodeFormat,
            merchantUrl = docs.lastOrNull { it.merchantUrl != null }?.merchantUrl,
        )
    }

    override suspend fun hasPendingTxMetadataToSave(): Boolean {
        return transactionMetadataChangeCacheDao.count() > 0 || getUnsavedTransactions().first.isNotEmpty()
    }

    // this is a slow operation?
    override suspend fun getUnsavedTransactions(): Pair<List<Transaction>, Long> {
        val watch = Stopwatch.createStarted()
        val start = dashPayConfig.get(DashPayConfig.TRANSACTION_METADATA_LAST_PAST_SAVE) ?: 0L
        val end = System.currentTimeMillis()

        val notCoinJoinFilter = object : TransactionFilter {
            override fun matches(tx: Transaction): Boolean {
                val type = CoinJoinTransactionType.fromTx(tx, walletDataProvider.wallet)
                return type == CoinJoinTransactionType.None || type == CoinJoinTransactionType.Send
            }
        }
        val listOfUnsaved = arrayListOf<Transaction>()
        var firstUnsavedTxDate = 0L
        walletDataProvider.getTransactions(notCoinJoinFilter).forEach { tx ->
            if (tx.updateTime.time in start .. end) {
                if (!transactionMetadataProvider.exists(tx.txId)) {
                    listOfUnsaved.add(tx)
                    firstUnsavedTxDate = if (firstUnsavedTxDate != 0L) {
                        min(firstUnsavedTxDate, tx.updateTime.time)
                    } else {
                        tx.updateTime.time
                    }
                } else {
                    val previouslySavedItems = transactionMetadataDocumentDao.getTransactionMetadata(tx.txId)
                    val previouslySaved = mergeTransactionMetadataDocuments(tx.txId, previouslySavedItems)
                    val currentItem = transactionMetadataProvider.getTransactionMetadata(tx.txId)!!
                    val giftCard = giftCardDao.getCardForTransaction(tx.txId)

                    if (!previouslySaved.compare(currentItem, giftCard)) {
                        listOfUnsaved.add(tx)
                        firstUnsavedTxDate = if (firstUnsavedTxDate != 0L) {
                            min(firstUnsavedTxDate, tx.updateTime.time)
                        } else {
                            tx.updateTime.time
                        }
                    }
                }
            }
        }
        log.info("determining unsaved transactions: {}, {} txes", watch, listOfUnsaved.size)
        return Pair(listOfUnsaved, firstUnsavedTxDate)
    }

    suspend fun publishPastTxMetadata(progressListener: suspend (Int) -> Unit): TxMetadataSaveInfo {
        // determine any changes that haven't been saved before [DashPayConfig.TRANSACTION_METADATA_LAST_PAST_SAVE]
        val alreadySaved = dashPayConfig.get(DashPayConfig.TRANSACTION_METADATA_LAST_PAST_SAVE) ?: 0L
        // add to those changes to the change cache
        val txes = walletApplication.wallet?.getTransactions(true)
        var itemsToSave = 0
        txes?.forEachIndexed { i, tx ->
            if (tx.updateTime.time >= alreadySaved) {
                transactionMetadataProvider.getTransactionMetadata(tx.txId)?.let { metadata ->
                    val giftCard = giftCardDao.getCardForTransaction(tx.txId)

                    // make sure it is not already saved?

                    val previouslySaved = transactionMetadataDocumentDao.getTransactionMetadata(tx.txId)
                    log.info("publish: previously saved: {}", previouslySaved)

                    val saved = mergeTransactionMetadataDocuments(tx.txId, previouslySaved)
                    log.info("publish: merged saved: {}", saved)

                    val metadataItem = TransactionMetadataCacheItem(
                        metadata,
                        giftCard
                    )
                    log.info("publish: item: {}", metadataItem)
                    val diff = metadataItem - saved
                    log.info("publish: diff: {}", diff)
                    if (diff.isNotEmpty() && !transactionMetadataChangeCacheDao.has(diff)) {
                        transactionMetadataChangeCacheDao.insert(diff)
                    }
                    itemsToSave++
                }
                progressListener.invoke(i * 100 / txes.size / 2)
            }
        }
        // call publishChangeCache
        val itemsSaved = publishChangeCache(System.currentTimeMillis(), saveAll = true) { progress ->
            progressListener.invoke(50 + progress / 2)
        }
        return itemsSaved
    }

    private suspend fun publishChangeCache(before: Long, saveAll: Boolean, progressListener: (suspend (Int) -> Unit)? = null): TxMetadataSaveInfo {
        if (!Constants.SUPPORTS_TXMETADATA) {
            return TxMetadataSaveInfo.NONE
        }
        log.info("publishing updates to tx metadata items before $before")
        val itemsToPublish = hashMapOf<Sha256Hash, TransactionMetadataCacheItem>()
        val changedItems = transactionMetadataChangeCacheDao.getCachedItemsBefore(before)

        if (changedItems.isEmpty()) {
            log.info("no tx metadata changes before this time")
            return TxMetadataSaveInfo.NONE
        }
        val saveSettings = dashPayConfig.getTransactionMetadataSettings()

        log.info("preparing to [publish] ${changedItems.size} tx metadata changes to platform")

        for (changedItem in changedItems) {
            if (itemsToPublish.containsKey(changedItem.txId)) {
                val item = itemsToPublish[changedItem.txId]!!

                if (saveSettings.shouldSavePrivateMemos(saveAll)) {
                    changedItem.memo?.let { memo ->
                        item.memo = memo
                    }
                }
                if (saveSettings.shouldSaveExchangeRates(saveAll)) {
                    if (changedItem.rate != null && changedItem.currencyCode != null) {
                        item.rate = changedItem.rate
                        item.currencyCode = changedItem.currencyCode
                    }
                    changedItem.sentTimestamp?.let { timestamp ->
                        item.sentTimestamp = timestamp
                    }
                }
                if (saveSettings.shouldSaveTaxCategory(saveAll)) {
                    changedItem.taxCategory?.let { taxCategory ->
                        item.taxCategory = taxCategory
                    }
                }
                if (saveSettings.shouldSavePaymentCategory(saveAll)) {
                    changedItem.service?.let { service ->
                        item.service = service
                    }
                }
                if (saveSettings.shouldSaveGiftcardInfo(saveAll)) {
                    changedItem.customIconUrl?.let { customIconUrl ->
                        item.customIconUrl = customIconUrl
                    }
                    changedItem.giftCardNumber?.let { giftCardNumber ->
                        item.giftCardNumber = giftCardNumber
                    }
                    changedItem.giftCardPin?.let { giftCardPin ->
                        item.giftCardPin = giftCardPin
                    }
                    changedItem.merchantName?.let { merchantName ->
                        item.merchantName = merchantName
                    }
                    changedItem.originalPrice?.let { originalPrice ->
                        item.originalPrice = originalPrice
                    }
                    changedItem.barcodeValue?.let { barcodeValue ->
                        item.barcodeValue = barcodeValue
                    }
                    changedItem.barcodeFormat?.let { barcodeFormat ->
                        item.barcodeFormat = barcodeFormat
                    }
                    changedItem.merchantUrl?.let { merchantUrl ->
                        item.merchantUrl = merchantUrl
                    }
                }
            } else {
                itemsToPublish[changedItem.txId] = changedItem
            }
        }
        progressListener?.invoke(10)
        var itemsSaved = 0
        var itemsToSave = changedItems.size
        try {
            log.info("publishing ${itemsToPublish.values.size} tx metadata items to platform")

            // publish non-empty items
            publishTransactionMetadata(itemsToPublish.values.filter { it.isNotEmpty() }) {
                progressListener?.invoke(10 + it * 90 / 100)
            }
            log.info("published ${itemsToPublish.values.size} tx metadata items to platform")

            // clear out published items from the cache table
            log.info("published and remove ${changedItems.map { it.id }} tx metadata items from cache")
            transactionMetadataChangeCacheDao.removeByIds(changedItems.map { it.id })
            config.set(DashPayConfig.LAST_METADATA_PUSH, System.currentTimeMillis())
            itemsSaved = changedItems.size

            updateTransactionMetadata()
        } catch (_: CancellationException) {
            log.info("publishing updates canceled")
        } catch (e: Exception) {
            log.error("publishing exception caught", e)
        }

        log.info("publishing updates to tx metadata items complete")
        return TxMetadataSaveInfo(itemsSaved, itemsToSave)
    }

    // uses get_vote_polls to get active vote polls, but must check remaining
    // items in the username_requests table and remove them
    override suspend fun updateUsernameRequestsWithVotes() {
        checkUsernameVotingStatus()
        log.info("updateUsernameRequestsWithVotes starting")
        try {
            log.info("updateUsernameRequestsWithVotes: getCurrentVotePolls start")
            val votePolls = platform.platform.names.getCurrentVotePolls()
            log.info("updateUsernameRequestsWithVotes: getCurrentVotePolls end")
            // usernameRequestDao.clear()
            // val myIdentifier = platformRepo.blockchainIdentity.uniqueIdentifier
            val currentRequestList = usernameRequestDao.getAll().toMutableList()
            val currentUsernames = arrayListOf<String>()
            for (votePoll in votePolls) {
                try {
                    val name :String? = when (votePoll) {
                        is ContestedDocumentResourceVotePoll -> {
                            votePoll.indexValues[1]
                        }
                        else -> null
                    }

                    name?.let { normalizedLabel ->
                        val voteContender = platformRepo.getVoteContenders(normalizedLabel)
                        val votes = usernameVoteDao.getVotes(name)

                        voteContender.map.forEach { (identifier, contender) ->

                            if (voteContender.winner.isEmpty) {
                                val contestedDocument = contender.serializedDocument?.let { serialized ->
                                    DomainDocument(
                                        platform.platform.names.deserialize(serialized)
                                    )
                                }

                                if (contestedDocument != null) {
                                    val identityVerifyDocument = IdentityVerify(platform.platform).get(identifier, name)

                                    val requestId = UsernameRequest.getRequestId(identifier.toString(), normalizedLabel)
                                    val lastVote = votes.lastOrNull()
                                    val usernameRequest = UsernameRequest(
                                        requestId = requestId,
                                        username = contestedDocument.label,
                                        normalizedLabel = name,
                                        createdAt = contestedDocument.createdAt ?: -1L,
                                        identity = identifier.toString(),
                                        link = identityVerifyDocument?.url,
                                        votes = contender.votes,
                                        lockVotes = voteContender.lockVoteTally,
                                        isApproved = lastVote?.let { it.identity == identifier.toString() } ?: false
                                    )
                                    usernameRequestDao.insert(usernameRequest)
                                    currentRequestList.remove(usernameRequest)
                                    currentUsernames.add(usernameRequest.normalizedLabel)
                                } else {
                                    // voting is complete
                                    usernameRequestDao.remove(
                                        UsernameRequest.getRequestId(identifier.toString(), name)
                                    )
                                    // remove related votes
                                    usernameVoteDao.remove(name)
                                }
                            } else {
                                // there is a winner
                                usernameRequestDao.remove(
                                    UsernameRequest.getRequestId(identifier.toString(), name)
                                )
                                // remove related votes
                                usernameVoteDao.remove(name)
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.warn("problem getting vote polls", e)
                }
            }

            // check the remaining items to ensure voting has ended
            currentRequestList.forEach { request ->
                val voteContender = platformRepo.getVoteContenders(request.normalizedLabel)
                if (voteContender.winner.isPresent) {
                    // remove request
                    usernameRequestDao.remove(request.requestId)
                    // remove related votes
                    usernameVoteDao.remove(request.normalizedLabel)
                }
            }
            // check votes and remove those from previous vote polls
            usernameVoteDao.getAllVotes().forEach { vote ->
                if (!currentUsernames.contains(vote.username)) {
                    usernameVoteDao.remove(vote.username)
                }
            }
        } catch (e: Exception) {
            log.info("problem obtaining votes:", e)
        } finally {
            log.info("updateUsernameRequestsWithVotes complete")
        }
    }

    /**
     * update databases for a single username (normalized)
     */
    override suspend fun updateUsernameRequestWithVotes(name: String) {
        try {
            val voteContender = platformRepo.getVoteContenders(name)

            voteContender.map.forEach { (identifier, contender) ->
                val contestedDocument = contender.serializedDocument?.let { serialized ->
                    DomainDocument(
                        platform.platform.names.deserialize(serialized)
                    )
                }
                val hasWinner = voteContender.winner.isPresent

                if (!hasWinner) {
                    if (contestedDocument != null) {
                        val identityVerifyDocument = IdentityVerify(platform.platform).get(identifier, name)

                        val requestId = UsernameRequest.getRequestId(identifier.toString(), name)
                        val votes = usernameVoteDao.getVotes(name)
                        val lastVote = votes.lastOrNull()

                        val usernameRequest = UsernameRequest(
                            requestId = requestId,
                            username = contestedDocument.label,
                            normalizedLabel = name,
                            createdAt = contestedDocument.createdAt ?: -1L,
                            identity = identifier.toString(),
                            link = identityVerifyDocument?.url,
                            votes = contender.votes,
                            lockVotes = voteContender.lockVoteTally,
                            isApproved = lastVote?.let { it.identity == identifier.toString() } ?: false
                        )
                        usernameRequestDao.insert(usernameRequest)
                    }
                } else {
                    // voting is complete
                    usernameRequestDao.remove(
                        UsernameRequest.getRequestId(identifier.toString(), name)
                    )
                    // remove related votes
                    usernameVoteDao.remove(name)
                }
            }
        } catch (e: Exception) {
            log.info("problem obtaining votes for {}:", name, e)
        }
    }

    override suspend fun triggerPreBlockDownloadComplete() {
        finishPreBlockDownload()
    }

    private suspend fun finishPreBlockDownload() {
        log.info("PreBlockDownload: complete")
        if (config.areNotificationsDisabled()) {
            // this will enable notifications, since platform information has been synced
            config.set(DashPayConfig.LAST_SEEN_NOTIFICATION_TIME, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7))
        }
        log.info("PreBlockDownload: $preDownloadBlocksFuture")
        preDownloadBlocksFuture?.set(true)
        preDownloadBlocks.set(false)
    }

    private fun isRunningInForeground(): Boolean {
        val appProcessInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        return appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun updateBloomFilters() {
        // if we are not running in the foreground, don't try to start to update the bloom filters
        // This should be OK, since the blockchain shouldn't be syncing.

        // Nevertheless, platformSyncJob should be inactive when the BlockchainService is destroyed
        // Perhaps the updateContactRequests method is being run while the job is canceled
        if (platformSyncJob?.isActive == true) {
            if (isRunningInForeground()) {
                log.info("attempting to update bloom filters when the app is in the foreground")
                val intent = Intent(
                    BlockchainService.ACTION_RESET_BLOOMFILTERS,
                    null,
                    walletApplication,
                    BlockchainServiceImpl::class.java
                )
                walletApplication.startService(intent)
            } else {
                log.info("attempting to update bloom filters when the app is in the background")
            }
        }
    }

    /**
     * Called before DashJ starts synchronizing the blockchain,
     * Platform DAPI calls should be delayed until this function
     * is called because an updated Masternode and Quorun List is
     * required for proof verification
     */
    override fun preBlockDownload(future: SettableFuture<Boolean>) {
        syncScope.launch(Dispatchers.IO) {
            preDownloadBlocks.set(true)
            lastPreBlockStage = PreBlockStage.None
            preDownloadBlocksFuture = future
            log.info("preBlockDownload: starting")
            if (!Constants.SUPPORTS_PLATFORM) {
                finishPreBlockDownload()
                return@launch
            }

            // TODO: ideally we shoud do this, but there is not a good way
            // to determine if an EvoNode has Evolution
            // platform.setMasternodeListManager(walletApplication.wallet!!.context.masternodeListManager)


            // first check to see if there is a blockchain identity
            // or if the previous restore is incomplete
            val identityData = blockchainIdentityDataDao.load()
            if (identityData == null || identityData.restoring) {
                log.info("preBlockDownload: checking for existing associated identity")

                val identity = platformRepo.getIdentityFromPublicKeyId()
                platformRepo.onIdentityResolved?.invoke(identity)

                if (identity != null) {
                    log.info("preBlockDownload: initiate recovery of existing identity ${identity.id}")
                    RestoreIdentityOperation(walletApplication)
                        .create(identity.id.toString())
                        .enqueue()
                    return@launch
                } else {
                    log.info("preBlockDownload: no existing identity found")
                    // resume Sync process, since there is no Platform data to sync
                    finishPreBlockDownload()
                }
            }
            // update contacts, profiles and other platform data
            else {
                checkVotingStatus(identityData)

                if (!updatingContacts.get()) {
                    updateContactRequests(initialSync = true)
                }
            }
            initSync()
        }
    }

    override suspend fun checkUsernameVotingStatus() {
        identityConfig.load()?.let {
            checkVotingStatus(it)
        }
    }

    suspend fun checkVotingStatus(identityData: BlockchainIdentityData) {
        if (identityData.username != null && identityData.creationState == BlockchainIdentityData.CreationState.VOTING) {
            // query username first to load the data contract cache
            val resource = platformRepo.getUsername(identityData.username!!)
            val voteResults = platformRepo.getVoteContenders(identityData.username!!)
            if (voteResults.winner.isPresent) {
                val winner = voteResults.winner.get().first
                when {
                    winner.isLocked -> {
                        identityData.usernameRequested = UsernameRequestStatus.LOCKED
                        syncScope.launch { platformRepo.updateBlockchainIdentityData(identityData) }
                    }

                    winner.isWinner(Identifier.from(identityData.userId)) -> {
                        identityData.usernameRequested = UsernameRequestStatus.APPROVED
                        syncScope.launch { platformRepo.updateBlockchainIdentityData(identityData) }
                    }

                    winner.noWinner -> {
                        // ?
                    }

                    else -> {
                        identityData.usernameRequested = UsernameRequestStatus.LOST_VOTE
                        syncScope.launch { platformRepo.updateBlockchainIdentityData(identityData) }
                    }
                }
                if (resource.status == Status.SUCCESS && resource.data != null) {
                    val domainDocument = DomainDocument(resource.data)
                    if (domainDocument.dashUniqueIdentityId == identityData.identity?.id) {
                        identityData.creationState = BlockchainIdentityData.CreationState.DONE_AND_DISMISS
                        platformRepo.updateBlockchainIdentityData(identityData)
                    }
                } else {

                }
            }
        }
    }

    override fun addContactsUpdatedListener(listener: OnContactsUpdated) {
        onContactsUpdatedListeners.add(listener)
    }

    override fun removeContactsUpdatedListener(listener: OnContactsUpdated?) {
        onContactsUpdatedListeners.remove(listener)
    }

    override fun fireContactsUpdatedListeners() {
        for (listener in onContactsUpdatedListeners) {
            listener.onContactsUpdated()
        }
    }

    override suspend fun clearDatabases() {
        // push all changes to platform before clearing the database tables
        if (Constants.SUPPORTS_PLATFORM && dashPayConfig.shouldSaveOnReset()) {
            publishChangeCache(System.currentTimeMillis(), saveAll = true) // Before now - push everything
        }
        transactionMetadataChangeCacheDao.clear()
        transactionMetadataDocumentDao.clear()
    }

    override fun addPreBlockProgressListener(listener: OnPreBlockProgressListener) {
        onPreBlockContactListeners.add(listener)
    }

    override fun removePreBlockProgressListener(listener: OnPreBlockProgressListener) {
        onPreBlockContactListeners.remove(listener)
    }

    private fun firePreBlockProgressListeners(stage: PreBlockStage) {
        for (listener in onPreBlockContactListeners) {
            listener.onPreBlockProgressUpdated(stage)
        }
    }

    private suspend fun checkTopUps() {
        platformRepo.getWalletEncryptionKey()?.let {
            topUpRepository.checkTopUps(it)
        }
    }
}
