/*
 * Copyright 2022 Dash Core Group.
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

import com.google.common.base.Preconditions
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.DashSystemService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.KeyId
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.evolution.EvolutionContact
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import org.dashj.platform.dashpay.callback.SimpleSignerCallback
import org.dashj.platform.dashpay.callback.WalletSignerCallback
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.voting.ResourceVoteChoice
import org.dashj.platform.dpp.voting.Vote
import org.dashj.platform.sdk.Purpose
import org.dashj.platform.wallet.IdentityVerifyDocument
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import javax.inject.Inject

interface PlatformBroadcastService {
    suspend fun broadcastUpdatedProfile(dashPayProfile: DashPayProfile, encryptionKey: KeyParameter): DashPayProfile
    suspend fun sendContactRequest(toUserId: String): DashPayContactRequest
    suspend fun sendContactRequest(toUserId: String, encryptionKey: KeyParameter): DashPayContactRequest
    suspend fun broadcastIdentityVerify(username: String, url: String, encryptionKey: KeyParameter?): IdentityVerifyDocument
    suspend fun broadcastUsernameVotes(
        usernames: List<String>,
        resourceVoteChoices: List<ResourceVoteChoice>,
        masternodeKeys: List<ByteArray>,
        encryptionKey: KeyParameter?
    ): List<Triple<ResourceVoteChoice, Vote?, Exception?>>
}

class PlatformDocumentBroadcastService @Inject constructor(
    val dashSystemService: DashSystemService,
    val platform: PlatformService,
    val platformRepo: PlatformRepo,
    val analytics: AnalyticsService,
    val walletDataProvider: WalletDataProvider,
    val platformSyncService: PlatformSyncService
) : PlatformBroadcastService {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(PlatformDocumentBroadcastService::class.java)
    }

    @Throws(Exception::class)
    override suspend fun sendContactRequest(toUserId: String): DashPayContactRequest {
        if (walletDataProvider.wallet!!.isEncrypted) {
            // always create a SecurityGuard when it is required
            val securityGuard = SecurityGuard.getInstance()
            val password = securityGuard.retrievePassword()
            // Don't bother with DeriveKeyTask here, just call deriveKey
            val encryptionKey = walletDataProvider.wallet!!.keyCrypter!!.deriveKey(password)
            return sendContactRequest(toUserId, encryptionKey)
        }
        throw IllegalStateException("sendContactRequest doesn't support non-encrypted wallets")
    }

    @Throws(Exception::class)
    override suspend fun sendContactRequest(toUserId: String, encryptionKey: KeyParameter): DashPayContactRequest {
        val potentialContactIdentity = platform.identities.get(toUserId)
        log.info("potential contact identity: $potentialContactIdentity")
        val blockchainIdentity = platformRepo.blockchainIdentity

        // Create Contact Request
        val timer = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_CONTACT_REQUEST_SEND)
        val cr = platform.contactRequests.create(blockchainIdentity, potentialContactIdentity!!, encryptionKey)
        timer.logTiming()
        log.info("contact request sent")

        // add our receiving from this contact keychain if it doesn't exist
        val contact = EvolutionContact(blockchainIdentity.uniqueIdString, toUserId)

        if (!walletDataProvider.wallet!!.hasReceivingKeyChain(contact)) {
            Context.propagate(walletDataProvider.wallet!!.context)
            blockchainIdentity.addPaymentKeyChainFromContact(potentialContactIdentity, cr, encryptionKey)

            // update bloom filters now on main thread
            platformSyncService.postUpdateBloomFilters()
        }

        log.info("contact request: $cr")
        val dashPayContactRequest = DashPayContactRequest.fromDocument(cr)
        platformRepo.updateDashPayContactRequest(dashPayContactRequest) // update the database since the cr was accepted
        platformRepo.updateDashPayProfile(toUserId) // update the profile
        platformSyncService.fireContactsUpdatedListeners() // trigger listeners
        return dashPayContactRequest
    }

    override suspend fun broadcastIdentityVerify(username: String, url: String, encryptionKey: KeyParameter?): IdentityVerifyDocument {
        val blockchainIdentity = platformRepo.blockchainIdentity

        // Create Identity Verify
        val timer = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_CONTACT_REQUEST_SEND)
        val identityVerifyDocument = platform.identityVerify.createForDashDomain(
            username,
            url,
            blockchainIdentity.identity!!,
            WalletSignerCallback(walletDataProvider.wallet!!, encryptionKey)
        )
        timer.logTiming()
        log.info("identity verify sent")

        log.info("contact request: $identityVerifyDocument")

        return identityVerifyDocument
    }

    override suspend fun broadcastUsernameVotes(
        usernames: List<String>,
        resourceVoteChoices: List<ResourceVoteChoice>,
        masternodeKeys: List<ByteArray>,
        encryptionKey: KeyParameter?
    ): List<Triple<ResourceVoteChoice, Vote?, Exception?>> {
        Preconditions.checkArgument(usernames.size == resourceVoteChoices.size)
        val votes = arrayListOf<Triple<ResourceVoteChoice, Vote?, Exception?>>()
        masternodeKeys.forEach { masternodeKeyBytes ->
            // determine identity
            val masternodeKey = ECKey.fromPrivate(masternodeKeyBytes)
            val votingKeyId = KeyId.fromBytes(masternodeKey.pubKeyHash)
            val boas = ByteArrayOutputStream(32 + 20)
            val masternodes = dashSystemService.system.masternodeListManager.masternodeList.getMasternodesByVotingKey(votingKeyId)
            masternodes.forEach { masternode ->
                try {
                    boas.write(masternode.proTxHash.bytes)
                    boas.write(masternodeKey.pubKeyHash)
                    val idBytes = Sha256Hash.of(boas.toByteArray())
                    val identity = platform.identities.get(Identifier.from(idBytes.bytes))
                    val votingIdentityPublicKey = identity!!.publicKeys.first { it.purpose == Purpose.VOTING }

                    usernames.forEachIndexed { index, username ->
                        val resourceVoteChoice = resourceVoteChoices[index]
                        try {
                            val vote = platform.names.broadcastVote(
                                resourceVoteChoice,
                                username,
                                masternode.proTxHash,
                                votingIdentityPublicKey,
                                SimpleSignerCallback(
                                    mapOf(votingIdentityPublicKey to masternodeKey),
                                    encryptionKey
                                )
                            )
                            votes.add(Triple(resourceVoteChoice, vote, null))
                        } catch (e: Exception) {
                            votes.add(Triple(resourceVoteChoice, null, e))
                        }
                    }
                } catch (e: Exception) {
                    log.info("broadcast username vote failed:", e)
                }
            }
        }
        return votes
    }

    @Throws(Exception::class)
    override suspend fun broadcastUpdatedProfile(dashPayProfile: DashPayProfile, encryptionKey: KeyParameter): DashPayProfile {
        log.info("broadcast profile")
        val blockchainIdentity = platformRepo.blockchainIdentity

        val displayName = if (dashPayProfile.displayName.isNotEmpty()) dashPayProfile.displayName else null
        val publicMessage = if (dashPayProfile.publicMessage.isNotEmpty()) dashPayProfile.publicMessage else null
        val avatarUrl = if (dashPayProfile.avatarUrl.isNotEmpty()) dashPayProfile.avatarUrl else null

        //Create Contact Request
        val timer: AnalyticsTimer
        val createdProfile = if (dashPayProfile.createdAt == 0L) {
            timer = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_PROFILE_CREATE)
            blockchainIdentity.registerProfile(displayName,
                publicMessage,
                avatarUrl,
                dashPayProfile.avatarHash,
                dashPayProfile.avatarFingerprint,
                encryptionKey)
        } else {
            timer = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_PROFILE_UPDATE)
            blockchainIdentity.updateProfile(displayName,
                publicMessage,
                avatarUrl,
                dashPayProfile.avatarHash,
                dashPayProfile.avatarFingerprint,
                encryptionKey)
        }
        timer.logTiming()
        log.info("profile broadcast")

        // TODO: Verify that the Contact Request was seen on the network?

        log.info("updated profile: $createdProfile")
        val updatedDashPayProfile = DashPayProfile.fromDocument(createdProfile, dashPayProfile.username)
        platformRepo.updateDashPayProfile(updatedDashPayProfile) //update the database since the cr was accepted
        return updatedDashPayProfile
    }
}
