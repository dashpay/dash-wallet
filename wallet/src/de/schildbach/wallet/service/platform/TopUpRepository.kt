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

import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.dao.TopUpsDao
import de.schildbach.wallet.database.entity.TopUp
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
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension

/**
 * contains topup related functions that are used by [CreateIdentityService] to create
 * an identity and by [TopupIdentityWorker] to topup an identity
 */
interface TopUpRepository {
    fun createAssetLockTransaction(
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
}

class TopUpRepositoryImpl @Inject constructor(
    private val walletApplication: WalletApplication,
    private val walletDataProvider: WalletDataProvider,
    private val platformRepo: PlatformRepo,
    private val topUpsDao: TopUpsDao
) : TopUpRepository {
    companion object {
        private val log = LoggerFactory.getLogger(TopUpRepositoryImpl::class.java)
        private const val MIN_DUST_FACTOR = 10L
    }

    private val platform = platformRepo.platform
    private val authExtension by lazy { walletDataProvider.wallet!!.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension }

    override fun createAssetLockTransaction(
        blockchainIdentity: BlockchainIdentity,
        username: String,
        keyParameter: KeyParameter?,
        useCoinJoin: Boolean
    ) {
        Context.propagate(walletDataProvider.wallet!!.context)
        val fee = if (Names.isUsernameContestable(username)) {
            Constants.DASH_PAY_FEE_CONTESTED
        } else {
            Constants.DASH_PAY_FEE
        }
        val balance = walletDataProvider.wallet!!.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE)
        val emptyWallet = balance == fee && balance <= (fee + Transaction.MIN_NONDUST_OUTPUT.multiply(MIN_DUST_FACTOR))
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
}