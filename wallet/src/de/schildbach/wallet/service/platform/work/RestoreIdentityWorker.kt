/*
 * Copyright 2025 Dash Core Group
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
package de.schildbach.wallet.service.platform.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.service.work.BaseForegroundWorker
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.PreBlockStage
import de.schildbach.wallet.ui.dashpay.work.GetUsernameVotingResultOperation
import de.schildbach.wallet_test.R
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dashpay.UsernameInfo
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.dashj.platform.dashpay.UsernameStatus
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.sdk.platform.DomainDocument
import org.dashj.platform.wallet.IdentityVerify
import org.slf4j.LoggerFactory

@HiltWorker
class RestoreIdentityWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    val walletApplication: WalletApplication,
    val analytics: AnalyticsService,
    val platformSyncService: PlatformSyncService,
    val walletDataProvider: WalletDataProvider,
    val platformRepo: PlatformRepo,
    val coinJoinConfig: CoinJoinConfig,
    val identityConfig: BlockchainIdentityConfig,
    val usernameRequestDao: UsernameRequestDao
) : BaseForegroundWorker(
    context,
    parameters,
    CHANNEL_ID,
    NOTIFICATION_ID,
    context.getString(R.string.restore_identity),
    context.getString(R.string.processing_home_title),
    context.getString(R.string.processing_home_step_1)
) {
    companion object {
        private val log = LoggerFactory.getLogger(RestoreIdentityWorker::class.java)
        const val KEY_PASSWORD = "RestoreIdentityWorker.PASSWORD"
        const val KEY_IDENTITY = "RestoreIdentityWorker.IDENTITY"
        const val KEY_RETRY = "RestoreIdentityWorker.RETRY"
        const val CHANNEL_ID = "restore_identity_work_channel"
        const val NOTIFICATION_ID = 1000
    }

    override suspend fun doWorkInForeground(inForeground: Boolean): Result {
        val password = inputData.getString(KEY_PASSWORD)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_PASSWORD parameter"))
        val identity = inputData.getString(KEY_IDENTITY)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_IDENTITY parameter"))
        val retrying = inputData.getBoolean(KEY_RETRY, false)

        return try {
            // restore identity and all other
            restoreIdentity(Identifier.from(identity).toBuffer(), retrying)
            Result.success(
                workDataOf(
                    KEY_IDENTITY to identity,
                )
            )
        } catch (ex: Exception) {
            analytics.logError(ex, "Restore Identity: failed to restore identity")
            Result.failure(
                workDataOf(
                    KEY_IDENTITY to identity,
                    KEY_ERROR_MESSAGE to formatExceptionMessage("restore identity", ex)
                )
            )
        }
    }

    private suspend fun restoreIdentity(identity: ByteArray, retrying: Boolean) {
        log.info("Restoring identity and username")
        try {
            updateNotification(applicationContext.getString(R.string.processing_home_title), applicationContext.getString(R.string.processing_home_step_1), 5, 0)
            platformSyncService.updateSyncStatus(PreBlockStage.StartRecovery)

            // use an "empty" state for each
            val blockchainIdentityData = BlockchainIdentityData(BlockchainIdentityData.CreationState.NONE, null, null, null, true)

            val authExtension =
                walletDataProvider.wallet!!.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
            //authExtension.setWallet(walletApplication.wallet!!) // why is the wallet not set?  we didn't deserialize it probably!
            val cftxs = authExtension.assetLockTransactions

            val creditFundingTransaction: AssetLockTransaction? =
                cftxs.find { it.identityId.bytes!!.contentEquals(identity) }

            val existingBlockchainIdentityData = identityConfig.load()
            if (existingBlockchainIdentityData != null && !(existingBlockchainIdentityData.restoring /*&& existingBlockchainIdentityData.creationStateErrorMessage != null*/)) {
                log.info("Attempting restore of existing identity and username; save credit funding txid")
                val blockchainIdentity = platformRepo.blockchainIdentity
                blockchainIdentity.assetLockTransaction = creditFundingTransaction
                existingBlockchainIdentityData.creditFundingTxId = creditFundingTransaction!!.txId
                platformRepo.updateBlockchainIdentityData(existingBlockchainIdentityData)
                return
            }
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData)
            updateNotification(applicationContext.getString(R.string.processing_home_title), applicationContext.getString(R.string.processing_home_step_1), 5, 1)
            val loadingFromAssetLockTransaction = creditFundingTransaction != null
            val existingIdentity: Identity?

            if (!loadingFromAssetLockTransaction) {
                existingIdentity = platformRepo.getIdentityFromPublicKeyId()
                if (existingIdentity == null) {
                    throw IllegalArgumentException("identity $identity does not match a credit funding transaction or it doesn't exist on the network")
                }
            }

            val wallet = walletDataProvider.wallet!!
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
            updateNotification(applicationContext.getString(R.string.processing_home_title), applicationContext.getString(R.string.processing_home_step_2), 5, 2)
            platformRepo.updateIdentityCreationState(blockchainIdentityData, BlockchainIdentityData.CreationState.IDENTITY_REGISTERING)
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
            platformRepo.updateIdentityCreationState(blockchainIdentityData, BlockchainIdentityData.CreationState.IDENTITY_REGISTERED)
            platformSyncService.updateSyncStatus(PreBlockStage.GetIdentity)
            updateNotification(applicationContext.getString(R.string.processing_home_title), applicationContext.getString(R.string.processing_home_step_3_restoring), 5, 3)

            //
            // Step 4: We don't need to find the preorder documents
            //

            //
            // Step 5: Find the username
            //
            platformRepo.updateIdentityCreationState(blockchainIdentityData, BlockchainIdentityData.CreationState.USERNAME_REGISTERING)
            platformRepo.recoverUsernamesAsync(blockchainIdentity)
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
            platformRepo.updateIdentityCreationState(blockchainIdentityData, BlockchainIdentityData.CreationState.USERNAME_REGISTERED)
            platformSyncService.updateSyncStatus(PreBlockStage.GetName)
            updateNotification(applicationContext.getString(R.string.processing_home_title), applicationContext.getString(R.string.processing_home_step_3_restoring), 5, 4)

            if (blockchainIdentity.currentUsername == null) {
                platformRepo.updateIdentityCreationState(blockchainIdentityData, BlockchainIdentityData.CreationState.REQUESTED_NAME_CHECKING)

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

                if (blockchainIdentity.currentUsername != null) {

                    platformRepo.updateIdentityCreationState(
                        blockchainIdentityData,
                        BlockchainIdentityData.CreationState.REQUESTED_NAME_CHECKED
                    )
                    platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
                    platformRepo.updateIdentityCreationState(
                        blockchainIdentityData,
                        BlockchainIdentityData.CreationState.REQUESTED_NAME_CHECKING
                    )

                    // recover the verification link
                    platformRepo.updateIdentityCreationState(
                        blockchainIdentityData,
                        BlockchainIdentityData.CreationState.REQUESTED_NAME_CHECKED
                    )
                    platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
                    // set voting state
                    platformRepo.updateIdentityCreationState(
                        blockchainIdentityData,
                        BlockchainIdentityData.CreationState.VOTING
                    )
                    platformRepo.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
                }
            }
            updateNotification(applicationContext.getString(R.string.processing_home_title), applicationContext.getString(R.string.processing_home_step_3_restoring), 5, 5)

            // At this point, let's see what has been recovered.  It is possible that only the identity was recovered.
            // In this case, we should require that the user enters in a new username.
            if (blockchainIdentity.identity != null && blockchainIdentity.currentUsername == null) {
                blockchainIdentityData.creationState = BlockchainIdentityData.CreationState.USERNAME_REGISTERING
                blockchainIdentityData.restoring = false
                platformRepo.updateBlockchainIdentityData(blockchainIdentityData)
                error("missing domain document for ${blockchainIdentity.uniqueId}")
            }

            //
            // Step 6: Find the profile
            //
            platformRepo.recoverDashPayProfile(blockchainIdentity)
            // blockchainIdentity hasn't changed
            platformSyncService.updateSyncStatus(PreBlockStage.GetProfile)

            platformRepo.addInviteUserAlert()

            // We are finished recovering
            blockchainIdentityData.finishRestoration()
            if (blockchainIdentityData.creationState != BlockchainIdentityData.CreationState.VOTING) {
                platformRepo.updateIdentityCreationState(blockchainIdentityData, BlockchainIdentityData.CreationState.DONE)
                platformRepo.updateBlockchainIdentityData(blockchainIdentityData)
                // Complete the entire process
                platformRepo.updateIdentityCreationState(blockchainIdentityData, BlockchainIdentityData.CreationState.DONE_AND_DISMISS)
            }
            platformRepo.updateBlockchainIdentityData(blockchainIdentityData)

            platformSyncService.updateSyncStatus(PreBlockStage.RecoveryComplete)
            platformRepo.init()
            platformSyncService.initSync(true)
        } catch (e: Exception) {
            val blockchainIdentityData = identityConfig.load()
            blockchainIdentityData?.let {
                platformRepo.updateIdentityCreationState(it, it.creationState, e)
            }
            // triggering the end of the preBlockDownload stage as complete
            // could be problematic, what if there were errors
            platformSyncService.triggerPreBlockDownloadComplete()
            throw e
        }
    }
}