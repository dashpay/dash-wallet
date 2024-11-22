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
import org.bitcoinj.core.DumpedPrivateKey
import org.bitcoinj.crypto.KeyCrypterException
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.voting.ContestedDocumentResourceVotePoll
import org.dashj.platform.dpp.voting.ResourceVote
import org.dashj.platform.dpp.voting.ResourceVoteChoice
import org.dashj.platform.dpp.voting.TowardsIdentity
import org.dashj.platform.dpp.voting.Vote
import org.dashj.platform.sdk.PlatformValue
import org.slf4j.LoggerFactory

@HiltWorker
class BroadcastUsernameVotesWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val analytics: AnalyticsService,
    private val platformBroadcastService: PlatformBroadcastService,
    private val platformSyncService: PlatformSyncService,
    private val walletDataProvider: WalletDataProvider,
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
            val arrayOfnames: Array<String> = votingResults.map {
                ((it.second?.resourceVote?.votePoll as? ContestedDocumentResourceVotePoll)?.indexValues?.get(1) ?: "null")
            }.toSet().toTypedArray()
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
                    else -> {
                        // skip any other type of VotePoll
                    }
                }
            }
            votes.forEach { (_, usernameVote) ->
                updateUsernameVotes(usernameVote)
            }
            val errorCount = votingResults.count { it.third != null }
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
                    votingResults.forEach {
                        it.third?.let { e ->
                            log.error("error with vote: {}", it.first, e)
                        }
                    }
                    Result.failure(
                        workDataOf(
                            KEY_NORMALIZED_LABELS to arrayOfnames,
                            KEY_LABELS to arrayOfnames.map { labelMap[it] },
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
                    votingResults.forEach {
                        it.third?.let { e ->
                            log.error("error with vote: {}", it.first, e)
                        }
                    }
                    Result.success(
                        workDataOf(
                            KEY_NORMALIZED_LABELS to arrayOfnames,
                            KEY_LABELS to arrayOfnames.map { labelMap[it] },
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