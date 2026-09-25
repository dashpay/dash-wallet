/*
 * Copyright 2024 Dash Core Group
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
package de.schildbach.wallet.ui.dashpay.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.dao.UsernameVoteDao
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.database.entity.UsernameVote
import de.schildbach.wallet.service.platform.PlatformBroadcastService
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.service.work.BaseWorker
import org.bitcoinj.core.DumpedPrivateKey
import org.bitcoinj.crypto.KeyCrypterException
import org.bouncycastle.crypto.params.KeyParameter
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.voting.ContestedDocumentResourceVotePoll
import org.dashj.platform.dpp.voting.ResourceVote
import org.dashj.platform.dpp.voting.ResourceVoteChoice
import org.dashj.platform.dpp.voting.TowardsIdentity
import org.dashj.platform.dpp.voting.Vote
import org.slf4j.LoggerFactory

@HiltWorker
class BroadcastUsernameVotesWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val analytics: AnalyticsService,
    private val platformBroadcastService: PlatformBroadcastService,
    private val platformSyncService: PlatformSyncService,
    private val walletDataProvider: WalletData,
    private val usernameRequestDao: UsernameRequestDao,
    private val usernameVoteDao: UsernameVoteDao
) : BaseWorker(context, parameters) {
    companion object {
        private val log = LoggerFactory.getLogger(BroadcastUsernameVotesWorker::class.java)

        const val KEY_PASSWORD = "BroadcastUsernameVotesWorker.PASSWORD"
        const val KEY_NORMALIZED_LABELS = "BroadcastUsernameVotesWorker.NORMALIZED_LABELS"
        const val KEY_LABELS = "BroadcastUsernameVotesWorker.LABELS"
        const val KEY_VOTE_CHOICES = "BroadcastUsernameVotesWorker.VOTE_CHOICES"
        const val KEY_MASTERNODE_KEYS = "BroadcastUsernameVotesWorker.MASTERNODE_KEYS"
        const val KEY_QUICK_VOTING = "BroadcastUsernameVotesWorker.QUICK_VOTING"
    }

    override suspend fun doWorkWithBaseProgress(): Result {
        val password = inputData.getString(KEY_PASSWORD)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_PASSWORD parameter"))
        val normalizedLabels = inputData.getStringArray(KEY_NORMALIZED_LABELS)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_USERNAMES parameter"))
        val labels = inputData.getStringArray(KEY_LABELS)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_USERNAMES parameter"))
        val voteChoices = inputData.getStringArray(KEY_VOTE_CHOICES)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_VOTE_CHOICES parameter"))
        val masternodeKeys = inputData.getStringArray(KEY_MASTERNODE_KEYS)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_MASTERNODE_KEYS parameter"))
        val isQuickVoting = inputData.getBoolean(KEY_QUICK_VOTING, false)

        // TODO: add decryption later?
        val encryptionKey: KeyParameter
        try {
            encryptionKey = walletDataProvider.wallet!!.keyCrypter!!.deriveKey(password)
        } catch (ex: KeyCrypterException) {
            analytics.logError(ex, "Broadcast Username Vote: failed to derive encryption key")
            val msg = formatExceptionMessage("derive encryption key", ex)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to msg))
        }

        return try {
            val labelMap = hashMapOf<String, String>()
            normalizedLabels.forEachIndexed { i, normalizedLabel ->  labelMap[normalizedLabel] = labels[i] }
            log.info("executing BroadcastUsernameVotesWorker({}, {})", normalizedLabels, voteChoices)
            log.info("voting BroadcastUsernameVotesWorker({}, {})", normalizedLabels, voteChoices)

            // comment this out for now
            val votingResults = platformBroadcastService.broadcastUsernameVotes(
                normalizedLabels.toList(),
                voteChoices.map { ResourceVoteChoice.from(it) },
                masternodeKeys.map { DumpedPrivateKey.fromBase58(Constants.NETWORK_PARAMETERS, it).key.privKeyBytes },
                encryptionKey
            )
            log.info("voted BroadcastUsernameVotesWorker({}, {})", normalizedLabels, voteChoices)
            // mock the voting results
//            val votingResults = arrayListOf<Triple<ResourceVoteChoice, Vote?, Exception?>>()
//            usernames.forEachIndexed { i, username ->
//                votingResults.add(
//                    Triple(
//                        ResourceVoteChoice.from(voteChoices[i]),
//                        Vote(
//                            ResourceVote(
//                                ResourceVoteChoice.from(voteChoices[i]),
//                                ContestedDocumentResourceVotePoll(
//                                    SystemIds.dpnsDataContractId,
//                                    "domain",
//                                    "parentDomainAndLabel",
//                                    listOf("dash", username)
//                                )
//                            )
//                        ),
//                        null
//                    )
//                )
//            }
            // this will update the DB and trigger observers
            // this is taking too long...
            //platformSyncService.updateUsernameRequestsWithVotes()

            // update local database
            analytics.logEvent(AnalyticsConstants.UsernameVoting.VOTE_SUCCESS, mapOf())
            // A failed vote carries no Vote, so it contributes no poll name. Drop those
            // rather than substituting the string "null": that placeholder has no entry
            // in labelMap, and looking it up is what produced the null element that
            // `Data` rejected. When nothing is left — every vote failed, or every vote
            // was already cast — fall back to the labels that were actually submitted.
            val arrayOfnames: Array<String> = votingResults.mapNotNull {
                (it.second?.resourceVote?.votePoll as? ContestedDocumentResourceVotePoll)?.indexValues?.get(1)
            }.toSet().ifEmpty { normalizedLabels.toSet() }.toTypedArray()
            val votes = hashMapOf<String, UsernameVote>()
            votingResults.forEach {
                when (val votePoll = it.second?.resourceVote?.votePoll as? ContestedDocumentResourceVotePoll) {
                    is ContestedDocumentResourceVotePoll -> {
                        val normalizedLabel = votePoll.indexValues[1]
                        val identity = when (it.first) {
                            is TowardsIdentity -> {
                                (it.first as TowardsIdentity).identifier
                            }
                            else -> null
                        }
                        votes[normalizedLabel] = UsernameVote(normalizedLabel, identity.toString(), it.first)
                    }
                }
            }
            votes.forEach { (_, usernameVote) ->
                updateUsernameVotes(usernameVote)
            }
            // A broadcast error is not automatically a lost vote: "vote is already
            // present" means this masternode ALREADY voted this poll, so the end state
            // the user asked for is already true. Only terminal verdicts count against
            // the broadcast. See [classifyVoteFailure].
            val verdicts = votingResults.map { result ->
                result.third?.let { classifyVoteFailure(voteFailureText(it)) }
            }
            val errorCount = verdicts.count { it?.isTerminal == true }
            val alreadyCastCount = verdicts.count { it == VoteFailureVerdict.ALREADY_CAST }
            if (alreadyCastCount != 0) {
                log.info(
                    "{} of {} votes were already cast by this masternode; reconciling as success",
                    alreadyCastCount,
                    votingResults.size
                )
            }
            when (errorCount) {
                0 -> {
                    // all were successful
                    log.info("all votes succeeded: total submitted {}", errorCount, votingResults.size)
                    Result.success(
                        workDataOf(
                            KEY_NORMALIZED_LABELS to if (votingResults.isNotEmpty()) {
                                arrayOfnames
                            } else {
                                listOf("").toTypedArray()
                            },
                            KEY_LABELS to labels,
                            KEY_VOTE_CHOICES to votingResults.map {
                                it.first.toString()
                            }.toTypedArray(),
                            KEY_QUICK_VOTING to isQuickVoting
                        )
                    )
                }
                votingResults.size -> {
                    // all have failed
                    log.error("all votes failed: errors: {} vs total submitted {}", errorCount, votingResults.size)
                    // errors that can be returned
                    // Dapi client error: Transport(Status { code: InvalidArgument, message: "Masternode vote is already present for masternode EbitFAjpGsuf7qKPpsQMZw2ZKZ8rs2S1PdqKvYA8J2Ux voting for ContestedDocumentResourceVotePoll(ContestedDocumentResourceVotePoll { contract_id: GWRSAVFMjXx8HpQFaNJMqBV7MBgMK4br5UESsB4S31Ec, document_type_name: domain, index_name: parentNameAndLabel, index_values: [string dash, string test-1101] })", metadata: MetadataMap { headers: {"drive-error-data-bin": "oW9zZXJpYWxpemVkRXJyb3KYbwIYKxjKDQkQABgqGO0YuRh/GLMDGOkYexgdGLEVGIMYvhhiGLMY2xiLGGEYRxj/GKgYSxiYGDAYnxjOGHEAGOYYaBjGGFkYrxhmGK4Y4RjnGCwYGBhtGN4YexhbGH4KGB0YcRgqCRjEDRhXGCEY9hgiGL8YUxjFGDEYVQYYZBhvGG0YYRhpGG4SGHAYYRhyGGUYbhh0GE4YYRhtGGUYQRhuGGQYTBhhGGIYZRhsAhIEGGQYYRhzGGgSCRh0GGUYcxh0GC0YMRgxGDAYMQ==", "code": "40304", "grpc-accept-encoding": "identity", "grpc-encoding": "identity", "content-type": "application/grpc+proto", "date": "Mon, 28 Oct 2024 22:27:37 GMT", "x-envoy-upstream-service-time": "55", "server": "envoy"} }, source: None }, Address { ban_count: 0, banned_until: None, uri: https://52.89.154.48:1443/ })
                    // Dapi client error: Transport(Status { code: InvalidArgument, message: "Masternode with id: CmbJumQ1ALJXHYFpUdCCnvbfgvXKSajErNXGhv3H4GN1 already voted 5 times and is trying to vote again, they can only vote 5 times"
                    logVoteFailures(votingResults, verdicts)
                    val error = votingResults.first().third!!
                    // Keep SDK metadata from consuming WorkManager's 10 KB output budget.
                    val errorMessage = voteFailureText(error)
                        .ifBlank { "Unknown error - ${error.javaClass.simpleName}" }
                        .take(1024)
                    Result.failure(
                        workDataOf(
                            KEY_ERROR_MESSAGE to errorMessage,
                            KEY_NORMALIZED_LABELS to arrayOfnames,
                            KEY_LABELS to labelsFor(arrayOfnames, labelMap),
                            KEY_VOTE_CHOICES to votingResults.map {
                                it.first.toString()
                            }.toTypedArray(),
                            KEY_QUICK_VOTING to isQuickVoting
                        )
                    )
                }
                else -> {
                    // some have failed, how can we report this?
                    log.error("not all votes succeeeded: errors: {} vs total submitted {}", errorCount, votingResults.size)
                    logVoteFailures(votingResults, verdicts)
                    Result.success(
                        workDataOf(
                            KEY_NORMALIZED_LABELS to arrayOfnames,
                            KEY_LABELS to labelsFor(arrayOfnames, labelMap),
                            KEY_VOTE_CHOICES to voteChoices,
                            KEY_QUICK_VOTING to isQuickVoting
                        )
                    )
                }
            }
        } catch (ex: Exception) {
            analytics.logEvent(AnalyticsConstants.UsernameVoting.VOTE_ERROR, mapOf())
            analytics.logError(ex, "Username Voting: failed to broadcast votes")
            Result.failure(
                workDataOf(
                    KEY_ERROR_MESSAGE to formatExceptionMessage("broadcast username vote", ex),
                    KEY_NORMALIZED_LABELS to normalizedLabels,
                    KEY_VOTE_CHOICES to voteChoices,
                    KEY_QUICK_VOTING to isQuickVoting
                )
            )
        } finally {
            log.info("finished BroadcastUsernameVotesWorker({}, {})", normalizedLabels, voteChoices)
        }
    }

    /**
     * Logs each failed vote at the severity its verdict deserves — an already-cast vote
     * is an expected reconcile, not an error worth a stack trace.
     */
    private fun logVoteFailures(
        votingResults: List<Triple<ResourceVoteChoice, Vote?, Exception?>>,
        verdicts: List<VoteFailureVerdict?>
    ) {
        votingResults.forEachIndexed { i, result ->
            result.third?.let { e ->
                if (verdicts[i] == VoteFailureVerdict.ALREADY_CAST) {
                    log.info("vote already cast, nothing to do: {}: {}", result.first, e.message)
                } else {
                    log.error("error with vote: {}", result.first, e)
                }
            }
        }
    }

    private suspend fun updateUsernameVotes(
        normalizedLabel: String,
        identity: Identifier?,
        keyCount: Int,
        voteType: ResourceVoteChoice
    ) {
        // usernameRequestDao.removeApproval(request.username)
        // usernameRequestDao.update(request.copy(votes = request.votes + keyCount, isApproved = true))
        usernameVoteDao.insert(
            UsernameVote(
                normalizedLabel,
                identity?.toString() ?: "",
                voteType
            )
        )
    }

    private suspend fun updateUsernameVotes(
        vote: UsernameVote
    ) {
        // usernameRequestDao.removeApproval(request.username)
        // usernameRequestDao.update(request.copy(votes = request.votes + keyCount, isApproved = true))
        usernameVoteDao.insert(vote)
    }
}

/**
 * Display labels for [names], positionally aligned with them.
 *
 * `androidx.work.Data` accepts `String[]` and rejects BOTH a `List` and an
 * `Array<String?>` — passing either throws `IllegalArgumentException: has invalid type`
 * from `Data.Builder.put`, which is exactly how the worker's failure paths used to blow
 * up before they could report the real vote error. So this hands back a non-null
 * `Array<String>`.
 *
 * A name with no entry in [labelMap] falls back to the normalized name itself rather
 * than being filtered out: `UsernameRequestsFragment` reads KEY_LABELS and
 * KEY_NORMALIZED_LABELS as parallel arrays, so dropping an element would desync them,
 * and the normalized name is still a truthful thing to show the user.
 *
 * Pure — host-testable.
 */
internal fun labelsFor(names: Array<String>, labelMap: Map<String, String>): Array<String> =
    names.map { labelMap[it] ?: it }.toTypedArray()

/**
 * Literal fragments of the Drive/DAPI vote errors [classifyVoteFailure] keys on.
 *
 * These are matched against message TEXT because that is all the engine gives us — the
 * failure arrives as a plain `java.lang.Exception` with no code to switch on. The full
 * observed messages are pinned in `VoteFailureClassificationTest`, so an SDK reword
 * fails that test loudly rather than silently reclassifying an already-cast vote as
 * fatal (or, worse, a spent vote budget as success).
 */
internal const val ALREADY_CAST_MARKER = "vote is already present"
internal const val VOTE_LIMIT_MARKER = "can only vote"

/**
 * What a single vote's broadcast failure actually means.
 */
internal enum class VoteFailureVerdict(val isTerminal: Boolean) {
    /**
     * "Masternode vote is already present for masternode <id> voting for
     * ContestedDocumentResourceVotePoll(...)".
     *
     * This masternode has already voted this poll: the end state the user asked for is
     * ALREADY TRUE. Reconciles as success — not an error.
     */
    ALREADY_CAST(isTerminal = false),

    /**
     * "Masternode with id: <id> already voted 5 times and is trying to vote again, they
     * can only vote 5 times".
     *
     * The per-masternode vote budget for this poll is spent and this vote will never
     * land. Genuinely terminal, and deliberately NOT collapsed into [ALREADY_CAST].
     */
    VOTE_LIMIT_REACHED(isTerminal = true),

    /** Anything else — treat as a real failure. */
    FAILED(isTerminal = true)
}

/**
 * Verdict for one failed vote, derived from the engine's message text.
 *
 * Pure and top-level so it is host-JVM testable without WorkManager or the SDK — the
 * same shape as `isOwnContestedCandidate` / `contestedNameCandidates` in
 * `RestoreIdentityWorker`.
 *
 * ORDER MATTERS: the vote-limit message also contains the words "already voted", so the
 * limit marker is tested FIRST. Reversing these two branches would report a spent vote
 * budget as a successful vote.
 */
internal fun classifyVoteFailure(reason: String?): VoteFailureVerdict {
    val text = reason?.lowercase() ?: return VoteFailureVerdict.FAILED
    return when {
        text.contains(VOTE_LIMIT_MARKER) -> VoteFailureVerdict.VOTE_LIMIT_REACHED
        text.contains(ALREADY_CAST_MARKER) -> VoteFailureVerdict.ALREADY_CAST
        else -> VoteFailureVerdict.FAILED
    }
}

/**
 * Flattens a throwable's whole cause chain into one string for [classifyVoteFailure].
 *
 * The marker text arrives WRAPPED: the field failure was `java.lang.Exception:
 * Attempted to unwrap a Failure: Protocol error: Masternode vote is already present
 * ...`, and a future SDK may push it a cause deeper still. Cycle-safe.
 */
internal fun voteFailureText(error: Throwable?): String {
    val seen = mutableSetOf<Throwable>()
    val parts = mutableListOf<String>()
    var current = error
    while (current != null && seen.add(current)) {
        current.message?.let { parts.add(it) }
        current = current.cause
    }
    return parts.joinToString(" | ")
}
