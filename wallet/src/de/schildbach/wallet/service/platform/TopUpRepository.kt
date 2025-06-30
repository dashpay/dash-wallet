/*
 * Copyright 2024 Dash Core Group.
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

import android.net.Uri
import com.google.common.base.Stopwatch
import com.appsflyer.AppsFlyerLib
import com.appsflyer.share.LinkGenerator
import com.appsflyer.share.LinkGenerator.ResponseListener
import com.appsflyer.share.ShareInviteHelper
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.data.DynamicLink
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.dao.TopUpsDao
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.Invitation
import de.schildbach.wallet.database.entity.TopUp
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.platform.work.TopupIdentityWorker
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.DumpedPrivateKey
import org.bitcoinj.core.RejectMessage
import org.bitcoinj.core.RejectedTransactionException
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.core.Utils
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.quorums.InstantSendLock
import org.bitcoinj.wallet.Wallet
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.WalletDataProvider
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dpp.toHex
import org.dashj.platform.sdk.platform.Names
import org.slf4j.LoggerFactory
import javax.inject.Inject
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.work.SendInviteWorker
import de.schildbach.wallet_test.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.bitcoinj.coinjoin.CoinJoin
import org.bitcoinj.core.Address
import org.bitcoinj.core.AddressFormatException
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.wallet.AuthenticationKeyChain
import kotlinx.coroutines.flow.first
import okhttp3.internal.wait
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.dashj.platform.dapiclient.MaxRetriesReachedException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.resume

/**
 * contains topup related functions that are used by:
 * 1. [CreateIdentityService] to create an identity
 * 2. [TopupIdentityWorker] to topup an identity
 * 3. [SendInviteWorker] to create Invitations (dynamic link)
 */
interface TopUpRepository {
    suspend fun createAssetLockTransaction(
        blockchainIdentity: BlockchainIdentity,
        username: String,
        keyParameter: KeyParameter?,
        useCoinJoin: Boolean
    )

    fun createTopupTransaction(
        blockchainIdentity: BlockchainIdentity,
        topupAmount: Coin,
        keyParameter: KeyParameter?,
        useCoinJoin: Boolean
    ): AssetLockTransaction

    fun obtainAssetLockTransaction(
        blockchainIdentity: BlockchainIdentity,
        invite: InvitationLinkData
    )

    /** sends the transaction and waits for IS or CL */
    suspend fun sendTransaction(cftx: AssetLockTransaction): Boolean

    /** top up identity and save topup state to the db */
    suspend fun topUpIdentity(
        topupAssetLockTransaction: AssetLockTransaction,
        aesKeyParameter: KeyParameter
    )

    suspend fun checkTopUps(aesKeyParameter: KeyParameter)

    // invitation related methods
    suspend fun createInviteFundingTransaction(
        blockchainIdentity: BlockchainIdentity,
        fundingAddress: Address,
        keyParameter: KeyParameter?,
        topupAmount: Coin
    ): AssetLockTransaction

    suspend fun updateInvitation(invitation: Invitation)
    suspend fun getInvitation(userId: String): Invitation?

    suspend fun createAppsFlyerLink(
        dashPayProfile: DashPayProfile,
        assetLockTx: AssetLockTransaction,
        aesKeyParameter: KeyParameter
    ): DynamicLink

    suspend fun checkInvites(encryptionKey: KeyParameter)
    suspend fun updateInvitations()
    fun handleSentAssetLockTransaction(cftx: AssetLockTransaction, blockTimestamp: Long)
    /**
     * validates an invite
     *
     * @return Returns true if it is valid, false if the invite has been used.
     *
     * @throws Exception if the invite is invalid
     */
    fun validateInvitation(invite: InvitationLinkData): Boolean
    fun close()

    fun getAssetLockTransaction(invite: InvitationLinkData): AssetLockTransaction
    fun isInvitationMixed(inviteAssetLockTx: AssetLockTransaction): Boolean
    suspend fun clearInvitation()
}

class TopUpRepositoryImpl @Inject constructor(
    private val walletApplication: WalletApplication,
    private val walletDataProvider: WalletDataProvider,
    private val platformRepo: PlatformRepo,
    private val topUpsDao: TopUpsDao,
    private val dashPayProfileDao: DashPayProfileDao,
    private val invitationsDao: InvitationsDao,
    private val coinJoinConfig: CoinJoinConfig,
    private val dashPayConfig: DashPayConfig
) : TopUpRepository {
    companion object {
        private val log = LoggerFactory.getLogger(TopUpRepositoryImpl::class.java)
        private const val MIN_DUST_FACTOR = 10L
    }

    private val workerJob = Job()
    private var workerScope = CoroutineScope(workerJob + Dispatchers.IO)
    private val platform = platformRepo.platform
    private val authExtension by lazy { walletDataProvider.wallet!!.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension }

    override suspend fun createAssetLockTransaction(
        blockchainIdentity: BlockchainIdentity,
        username: String,
        keyParameter: KeyParameter?,
        useCoinJoin: Boolean
    ) {
        val fee = if (Names.isUsernameContestable(username)) {
            Constants.DASH_PAY_FEE_CONTESTED
        } else {
            Constants.DASH_PAY_FEE
        }
        val balance = walletDataProvider.observeSpendableBalance().first()
        val emptyWallet = balance == fee ||
                (balance >= fee && balance <= (fee + Transaction.MIN_NONDUST_OUTPUT.multiply(MIN_DUST_FACTOR)))
        Context.propagate(walletDataProvider.wallet!!.context)
        val cftx = blockchainIdentity.createAssetLockTransaction(
            fee,
            keyParameter,
            useCoinJoin,
            returnChange = true,
            emptyWallet = emptyWallet
        )
        blockchainIdentity.initializeAssetLockTransaction(cftx)
    }

    override fun createTopupTransaction(
        blockchainIdentity: BlockchainIdentity,
        topupAmount: Coin,
        keyParameter: KeyParameter?,
        useCoinJoin: Boolean
    ): AssetLockTransaction {
        Context.propagate(walletDataProvider.wallet!!.context)
        val balance = walletDataProvider.wallet!!.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE)
        val emptyWallet = balance == topupAmount && balance <= (topupAmount + Transaction.MIN_NONDUST_OUTPUT)
        return blockchainIdentity.createTopupFundingTransaction(
            topupAmount,
            keyParameter,
            useCoinJoin,
            returnChange = true,
            emptyWallet = emptyWallet
        )
    }

    override fun getAssetLockTransaction(invite: InvitationLinkData): AssetLockTransaction {
        Context.propagate(walletDataProvider.wallet!!.context)
        var cftxData = platform.client.getTransaction(invite.cftx)
        //TODO: remove when iOS uses big endian
        if (cftxData == null)
            cftxData = platform.client.getTransaction(Sha256Hash.wrap(invite.cftx).reversedBytes.toHex())
        val assetLockTx = AssetLockTransaction(platform.params, cftxData!!)
        val privateKey = DumpedPrivateKey.fromBase58(platform.params, invite.privateKey).key
        assetLockTx.addAssetLockPublicKey(privateKey)
        // TODO: when all instantsend locks are deterministic, we don't need the catch block
        val instantSendLock = InstantSendLock(platform.params, Utils.HEX.decode(invite.instantSendLock), InstantSendLock.ISDLOCK_VERSION)

        assetLockTx.confidence.setInstantSendLock(instantSendLock)
        return assetLockTx
    }

    override fun isInvitationMixed(inviteAssetLockTx: AssetLockTransaction): Boolean {
        val inputTxes = hashMapOf<Sha256Hash, Transaction>()
        return inviteAssetLockTx.inputs.map { input ->
            val tx = inputTxes[input.outpoint.hash]
                ?: platformRepo.platform.client.getTransaction(input.outpoint.hash.toString())?.let {
                    Transaction(Constants.NETWORK_PARAMETERS, it)
                }
            log.info("obtaining input tx: {}", input.outpoint.hash)
            tx?.let {
                log.info(" --> input tx: {}", tx.txId)
                input.connect(it.getOutput(input.outpoint.index))
                log.info(" --> input tx: {}", input.value)
                input.value
            } ?: Coin.ZERO
        }.all { value ->
            CoinJoin.isDenominatedAmount(value)
        }
    }

    override suspend fun clearInvitation() {
        dashPayConfig.set(DashPayConfig.INVITATION_LINK, "")
        dashPayConfig.set(DashPayConfig.INVITATION_FROM_ONBOARDING, false)
    }

    //
    // Step 2 is to obtain the credit funding transaction for invites
    //
    override fun obtainAssetLockTransaction(blockchainIdentity: BlockchainIdentity, invite: InvitationLinkData) {
        Context.propagate(walletDataProvider.wallet!!.context)
        var cftxData = platform.client.getTransaction(invite.cftx)
        //TODO: remove when iOS uses big endian
        if (cftxData == null)
            cftxData = platform.client.getTransaction(Sha256Hash.wrap(invite.cftx).reversedBytes.toHex())
        val assetLockTx = AssetLockTransaction(platform.params, cftxData!!)
        val privateKey = DumpedPrivateKey.fromBase58(platform.params, invite.privateKey).key
        assetLockTx.addAssetLockPublicKey(privateKey)

        // TODO: when all instantsend locks are deterministic, we don't need the catch block
        val instantSendLock = InstantSendLock(platform.params, Utils.HEX.decode(invite.instantSendLock), InstantSendLock.ISDLOCK_VERSION)

        assetLockTx.confidence.setInstantSendLock(instantSendLock)
        blockchainIdentity.initializeAssetLockTransaction(assetLockTx)
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
    override suspend fun sendTransaction(cftx: AssetLockTransaction): Boolean {
        log.info("Sending credit funding transaction: ${cftx.txId}")
        return suspendCoroutine { continuation ->
            log.info("adding credit funding transaction listener for ${cftx.txId}")
            cftx.getConfidence(Constants.CONTEXT).addEventListener(object : TransactionConfidence.Listener {
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
                        TransactionConfidence.Listener.ChangeReason.SEEN_PEERS -> {
                            // If this transaction has been seen by more than 1 peer,
                            // then it has been sent successfully.  However,
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

    private suspend fun addTopUp(txId: Sha256Hash): TopUp {
        val topUp = TopUp(
            txId,
            platformRepo.blockchainIdentity.uniqueIdString
        )
        topUpsDao.insert(topUp)
        return topUp
    }

    override suspend fun topUpIdentity(
        topUpTx: AssetLockTransaction,
        aesKeyParameter: KeyParameter
    ) {
        val topUp = topUpsDao.getByTxId(
            topUpTx.txId
        ) ?: addTopUp(topUpTx.txId)
        Context.propagate(walletDataProvider.wallet!!.context)
        val confidence = topUpTx.getConfidence(walletDataProvider.wallet!!.context)
        val wasTxSent = confidence.isChainLocked ||
            confidence.isTransactionLocked ||
            confidence.numBroadcastPeers() > 0
        if (!wasTxSent) {
            sendTransaction(topUpTx)
        }
        log.info("topup tx sent: {}", topUpTx.txId)
        try {
            platformRepo.blockchainIdentity.topUp(
                topUpTx,
                aesKeyParameter,
                useISLock = true,
                waitForChainlock = true
            )
            log.info("topup success: {}", topUpTx.txId)
            topUpsDao.insert(topUp.copy(creditedAt = System.currentTimeMillis()))
        } catch (e: Exception) {
            val regex = Regex(""".*Asset lock transaction [a-fA-F0-9]{64} output \d+ already completely used""")
            if (e.message?.let { regex.matches(it) || it.contains("Object already exists: state transition already in chain")} == true) {
                // the asset lock was already used
                topUpsDao.insert(topUp.copy(creditedAt = System.currentTimeMillis()))
            } else {
                throw e
            }
        }
    }

    private var checkedPreviousTopUps = false

    override suspend fun checkTopUps(aesKeyParameter: KeyParameter) {
        val topUps = topUpsDao.getUnused()
        topUps.forEach { topUp ->
            try {
                val tx = walletDataProvider.wallet!!.getTransaction(topUp.txId)
                val assetLockTx = authExtension.getAssetLockTransaction(tx)
                topUpIdentity(assetLockTx, aesKeyParameter)
                topUpsDao.insert(topUp.copy(creditedAt = System.currentTimeMillis()))
            } catch (e: Exception) {
                // swallow
            }
        }
        // only check once per app start
        if (!checkedPreviousTopUps) {
            log.info("checking all topup transactions")
            authExtension.topupFundingTransactions.forEach { assetLockTx ->
                val topUp = topUpsDao.getByTxId(assetLockTx.txId)
                if (topUp == null || topUp.notUsed()) {
                    val identity = topUp?.toUserId ?: platformRepo.blockchainIdentity.uniqueIdentifier.toString()
                    if (topUp == null) {
                        topUpsDao.insert(TopUp(assetLockTx.txId, identity))
                    }
                    topUpIdentity(assetLockTx, platformRepo.getWalletEncryptionKey()!!)
//                    TopupIdentityOperation(walletApplication)
//                        .create(identity, assetLockTx.txId)
//                        .enqueue()
                }
            }
            checkedPreviousTopUps = true
        }
    }

    override suspend fun createInviteFundingTransaction(
        blockchainIdentity: BlockchainIdentity,
        fundingAddress: Address,
        keyParameter: KeyParameter?,
        topupAmount: Coin
    ): AssetLockTransaction {
        // dashj Context does not work with coroutines well, so we need to call Context.propogate
        // in each suspend method that uses the dashj Context
        Context.propagate(walletApplication.wallet!!.context)
        log.info("createInviteFundingTransactionAsync prop context")
        val balance = walletApplication.wallet!!.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE)
        val emptyWallet = balance == topupAmount && balance <= (topupAmount + Transaction.MIN_NONDUST_OUTPUT)

        val cftx = blockchainIdentity.createInviteFundingTransaction(
            topupAmount,
            keyParameter,
            useCoinJoin = coinJoinConfig.getMode() != CoinJoinMode.NONE,
            returnChange = true,
            emptyWallet = emptyWallet
        )
        val invitation = Invitation(
            fundingAddress.toBase58(),
            cftx.identityId.toStringBase58(),
            cftx.txId,
            System.currentTimeMillis()
        )
        // update database
        updateInvitation(invitation)

        sendTransaction(cftx)
        // update database
        updateInvitation(invitation.copy(sentAt = System.currentTimeMillis()))
        return cftx
    }

    override suspend fun updateInvitation(invitation: Invitation) {
        invitationsDao.insert(invitation)
    }

    override suspend fun getInvitation(userId: String): Invitation? {
        return invitationsDao.loadByUserId(userId)
    }

//    override suspend fun createDynamicLink(
//        dashPayProfile: DashPayProfile,
//        assetLockTx: AssetLockTransaction,
//        aesKeyParameter: KeyParameter
//    ): DynamicLink {
//        log.info("creating dynamic link for invitation")
//        // dashj Context does not work with coroutines well, so we need to call Context.propogate
//        // in each suspend method that uses the dashj Context
//        Context.propagate(walletDataProvider.wallet!!.context)
//        val username = dashPayProfile.username
//        val avatarUrlEncoded = URLEncoder.encode(dashPayProfile.avatarUrl, StandardCharsets.UTF_8.displayName())
//        return FirebaseDynamicLinks.getInstance()
//            .createDynamicLink().apply {
//                link = InvitationLinkData.create(username, dashPayProfile.displayName, avatarUrlEncoded, assetLockTx, aesKeyParameter).link
//                domainUriPrefix = Constants.Invitation.DOMAIN_URI_PREFIX
//                setAndroidParameters(DynamicLink.AndroidParameters.Builder().build())
//                setIosParameters(
//                    DynamicLink.IosParameters.Builder(
//                    Constants.Invitation.IOS_APP_BUNDLEID
//                ).apply {
//                    appStoreId = Constants.Invitation.IOS_APP_APPSTOREID
//                }.build())
//            }
//            .setSocialMetaTagParameters(DynamicLink.SocialMetaTagParameters.Builder().apply {
//                title = walletApplication.getString(R.string.invitation_preview_title)
//                val nameLabel = dashPayProfile.nameLabel
//                val nameLabelEncoded = URLEncoder.encode(nameLabel, StandardCharsets.UTF_8.displayName())
//                imageUrl = Uri.parse("https://invitations.dashpay.io/fun/invite-preview?display-name=$nameLabelEncoded&avatar-url=$avatarUrlEncoded")
//                description = walletApplication.getString(R.string.invitation_preview_message, nameLabel)
//            }.build())
//            .setGoogleAnalyticsParameters(
//                DynamicLink.GoogleAnalyticsParameters.Builder(
//                    walletApplication.getString(R.string.app_name_dashpay),
//                    Constants.Invitation.UTM_MEDIUM,
//                    Constants.Invitation.UTM_CAMPAIGN
//                ).build()
//            )
//            .buildDynamicLink()
//    }
//
//    override suspend fun buildShortDynamicLink(dynamicLink: DynamicLink): ShortDynamicLink {
//        return suspendCoroutine { continuation ->
//            FirebaseDynamicLinks.getInstance().createDynamicLink()
//                .setLongLink(dynamicLink.uri)
//                .buildShortDynamicLink()
//                .addOnSuccessListener {
//                    log.debug("dynamic link successfully created")
//                    continuation.resume(it)
//                }
//                .addOnFailureListener {
//                    log.error(it.message, it)
//                    continuation.resumeWithException(it)
//                }
//        }
//    }

    override suspend fun createAppsFlyerLink(
        dashPayProfile: DashPayProfile,
        assetLockTx: AssetLockTransaction,
        aesKeyParameter: KeyParameter
    ): DynamicLink {
        log.info("creating AppsFlyer link for invitation")
        // dashj Context does not work with coroutines well, so we need to call Context.propogate
        // in each suspend method that uses the dashj Context
        Context.propagate(walletDataProvider.wallet!!.context)
        val username = dashPayProfile.username
        val avatarUrlEncoded = URLEncoder.encode(dashPayProfile.avatarUrl, StandardCharsets.UTF_8.displayName())
        val invitationLinkData = InvitationLinkData.create(username, dashPayProfile.displayName, avatarUrlEncoded, assetLockTx, aesKeyParameter)

        return suspendCoroutine { continuation ->
            val linkGenerator = ShareInviteHelper.generateInviteUrl(walletApplication)
            linkGenerator.setBaseDeeplink(invitationLinkData.link.toString())
            // linkGenerator.addParameter("af_android_url", invitationLinkData.link.toString())
            linkGenerator.setChannel("invitation")
            linkGenerator.setReferrerUID(UUID.randomUUID().toString())
            linkGenerator.setCampaign("dashpay_invitation")
            val title = walletApplication.getString(R.string.invitation_preview_title)
            val nameLabel = dashPayProfile.nameLabel
            val nameLabelEncoded = URLEncoder.encode(nameLabel, StandardCharsets.UTF_8.displayName())
            val imageUrl = Uri.parse("https://invitations.dashpay.io/fun/invite-preview?display-name=$nameLabelEncoded&avatar-url=$avatarUrlEncoded")
            val description = walletApplication.getString(R.string.invitation_preview_message, nameLabel)

            linkGenerator.addParameters(
                mapOf(
                    "af_og_title" to title,
                    "af_og_description" to description,
                    "af_og_image" to imageUrl.toString()
                )
            )
            linkGenerator.generateLink(walletApplication, object : ResponseListener {
                override fun onResponse(link: String?) {

                    log.info("AppsFlyer link generated successfully: {}", link)
                    log.info("AppsFlyer linkgenerator : {}", linkGenerator.generateLink())
                    log.info("AppsFlyer af_dp : {}", invitationLinkData.link.toString())

                    continuation.resume(
                        DynamicLink(
                            link!!,
                            linkGenerator.generateLink(),
                            invitationLinkData.link.toString(),
                            DynamicLink.AppsFlyer
                        )
                    )
                }

                override fun onResponseError(error: String?) {
                    log.error("Failed to generate AppsFlyer link: $error")
                    continuation.resumeWithException(Exception("Failed to generate AppsFlyer link: $error"))
                }
            })
        }
    }

    private var checkedPreviousInvitations = false

    override suspend fun checkInvites(encryptionKey: KeyParameter) {
        try {
            if (!checkedPreviousInvitations) {
                // get a list of all invite funding transactions
                val fundingTxes = authExtension.invitationFundingTransactions.associateBy { it.txId }.toMutableMap()
                // get a list of all created invites from the DB
                val invitations = invitationsDao.loadAll()
                // x-ref them and finish the ones that are not completed
                invitations.forEach { invitation ->
                    if (invitation.dynamicLink != null) {
                        fundingTxes.remove(invitation.txid)
                    } else {
                        // TODO: should we fix the link now or let the user do it
                        val dashPayProfile = platformRepo.getLocalUserProfile()
                        val assetLockTx = fundingTxes[invitation.txid]
                        if (assetLockTx != null) {
                            val appsFlyerLink = createAppsFlyerLink(dashPayProfile!!, assetLockTx, encryptionKey)
                            updateInvitation(
                                invitation.copy(
                                    shortDynamicLink = appsFlyerLink.shortLink,
                                    dynamicLink = appsFlyerLink.link
                                )
                            )
                        }
                    }
                }
                // look at remaining fundingTxes
                fundingTxes.forEach { (_, assetLockTx) ->
                    val fundingAddress = Address.fromKey(Constants.NETWORK_PARAMETERS, assetLockTx.assetLockPublicKey)
                    val invitation = Invitation(
                        fundingAddress.toBase58(),
                        assetLockTx.identityId.toStringBase58(),
                        assetLockTx.txId,
                        assetLockTx.updateTime.time,
                        "",
                        assetLockTx.updateTime.time
                    )
                    updateInvitation(invitation)
                    // TODO: should we recreate the links or have the user do it?
//            val dynamicLink = createDynamicLink(dashPayProfile!!, assetLockTx, encryptionKey)
//            val shortDynamicLink = buildShortDynamicLink(dynamicLink)
//            platformRepo.updateInvitation(
//                invitation.copy(
//                    shortDynamicLink = shortDynamicLink.shortLink.toString(),
//                    dynamicLink = dynamicLink.uri.toString()
//                )
//            )
                }
            }
        } finally {
            checkedPreviousInvitations = true
        }
    }

    /**
     * Updates invitation status
     */
    override suspend fun updateInvitations() {
        val invitations = invitationsDao.loadAll()
        for (invitation in invitations) {
            if (invitation.acceptedAt == 0L) {
                val identity = platform.identities.get(invitation.userId)
                if (identity != null) {
                    platformRepo.updateDashPayProfile(identity.id.toString())
                    updateInvitation(
                        invitation.copy(
                            acceptedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    override fun handleSentAssetLockTransaction(cftx: AssetLockTransaction, blockTimestamp: Long) {
        val extension = authExtension

        if (platformRepo.hasBlockchainIdentity) {
            workerScope.launch(Dispatchers.IO) {
                // Context.getOrCreate(platform.params)
                val inviteKey = extension.invitationFundingKeyChain.findKeyFromPubHash(cftx.assetLockPublicKeyId.bytes)
                val isInvite = inviteKey != null
                val isTopup = extension.identityTopupKeyChain.findKeyFromPubHash(cftx.assetLockPublicKeyId.bytes) != null
                val isIdentity = extension.identityFundingKeyChain.findKeyFromPubHash(cftx.assetLockPublicKeyId.bytes) != null
                val identityId = cftx.identityId.toStringBase58()
                if (isInvite && !isTopup && !isIdentity && invitationsDao.loadByUserId(identityId) == null) {
                    // this is not in our database
                    var invite = Invitation(
                        Address.fromKey(Constants.NETWORK_PARAMETERS, inviteKey).toBase58(),
                        identityId,
                        cftx.txId,
                        blockTimestamp,
                        "",
                        blockTimestamp,
                        0
                    )

                    // profile information here
                    try {
                        if (platformRepo.updateDashPayProfile(identityId)) {
                            val profile = dashPayProfileDao.loadByUserId(identityId)
                            invite = invite.copy(acceptedAt = profile?.createdAt ?: -1) // it was accepted in the past, use profile creation as the default
                        }
                    } catch (e: NullPointerException) {
                        // swallow, the identity was not found for this invite
                        log.error("NullPointerException encountered while updating DashPayProfile", e)
                    } catch (e: MaxRetriesReachedException) {
                        // swallow, the profile could not be retrieved
                        // the invite status update function should be able to try again
                        log.error("MaxRetriesReachedException encountered while updating DashPayProfile", e)
                    }
                    invitationsDao.insert(invite)
                }
            }
        }
    }

    private fun getAssetLockTransaction(txId: String): ByteArray? {
        for (attempt in 0..10) {
            val txByteArray = platform.client.getTransaction(txId)
            if (txByteArray != null) {
                return txByteArray
            }
        }
        log.info("cannot find asset lock transaction: $txId")
        return null
    }


    /**
     * validates an invite
     *
     * @return Returns true if it is valid, false if the invite has been used.
     *
     * @throws Exception if the invite is invalid
     */

    override fun validateInvitation(invite: InvitationLinkData): Boolean {
        val stopWatch = Stopwatch.createStarted()
        var tx = getAssetLockTransaction(invite.cftx)
        log.info("validateInvitation: obtaining transaction info for invite took $stopWatch")
        // TODO: remove when iOS uses big endian
        if (tx == null) {
            tx = getAssetLockTransaction(Sha256Hash.wrap(invite.cftx).reversedBytes.toHex())
        }
        if (tx != null) {
            val cfTx = AssetLockTransaction(Constants.NETWORK_PARAMETERS, tx)
            val identity = platform.identities.get(cfTx.identityId.toStringBase58())
            if (identity == null) {
                // determine if the invite has enough credits
                if (cfTx.lockedOutput.value < Constants.DASH_PAY_INVITE_MIN) {
                    val reason = "Invite does not have enough credits ${cfTx.lockedOutput.value} < ${Constants.DASH_PAY_INVITE_MIN}"
                    log.warn(reason)
                    log.info("validateInvitation took $stopWatch")
                    throw InsufficientMoneyException(cfTx.lockedOutput.value, reason)
                }
                return try {
                    DumpedPrivateKey.fromBase58(Constants.NETWORK_PARAMETERS, invite.privateKey)
                    // TODO: when all instantsend locks are deterministic, we don't need the catch block
                    try {
                        InstantSendLock(
                            Constants.NETWORK_PARAMETERS,
                            Utils.HEX.decode(invite.instantSendLock),
                            InstantSendLock.ISDLOCK_VERSION
                        )
                    } catch (e: Exception) {
                        InstantSendLock(
                            Constants.NETWORK_PARAMETERS,
                            Utils.HEX.decode(invite.instantSendLock),
                            InstantSendLock.ISLOCK_VERSION
                        )
                    }
                    log.info("Invite is valid and took $stopWatch")
                    true
                } catch (e: AddressFormatException.WrongNetwork) {
                    log.warn("Invite has private key from wrong network: $e and took $stopWatch")
                    throw e
                } catch (e: AddressFormatException) {
                    log.warn("Invite has invalid private key: $e and took $stopWatch")
                    throw e
                } catch (e: Exception) {
                    log.warn("Invite has invalid instantSendLock: $e and took $stopWatch")
                    throw e
                }
            } else {
                log.warn("Invitation has been used: ${identity.id} and took $stopWatch")
                return false
            }
        }
        log.warn("Invitation uses an invalid transaction ${invite.cftx} and took $stopWatch")
        throw IllegalArgumentException("Invitation uses an invalid transaction ${invite.cftx}")
    }

    override fun close() {
        workerScope.cancel()
    }
}
