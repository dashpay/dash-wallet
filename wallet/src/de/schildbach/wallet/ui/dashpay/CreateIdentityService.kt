package de.schildbach.wallet.ui.dashpay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import com.google.android.gms.common.internal.Preconditions.checkState
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.dao.UserAlertDao
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.BlockchainIdentityData.CreationState
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.ui.dashpay.UserAlert.Companion.INVITATION_NOTIFICATION_ICON
import de.schildbach.wallet.ui.dashpay.UserAlert.Companion.INVITATION_NOTIFICATION_TEXT
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.work.GetUsernameVotingResultOperation
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.*
import org.bitcoinj.core.Coin
import org.bitcoinj.core.RejectMessage
import org.bitcoinj.core.RejectedTransactionException
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bouncycastle.crypto.params.Blake3Parameters.context
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import org.dashj.platform.dapiclient.model.GrpcExceptionInfo
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dashpay.UsernameInfo
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.dashj.platform.dashpay.UsernameStatus
import org.dashj.platform.dashpay.callback.WalletSignerCallback
import org.dashj.platform.dpp.errors.ConcensusErrorMetadata
import org.dashj.platform.dpp.errors.concensus.ConcensusException
import org.dashj.platform.dpp.errors.concensus.basic.identity.IdentityAssetLockTransactionOutPointAlreadyExistsException
import org.dashj.platform.dpp.errors.concensus.basic.identity.InvalidInstantAssetLockProofSignatureException
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.sdk.platform.DomainDocument
import org.dashj.platform.sdk.platform.Names
import org.dashj.platform.wallet.IdentityVerify
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject

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
    }

    private val walletApplication by lazy { application as WalletApplication }
    @Inject lateinit var configuration: Configuration
    @Inject lateinit var platformRepo: PlatformRepo
    @Inject lateinit var platformSyncService: PlatformSyncService
    @Inject lateinit var topUpRepository: TopUpRepository
    @Inject lateinit var userAlertDao: UserAlertDao
    @Inject lateinit var blockchainIdentityDataDao: BlockchainIdentityConfig
    @Inject lateinit var securityFunctions: SecurityFunctions
    @Inject lateinit var coinJoinConfig: CoinJoinConfig
    @Inject lateinit var usernameRequestDao: UsernameRequestDao
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
        analytics.logEvent(AnalyticsConstants.UsersContacts.CREATE_USERNAME_ERROR, mapOf())

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
            securityGuard = SecurityGuard.getInstance()
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
            if (blockchainIdentityData != null && blockchainIdentityData.creationState != CreationState.DONE && !blockchainIdentityData.restoring) {
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
        log.info("username registration starting($username, $retryWithNewUserName)")
        org.bitcoinj.core.Context.propagate(walletApplication.wallet!!.context)
        val timerEntireProcess = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_CREATE)
        val timerStep1 = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_CREATE_STEP_1)

        val blockchainIdentityDataTmp = platformRepo.loadBlockchainIdentityData()
        val blockchainIdentityDataBase = platformRepo.loadBlockchainIdentityBaseData() // for other info
        when {
            (blockchainIdentityDataTmp != null && blockchainIdentityDataTmp.restoring && blockchainIdentityDataTmp.creationStateErrorMessage == null) -> {
                // TODO: handle case when blockchain reset has happened and the cftx was not found yet
                val cftx = blockchainIdentityDataTmp.findAssetLockTransaction(walletApplication.wallet)
                        ?: throw IllegalStateException("can't find asset lock transaction")

                restoreIdentity(cftx.identityId.bytes)
                return
            }
            (blockchainIdentityDataTmp != null && !retryWithNewUserName) -> {
                blockchainIdentityData = blockchainIdentityDataTmp
                if (username != null && blockchainIdentityData.username != username && !retryWithNewUserName) {
                    throw IllegalStateException()
                }

            }
            (username != null) -> {
                blockchainIdentityData = BlockchainIdentityData(
                    CreationState.NONE,
                    null,
                    username,
                    null,
                    false,
                    verificationLink = blockchainIdentityDataBase?.verificationLink
                )
                platformRepo.updateBlockchainIdentityData(blockchainIdentityData)
            }
            else -> {
                throw IllegalStateException()
            }
        }

        var isRetry = false
        checkState(!blockchainIdentityData.usingInvite, "use createIdentityFromInvite instead")
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
            } else if (blockchainIdentityData.creationState >= CreationState.IDENTITY_REGISTERED) {
                isRetry = true
            }

            if (blockchainIdentityData.creationState == CreationState.USERNAME_REGISTERING) {
                val errorMessage = blockchainIdentityData.creationStateErrorMessage ?: ""
                if (errorMessage.contains("preorderDocument was not found with a salted domain hash") ||
                    errorMessage.contains("cannot find preorder document, though it should be somewhere")) {
                    blockchainIdentityData.creationState = CreationState.PREORDER_REGISTERING
                    platformRepo.updateBlockchainIdentityData(blockchainIdentityData)
                } else if (errorMessage.contains("missing domain document for")) {
                    blockchainIdentityData.creationState = CreationState.PREORDER_REGISTERING
                    platformRepo.updateBlockchainIdentityData(blockchainIdentityData)
                } else if (retryWithNewUserName) {
                    // lets rewind the state to allow for a new username registration or request
                    // it may have failed later in the process
                    if (blockchainIdentityData.creationState > CreationState.USERNAME_REGISTERING) {
                        blockchainIdentityData.creationState = CreationState.USERNAME_REGISTERING
                    }
                }
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
        for (tx in authenticationGroupExtension.assetLockTransactions) {
            tx as AssetLockTransaction
            if (authenticationGroupExtension.identityFundingKeyChain.findKeyFromPubHash(tx.assetLockPublicKeyId.bytes) != null) {
                blockchainIdentity.initializeAssetLockTransaction(tx)
            }
        }
        var assetLockTransaction: AssetLockTransaction? = null
        if (blockchainIdentityData.creationState <= CreationState.CREDIT_FUNDING_TX_CREATING) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.CREDIT_FUNDING_TX_CREATING)
            //
            // Step 2: Create and send the credit funding transaction
            //
            // check to see if the funding transaction exists
            val useCoinJoin = coinJoinConfig.getMode() != CoinJoinMode.NONE
            if (blockchainIdentity.assetLockTransaction == null) {
                if (blockchainIdentity.identity == null) {
                    topUpRepository.createAssetLockTransaction(
                        blockchainIdentity,
                        blockchainIdentityData.username!!,
                        encryptionKey,
                        useCoinJoin
                    )
                    assetLockTransaction = blockchainIdentity.assetLockTransaction
                }
            } else {
                // don't use platformRepo.getIdentityBalance() because platformRepo.blockchainIdentity is not initialized
                val balanceInfo = blockchainIdentityData.identity?.let { platformRepo.getIdentityBalance(it.id) }
                val balanceRequirement = if (Names.isUsernameContestable(blockchainIdentityData.username!!)) {
                    Constants.DASH_PAY_FEE_CONTESTED
                } else {
                    Constants.DASH_PAY_FEE
                }

                if (balanceInfo != null && balanceInfo.balance < balanceRequirement.value * 1000) {
                    val topupValue = if (Names.isUsernameContestable(blockchainIdentityData.username!!)) {
                        Constants.DASH_PAY_FEE_CONTESTED_NAME
                    } else {
                        Constants.DASH_PAY_FEE
                    }
                    assetLockTransaction = topUpRepository.createTopupTransaction(
                        blockchainIdentity,
                        topupValue,
                        encryptionKey,
                        useCoinJoin
                    )
                }
            }
        }

        if (blockchainIdentityData.creationState <= CreationState.CREDIT_FUNDING_TX_SENDING) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.CREDIT_FUNDING_TX_SENDING)
            val timerIsLock = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_CREATE_ISLOCK)
            // check to see if the funding transaction has been sent previously
            org.bitcoinj.core.Context.propagate(wallet.context)
            if (assetLockTransaction != null) {
                val sent = assetLockTransaction.confidence?.let {
                    it.isSent || it.isIX || it.numBroadcastPeers() > 0 || it.confidenceType == TransactionConfidence.ConfidenceType.BUILDING
                } ?: false

                if (!sent) {
                    topUpRepository.sendTransaction(assetLockTransaction)
                }
            }
            timerIsLock.logTiming()
        }

        //TODO: check to see if the funding transaction has been been confirmed
        if (blockchainIdentityData.creationState <= CreationState.CREDIT_FUNDING_TX_CONFIRMED) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.CREDIT_FUNDING_TX_CONFIRMED)
            // If the tx is in a block, seen by a peer, InstantSend lock, then it is considered confirmed
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
        }

        // did we fail in the previous attempt - lost the vote
        val retryAfterLostUsernameRequest = blockchainIdentityData.creationState == CreationState.VOTING &&
                blockchainIdentityData.usernameRequested == UsernameRequestStatus.LOST_VOTE ||
                blockchainIdentityData.usernameRequested == UsernameRequestStatus.LOCKED
        if (retryAfterLostUsernameRequest) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.CREDIT_FUNDING_TX_CONFIRMED)
        }

        timerStep1.logTiming()
        val timerStep2 = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_CREATE_STEP_2)

        if (blockchainIdentityData.creationState <= CreationState.IDENTITY_REGISTERING) {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.IDENTITY_REGISTERING)
            //
            // Step 3: Register the identity
            //
            val existingIdentity = platformRepo.getIdentityFromPublicKeyId()
            if (existingIdentity != null) {
                val firstIdentityKey = platformRepo.getBlockchainIdentityKey(0, encryptionKey)!!
                platformRepo.recoverIdentityAsync(blockchainIdentity, firstIdentityKey.pubKeyHash)
                if (assetLockTransaction != null) {
                    topUpRepository.topUpIdentity(assetLockTransaction, encryptionKey)
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
                val cftx = blockchainIdentityDataTmp.findAssetLockTransaction(walletApplication.wallet)
                        ?: throw IllegalStateException()

                restoreIdentity(cftx.identityId.bytes)
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
        blockchainIdentityData.usingInvite = true
        platformRepo.updateBlockchainIdentityData(blockchainIdentityData)

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
            topUpRepository.obtainAssetLockTransaction(blockchainIdentity, blockchainIdentityData.invite!!)
        } else {
            // if we are retrying, then we need to initialize the credit funding tx
            topUpRepository.obtainAssetLockTransaction(blockchainIdentity, blockchainIdentityData.invite!!)
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
                val existingIdentity = platformRepo.getIdentityFromPublicKeyId()
                if (existingIdentity != null) {
                    val encryptionKey = platformRepo.getWalletEncryptionKey()
                    val firstIdentityKey = platformRepo.getBlockchainIdentityKey(0, encryptionKey)!!
                    platformRepo.recoverIdentityAsync(blockchainIdentity, firstIdentityKey.pubKeyHash)
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

                    log.error(e.toString())
                    throw e
                }
                throw e
            }
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
        }

        topUpRepository.clearInvitation()

        finishRegistration(blockchainIdentity, encryptionKey)

        invite?.apply {
            val results = platformRepo.getUser(user)
            if (results.isNotEmpty()) {
                val inviterUserId = results[0].dashPayProfile.userId
                SendContactRequestOperation(walletApplication)
                        .create(inviterUserId)
                        .enqueue()
                configuration.apply {
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

        addInviteUserAlert()

        // check for contested username
        if (Names.isUsernameContestable(blockchainIdentityData.username!!)) {
            // get the createdAt date to estimate 2 week voting period
            // check that this username is contested and up for a vote
            // save the verification link in a new document

            if (blockchainIdentityData.creationState <= CreationState.REQUESTED_NAME_CHECKING) {
                platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.REQUESTED_NAME_CHECKING)


                val contenders = platformRepo.getVoteContenders(blockchainIdentityData.username!!)

                blockchainIdentity.currentVotingPeriodStarts = System.currentTimeMillis()
                blockchainIdentity.currentUsernameRequested = true

                if (contenders.isEmpty()) {
                    error("${blockchainIdentityData.username} not found because there are no contenders")
                }

                val documentWithVotes = contenders.map[blockchainIdentity.uniqueIdentifier]
                    ?: error("${blockchainIdentityData.username} does not have ${blockchainIdentity.uniqueIdentifier} as a contender")

                val document = DomainDocument(
                    platformRepo.platform.names.deserialize(documentWithVotes.serializedDocument!!)
                )

                usernameRequestDao.insert(
                    UsernameRequest(
                        UsernameRequest.getRequestId(blockchainIdentity.uniqueIdString, blockchainIdentityData.username!!),
                        blockchainIdentityData.username!!,
                        Names.normalizeString(blockchainIdentityData.username!!),
                        document.createdAt!!,
                        blockchainIdentity.uniqueIdString,
                        blockchainIdentityData.verificationLink,
                        documentWithVotes.votes,
                        contenders.lockVoteTally,
                        false
                    )
                )

                val usernameInfo = blockchainIdentity.usernameStatuses[blockchainIdentity.currentUsername!!]!!

                // determine when voting started by finding the minimum timestamp
                val earliestCreatedAt = contenders.map.values.minOf {
                    val document = platformRepo.platform.names.deserialize(documentWithVotes.serializedDocument!!)
                    document.createdAt ?: 0
                }

                usernameInfo.votingStartedAt = earliestCreatedAt
                usernameInfo.requestStatus = UsernameRequestStatus.VOTING

                platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)

                // schedule work to check the status after voting has ended
                GetUsernameVotingResultOperation(walletApplication)
                    .create(
                        usernameInfo.username!!,
                        blockchainIdentity.uniqueIdentifier.toString(),
                        earliestCreatedAt
                    )
                    .enqueue()
            }


            if (blockchainIdentityData.creationState <= CreationState.REQUESTED_NAME_CHECKED) {
                platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.REQUESTED_NAME_LINK_SAVING)
                platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)

                blockchainIdentityData.verificationLink?.let { verificationLink ->
                    if (verificationLink.matches(Regex("^https?:\\/\\/.+\$"))) {
                        IdentityVerify(platformRepo.platform.platform).createForDashDomain(
                            blockchainIdentityData.username!!,
                            verificationLink,
                            blockchainIdentity.identity!!,
                            WalletSignerCallback(walletApplication.wallet!!, encryptionKey)
                        )
                    }
                }
            }

            if (blockchainIdentityData.creationState <= CreationState.REQUESTED_NAME_LINK_SAVING) {
                platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.REQUESTED_NAME_LINK_SAVED)
                platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)

                // save the verification link
            }

            if (blockchainIdentityData.creationState <= CreationState.REQUESTED_NAME_LINK_SAVED) {
                platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.REQUESTED_NAME_CHECKED)
                platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
            }
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.VOTING)
        } else {
            platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.DONE)
        }

        // Step 6: A profile will not be created, since the user has not yet specified
        //         a display name, public message (bio) or an avatarUrl
        //         However, a default empty profile will be saved to the local database.
        val emptyProfile = DashPayProfile(blockchainIdentity.uniqueIdString, blockchainIdentity.currentUsername!!)
        platformRepo.updateDashPayProfile(emptyProfile)
        analytics.logEvent(
            if (blockchainIdentityData.usingInvite) {
                AnalyticsConstants.UsersContacts.CREATE_USERNAME_INVITE_SUCCESS
            } else {
                AnalyticsConstants.UsersContacts.CREATE_USERNAME_SUCCESS
            }, mapOf()
        )
        platformRepo.init()
        platformSyncService.initSync()

        timerStep3.logTiming()
        // aaaand we're done :)
        log.info("username registration complete")
    }

    private suspend fun addInviteUserAlert() {
        // this alert will be shown or not based on the current balance and will be
        // managed by NotificationsLiveData
        val userAlert = UserAlert(INVITATION_NOTIFICATION_TEXT, INVITATION_NOTIFICATION_ICON)
        userAlertDao.insert(userAlert)
    }

    /**
     * restores an identity using information from the wallet and platform
     */
    private suspend fun restoreIdentity(identity: ByteArray) {
        log.info("Restoring identity and username")
        try {
            platformSyncService.updateSyncStatus(PreBlockStage.StartRecovery)

            // use an "empty" state for each
            blockchainIdentityData = BlockchainIdentityData(CreationState.NONE, null, null, null, true)

            val authExtension =
                walletApplication.wallet!!.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
            //authExtension.setWallet(walletApplication.wallet!!) // why is the wallet not set?  we didn't deserialize it probably!
            val cftxs = authExtension.assetLockTransactions

            val creditFundingTransaction: AssetLockTransaction? =
                cftxs.find { it.identityId.bytes!!.contentEquals(identity) }

            val existingBlockchainIdentityData = blockchainIdentityDataDao.load()
            if (existingBlockchainIdentityData != null && !(existingBlockchainIdentityData.restoring /*&& existingBlockchainIdentityData.creationStateErrorMessage != null*/)) {
                log.info("Attempting restore of existing identity and username; save credit funding txid")
                val blockchainIdentity = platformRepo.blockchainIdentity
                blockchainIdentity.assetLockTransaction = creditFundingTransaction
                existingBlockchainIdentityData.creditFundingTxId = creditFundingTransaction!!.txId
                platformRepo.updateBlockchainIdentityData(existingBlockchainIdentityData)
                return
            }

            val loadingFromAssetLockTransaction = creditFundingTransaction != null
            val existingIdentity: Identity?

            if (!loadingFromAssetLockTransaction) {
                existingIdentity = platformRepo.getIdentityFromPublicKeyId()
                if (existingIdentity == null) {
                    throw IllegalArgumentException("identity $identity does not match a credit funding transaction or it doesn't exist on the network")
                }
            }

            val wallet = walletApplication.wallet!!
            val encryptionKey = platformRepo.getWalletEncryptionKey()
                ?: throw IllegalStateException("cannot obtain wallet encryption key")
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
            if (loadingFromAssetLockTransaction) {
                platformRepo.recoverIdentityAsync(blockchainIdentity, creditFundingTransaction!!)
            } else {
                val firstIdentityKey = platformRepo.getBlockchainIdentityKey(0, encryptionKey)!!
                platformRepo.recoverIdentityAsync(
                    blockchainIdentity,
                    firstIdentityKey.pubKeyHash
                )
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

            if (blockchainIdentity.currentUsername == null) {
                platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.REQUESTED_NAME_CHECKING)

                // check if the network has this name in the queue for voting
                val contestedNames = platformRepo.platform.names.getAllContestedNames()

                contestedNames.forEach { name ->
                    val voteContenders = platformRepo.getVoteContenders(name)
                    val winner = voteContenders.winner
                    voteContenders.map.forEach { (identifier, documentWithVotes) ->
                        if (blockchainIdentity.uniqueIdentifier == identifier) {
                            blockchainIdentity.currentUsername = name
                            // load the serialized doc to get voting period and status...
                            val usernameRequestStatus = if (winner.isEmpty) {
                                UsernameRequestStatus.VOTING
                            } else {
                                val winnerInfo = winner.get().first
                                when {
                                    winnerInfo.isLocked -> UsernameRequestStatus.LOCKED
                                    winnerInfo.isWinner(blockchainIdentity.uniqueIdentifier) -> UsernameRequestStatus.APPROVED
                                    else -> UsernameRequestStatus.LOST_VOTE
                                }
                            }

                            blockchainIdentity.usernameStatuses.apply {
                                clear()
                                val usernameInfo = UsernameInfo(
                                    null,
                                    UsernameStatus.CONFIRMED,
                                    blockchainIdentity.currentUsername!!,
                                    usernameRequestStatus,
                                    0
                                )
                                put(blockchainIdentity.currentUsername!!, usernameInfo)
                            }
                            var votingStartedAt = -1L
                            var label = name
                            if (winner.isEmpty) {
                                val contestedDocument = DomainDocument(
                                    platformRepo.platform.names.deserialize(documentWithVotes.serializedDocument!!)
                                )
                                blockchainIdentity.currentUsername = contestedDocument.label
                                votingStartedAt = contestedDocument.createdAt!!
                                label = contestedDocument.label
                            }
                            val verifyDocument = IdentityVerify(platformRepo.platform.platform).get(
                                blockchainIdentity.uniqueIdentifier,
                                name
                            )

                            usernameRequestDao.insert(
                                UsernameRequest(
                                    UsernameRequest.getRequestId(
                                        blockchainIdentity.uniqueIdString,
                                        blockchainIdentity.currentUsername!!
                                    ),
                                    label,
                                    name,
                                    votingStartedAt,
                                    blockchainIdentity.uniqueIdString,
                                    verifyDocument?.url, // get it from the document
                                    documentWithVotes.votes,
                                    voteContenders.lockVoteTally,
                                    false
                                )
                            )
                            // what if usernameInfo would have been null, we should create it.

                            var usernameInfo = blockchainIdentity.usernameStatuses[blockchainIdentity.currentUsername!!]
                            if (usernameInfo == null) {
                                usernameInfo = UsernameInfo(
                                    null,
                                    UsernameStatus.CONFIRMED,
                                    blockchainIdentity.currentUsername!!,
                                    UsernameRequestStatus.VOTING
                                )
                                blockchainIdentity.usernameStatuses[blockchainIdentity.currentUsername!!] = usernameInfo
                            }

                            // determine when voting started by finding the minimum timestamp
                            val earliestCreatedAt = voteContenders.map.values.minOf {
                                val document = documentWithVotes.serializedDocument?.let { platformRepo.platform.names.deserialize(it) }
                                document?.createdAt ?: 0
                            }

                            usernameInfo.votingStartedAt = earliestCreatedAt
                            usernameInfo.requestStatus = usernameRequestStatus

                            // schedule work to check the status after voting has ended
                            GetUsernameVotingResultOperation(walletApplication)
                                .create(
                                    usernameInfo.username!!,
                                    blockchainIdentity.uniqueIdentifier.toString(),
                                    earliestCreatedAt
                                )
                                .enqueue()
                        }
                    }
                }

                platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.REQUESTED_NAME_CHECKED)
                platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
                
                platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.REQUESTED_NAME_CHECKING)
                
                // recover the verification link

                platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.REQUESTED_NAME_CHECKED)
                platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)

                platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.VOTING)
                platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
            }

            // At this point, let's see what has been recovered.  It is possible that only the identity was recovered.
            // In this case, we should require that the user enters in a new username.
            if (blockchainIdentity.identity != null && blockchainIdentity.currentUsername == null) {
                blockchainIdentityData.creationState = CreationState.USERNAME_REGISTERING
                blockchainIdentityData.restoring = false
                error("missing domain document for ${blockchainIdentity.uniqueId}")
            }

            //
            // Step 6: Find the profile
            //
            platformRepo.recoverDashPayProfile(blockchainIdentity)
            // blockchainIdentity hasn't changed
            platformSyncService.updateSyncStatus(PreBlockStage.GetProfile)

            addInviteUserAlert()

            // We are finished recovering
            blockchainIdentityData.finishRestoration()
            if (blockchainIdentityData.creationState != CreationState.VOTING) {
                platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.DONE)
                platformRepo.updateBlockchainIdentityData(blockchainIdentityData)
                // Complete the entire process
                platformRepo.updateIdentityCreationState(blockchainIdentityData, CreationState.DONE_AND_DISMISS)
            }
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData)

            platformSyncService.updateSyncStatus(PreBlockStage.RecoveryComplete)
            platformRepo.init()
            platformSyncService.initSync()
        } catch (e: Exception) {
            platformSyncService.triggerPreBlockDownloadComplete()
            throw e
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
