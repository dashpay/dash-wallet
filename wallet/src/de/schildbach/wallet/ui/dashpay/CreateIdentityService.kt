package de.schildbach.wallet.ui.dashpay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.dao.UserAlertDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.BlockchainIdentityData.CreationState
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import de.schildbach.wallet_test.R
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.*
import org.bitcoinj.core.RejectMessage
import org.bitcoinj.core.RejectedTransactionException
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.evolution.CreditFundingTransaction
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import org.dashj.platform.dapiclient.model.GrpcExceptionInfo
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dpp.errors.ConcensusErrorMetadata
import org.dashj.platform.dpp.errors.concensus.ConcensusException
import org.dashj.platform.dpp.errors.concensus.basic.identity.IdentityAssetLockTransactionOutPointAlreadyExistsException
import org.dashj.platform.dpp.errors.concensus.basic.identity.InvalidInstantAssetLockProofSignatureException
import org.dashj.platform.dpp.identity.Identity
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@AndroidEntryPoint
class CreateIdentityService : LifecycleService() {

    companion object {
        private val log = LoggerFactory.getLogger(CreateIdentityService::class.java)

        private const val ACTION_CREATE_IDENTITY = "org.dash.dashpay.action.CREATE_IDENTITY"
        private const val ACTION_CREATE_IDENTITY_FROM_INVITATION = "org.dash.dashpay.action.CREATE_IDENTITY_FROM_INVITATION"


        private const val ACTION_RETRY_WITH_NEW_USERNAME = "org.dash.dashpay.action.ACTION_RETRY_WITH_NEW_USERNAME"
        private const val ACTION_RETRY_AFTER_INTERRUPTION = "org.dash.dashpay.action.ACTION_RETRY_AFTER_INTERRUPTION"

        private const val ACTION_RETRY_INVITE_WITH_NEW_USERNAME = "org.dash.dashpay.action.ACTION_RETRY_INVITE_WITH_NEW_USERNAME"
        private const val ACTION_RETRY_INVITE_AFTER_INTERRUPTION = "org.dash.dashpay.action.ACTION_RETRY_INVITE_AFTER_INTERRUPTION"

        private const val ACTION_RESTORE_IDENTITY = "org.dash.dashpay.action.RESTORE_IDENTITY"

        private const val EXTRA_USERNAME = "org.dash.dashpay.extra.USERNAME"
        private const val EXTRA_START_FOREGROUND_PROMISED = "org.dash.dashpay.extra.EXTRA_START_FOREGROUND_PROMISED"
        private const val EXTRA_IDENTITY = "org.dash.dashpay.extra.IDENTITY"
        private const val EXTRA_INVITE = "org.dash.dashpay.extra.INVITE"


        @JvmStatic
        fun createIntentForNewUsername(context: Context, username: String): Intent {
            return Intent(context, CreateIdentityService::class.java).apply {
                action = ACTION_RETRY_WITH_NEW_USERNAME
                putExtra(EXTRA_USERNAME, username)
            }
        }

        @JvmStatic
        fun createIntent(context: Context, username: String): Intent {
            return Intent(context, CreateIdentityService::class.java).apply {
                action = ACTION_CREATE_IDENTITY
                putExtra(EXTRA_USERNAME, username)
            }
        }

        @JvmStatic
        fun createIntentFromInvite(context: Context, username: String, invite: InvitationLinkData): Intent {
            return Intent(context, CreateIdentityService::class.java).apply {
                action = ACTION_CREATE_IDENTITY_FROM_INVITATION
                putExtra(EXTRA_USERNAME, username)
                putExtra(EXTRA_INVITE, invite)
            }
        }

        @JvmStatic
        fun createIntentFromInviteForNewUsername(context: Context, username: String): Intent {
            return Intent(context, CreateIdentityService::class.java).apply {
                action = ACTION_RETRY_INVITE_WITH_NEW_USERNAME
                putExtra(EXTRA_USERNAME, username)
            }
        }

        @JvmStatic
        fun createIntentForRetry(context: Context, startForegroundPromised: Boolean = false): Intent {
            return Intent(context, CreateIdentityService::class.java).apply {
                action = ACTION_RETRY_AFTER_INTERRUPTION
                putExtra(EXTRA_START_FOREGROUND_PROMISED, startForegroundPromised)
            }
        }

        @JvmStatic
        fun createIntentForRetryFromInvite(context: Context, startForegroundPromised: Boolean = false): Intent {
            return Intent(context, CreateIdentityService::class.java).apply {
                action = ACTION_RETRY_INVITE_AFTER_INTERRUPTION
                putExtra(EXTRA_START_FOREGROUND_PROMISED, startForegroundPromised)
            }
        }

        @JvmStatic
        fun createIntentForRestore(context: Context, identity: ByteArray): Intent {
            return Intent(context, CreateIdentityService::class.java).apply {
                action = ACTION_RESTORE_IDENTITY
                putExtra(EXTRA_IDENTITY, identity)
            }
        }
    }

    private val walletApplication by lazy { application as WalletApplication }
    @Inject lateinit var platformRepo: PlatformRepo// by lazy { PlatformRepo.getInstance() }
    @Inject lateinit var platformSyncService: PlatformSyncService
    @Inject lateinit var userAlertDao: UserAlertDao
    @Inject lateinit var blockchainIdentityDataDao: BlockchainIdentityConfig
    @Inject lateinit var securityFunctions: SecurityFunctions
    @Inject lateinit var coinJoinConfig: CoinJoinConfig
    private lateinit var securityGuard: SecurityGuard

    private val wakeLock by lazy {
        val lockName = "$packageName create identity"
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockName)
    }

    private val createIdentityNotification by lazy {
        CreateIdentityNotification(this, blockchainIdentityDataDao)
    }

    private val serviceJob = Job()
    private var serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    lateinit var blockchainIdentity: BlockchainIdentity
    lateinit var blockchainIdentityData: BlockchainIdentityData

    @Inject
    lateinit var analytics: AnalyticsService

    private var workInProgress = false

    private val createIdentityExceptionHandler = CoroutineExceptionHandler { _, exception ->
        log.error(exception.message, exception)
        analytics.logError(exception, "Failed to create Identity")

        GlobalScope.launch {
            var isInvite = false
            if (this@CreateIdentityService::blockchainIdentityData.isInitialized) {
                log.error("[${blockchainIdentityData.creationState}(error)]", exception)
                platformRepo.updateIdentityCreationState(blockchainIdentityData, blockchainIdentityData.creationState, exception)
                if (this@CreateIdentityService::blockchainIdentity.isInitialized) {
                    platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
                }
                isInvite = blockchainIdentityData.usingInvite
            }
            createIdentityNotification.displayErrorAndStopService(isInvite)
        }
        workInProgress = false
    }

    override fun onCreate() {
        super.onCreate()
        try {
            securityGuard = SecurityGuard()
        } catch (e: Exception) {
            log.error("unable to instantiate SecurityGuard", e)
            stopSelf()
            return
        }
        createIdentityNotification.startServiceForeground()
        wakeLock.acquire(TimeUnit.MINUTES.toMillis(10))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent == null) {
            // the service has been restarted by the system
            val blockchainIdentityData = runBlocking {
                platformRepo.loadBlockchainIdentityBaseData()
            }
            if (blockchainIdentityData != null && blockchainIdentityData.creationState != CreationState.DONE) {
                handleCreateIdentityAction(null)
            }

        } else if (!workInProgress) {

            when (intent.action) {
                ACTION_CREATE_IDENTITY,
                ACTION_RETRY_WITH_NEW_USERNAME -> {
                    val username = intent.getStringExtra(EXTRA_USERNAME)
                    val retryWithNewUserName = intent.action == ACTION_RETRY_WITH_NEW_USERNAME
                    handleCreateIdentityAction(username, retryWithNewUserName)
                }
                ACTION_CREATE_IDENTITY_FROM_INVITATION,
                ACTION_RETRY_INVITE_WITH_NEW_USERNAME -> {
                    val username = intent.getStringExtra(EXTRA_USERNAME)
                    val invitation = intent.getParcelableExtra<InvitationLinkData>(EXTRA_INVITE)

                    handleCreateIdentityFromInvitationAction(username, invitation)
                }
                ACTION_RETRY_AFTER_INTERRUPTION -> {
                    val startForegroundPromised = intent.getBooleanExtra(EXTRA_START_FOREGROUND_PROMISED, false)
                    if (startForegroundPromised) {
                        createIdentityNotification.startServiceForeground()
                    }
                    handleCreateIdentityAction(null)
                }
                ACTION_RETRY_INVITE_AFTER_INTERRUPTION -> {
                    val startForegroundPromised = intent.getBooleanExtra(EXTRA_START_FOREGROUND_PROMISED, false)
                    if (startForegroundPromised) {
                        createIdentityNotification.startServiceForeground()
                    }
                    handleCreateIdentityFromInvitationAction(null, null)
                }
                ACTION_RESTORE_IDENTITY -> {
                    val identity = intent.getByteArrayExtra(EXTRA_IDENTITY)!!
                    handleRestoreIdentityAction(identity)
                }
            }
        } else {
            log.info("work in progress, ignoring ${intent.action}")
        }

        return Service.START_STICKY
    }

    private fun handleCreateIdentityAction(username: String?, retryWithNewUserName: Boolean = false) {
        workInProgress = true
        serviceScope.launch(createIdentityExceptionHandler) {
            createIdentity(username, retryWithNewUserName)
            workInProgress = false
            stopSelf()
        }
    }

    private suspend fun createIdentity(username: String?, retryWithNewUserName: Boolean) {
        log.info("username registration starting")
        org.bitcoinj.core.Context.propagate(walletApplication.wallet!!.context)
        val timerEntireProcess = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_CREATE)
        val timerStep1 = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_CREATE_STEP_1)

        val blockchainIdentityDataTmp = platformRepo.loadBlockchainIdentityData()

        when {
            (blockchainIdentityDataTmp != null && blockchainIdentityDataTmp.restoring) -> {
                // TODO: handle case when blockchain reset has happened and the cftx was not found yet
                val cftx = blockchainIdentityDataTmp.findCreditFundingTransaction(walletApplication.wallet)
                        ?: throw IllegalStateException()

                restoreIdentity(cftx.creditBurnIdentityIdentifier.bytes)
                return
            }
            (blockchainIdentityDataTmp != null && !retryWithNewUserName) -> {
                blockchainIdentityData = blockchainIdentityDataTmp
                if (username != null && blockchainIdentityData.username != username && !retryWithNewUserName) {
                    throw IllegalStateException()
                }

            }
            (username != null) -> {
                blockchainIdentityData = BlockchainIdentityData(CreationState.NONE, null, username, null, false)
                platformRepo.updateBlockchainIdentityData(blockchainIdentityData)
            }
            else -> {
                throw IllegalStateException()
            }
        }

        var isRetry = false
        if (blockchainIdentityData.creationState != CreationState.NONE || blockchainIdentityData.creationStateErrorMessage != null) {
            log.info("resuming identity creation process [${blockchainIdentityData.creationState}(${blockchainIdentityData.creationStateErrorMessage})]")

            // handle case of "InvalidIdentityAssetLockProofSignatureError", where we need to start over from scratch
            val isInvalidLockProof = try {
                val errorMetadata =
                    ConcensusErrorMetadata(blockchainIdentityData.getErrorMetadata()!!)
                val exception = ConcensusException.create(errorMetadata)
                exception is InvalidInstantAssetLockProofSignatureException
            } catch (e: IllegalArgumentException) {
                false
            } catch (e: Exception) {
                false
            }
            if (blockchainIdentityData.creationState == CreationState.IDENTITY_REGISTERING &&
                    isInvalidLockProof) {
                blockchainIdentityData.creationState = CreationState.NONE
                blockchainIdentityData.creditFundingTxId = null
                isRetry = true
            }
        }

        platformRepo.resetIdentityCreationStateError(blockchainIdentityData)

        val wallet = walletApplication.wallet!!

        val encryptionKey = platformRepo.getWalletEncryptionKey() ?: throw IllegalStateException("cannot obtain wallet encryption key")

        if (blockchainIdentityData.creationState <= CreationState.UPGRADING_WALLET) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.UPGRADING_WALLET)
            val seed = wallet.keyChainSeed ?: throw IllegalStateException("cannot obtain wallet seed")
            platformRepo.addWalletAuthenticationKeysAsync(seed, encryptionKey)
        }

        val authenticationGroupExtension = wallet.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
        val blockchainIdentity = platformRepo.initBlockchainIdentity(blockchainIdentityData, wallet)
        // look for the credit funding tx in case there was an error in the next step previously
        for (tx in authenticationGroupExtension.creditFundingTransactions) {
            tx as CreditFundingTransaction
            if (authenticationGroupExtension.identityFundingKeyChain.findKeyFromPubHash(tx.creditBurnPublicKeyId.bytes) != null) {
                blockchainIdentity.initializeCreditFundingTransaction(tx)
            }
        }

        if (blockchainIdentityData.creationState <= CreationState.CREDIT_FUNDING_TX_CREATING) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.CREDIT_FUNDING_TX_CREATING)
            //
            // Step 2: Create and send the credit funding transaction
            //
            // check to see if the funding transaction exists
            if (blockchainIdentity.creditFundingTransaction == null) {
                val useCoinJoin = coinJoinConfig.getMode() != CoinJoinMode.NONE
                platformRepo.createCreditFundingTransactionAsync(blockchainIdentity, encryptionKey, useCoinJoin)
            }
        }

        if (blockchainIdentityData.creationState <= CreationState.CREDIT_FUNDING_TX_SENDING) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.CREDIT_FUNDING_TX_SENDING)
            val timerIsLock = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_CREATE_ISLOCK)
            // check to see if the funding transaction has been sent previously
            val sent = blockchainIdentity.creditFundingTransaction!!.confidence?.let {
                it.isSent || it.isIX || it.numBroadcastPeers() > 0 || it.confidenceType == TransactionConfidence.ConfidenceType.BUILDING
            } ?: false

            if (!sent) {
                sendTransaction(blockchainIdentity.creditFundingTransaction!!)
            }
            timerIsLock.logTiming()
        }

        //TODO: check to see if the funding transaction has been been confirmed
        if (blockchainIdentityData.creationState <= CreationState.CREDIT_FUNDING_TX_CONFIRMED) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.CREDIT_FUNDING_TX_CONFIRMED)
            // If the tx is in a block, seen by a peer, InstantSend lock, then it is considered confirmed
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
        }
        timerStep1.logTiming()
        val timerStep2 = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_CREATE_STEP_2)

        if (blockchainIdentityData.creationState <= CreationState.IDENTITY_REGISTERING) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.IDENTITY_REGISTERING)
            //
            // Step 3: Register the identity
            //
            if(isRetry) {
                val existingIdentity = platformRepo.getIdentityFromPublicKeyId()
                if (existingIdentity != null) {
                    val encryptionKey = platformRepo.getWalletEncryptionKey()
                    val firstIdentityKey = platformRepo.getBlockchainIdentityKey(0, encryptionKey)!!
                    platformRepo.recoverIdentityAsync(blockchainIdentity, firstIdentityKey.pubKey)
                }
            } else {
                platformRepo.registerIdentityAsync(blockchainIdentity, encryptionKey)
            }
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
        }
        timerStep2.logTiming()

        finishRegistration(blockchainIdentity, encryptionKey)
        timerEntireProcess.logTiming()
    }

    private fun handleCreateIdentityFromInvitationAction(username: String?, invite: InvitationLinkData?) {
        workInProgress = true
        serviceScope.launch(createIdentityExceptionHandler) {
            createIdentityFromInvitation(username, invite)
            workInProgress = false
            stopSelf()
        }
    }

    private suspend fun createIdentityFromInvitation(username: String?, invite: InvitationLinkData?, retryWithNewUserName: Boolean = false) {
        log.info("username registration starting from invitation")
        val timerInviteProcess = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_INVITATION_CLAIM)

        val blockchainIdentityDataTmp = platformRepo.loadBlockchainIdentityData()

        when {
            (blockchainIdentityDataTmp != null && blockchainIdentityDataTmp.restoring) -> {
                val cftx = blockchainIdentityDataTmp.findCreditFundingTransaction(walletApplication.wallet)
                        ?: throw IllegalStateException()

                restoreIdentity(cftx.creditBurnIdentityIdentifier.bytes)
                return
            }
            (blockchainIdentityDataTmp != null && !retryWithNewUserName) -> {
                blockchainIdentityData = blockchainIdentityDataTmp
                if (username != null && blockchainIdentityData.username != username && !retryWithNewUserName) {
                    throw IllegalStateException()
                }
            }
            (username != null) -> {
                blockchainIdentityData = BlockchainIdentityData(CreationState.NONE,
                        null, username, null, false,
                        usingInvite = true, invite = invite)
                platformRepo.updateBlockchainIdentityData(blockchainIdentityData)
            }
            else -> {
                throw IllegalStateException()
            }
        }

        var isRetry = false
        if (blockchainIdentityData.creationState != CreationState.NONE || blockchainIdentityData.creationStateErrorMessage != null) {
            // if this happens, then the invite cannot be used
            log.info("resuming identity creation process from invitiation [${blockchainIdentityData.creationState}(${blockchainIdentityData.creationStateErrorMessage})]")

            // handle case of "InvalidIdentityAssetLockProofSignatureError", where we need to start over from scratch
            val isInvalidLockProof = try {
                val errorMetadata =
                    ConcensusErrorMetadata(blockchainIdentityData.getErrorMetadata()!!)
                val exception = ConcensusException.create(errorMetadata)
                exception is InvalidInstantAssetLockProofSignatureException
            } catch (e: IllegalArgumentException) {
                false
            } catch (e: Exception) {
                false
            }
            if (blockchainIdentityData.creationState == CreationState.IDENTITY_REGISTERING &&
                    isInvalidLockProof) {
                blockchainIdentityData.creationState = CreationState.NONE
                blockchainIdentityData.creditFundingTxId = null
                isRetry = true
            }
        }

        platformRepo.resetIdentityCreationStateError(blockchainIdentityData)

        val wallet = walletApplication.wallet!!

        val encryptionKey = platformRepo.getWalletEncryptionKey()  ?: throw IllegalStateException("cannot obtain wallet encryption key")

        if (blockchainIdentityData.creationState <= CreationState.UPGRADING_WALLET) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.UPGRADING_WALLET)
            val seed = wallet.keyChainSeed ?: throw IllegalStateException("cannot obtain wallet seed")
            platformRepo.addWalletAuthenticationKeysAsync(seed, encryptionKey)
        }

        val blockchainIdentity = platformRepo.initBlockchainIdentity(blockchainIdentityData, wallet)


        if (blockchainIdentityData.creationState <= CreationState.CREDIT_FUNDING_TX_CREATING) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.CREDIT_FUNDING_TX_CREATING)
            //
            // Step 2: Create and send the credit funding transaction
            //
            platformRepo.obtainCreditFundingTransactionAsync(blockchainIdentity, blockchainIdentityData.invite!!)
        } else {
            // if we are retrying, then we need to initialize the credit funding tx
            platformRepo.obtainCreditFundingTransactionAsync(blockchainIdentity, blockchainIdentityData.invite!!)
        }

        if (blockchainIdentityData.creationState <= CreationState.CREDIT_FUNDING_TX_SENDING) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.CREDIT_FUNDING_TX_SENDING)
            // invite transactions have already been sent
        }

        if (blockchainIdentityData.creationState <= CreationState.CREDIT_FUNDING_TX_CONFIRMED) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.CREDIT_FUNDING_TX_CONFIRMED)
            // If the tx is in a block, seen by a peer, InstantSend lock, then it is considered confirmed
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
        }

        // This step will fail because register identity
        if (blockchainIdentityData.creationState <= CreationState.IDENTITY_REGISTERING) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.IDENTITY_REGISTERING)
            //
            // Step 3: Register the identity
            //
            try {
                if(isRetry) {
                    val existingIdentity = platformRepo.getIdentityFromPublicKeyId()
                    if (existingIdentity != null) {
                        val encryptionKey = platformRepo.getWalletEncryptionKey()
                        val firstIdentityKey = platformRepo.getBlockchainIdentityKey(0, encryptionKey)!!
                        platformRepo.recoverIdentityAsync(blockchainIdentity, firstIdentityKey.pubKey)
                    }
                } else {
                    platformRepo.registerIdentityAsync(blockchainIdentity, encryptionKey)
                }
            } catch (e: StatusRuntimeException) {
                //2021-03-26 10:08:08.411 28005-28085/hashengineering.darkcoin.wallet_test W/DapiClient: [DefaultDispatcher-worker-2] RPC failed with 54.187.224.80: Status{code=INVALID_ARGUMENT, description=State Transition is invalid, cause=null}: Metadata(server=nginx/1.19.7,date=Fri, 26 Mar 2021 17:08:09 GMT,content-type=application/grpc,content-length=0,errors=[{"name":"IdentityAssetLockTransactionOutPointAlreadyExistsError","message":"Asset lock transaction outPoint already exists","outPoint":{"type":"Buffer","data":[55,69,23,188,75,149,231,235,207,70,187,182,129,183,150,17,229,10,161,32,78,107,54,101,131,27,181,254,197,4,167,134,1,0,0,0]}}])
                // did this fail because the invitation was already used?
                if (e.status.code == Status.INVALID_ARGUMENT.code) {
                    val exception = GrpcExceptionInfo(e).exception

                        if (exception is IdentityAssetLockTransactionOutPointAlreadyExistsException) {
                            log.warn("Invite has already been used")

                            // activate link or activity with the link (to show that the invite was used)
                            // and then, wipe all blockchain identity data and status (NONE)
                            //
                            throw IllegalStateException("Invite has already been used", exception)
                        }

                    log.error(e.toString());
                    throw e
                }
                throw e
            }
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
        }

        finishRegistration(blockchainIdentity, encryptionKey)

        invite?.apply {
            val results = platformRepo.getUser(user)
            if (results.isNotEmpty()) {
                val inviterUserId = results[0].dashPayProfile.userId
                SendContactRequestOperation(walletApplication)
                        .create(inviterUserId)
                        .enqueue()
                walletApplication.configuration.apply {
                    inviter = inviterUserId
                    inviterContactRequestSentInfoShown = false
                }
            }
        }
        timerInviteProcess.logTiming()
        log.info("username registration with invite complete")
    }

    private suspend fun finishRegistration(blockchainIdentity: BlockchainIdentity, encryptionKey: KeyParameter) {

        // This Step is obsolete, verification is handled by the previous block, lets leave it in for now
        if (blockchainIdentityData.creationState <= CreationState.IDENTITY_REGISTERED) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.IDENTITY_REGISTERED)
            //
            // Step 3: Verify that the identity was registered
            //
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
        }
        val timerStep3 = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_CREATE_STEP_3)

        if (blockchainIdentityData.creationState <= CreationState.PREORDER_REGISTERING) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.PREORDER_REGISTERING)
            //
            // Step 4: Preorder the username
            if (!blockchainIdentity.getUsernames().contains(blockchainIdentityData.username!!)) {
                blockchainIdentity.addUsername(blockchainIdentityData.username!!)
            }
            platformRepo.preorderNameAsync(blockchainIdentity, encryptionKey)
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
        }

        // This Step is obsolete, verification is handled by the previous block, lets leave it in for now
        if (blockchainIdentityData.creationState <= CreationState.PREORDER_REGISTERED) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.PREORDER_REGISTERED)
            //
            // Step 4: Verify that the username was preordered
            //
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
        }

        if (blockchainIdentityData.creationState <= CreationState.USERNAME_REGISTERING) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.USERNAME_REGISTERING)
            //
            // Step 5: Register the username
            //
            platformRepo.registerNameAsync(blockchainIdentity, encryptionKey)
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
        }

        // This Step is obsolete, verification is handled by the previous block, lets leave it in for now
        if (blockchainIdentityData.creationState <= CreationState.USERNAME_REGISTERED) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.USERNAME_REGISTERED)
            //
            // Step 5: Verify that the username was registered
            //
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
            analytics.logEvent(AnalyticsConstants.UsersContacts.CREATE_USERNAME, mapOf())
        }

        // Step 6: A profile will not be created, since the user has not yet specified
        //         a display name, public message (bio) or an avatarUrl
        //         However, a default empty profile will be saved to the local database.
        val emptyProfile = DashPayProfile(blockchainIdentity.uniqueIdString, blockchainIdentity.currentUsername!!)
        platformRepo.updateDashPayProfile(emptyProfile)

        addInviteUserAlert(walletApplication.wallet!!)

        platformRepo.init()

        timerStep3.logTiming()
        // aaaand we're done :)
        log.info("username registration complete")
    }

    private suspend fun addInviteUserAlert(wallet: Wallet) {
        if (blockchainIdentityData.creationState < CreationState.DONE) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.DONE)

            // this alert will be shown or not based on the current balance and will be
            // managed by NotificationsLiveData
            val userAlert = UserAlert(R.string.invitation_notification_text,
                    R.drawable.ic_invitation)
            userAlertDao.insert(userAlert)

        }
    }

    private fun handleRestoreIdentityAction(identity: ByteArray) {
        workInProgress = true
        serviceScope.launch(createIdentityExceptionHandler) {
            restoreIdentity(identity)
            workInProgress = false
            stopSelf()
        }
    }

    private suspend fun restoreIdentity(identity: ByteArray) {
        log.info("Restoring identity and username")
        platformSyncService.updateSyncStatus(PreBlockStage.StartRecovery)

        // use an "empty" state for each
        blockchainIdentityData = BlockchainIdentityData(CreationState.NONE, null, null, null, true)

        val authExtension = walletApplication.wallet!!.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
        //authExtension.setWallet(walletApplication.wallet!!) // why is the wallet not set?  we didn't deserialize it probably!
        val cftxs = authExtension.creditFundingTransactions

        val creditFundingTransaction: CreditFundingTransaction? = cftxs.find { it.creditBurnIdentityIdentifier.bytes!!.contentEquals(identity) }

        val existingBlockchainIdentityData = blockchainIdentityDataDao.load()
        if (existingBlockchainIdentityData != null) {
            log.info("Attempting restore of existing identity and username; save credit funding txid")
            val blockchainIdentity = platformRepo.blockchainIdentity
            blockchainIdentity.creditFundingTransaction = creditFundingTransaction
            existingBlockchainIdentityData.creditFundingTxId = creditFundingTransaction!!.txId
            platformRepo.updateBlockchainIdentityData(existingBlockchainIdentityData)
            return
        }

        val loadingFromCreditFundingTransaction = creditFundingTransaction != null
        val existingIdentity: Identity?

        if (!loadingFromCreditFundingTransaction) {
            existingIdentity = platformRepo.getIdentityFromPublicKeyId()
            if (existingIdentity == null) {
                throw IllegalArgumentException("identity $identity does not match a credit funding transaction or it doesn't exist on the network")
            }
        }

        val wallet = walletApplication.wallet!!
        val encryptionKey = platformRepo.getWalletEncryptionKey() ?: throw IllegalStateException("cannot obtain wallet encryption key")
        val seed = wallet.keyChainSeed ?: throw IllegalStateException("cannot obtain wallet seed")

        // create the Blockchain Identity object
        val blockchainIdentity = BlockchainIdentity(platformRepo.platform.platform, 0, wallet, authExtension)
        // this process should have been done already, otherwise the credit funding transaction
        // will not have the credit burn keys associated with it
        platformRepo.addWalletAuthenticationKeysAsync(seed, encryptionKey)
        platformSyncService.updateSyncStatus(PreBlockStage.InitWallet)

        //
        // Step 2: The credit funding registration exists, no need to create it
        //

        //
        // Step 3: Find the identity
        //
        platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.IDENTITY_REGISTERING)
        if (loadingFromCreditFundingTransaction) {
            platformRepo.recoverIdentityAsync(blockchainIdentity, creditFundingTransaction!!)
        } else {
            val firstIdentityKey = platformRepo.getBlockchainIdentityKey(0, encryptionKey)!!
            platformRepo.recoverIdentityAsync(blockchainIdentity,
                firstIdentityKey.pubKeyHash)
        }
        platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
        platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.IDENTITY_REGISTERED)
        platformSyncService.updateSyncStatus(PreBlockStage.GetIdentity)


        //
        // Step 4: We don't need to find the preorder documents
        //

        //
        // Step 5: Find the username
        //
        platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.USERNAME_REGISTERING)
        platformRepo.recoverUsernamesAsync(blockchainIdentity)
        platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
        platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.USERNAME_REGISTERED)
        platformSyncService.updateSyncStatus(PreBlockStage.GetName)

        //
        // Step 6: Find the profile
        //
        platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.DASHPAY_PROFILE_CREATING)
        platformRepo.recoverDashPayProfile(blockchainIdentity)
        // blockchainIdentity hasn't changed
        platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.DASHPAY_PROFILE_CREATED)
        platformSyncService.updateSyncStatus(PreBlockStage.GetProfile)

        addInviteUserAlert(walletApplication.wallet!!)

        // We are finished recovering
        platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.DONE)

        // Complete the entire process
        platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.DONE_AND_DISMISS)

        platformSyncService.updateSyncStatus(PreBlockStage.RecoveryComplete)
        platformRepo.init()
    }

    /**
     * Send the credit funding transaction and wait for confirmation from other nodes that the
     * transaction was sent.  InstantSendLock, in a block or seen by more than one peer.
     *
     * Exceptions are returned in the case of a reject message (may not be sent with Dash Core 0.16)
     * or in the case of a double spend or some other error.
     *
     * @param cftx The credit funding transaction to send
     * @return True if successful
     */
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
                                log.info("credit funding transaction verified with instantsend: ${cftx.txId}")
                                confidence.removeEventListener(this)
                                continuation.resumeWith(Result.success(true))
                            }
                        }

                        TransactionConfidence.Listener.ChangeReason.CHAIN_LOCKED -> {
                            if (confidence!!.isChainLocked) {
                                log.info("credit funding transaction verified with chainlock: ${cftx.txId}")
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

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()

        if (wakeLock.isHeld) {
            log.debug("wakelock still held, releasing")
            wakeLock.release()
        }
    }
}
