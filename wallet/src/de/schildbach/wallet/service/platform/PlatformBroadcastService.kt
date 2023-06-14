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

import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import org.bitcoinj.core.Context
import org.bitcoinj.evolution.EvolutionContact
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import org.dashj.platform.dashpay.RetryDelayType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeoutException
import javax.inject.Inject

interface PlatformBroadcastService {
    suspend fun broadcastUpdatedProfile(dashPayProfile: DashPayProfile, encryptionKey: KeyParameter): DashPayProfile
    suspend fun sendContactRequest(toUserId: String): DashPayContactRequest
    suspend fun sendContactRequest(toUserId: String, encryptionKey: KeyParameter): DashPayContactRequest
}

class PlatformDocumentBroadcastService @Inject constructor(
    val platformRepo: PlatformRepo,
    val analytics: AnalyticsService,
    val walletDataProvider: WalletDataProvider,
    val platformSyncService: PlatformSyncService) : PlatformBroadcastService {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(PlatformDocumentBroadcastService::class.java)
    }

    val platform = platformRepo.platform
    val contactRequests = platformRepo.contactRequests
    
    @Throws(Exception::class)
    override suspend fun sendContactRequest(toUserId: String): DashPayContactRequest {
        if (walletDataProvider.wallet!!.isEncrypted) {
            // always create a SecurityGuard when it is required
            val securityGuard = SecurityGuard()
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
        val cr = contactRequests.create(blockchainIdentity, potentialContactIdentity!!, encryptionKey)
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

        //Verify that the Contact Request was seen on the network
        val updatedProfile = blockchainIdentity.watchProfile(100, 5000, RetryDelayType.LINEAR)

        if (createdProfile != updatedProfile) {
            log.warn("Created profile doesn't match profile from network $createdProfile != $updatedProfile")
        }

        log.info("updated profile: $updatedProfile")
        if (updatedProfile != null) {
            val updatedDashPayProfile = DashPayProfile.fromDocument(updatedProfile, dashPayProfile.username)
            platformRepo.updateDashPayProfile(updatedDashPayProfile!!) //update the database since the cr was accepted
            return updatedDashPayProfile
        } else {
            throw TimeoutException("timeout when updating profile")
        }
    }
}
