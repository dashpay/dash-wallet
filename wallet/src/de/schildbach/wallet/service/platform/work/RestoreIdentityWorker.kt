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
import de.schildbach.wallet.service.platform.sdk.SdkTransparentUsernameCreation
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
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
import java.util.concurrent.TimeUnit

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
    val usernameRequestDao: UsernameRequestDao,
    val transparentUsernameCreation: SdkTransparentUsernameCreation
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

        /**
         * Wall-clock ceiling for the UNTARGETED (broad) contested-name scan — the
         * path taken only when this identity has no known candidate name at all
         * (see [ownContestedCandidates]). Checked BEFORE each per-name network
         * query, so the scan is bounded by this budget plus one query; it never
         * interrupts a query in flight. Blowing the budget is not an error: the
         * outcome is exactly "no contested name found", which is what the broad
         * scan reports for the overwhelming majority of restores anyway, and
         * restoration proceeds to [BlockchainIdentityData.finishRestoration].
         */
        const val BROAD_SCAN_BUDGET_MS = 60_000L
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

            // A fromCreation run can arrive with the creation state ALREADY
            // advanced to DONE by the transparent-create path (a confirmed,
            // non-contested primary sets DONE so the welcome tile appears the
            // instant creation completes). Resetting that to NONE/restoring=true
            // here — before the background recovery re-advances it — would blink
            // the just-shown welcome tile out. So for a fromCreation run whose
            // persisted state is already completed (>= DONE), PRESERVE it: seed
            // the working object from the persisted state and skip the reset
            // persist. A genuine device restore (fromCreation=false) and a
            // not-yet-completed creation (e.g. a contested primary still at
            // USERNAME_REGISTERED, which must route to VOTING) both keep the
            // historic empty/restoring reset unchanged.
            val persistedRecord = if (fromCreation) identityConfig.load() else null
            val alreadyCompletedFromCreation = persistedRecord?.takeIf { it.creationState >= IdentityCreationState.DONE }
            // use an "empty" state for a genuine device restore (nothing local
            // worth keeping) — but NEVER for a fromCreation run: the persisted
            // record carries the REQUESTED names, and the historic empty reset
            // destroyed them before the recovery walk. recoverUsernames() then
            // rebuilt the record purely from on-chain REGISTERED names, so a
            // DUAL create's pending CONTESTED primary (not a registered domain
            // until its vote resolves) vanished — the contested-name scan's
            // candidate label was gone before the scan ran, and the identity
            // completed DONE holding only the instant name (Mo-972; S21
            // 2026-08-18, reproduced twice — the second time through this very
            // reset with the adoption guard already in place downstream). Seed
            // from the persisted record instead: restoring=true keeps the
            // processing-card semantics, and the walk re-advances creationState
            // exactly as before.
            val blockchainIdentityData = alreadyCompletedFromCreation
                ?: persistedRecord?.also { it.restoring = true }
                ?: BlockchainIdentityData(IdentityCreationState.NONE, null, null, null, null, true)

            val authExtension =
                walletDataProvider.wallet!!.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
            //authExtension.setWallet(walletApplication.wallet!!) // why is the wallet not set?  we didn't deserialize it probably!
            val cftxs = authExtension.assetLockTransactions

            if (alreadyCompletedFromCreation == null) {
                identityRepository.updateBlockchainIdentityData(blockchainIdentityData)
            } else {
                log.info(
                    "fromCreation restore: preserving already-completed creation state {} (not resetting to NONE/restoring)",
                    alreadyCompletedFromCreation.creationState
                )
            }
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

            // Post-cutover TRANSPARENT create whose DPNS name did not land: the
            // identity exists on chain but has no on-chain name yet. This worker
            // OWNS re-driving ONLY the name step via the same SDK seam the create
            // path uses — it NEVER funds (the identity already exists), so this is
            // the refund-safe retry/completion point. Gated on the COMMITTED
            // cutover rather than `fromCreation`: a home-tile retry re-runs this
            // worker with fromCreation=false, and post-cutover the dashj name path
            // is dead, so the SDK must own it either way. A genuine device restore
            // starts from an empty config (no requested USERNAME) and falls through
            // to the throw below (ask the user for a new username). Pre-cutover this
            // is skipped and the classic dashj retry path is unchanged.
            if (blockchainIdentity.identity != null && blockchainIdentity.currentUsername == null) {
                val requestedLabel = identityConfig.get(BlockchainIdentityConfig.USERNAME)
                val cutoverCommitted = try {
                    transparentUsernameCreation.isCutoverCommitted()
                } catch (e: Exception) {
                    log.warn("cutover-state read failed while completing DPNS registration; skipping", e)
                    false
                }
                if (cutoverCommitted && !requestedLabel.isNullOrEmpty()) {
                    log.info("identity has no on-chain name yet — registering DPNS name '{}' via the SDK", requestedLabel)
                    identityRepository.updateIdentityCreationState(blockchainIdentityData, IdentityCreationState.PREORDER_REGISTERING)
                    identityRepository.updateIdentityCreationState(blockchainIdentityData, IdentityCreationState.USERNAME_REGISTERING)
                    when (
                        val result = transparentUsernameCreation.registerDpnsNameForExistingIdentity(
                            Identifier.from(identity).toString(),
                            requestedLabel
                        )
                    ) {
                        is SdkWriteResult.Broadcast -> {
                            log.info("SDK DPNS registration of '{}' confirmed: {}", requestedLabel, result.value)
                            // Re-recover so the contested / DONE handling below sees
                            // the now on-chain name (populates currentUsername; the
                            // contested block picks up a name in voting).
                            platformRepo.recoverUsernames(blockchainIdentity)
                            identityRepository.updateBlockchainIdentityData(blockchainIdentityData, blockchainIdentity)
                            identityRepository.updateIdentityCreationState(blockchainIdentityData, IdentityCreationState.USERNAME_REGISTERED)
                        }
                        // Retryable: leave the state at USERNAME_REGISTERING (the
                        // catch below stamps a generic error — NOT "missing domain
                        // document", so the tile shows the retry card, and restoring
                        // stays true so a retry re-runs THIS worker, never a re-fund).
                        is SdkWriteResult.NotBroadcast ->
                            error("username registration did not complete (retryable): ${result.reason}")
                        is SdkWriteResult.Ambiguous ->
                            error("username registration outcome unconfirmed (retryable): ${result.cause.message}")
                    }
                }
            }

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

                // Both contested-name walks below exist to answer ONE question:
                // is THIS identity a contender for a contested name, and with
                // what status? Every iteration's body is gated on
                // `blockchainIdentity.uniqueIdentifier == identifier`, so a name
                // this identity never requested cannot produce any effect — its
                // per-name `getVoteContenders` network round trip is pure cost.
                //
                // Field evidence (11.10.84 mainnet restore): the pre-existing
                // narrowing was gated on `maybeDualUsernames`, which is FALSE
                // whenever the recovered name is itself contestable — so a
                // restore of `thedesert1ynx` fell through to a SERIAL walk of
                // all 728 contested names at ~1.8 min each (the SDK vote-state
                // query burns ~2 min against nodes with expired TLS certs
                // before the ~0.5 s dashj fallback runs). ~22 hours: the worker
                // never reached finishRestoration() → identityRepository.init()
                // → platformSyncService.initSync(true), so the 15 s contact
                // ticker was never armed and no DIP-15 friendship keychains
                // were ever derived (an incoming contact payment stayed
                // invisible).
                //
                // Narrow to the identity's OWN candidate names. Semantics for
                // those names are untouched: the same list is enumerated, the
                // same names are queried in the same order, the same body runs
                // — only names that provably cannot match are skipped.
                val ownCandidateNames = ownContestedCandidates(blockchainIdentity)
                val targetedScan = ownCandidateNames.isNotEmpty()
                // A name from the contested index is already DPNS-normalized;
                // normalizing again is idempotent (o→0, i/l→1). The historic
                // dual-username heuristic (the contested name is a substring of
                // the non-contestable "instant" name) is kept as a union term so
                // that path behaves exactly as before.
                fun isOwnCandidate(name: String): Boolean =
                    isOwnContestedCandidate(name, ownCandidateNames, maybeDualUsernames, instantUsername)
                val scanWatch = Stopwatch.createStarted()
                // Whether either contested-name index can hold anything this
                // identity could be a contender for. When it cannot, both
                // fetches are skipped outright — see [contestedNameListsCanMatch].
                val listsCanMatch = contestedNameListsCanMatch(targetedScan, ownCandidateNames)
                log.info(
                    "contested-name check: {} scan — own candidate name(s)={}, dualUsernames={}, " +
                        "contestable={}, listFetches={}",
                    if (targetedScan) "TARGETED" else "BROAD (no candidate name known)",
                    ownCandidateNames,
                    maybeDualUsernames,
                    currentIsContestable,
                    if (listsCanMatch) "needed" else "SKIPPED (no candidate name is contestable, " +
                        "so no contested-index entry can match)"
                )

                // find active voting here
                val watch = Stopwatch.createStarted()
                val currentlyContestedNames = if (listsCanMatch) {
                    platformRepo.platform.names.getCurrentlyContestedNames().also {
                        log.info("getCurrentlyContestedNames returns {} names and took {}", it.size, watch)
                    }
                } else {
                    emptyList()
                }

                val currentlyContestedToCheck = if (targetedScan) {
                    currentlyContestedNames.filter { isOwnCandidate(it) }
                } else {
                    currentlyContestedNames
                }
                log.info(
                    "contested-name check: querying contenders for {} of {} currently-contested name(s): {}",
                    currentlyContestedToCheck.size,
                    currentlyContestedNames.size,
                    if (targetedScan) currentlyContestedToCheck.toString() else "(broad scan)"
                )

                for (name in currentlyContestedToCheck) {
                    if (!targetedScan && scanWatch.elapsed(TimeUnit.MILLISECONDS) > BROAD_SCAN_BUDGET_MS) {
                        log.warn(
                            "contested-name check: BROAD scan exceeded its {} ms budget after {} — " +
                                "stopping at '{}'; treating as no contested name so restoration can finish",
                            BROAD_SCAN_BUDGET_MS, scanWatch, name
                        )
                        break
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
                    // The broad scan has no bound other than the list itself, so
                    // stop the moment the answer is known. The TARGETED scan is
                    // deliberately NOT short-circuited: it queries every candidate
                    // exactly as before, so a multi-candidate identity resolves to
                    // the same name it does today (last match wins).
                    if (foundContestedNameInVotingPeriod && !targetedScan) {
                        log.info("contested-name check: BROAD scan matched '{}' after {}", name, scanWatch)
                        break
                    }
                }

                // check all contests
                if (!foundContestedNameInVotingPeriod) {

                    // check if the network has this name in the queue for voting
                    val watch2 = Stopwatch.createStarted()
                    val contestedNames = if (listsCanMatch) {
                        platformRepo.platform.names.getAllContestedNames().also {
                            log.info("getAllContestedNames returns {} names and took {}", it.size, watch2)
                        }
                    } else {
                        emptyList()
                    }

                    val contestedNamesToCheck = if (targetedScan) {
                        contestedNames.filter { isOwnCandidate(it) }
                    } else {
                        contestedNames
                    }
                    log.info(
                        "contested-name check: querying contenders for {} of {} contested name(s): {}",
                        contestedNamesToCheck.size,
                        contestedNames.size,
                        if (targetedScan) contestedNamesToCheck.toString() else "(broad scan)"
                    )

                    // now much of this can be put in BlockchainIdentity
                    for (name in contestedNamesToCheck) {
                        if (!targetedScan && scanWatch.elapsed(TimeUnit.MILLISECONDS) > BROAD_SCAN_BUDGET_MS) {
                            log.warn(
                                "contested-name check: BROAD scan exceeded its {} ms budget after {} — " +
                                    "stopping at '{}'; treating as no contested name so restoration can finish",
                                BROAD_SCAN_BUDGET_MS, scanWatch, name
                            )
                            break
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
                        // Same short-circuit contract as the currently-contested
                        // walk above: bound the unbounded (broad) scan, leave the
                        // targeted scan's decision bit-for-bit unchanged.
                        if (foundContestedNameInVotingPeriod && !targetedScan) {
                            log.info("contested-name check: BROAD scan matched '{}' after {}", name, scanWatch)
                            break
                        }
                    }
                }
                log.info("contested-name check complete in {} (found={})", scanWatch, foundContestedNameInVotingPeriod)

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
            // Reached only when the identity has no on-chain name AND there was
            // nothing to register here (no requested USERNAME persisted) — the
            // SDK completion step above already handled (and, on failure, threw
            // a retryable error for) the fresh-create case that HAS a requested
            // label. With no label there is nothing to re-drive, so this stays
            // the historic "only the identity was recovered, ask for a new
            // username" path (a genuine device restore of a name-less identity).
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

    /**
     * Every name this identity could possibly be a CONTENDER for, DPNS-normalized
     * (`Names.normalizeString`: lowercase, o→0, i/l→1 — the exact key the contested
     * index and every `getVoteContenders` query use, and idempotent, so re-applying
     * it to an already-normalized index entry is a no-op).
     *
     * A contested-name contender document is created by the identity that REQUESTED
     * that name, so the candidate set is bounded by the names this wallet knows it
     * asked for:
     * - the recovered on-chain name(s) — `currentUsername` / `primaryUsername` /
     *   `secondaryUsername` and every key of `usernameStatuses` (dashj keys those on
     *   the normalized label);
     * - the requested-but-not-yet-on-chain label persisted as
     *   [BlockchainIdentityConfig.USERNAME] — the create/retry path's own record of
     *   what it asked for, which is the case where nothing is on chain yet.
     *
     * EMPTY means "this wallet knows of no name it ever requested" (a genuine device
     * restore of an identity whose only name request is a still-in-voting contested
     * one). Only then is the broad scan the sole way to find it, and only then does
     * the caller fall back to it — under a hard time budget.
     */
    private suspend fun ownContestedCandidates(blockchainIdentity: BlockchainIdentity): Set<String> {
        val requestedLabel = try {
            identityConfig.get(BlockchainIdentityConfig.USERNAME)
        } catch (e: Exception) {
            log.warn("contested-name check: could not read the requested username; continuing without it", e)
            null
        }
        return contestedNameCandidates(
            currentUsername = blockchainIdentity.currentUsername,
            primaryUsername = blockchainIdentity.primaryUsername,
            secondaryUsername = blockchainIdentity.secondaryUsername,
            usernameStatusKeys = blockchainIdentity.usernameStatuses.keys,
            requestedLabel = requestedLabel
        )
    }
}

/**
 * Pure half of [RestoreIdentityWorker.ownContestedCandidates] — see its KDoc for
 * why the candidate set is exactly this. Every entry is DPNS-normalized with the
 * real `Names.normalizeString` (lowercase, o→0, i/l→1), the key the contested
 * index and `getVoteContenders` both use; it is idempotent, so an already-
 * normalized value passes through unchanged.
 */
internal fun contestedNameCandidates(
    currentUsername: String?,
    primaryUsername: String?,
    secondaryUsername: String?,
    usernameStatusKeys: Collection<String>,
    requestedLabel: String?
): Set<String> = (
    listOfNotNull(currentUsername, primaryUsername, secondaryUsername, requestedLabel) + usernameStatusKeys
    )
    .filter { it.isNotBlank() }
    .map { Names.normalizeString(it) }
    .toSet()

/**
 * Can this identity possibly be a contender for the contested name [name]?
 *
 * True when [name] normalizes onto one of the identity's own candidate names, OR
 * — preserving the historic dual-username narrowing verbatim — when the identity
 * holds a non-contestable "instant" name that contains [name].
 *
 * False means the per-name `getVoteContenders` round trip can be skipped: the
 * loop body it feeds only ever fires on `uniqueIdentifier == contender id`, which
 * cannot happen for a name this identity never requested.
 */
internal fun isOwnContestedCandidate(
    name: String,
    candidates: Set<String>,
    maybeDualUsernames: Boolean,
    instantUsername: String?
): Boolean = candidates.contains(Names.normalizeString(name)) ||
    (maybeDualUsernames && instantUsername?.contains(name) == true)

/**
 * Can the contested-name lists possibly contain a name this identity is a
 * contender for — i.e. is fetching them worth anything at all?
 *
 * Both indexes hold only CONTESTABLE names (`^[a-zA-Z01-]{3,19}$`; that is
 * what makes a name contested in the first place). So when the scan is
 * TARGETED and not one of the identity's own candidate names is contestable,
 * no entry in either list can match a candidate, every `getVoteContenders`
 * body is gated on `uniqueIdentifier == contender id` and cannot fire, and
 * the two fetches are pure cost.
 *
 * Field evidence (11.10.86 mainnet, splawik): candidate `sp1aw1k21` — not
 * contestable, it carries a `2` and a `9` — yet
 * `getCurrentlyContestedNames()` (12 names, 32.65 s) and
 * `getAllContestedNames()` (728 names, 3.56 s) both ran and both yielded
 * "querying contenders for 0 of N". 36.2 s of a restore spent proving the
 * arithmetic above.
 *
 * BEHAVIOUR NOTE: this deliberately does NOT keep the historic
 * dual-username SUBSTRING term ([isOwnContestedCandidate]'s second clause)
 * alive as a reason to fetch. That term can only match a name the identity
 * has no record of ever requesting — it fires on any contested name that
 * happens to be a substring of a non-contestable "instant" name — and
 * keeping it would mean every identity holding such a name pays both
 * fetches on every restore, forever, which is exactly the cost being
 * removed. Names the identity DID request are covered by the candidate set
 * (recovered names, usernameStatuses keys, and the persisted requested
 * label), and the BROAD path — taken whenever no candidate is known at all
 * — is untouched. Pure — host-testable.
 */
internal fun contestedNameListsCanMatch(
    targetedScan: Boolean,
    ownCandidateNames: Set<String>
): Boolean = !targetedScan || ownCandidateNames.any { Names.isUsernameContestable(it) }