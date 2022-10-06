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
import android.os.Handler
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import com.google.common.base.Preconditions
import com.google.common.base.Stopwatch
import com.google.common.util.concurrent.SettableFuture
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainIdentityData
import de.schildbach.wallet.data.DashPayContactRequest
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.data.TransactionMetadataCacheItem
import de.schildbach.wallet.data.TransactionMetadataChangeCacheDao
import de.schildbach.wallet.data.TransactionMetadataDocument
import de.schildbach.wallet.data.TransactionMetadataDocumentDao
import de.schildbach.wallet.livedata.SeriousError
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.BlockchainService
import de.schildbach.wallet.service.BlockchainServiceImpl
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import de.schildbach.wallet.ui.dashpay.OnContactsUpdated
import de.schildbach.wallet.ui.dashpay.OnPreBlockProgressListener
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.PreBlockStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.evolution.EvolutionContact
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.contracts.wallet.TxMetadataItem
import org.dashj.platform.dashpay.ContactRequest
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

interface PlatformSyncService {
    fun initGlobal()
    fun resume()
    fun shutdown()

    fun updateSyncStatus(stage: PreBlockStage)
    fun preBlockDownload(future: SettableFuture<Boolean>)

    suspend fun updateContactRequests()
    fun postUpdateBloomFilters()

    fun addContactsUpdatedListener(listener: OnContactsUpdated)
    fun removeContactsUpdatedListener(listener: OnContactsUpdated?)
    fun fireContactsUpdatedListeners()

    fun addPreBlockProgressListener(listener: OnPreBlockProgressListener)
    fun removePreBlockProgressListener(listener: OnPreBlockProgressListener)

    suspend fun clearDatabases()
}

class PlatformSynchronizationService @Inject constructor(
    val platformRepo: PlatformRepo,
    val analytics: AnalyticsService,
    val configuration: Configuration,
    val walletApplication: WalletApplication,
    val transactionMetadataProvider: TransactionMetadataProvider,
    val transactionMetadataChangeCacheDao: TransactionMetadataChangeCacheDao,
    val transactionMetadataDocumentDao: TransactionMetadataDocumentDao
) : PlatformSyncService {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(PlatformSynchronizationService::class.java)

        const val UPDATE_TIMER_DELAY = 15000L // 15 seconds
    }

    val platform = platformRepo.platform

    private lateinit var platformSyncJob: Job
    private val updatingContacts = AtomicBoolean(false)
    private val preDownloadBlocks = AtomicBoolean(false)
    private var preDownloadBlocksFuture: SettableFuture<Boolean>? = null

    private var mainHandler: Handler = Handler(platformRepo.walletApplication.mainLooper)

    private val blockchainIdentityDataDao = AppDatabase.getAppDatabase().blockchainIdentityDataDao()
    private val dashPayProfileDao = AppDatabase.getAppDatabase().dashPayProfileDao()
    private val dashPayContactRequestDao = AppDatabase.getAppDatabase().dashPayContactRequestDao()
    private val invitationsDao = AppDatabase.getAppDatabase().invitationsDao()
    private val onContactsUpdatedListeners = arrayListOf<OnContactsUpdated>()
    private val onPreBlockContactListeners = arrayListOf<OnPreBlockProgressListener>()
    private var lastPreBlockStage: PreBlockStage = PreBlockStage.None


    private val syncScope = CoroutineScope(
        Executors.newFixedThreadPool(5).asCoroutineDispatcher()
    )


    override fun initGlobal() {
        platformSyncJob = syncScope.launch {
            init()
        }
    }

    suspend fun init() {
        platformRepo.init()

        platformSyncJob = syncScope.launch {
            log.info("Starting the platform sync job")
            while (isActive) {
                updateContactRequests()
                delay(UPDATE_TIMER_DELAY)
            }
        }
    }

    override fun resume() {
        if (!platformSyncJob.isActive && platformRepo.getBlockchainIdentity() != null) {
            platformSyncJob = syncScope.launch {
                log.info("Resuming the platform sync job")
                while (isActive) {
                    updateContactRequests()
                    delay(UPDATE_TIMER_DELAY)
                }
            }
        }
    }

    override fun shutdown() {
        if (platformRepo.getBlockchainIdentity() != null) {
            Preconditions.checkState(platformSyncJob.isActive)
            log.info("Shutting down the platform sync job")
            platformSyncJob.cancel("shutdown the platform sync")
        }
    }

    var counterForReport = 0;

    /**
     * updateContactRequests will fetch new Contact Requests from the network
     * and verify that we have all requests and profiles in the local database
     *
     * This method should not use blockchainIdentity because in some cases
     * when the app starts, it has not yet been initialized
     */
    override suspend fun updateContactRequests() {

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
            Context.propagate(platformRepo.walletApplication.wallet!!.context)

            val lastContactRequestTime = if (dashPayContactRequestDao.countAllRequests() > 0) {
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
            val toContactDocuments = platformRepo.contactRequests.get(
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
                    addedContact = addedContact || checkAndAddSentRequest(userId, contactRequest)
                    log.info("contactRequest: added sent request from ${contactRequest.toUserId}")
                }
            }
            updateSyncStatus(PreBlockStage.GetReceivedRequests)
            // Get all contact requests where toUserId == userId, the users who have added me
            val fromContactDocuments = platformRepo.contactRequests.get(
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
                    addedContact =
                        addedContact || checkAndAddReceivedRequest(userId, contactRequest)
                    log.info("contactRequest: added received request from ${contactRequest.ownerId}")

                }
            }
            updateSyncStatus(PreBlockStage.GetSentRequests)

            // If new keychains were added to the wallet, then update the bloom filters
            if (addedContact) {
                postUpdateBloomFilters()
            }

            //obtain profiles from new contacts
            if (userIdList.isNotEmpty()) {
                updateContactProfiles(userIdList.toList(), 0L)
            }
            updateSyncStatus(PreBlockStage.GetNewProfiles)

            // fetch updated invitations
            updateInvitations()

            // fetch updated transaction metadata
            updateTransactionMetadata()

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
            log.error(platformRepo.formatExceptionMessage("error updating contacts", e))
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

    override fun updateSyncStatus(stage: PreBlockStage) {
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
                platformRepo.getBlockchainIdentity()!!.addPaymentKeyChainFromContact(
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
                platformRepo.getBlockchainIdentity()!!.addContactPaymentKeyChain(
                    contactIdentity!!,
                    contactRequest.document,
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
    private suspend fun updateContactProfiles(
        userIdList: List<String>,
        lastContactRequestTime: Long,
        checkingIntegrity: Boolean = false
    ) {
        try {
            if (userIdList.isNotEmpty()) {
                val identifierList = userIdList.map { Identifier.from(it) }
                val profileDocuments = platformRepo.profiles.getList(
                    identifierList,
                    lastContactRequestTime
                ) //only handles 100 userIds
                val profileById = profileDocuments.associateBy({ it.ownerId }, { it })

                val nameDocuments = platform.names.getList(identifierList)
                val nameById =
                    nameDocuments.associateBy({ platformRepo.getIdentityForName(it) }, { it })

                for (id in profileById.keys) {
                    if (nameById.containsKey(id)) {
                        val nameDocument =
                            nameById[id] // what happens if there is no username for the identity? crash
                        val username = nameDocument!!.data["normalizedLabel"] as String
                        val identityId = platformRepo.getIdentityForName(nameDocument)

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
                    val remainingMissingProfiles = userIdList.filter {
                        !profileById.containsKey(
                            Identifier.from(it)
                        )
                    }
                    for (identityId in remainingMissingProfiles) {
                        val nameDocument = nameById[Identifier.from(identityId)]
                        // what happens if there is no username for the identity? crash
                        if (nameDocument != null) {
                            val username = nameDocument.data["normalizedLabel"] as String
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
        mainHandler.post {
            updateBloomFilters()
        }
    }

    private fun updateTransactionMetadata() {

        val watch = Stopwatch.createStarted()

        val myEncryptionKey = platformRepo.getWalletEncryptionKey()

        val lastTxMetadataRequestTime = if (transactionMetadataDocumentDao.countAllRequests() > 0) {
            val lastTimeStamp = transactionMetadataDocumentDao.getLastTimestamp()
            // if the last txmetadata document was received in the past 10 minutes, then query for
            // documents that are 10 minutes before it.  If the tx metadata documented was
            // more than 10 minutes ago, then query all metadata documents that came after it.
            if (lastTimeStamp < System.currentTimeMillis() - DateUtils.MINUTE_IN_MILLIS * 10)
                lastTimeStamp
            else lastTimeStamp - DateUtils.MINUTE_IN_MILLIS * 10
        } else 0L

        log.info("fetching TxMetadataDocuments from {}", lastTxMetadataRequestTime)

        val items = platformRepo.getBlockchainIdentity()!!
            .getTxMetaData(lastTxMetadataRequestTime, myEncryptionKey)

        if (items.isNotEmpty()) {
            //val lastItem = items.keys.last()
            //lastItem.createdAt?.let {
            //    configuration.txMetadataUpdateTime = it
            //}
            log.info("processing TxMetadataDocuments: {}", items.toString())

            syncScope.launch {
                items.forEach { (doc, list) ->
                    if (transactionMetadataDocumentDao.count(doc.id) == 0) {
                        val timestamp = doc.createdAt!!
                        log.info("processing TxMetadata: ${doc.id} with ${list.size} items")
                        list.forEach { metadata ->
                            if (metadata.isNotEmpty()) {

                                val cachedItems = transactionMetadataChangeCacheDao.findAfter(
                                    Sha256Hash.wrap(metadata.txId),
                                    timestamp
                                )
                                log.info("processing TxMetadata: found ${cachedItems.size} related items in this document")

                                // what if the updates from platform are older

                                // if not change the main table

                                // we need to find a new way -- how can we know that we should change something?
                                // should we save to the DB table?
                                val txIdAsHash = Sha256Hash.wrap(metadata.txId)
                                val metadataDocumentRecord = TransactionMetadataDocument(doc.id, doc.createdAt!!, txIdAsHash)
                                metadata.timestamp?.let { timestamp ->
                                    metadataDocumentRecord.sentTimestamp = timestamp
                                    log.info("processing TxMetadata: sent time stamp")
                                    if (cachedItems.find { it.txId == txIdAsHash && it.cacheTimestamp > doc.createdAt!! && it.sentTimestamp != null && it.sentTimestamp != timestamp } == null) {
                                        log.info("processing TxMetadata: service change: changing service")
                                        transactionMetadataProvider.setTransactionSentTime(
                                            txIdAsHash,
                                            timestamp,
                                            true
                                        )
                                    }
                                }


                                metadata.service?.let { service ->
                                    metadataDocumentRecord.service = service
                                    log.info("processing TxMetadata: service change")
                                    if (cachedItems.find { it.txId == txIdAsHash && it.cacheTimestamp > doc.createdAt!! && it.service != null && it.service != service} == null) {
                                        log.info("processing TxMetadata: service change: changing service")
                                        transactionMetadataProvider.setTransactionService(
                                            txIdAsHash,
                                            service,
                                            true
                                        )
                                    }
                                }
                                metadata.memo?.let { memo ->
                                    metadataDocumentRecord.memo = memo
                                    log.info("processing TxMetadata: memo change: {}", cachedItems.find { it.txId == txIdAsHash && it.cacheTimestamp > doc.createdAt!! && it.memo != null && it.memo != memo})
                                    if (cachedItems.find { it.txId == txIdAsHash && it.cacheTimestamp > doc.createdAt!! && it.memo != null && it.memo != memo} == null) {
                                        log.info("processing TxMetadata: memo change: changing memo")
                                        transactionMetadataProvider.setTransactionMemo(txIdAsHash, memo, true)
                                    }
                                }
                                metadata.taxCategory?.let { taxCategoryAsString ->
                                    TaxCategory.fromValue(taxCategoryAsString)?.let { taxCategory ->
                                        metadataDocumentRecord.taxCategory = taxCategory
                                        log.info("processing TxMetadata: tax category change")
                                        if (cachedItems.find { it.txId == txIdAsHash && it.cacheTimestamp > doc.createdAt!! && it.taxCategory != null && it.taxCategory?.name != taxCategoryAsString} == null) {
                                            log.info("processing TxMetadata: tax category change: changing category")
                                            transactionMetadataProvider.setTransactionTaxCategory(
                                                txIdAsHash,
                                                taxCategory,
                                                true
                                            )
                                        }
                                    }
                                }
                                if (metadata.exchangeRate != null && metadata.currencyCode != null) {
                                    metadataDocumentRecord.rate = metadata.exchangeRate
                                    metadataDocumentRecord.currencyCode = metadata.currencyCode

                                    val prevItem = cachedItems.find { it.txId == txIdAsHash && it.cacheTimestamp > doc.createdAt!! && it.currencyCode != null && it.rate != null &&
                                            (it.currencyCode != metadata.currencyCode || it.rate != metadata.exchangeRate.toString())}
                                    log.info("processing TxMetadata: exchange rate change change: $prevItem")
                                    if (cachedItems.find { it.txId == txIdAsHash && it.cacheTimestamp > doc.createdAt!! && it.currencyCode != null && it.rate != null &&
                                                (it.currencyCode != metadata.currencyCode || it.rate != metadata.exchangeRate.toString())} == null) {
                                        log.info("processing TxMetadata: exchange rate change change: setting rate")
                                        transactionMetadataProvider.setTransactionExchangeRate(
                                            txIdAsHash, ExchangeRate(
                                                metadata.currencyCode!!,
                                                metadata.exchangeRate.toString()
                                            ),
                                            true
                                        )
                                    }
                                }
                                log.info("adding TxMetadataItem: {}", metadata)
                                transactionMetadataDocumentDao.insert(metadataDocumentRecord)
                            } else {
                                log.info("not adding TxMetadataItem: {} since it is empty", metadata)
                            }
                        }

                        //configuration.txMetadataUpdateTime = doc.createdAt!!
                    } else {
                        log.info("TxMetadataDocument:  this item already exists ${doc.id}")
                    }
                }
            }

            log.info("fetching ${items.size} tx metadata items in $watch")
        }

        publishTxMetadataChanges()
    }

    private fun publishTransactionMetadata(txMetadataItems: List<TransactionMetadataCacheItem>) {
        val metadataList = txMetadataItems.map {
            TxMetadataItem(it.txId.bytes, it.sentTimestamp, it.memo, it.rate?.toDouble(), it.currencyCode, it.taxCategory?.name?.lowercase(), it.service)
        }
        val walletEncryptionKey = platformRepo.getWalletEncryptionKey()
        platformRepo.getBlockchainIdentity()!!.publishTxMetaData(metadataList, walletEncryptionKey)
    }

    suspend fun publishChangeCache() {
        val itemsToPublish = hashMapOf<Sha256Hash, TransactionMetadataCacheItem>()
        val changedItems = transactionMetadataChangeCacheDao.load()
        log.info("preparing to publish ${changedItems.size} tx metadata changes to platform")

        for (changedItem in changedItems) {
            if (itemsToPublish.containsKey(changedItem.txId)) {
                val item = itemsToPublish[changedItem.txId]!!

                changedItem.memo?.let { memo ->
                    item.memo = memo
                }
                if (changedItem.rate != null && changedItem.currencyCode != null) {
                    item.rate = changedItem.rate
                    item.currencyCode = changedItem.currencyCode
                }
                changedItem.sentTimestamp?.let { timestamp ->
                    item.sentTimestamp = timestamp
                }
                changedItem.service?.let { service ->
                    item.service = service
                }
            } else {
                itemsToPublish[changedItem.txId] = changedItem
            }
        }

        try {
            log.info("publishing ${itemsToPublish.values.size} tx metadata changes to platform")
            // publish non-empty items
            publishTransactionMetadata(itemsToPublish.values.filter { it.isNotEmpty() })
            // clear out cache table after successful publishing on platform
            transactionMetadataChangeCacheDao.clear()
            log.info("published ${itemsToPublish.values.size} tx metadata changes to platform")
        } catch (e: Exception) {
            log.info("publishing exception caught", e)
        }
    }

    private fun publishTxMetadataChanges(): SettableFuture<Boolean> {
        val future = SettableFuture.create<Boolean>()
        syncScope.launch(Dispatchers.IO) {
            log.info("publishing updates to tx metadata items")
            publishChangeCache()
            log.info("publishing updates to tx metadata items complete")
            future.set(true)
        }
        return future
    }


    private fun finishPreBlockDownload() {
        log.info("PreDownloadBlocks: complete")
        if (configuration.areNotificationsDisabled()) {
            // this will enable notifications, since platform information has been synced
            configuration.lastSeenNotificationTime = System.currentTimeMillis()
        }
        preDownloadBlocksFuture?.set(true)
        preDownloadBlocks.set(false)
    }

    private fun isRunningInForeground(): Boolean {
        val appProcessInfo = ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(appProcessInfo);
        return (appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND || appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
    }

    fun updateBloomFilters() {
        // if we are not running in the foreground, don't try to start to update the bloom filters
        // This should be OK, since the blockchain shouldn't be syncing.

        // Nevertheless, platformSyncJob should be inactive when the BlockchainService is destroyed
        // Perhaps the updateContactRequests method is being run while the job is canceled
        if (platformSyncJob.isActive) {
            if (isRunningInForeground()) {
                log.info("attempting to update bloom filters when the app is in the foreground")
                val intent = Intent(
                    BlockchainService.ACTION_RESET_BLOOMFILTERS, null, walletApplication,
                    BlockchainServiceImpl::class.java
                )
                walletApplication.startService(intent)
            } else {
                log.info("attempting to update bloom filters when the app is in the background")
            }
        }
    }

    /**
     * Called before DashJ starts synchronizing the blockchain
     */
    override fun preBlockDownload(future: SettableFuture<Boolean>) {
        GlobalScope.launch(Dispatchers.IO) {
            preDownloadBlocks.set(true)
            lastPreBlockStage = PreBlockStage.None
            preDownloadBlocksFuture = future
            log.info("PreDownloadBlocks: starting")

            //first check to see if there is a blockchain identity
            if (blockchainIdentityDataDao.load() == null) {
                log.info("PreDownloadBlocks: checking for existing associated identity")

                val identity = platformRepo.getIdentityFromPublicKeyId()
                platformRepo.onIdentityResolved?.invoke(identity)

                if (identity != null) {
                    log.info("PreDownloadBlocks: initiate recovery of existing identity ${identity.id.toString()}")
                    ContextCompat.startForegroundService(
                        walletApplication,
                        CreateIdentityService.createIntentForRestore(
                            walletApplication,
                            identity.id.toBuffer()
                        )
                    )
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
        publishChangeCache()
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
}