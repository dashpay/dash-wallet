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
import com.google.common.base.Stopwatch
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.service.work.BaseForegroundWorker
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.PreBlockStage
import de.schildbach.wallet.ui.dashpay.work.GetUsernameVotingResultOperation
import de.schildbach.wallet_test.R
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dashpay.UsernameInfo
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.dashj.platform.dashpay.UsernameStatus
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.sdk.platform.DomainDocument
import org.dashj.platform.sdk.platform.Names
import org.dashj.platform.wallet.IdentityVerify
import org.slf4j.LoggerFactory

@HiltWorker
class RestoreIdentityWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    val walletApplication: WalletApplication,
    val analytics: AnalyticsService,
    val platformSyncService: PlatformSyncService,
    val walletDataProvider: WalletData,
    val identityRepository: IdentityRepository,
    val platformRepo: PlatformRepo,
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
        const val KEY_FROM_CREATION = "RestoreIdentityWorker.FROM_CREATION"
        const val CHANNEL_ID = "restore_identity_work_channel"
        const val NOTIFICATION_ID = 1000
    }

    override suspend fun doWorkInForeground(inForeground: Boolean): Result {
        val password = inputData.getString(KEY_PASSWORD)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_PASSWORD parameter"))
        val identity = inputData.getString(KEY_IDENTITY)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_IDENTITY parameter"))
        val retrying = inputData.getBoolean(KEY_RETRY, false)
        val fromCreation = inputData.getBoolean(KEY_FROM_CREATION, false)

        return try {
            // restore identity and all other
            restoreIdentity(Identifier.from(identity).toBuffer(), retrying, fromCreation)
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

    private suspend fun restoreIdentity(identity: ByteArray, retrying: Boolean, fromCreation: Boolean = false) {
        log.info("Restoring identity and username")
        try {
            updateNotification(applicationContext.getString(R.string.processing_home_title), applicationContext.getString(R.string.processing_home_step_1), 5, 0)
            platformSyncService.updateSyncStatus(PreBlockStage.StartRecovery)

            // use an "empty" state for each
            val blockchainIdentityData = BlockchainIdentityData(IdentityCreationState.NONE, null, null, null, null, true)

            val authExtension =
                walletDataProvider.wallet!!.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
            //authExtension.setWallet(walletApplication.wallet!!) // why is the wallet not set?  we didn't deserialize it probably!
            val cftxs = authExtension.assetLockTransactions

            identityRepository.updateBlockchainIdentityData(blockchainIdentityData)
            updateNotification(applicationContext.getString(R.string.processing_home_title), applicationContext.getString(R.string.processing_home_step_1), 5, 1)
            val existingIdentity = identityRepository.getIdentityFromPublicKeyId()
                ?: throw IllegalArgumentException("identity $identity doesn't exist on the network")

            val wallet = walletDataProvider.wallet!!
            val encryptionKey = platformRepo.getWalletEncryptionKey()
                ?: throw IllegalStateException("cannot obtain wallet encryption key")
            val seed = wallet.keyChainSeed ?: throw IllegalStateException("cannot obtain wallet seed")

            // create the Blockchain Identity object
            val blockchainIdentity = BlockchainIdentity(platformRepo.platform.platform, 0, wallet, authExtension)
            // this process should have been done already, otherwise the credit funding transaction
            // will not have the credit burn keys associated with it
            platformRepo.addWalletAuthenticationKeys(seed, encryptionKey)
            platformSyncService.updateSyncStatus(PreBlockStage.InitWallet)

            //
            // Step 2: The credit funding registration exists, no need to create it
            //

            //
            // Step 3: Find the identity
            //
            updateNotification(applicationContext.getString(R.string.processing_home_title), applicationContext.getString(R.string.processing_home_step_2), 5, 2)
            identityRepository.updateIdentityCreationState(blockchainIdentityData, IdentityCreationState.IDENTITY_REGISTERING)

            val firstIdentityKey = platformRepo.getBlockchainIdentityKey(0, encryptionKey)!!
            platformRepo.recoverIdentityAsync(
                blockchainIdentity,
                firstIdentityKey.pubKeyHash
            )

            // Recovery only fetches the identity; it never issues the identity's keys on the
            // wallet's BLOCKCHAIN_IDENTITY chain (lookahead 0). Identities created by the
            // Kotlin SDK (canonical 4-key set, derivation index == keyId) therefore arrive
            // with zero issued keys and every later legacy-path signature (contact request
            // send/accept, profile create/update) dies in WalletSignerCallback with
            // "signer callback returned 0". Backfill them now; a no-op for identities whose
            // keys were issued at creation by the legacy flow.
            platformRepo.ensureIdentityChainKeys(blockchainIdentity.identity, encryptionKey)

            identityRepository.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
            identityRepository.updateIdentityCreationState(blockchainIdentityData, IdentityCreationState.IDENTITY_REGISTERED)
            platformSyncService.updateSyncStatus(PreBlockStage.GetIdentity)
            updateNotification(applicationContext.getString(R.string.processing_home_title), applicationContext.getString(R.string.processing_home_step_3_restoring), 5, 3)

            //
            // Step 4: We don't need to find the preorder documents
            //

            //
            // Step 5: Find the username
            //
            identityRepository.updateIdentityCreationState(blockchainIdentityData, IdentityCreationState.USERNAME_REGISTERING)
            platformRepo.recoverUsernames(blockchainIdentity)
            identityRepository.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
            identityRepository.updateIdentityCreationState(blockchainIdentityData, IdentityCreationState.USERNAME_REGISTERED)
            platformSyncService.updateSyncStatus(PreBlockStage.GetName)
            updateNotification(applicationContext.getString(R.string.processing_home_title), applicationContext.getString(R.string.processing_home_step_3_restoring), 5, 4)

            var foundContestedNameInVotingPeriod = false
            val maybeDualUsernames = blockchainIdentity.currentUsername != null && !Names.isUsernameContestable(blockchainIdentity.currentUsername!!)
            val instantUsername = blockchainIdentity.currentUsername
            // dashj's recoverUsernames() keys the recovered name on its
            // DPNS-NORMALIZED label ("c0ntested1"); for a contested name that
            // is what lands in currentUsername/primaryUsername and, through
            // the USERNAME pref, on the More-screen tile. When a contestable
            // name is found directly this block was skipped, so its display
            // label was never recovered and no UsernameRequest row was
            // inserted for resolveRequestedUsernameDisplay to map back — the
            // tile showed the normalized form (Fix B, observed on the shielded
            // contested create). Enter it for contestable names too: the
            // voting-contender recovery below reads the user's own contender
            // document and restores the DISPLAY .label (and the request row),
            // while the normalized form stays the lookup key everywhere else.
            val currentIsContestable = blockchainIdentity.currentUsername
                ?.let { Names.isUsernameContestable(it) } == true
            if (blockchainIdentity.currentUsername == null || maybeDualUsernames || currentIsContestable) {
                identityRepository.updateIdentityCreationState(blockchainIdentityData, IdentityCreationState.REQUESTED_NAME_CHECKING)

                // find active voting here
                val watch = Stopwatch.createStarted()
                val currentlyContestedNames = platformRepo.platform.names.getCurrentlyContestedNames()
                log.info("getCurrentlyContestedNames returns {} names and took {}", currentlyContestedNames.size, watch)

                currentlyContestedNames.forEach { name ->
                    if (maybeDualUsernames && instantUsername?.contains(name) == false) {
                        // skip this name if it doesn't appear to be the contested name for the found username
                        log.info("getVoteContenders: skipping {} because its not related to {}", name, instantUsername)
                        return@forEach
                    }
                    val voteContenders = platformRepo.getVoteContenders(name)
                    val winner = voteContenders.winner
                    voteContenders.map.forEach { (identifier, documentWithVotes) ->
                        if (blockchainIdentity.uniqueIdentifier == identifier) {
                            foundContestedNameInVotingPeriod = true
                            if (blockchainIdentity.currentUsername != null) {
                                blockchainIdentity.secondaryUsername = blockchainIdentity.currentUsername
                            }
                            blockchainIdentity.currentUsername = name
                            blockchainIdentity.primaryUsername = name
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
                                // clear()
                                val usernameInfo = UsernameInfo(
                                    null,
                                    UsernameStatus.CONFIRMED,
                                    blockchainIdentity.currentUsername!!,
                                    usernameRequestStatus,
                                    null
                                )
                                put(blockchainIdentity.currentUsername!!, usernameInfo)
                            }
                            var votingStartedAt = -1L
                            var label = name
                            // The contested-names index only knows the DPNS-normalized
                            // label ("c0ntested1"); the user's own contender document
                            // carries the DISPLAY label they typed ("contested1").
                            // Recover it whenever the document is available — and set
                            // primaryUsername too: that field is what gets persisted
                            // as the USERNAME pref, and leaving it normalized is what
                            // put the normalized form on the More-screen tile
                            // (observed live).
                            documentWithVotes.serializedDocument?.let { serialized ->
                                val contestedDocument = DomainDocument(
                                    platformRepo.platform.names.deserialize(serialized)
                                )
                                label = contestedDocument.label
                                blockchainIdentity.currentUsername = label
                                blockchainIdentity.primaryUsername = label
                                votingStartedAt = contestedDocument.createdAt ?: -1L
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

                            // determine when voting started by finding the earliest
                            // contender document (each contender's OWN document — the
                            // old code re-read this identity's document for every
                            // entry). Non-positive/missing timestamps must never win:
                            // a 0 here was persisted as votingPeriodStart and rendered
                            // as "Results on Dec 31, 1969" (observed live).
                            val earliestCreatedAt = voteContenders.map.values
                                .mapNotNull { contender ->
                                    contender.serializedDocument
                                        ?.let { platformRepo.platform.names.deserialize(it) }
                                        ?.createdAt
                                }
                                .filter { createdAt -> createdAt > 0 }
                                .minOrNull()
                                ?: votingStartedAt

                            if (earliestCreatedAt > 0) {
                                usernameInfo.votingStartedAt = earliestCreatedAt
                            }
                            usernameInfo.requestStatus = usernameRequestStatus
                            identityRepository.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)

                            // schedule work to check the status after voting has ended
                            if (earliestCreatedAt > 0) {
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
                }

                // check all contests
                if (!foundContestedNameInVotingPeriod) {

                    // check if the network has this name in the queue for voting
                    val watch2 = Stopwatch.createStarted()
                    val contestedNames = platformRepo.platform.names.getAllContestedNames()
                    log.info("getAllContestedNames returns {} names and took {}", contestedNames.size, watch2)

                    // now much of this can be put in BlockchainIdentity
                    contestedNames.forEach { name ->
                        if (maybeDualUsernames && instantUsername?.contains(name) == false) {
                            // skip this name if it doesn't appear to be the contested name for the found username
                            log.info("getVoteContenders: skipping {} because its not related to {}", name, instantUsername)
                            return@forEach
                        }
                        val voteContenders = platformRepo.getVoteContenders(name)
                        val winner = voteContenders.winner
                        voteContenders.map.forEach { (identifier, documentWithVotes) ->
                            if (blockchainIdentity.uniqueIdentifier == identifier) {
                                foundContestedNameInVotingPeriod = true
                                if (blockchainIdentity.currentUsername != null) {
                                    blockchainIdentity.secondaryUsername = blockchainIdentity.currentUsername
                                }
                                blockchainIdentity.currentUsername = name
                                blockchainIdentity.primaryUsername = name
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
                                    // clear()
                                    val usernameInfo = UsernameInfo(
                                        null,
                                        UsernameStatus.CONFIRMED,
                                        blockchainIdentity.currentUsername!!,
                                        usernameRequestStatus,
                                        null
                                    )
                                    put(blockchainIdentity.currentUsername!!, usernameInfo)
                                }
                                var votingStartedAt = -1L
                                var label = name
                                // Same display-label recovery as the currently-contested
                                // loop above: the index name is normalized; the user's
                                // own document carries the typed display label, and
                                // primaryUsername is what gets persisted as USERNAME.
                                documentWithVotes.serializedDocument?.let { serialized ->
                                    val contestedDocument = DomainDocument(
                                        platformRepo.platform.names.deserialize(serialized)
                                    )
                                    label = contestedDocument.label
                                    blockchainIdentity.currentUsername = label
                                    blockchainIdentity.primaryUsername = label
                                    votingStartedAt = contestedDocument.createdAt ?: -1L
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
                                var usernameInfo =
                                    blockchainIdentity.usernameStatuses[blockchainIdentity.currentUsername!!]
                                if (usernameInfo == null) {
                                    usernameInfo = UsernameInfo(
                                        null,
                                        UsernameStatus.CONFIRMED,
                                        blockchainIdentity.currentUsername!!,
                                        UsernameRequestStatus.VOTING
                                    )
                                    blockchainIdentity.usernameStatuses[blockchainIdentity.currentUsername!!] =
                                        usernameInfo
                                }

                                // determine when voting started by finding the earliest
                                // contender document (each contender's OWN document);
                                // non-positive/missing timestamps must never win — a 0
                                // here was persisted as votingPeriodStart and rendered
                                // as the Unix epoch.
                                val earliestCreatedAt = voteContenders.map.values
                                    .mapNotNull { contender ->
                                        contender.serializedDocument
                                            ?.let { platformRepo.platform.names.deserialize(it) }
                                            ?.createdAt
                                    }
                                    .filter { createdAt -> createdAt > 0 }
                                    .minOrNull()
                                    ?: votingStartedAt

                                if (earliestCreatedAt > 0) {
                                    usernameInfo.votingStartedAt = earliestCreatedAt
                                }
                                usernameInfo.requestStatus = usernameRequestStatus
                                identityRepository.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)

                                // schedule work to check the status after voting has ended
                                if (earliestCreatedAt > 0) {
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
                    }
                }

                if (blockchainIdentity.currentUsername != null && foundContestedNameInVotingPeriod) {

                    identityRepository.updateIdentityCreationState(
                        blockchainIdentityData,
                        IdentityCreationState.REQUESTED_NAME_CHECKED
                    )
                    identityRepository.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
                    identityRepository.updateIdentityCreationState(
                        blockchainIdentityData,
                        IdentityCreationState.REQUESTED_NAME_CHECKING
                    )

                    // recover the verification link
                    identityRepository.updateIdentityCreationState(
                        blockchainIdentityData,
                        IdentityCreationState.REQUESTED_NAME_CHECKED
                    )
                    identityRepository.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
                    // set voting state
                    identityRepository.updateIdentityCreationState(
                        blockchainIdentityData,
                        IdentityCreationState.VOTING
                    )
                    identityRepository.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
                }
            }
            updateNotification(applicationContext.getString(R.string.processing_home_title), applicationContext.getString(R.string.processing_home_step_3_restoring), 5, 5)

            // At this point, let's see what has been recovered.  It is possible that only the identity was recovered.
            // In this case, we should require that the user enters in a new username.
            if (blockchainIdentity.identity != null && blockchainIdentity.currentUsername == null) {
                blockchainIdentityData.creationState = IdentityCreationState.USERNAME_REGISTERING
                blockchainIdentityData.restoring = false
                identityRepository.updateBlockchainIdentityData(blockchainIdentityData)
                error("missing domain document for ${blockchainIdentity.uniqueId}")
            }

            //
            // Step 6: Find the profile
            //
            identityRepository.recoverDashPayProfile(blockchainIdentity)
            // blockchainIdentity hasn't changed
            platformSyncService.updateSyncStatus(PreBlockStage.GetProfile)

            identityRepository.addInviteUserAlert()

            // We are finished recovering
            blockchainIdentityData.finishRestoration()
            if (blockchainIdentityData.creationState != IdentityCreationState.VOTING ||
                (blockchainIdentityData.creationState == IdentityCreationState.VOTING && blockchainIdentityData.lostVote && blockchainIdentityData.showSecondaryUsername)) {
                identityRepository.updateIdentityCreationState(blockchainIdentityData, IdentityCreationState.DONE)
                identityRepository.updateBlockchainIdentityData(blockchainIdentityData)
                // A FRESH creation (the shielded/Type-20 create handing its
                // new on-chain identity here) must STOP at DONE so the home
                // welcome tile appears — exactly like the L1
                // CreateIdentityService path, which leaves a completed
                // non-voting name at DONE for the user to dismiss. Only a
                // genuine device restore auto-advances to DONE_AND_DISMISS
                // (no welcome tile). Fix A: the restore handoff previously
                // over-advanced to DONE_AND_DISMISS unconditionally, marking
                // the tile already-dismissed so it never rendered.
                if (!fromCreation) {
                    // Complete the entire process
                    identityRepository.updateIdentityCreationState(blockchainIdentityData, IdentityCreationState.DONE_AND_DISMISS)
                }
            }
            identityRepository.updateBlockchainIdentityData(blockchainIdentityData)

            platformSyncService.updateSyncStatus(PreBlockStage.RecoveryComplete)
            identityRepository.init()
            platformSyncService.initSync(true)
        } catch (e: Exception) {
            val blockchainIdentityData = identityConfig.load()
            blockchainIdentityData?.let {
                identityRepository.updateIdentityCreationState(it, it.creationState, e)
            }
            // triggering the end of the preBlockDownload stage as complete
            // could be problematic, what if there were errors
            platformSyncService.triggerPreBlockDownloadComplete()
            throw e
        }
    }
}