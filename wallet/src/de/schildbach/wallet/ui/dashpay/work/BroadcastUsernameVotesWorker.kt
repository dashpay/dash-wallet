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
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bumptech.glide.load.engine.Resource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.platform.PlatformBroadcastService
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import org.bitcoinj.core.DumpedPrivateKey
import org.bitcoinj.crypto.KeyCrypterException
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dpp.voting.ResourceVoteChoice
import org.dashj.platform.sdk.ContestedDocumentResourceVotePoll
import org.slf4j.LoggerFactory

@HiltWorker
class BroadcastUsernameVotesWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    val analytics: AnalyticsService,
    val platformBroadcastService: PlatformBroadcastService,
    val platformSyncService: PlatformSyncService,
    val walletDataProvider: WalletDataProvider
) : BaseWorker(context, parameters) {

    companion object {
        private val log = LoggerFactory.getLogger(BroadcastUsernameVotesWorker::class.java)

        const val KEY_PASSWORD = "BroadcastUsernameVotesWorker.PASSWORD"
        const val KEY_USERNAMES = "BroadcastUsernameVotesWorker.USERNAMES"
        const val KEY_VOTE_CHOICES = "BroadcastUsernameVotesWorker.VOTE_CHOICES"
        const val KEY_MASTERNODE_KEYS = "BroadcastUsernameVotesWorker.MASTERNODE_KEYS"
    }

    override suspend fun doWorkWithBaseProgress(): Result {
        val password = inputData.getString(KEY_PASSWORD)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_PASSWORD parameter"))
        val usernames = inputData.getStringArray(KEY_USERNAMES)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_USERNAMES parameter"))
        val voteChoices = inputData.getStringArray(KEY_VOTE_CHOICES)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_VOTE_CHOICES parameter"))
        val masternodeKeys = inputData.getStringArray(KEY_MASTERNODE_KEYS)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_MASTERNODE_KEYS parameter"))

        val encryptionKey: KeyParameter
        try {
            encryptionKey = walletDataProvider.wallet!!.keyCrypter!!.deriveKey(password)
        } catch (ex: KeyCrypterException) {
            analytics.logError(ex, "Broadcast Username Vote: failed to derive encryption key")
            val msg = formatExceptionMessage("derive encryption key", ex)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to msg))
        }

        return try {
            log.info("creating BroadcastUsernameVotesWorker({}, {})", usernames, voteChoices)

            val votes = platformBroadcastService.broadcastUsernameVotes(
                usernames.toList(),
                voteChoices.map { ResourceVoteChoice.from(it) },
                masternodeKeys.map { DumpedPrivateKey.fromBase58(Constants.NETWORK_PARAMETERS, it).key.privKeyBytes },
                encryptionKey
            )
            // this will update the DB and trigger observers
            platformSyncService.updateUsernameRequestsWithVotes()
            Result.success(
                workDataOf(
                    KEY_USERNAMES to if(votes.isNotEmpty()) {
                        votes.map { (it.resourceVote.votePoll as? ContestedDocumentResourceVotePoll)?.index_values?.get(1) ?: "null" }.toTypedArray()
                    } else {
                        listOf("").toTypedArray()
                    },
                )
            )
        } catch (ex: Exception) {
            analytics.logError(ex, "Username Voting: failed to broadcast votes")
            Result.failure(workDataOf(
                    KEY_ERROR_MESSAGE to formatExceptionMessage("broadcast username vote", ex)))
        }
    }
}