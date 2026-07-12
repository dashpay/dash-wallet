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
import de.schildbach.wallet.service.platform.sdk.SdkDashPayWrites
import de.schildbach.wallet.service.platform.sdk.SdkIdentityVerifyWrites
import de.schildbach.wallet.service.platform.sdk.SdkWalletBinder
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.service.platform.sdk.WalletUnlock
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
import org.dashj.platform.dashpay.ContactRequest
import org.dashj.platform.dashpay.RetryDelayType
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
    val identityRepository: IdentityRepository,
    val platformRepo: PlatformRepo,
    val analytics: AnalyticsService,
    val walletDataProvider: WalletDataProvider,
    val platformSyncService: PlatformSyncService,
    val sdkDashPayWrites: SdkDashPayWrites,
    val sdkIdentityVerifyWrites: SdkIdentityVerifyWrites,
    val sdkWalletBinder: SdkWalletBinder
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
        // getContactIdentity (not identities.get) tolerates the legacy dashj
        // identity cache being unable to CBOR-serialize a v4.1 identity — e.g.
        // an iOS username being ACCEPTED here (accept is this reciprocal
        // sendContactRequest). identities.get fetches the identity fine but its
        // storeIdentity cache write throws "No converter for ...", losing the
        // fetched identity and aborting the accept before any broadcast (SDK or
        // dashj) is even attempted. Both branches below need this identity, so
        // recovering it uncached fixes the SDK Broadcast path and the dashj
        // fallback alike.
        val potentialContactIdentity = platform.getContactIdentity(Identifier.from(toUserId))
        // NEVER string-interpolate a legacy dashj Identity/BaseObject: its
        // toString() routes through hashCode() -> CBOR toBuffer(), which throws
        // "No converter for ..." on v4.x contract-bound identities (the exact
        // crash getContactIdentity above just recovered from). Log the id only.
        log.info("potential contact identity: ${potentialContactIdentity?.id}")
        val blockchainIdentity = identityRepository.blockchainIdentity
            ?: throw IllegalStateException("blockchain identity not available; ensure identity is loaded before calling PlatformBroadcastService.sendContactRequest")

        // Phase 3f: opportunistic background (re)bind — this call site
        // already holds the wallet decrypt key, so a bind that failed (or
        // never ran) at platform-sync start is healed here and the NEXT
        // write can take the SDK path. Fire-and-forget: never blocks or
        // fails this broadcast; inert unless a USE_KOTLIN_SDK_* flag is on.
        sdkWalletBinder.bindInBackground(WalletUnlock.EncryptionKey(encryptionKey))

        // Phase 3e/3g (docs/kotlin-sdk-migration-plan.md): DashPay write path
        // behind USE_KOTLIN_SDK_DASHPAY_WRITES (default off). This single
        // routing also covers ACCEPTING an incoming contact request — in this
        // app "accept" is exactly this reciprocal sendContactRequest (all
        // accept UI actions funnel here via SendContactRequestWorker); the
        // direction-specific bookkeeping (sending keychain + incoming DB row)
        // is done by PlatformSyncService when the incoming request is synced,
        // independent of which stack broadcasts the reciprocal. The result is
        // three-valued to keep the no-double-broadcast invariant:
        // NotBroadcast → the SDK definitively submitted nothing, run the
        // dashj path below unchanged; Broadcast → the request is on
        // Platform, reconcile local state from it and do NOT run dashj;
        // Ambiguous → surface the failure exactly like a dashj broadcast
        // failure — never retry via dashj in the same call.
        when (val sdkResult = sdkDashPayWrites.sendContactRequest(blockchainIdentity.uniqueIdString, toUserId)) {
            is SdkWriteResult.Broadcast -> {
                log.info("contact request sent via Kotlin SDK; reconciling from platform")
                // The SDK confirmed the broadcast, so the document is
                // committed; watch fetches it (with retries for propagation).
                val document = platform.contactRequests.watchContactRequest(
                    Identifier.from(blockchainIdentity.uniqueIdString),
                    Identifier.from(toUserId),
                    10,
                    1000,
                    RetryDelayType.LINEAR
                ) ?: throw IllegalStateException(
                    "contact request was broadcast via the Kotlin SDK but could not be retrieved " +
                        "from platform; local state will reconcile on the next contact sync"
                )
                return finalizeSentContactRequest(
                    ContactRequest(document),
                    blockchainIdentity,
                    potentialContactIdentity!!,
                    toUserId,
                    encryptionKey
                )
            }
            is SdkWriteResult.Ambiguous -> {
                log.error(
                    "SDK contact request outcome ambiguous (may be broadcast); surfacing error " +
                        "without dashj retry"
                )
                throw sdkResult.cause as? Exception ?: RuntimeException(sdkResult.cause)
            }
            is SdkWriteResult.NotBroadcast -> {
                // Fall through to the unchanged dashj path.
            }
        }

        // The legacy create below signs with the identity's HIGH/AUTHENTICATION key, resolved
        // through AuthenticationKeyChain.findKeyFromPubKey — which only knows keys ISSUED on
        // the BLOCKCHAIN_IDENTITY chain. SDK-created identities that were handed to dashj via
        // the restore path have none issued (the chain has lookahead 0), so signing fails with
        // "signer callback returned 0". Backfill any missing chain keys here; this repairs
        // wallets restored before the fix in RestoreIdentityWorker and is a no-op otherwise.
        platformRepo.ensureIdentityChainKeys(blockchainIdentity.identity, encryptionKey)

        // Create Contact Request
        val timer = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_CONTACT_REQUEST_SEND)
        val cr = platform.contactRequests.create(blockchainIdentity, potentialContactIdentity!!, encryptionKey)
        timer.logTiming()
        log.info("contact request sent")

        return finalizeSentContactRequest(cr, blockchainIdentity, potentialContactIdentity, toUserId, encryptionKey)
    }

    /**
     * Post-broadcast bookkeeping shared by the dashj and Kotlin-SDK contact
     * request paths (extracted unchanged from the dashj flow): add the
     * DIP-15 receiving friendship keychain for this contact if missing,
     * refresh bloom filters, persist the request + contact profile, and
     * notify listeners.
     */
    private suspend fun finalizeSentContactRequest(
        cr: ContactRequest,
        blockchainIdentity: org.dashj.platform.dashpay.BlockchainIdentity,
        potentialContactIdentity: org.dashj.platform.dpp.identity.Identity,
        toUserId: String,
        encryptionKey: KeyParameter
    ): DashPayContactRequest {
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
        identityRepository.updateDashPayContactRequest(dashPayContactRequest) // update the database since the cr was accepted
        platformRepo.updateDashPayProfile(toUserId) // update the profile
        platformSyncService.fireContactsUpdatedListeners() // trigger listeners
        return dashPayContactRequest
    }

    override suspend fun broadcastIdentityVerify(username: String, url: String, encryptionKey: KeyParameter?): IdentityVerifyDocument {
        val blockchainIdentity = identityRepository.blockchainIdentity
            ?: throw IllegalStateException("blockchain identity not available; ensure identity is loaded before calling PlatformBroadcastService.broadcastIdentityVerify")

        // Opportunistic background (re)bind, mirroring sendContactRequest:
        // this call site holds the wallet decrypt key, so a bind that failed
        // (or never ran) at platform-sync start is healed here and the NEXT
        // write can take the SDK path. Fire-and-forget; inert unless a
        // USE_KOTLIN_SDK_* flag is on.
        encryptionKey?.let { sdkWalletBinder.bindInBackground(WalletUnlock.EncryptionKey(it)) }

        // dashpay/platform#4088 (light way): the identityVerify document is
        // routed through the Kotlin SDK's GENERIC document-create API behind
        // USE_KOTLIN_SDK_DASHPAY_WRITES (default off) — same trust domain as
        // the other Platform document writes, no dedicated SDK surface. The
        // result is three-valued to keep the no-double-broadcast invariant:
        // NotBroadcast → the SDK definitively submitted nothing, run the
        // dashj path below unchanged; Broadcast → the document is on
        // Platform, return the rebuilt document (all fields are
        // client-determined) and do NOT run dashj; Ambiguous → surface the
        // failure exactly like a dashj broadcast failure — never retry via
        // dashj in the same call.
        when (
            val sdkResult = sdkIdentityVerifyWrites.createForDashDomain(
                blockchainIdentity.uniqueIdString,
                username,
                url
            )
        ) {
            is SdkWriteResult.Broadcast -> {
                log.info("identity verify document sent via Kotlin SDK")
                return sdkResult.value
            }
            is SdkWriteResult.Ambiguous -> {
                log.error(
                    "SDK identity verify outcome ambiguous (may be broadcast); surfacing error " +
                        "without dashj retry"
                )
                throw sdkResult.cause as? Exception ?: RuntimeException(sdkResult.cause)
            }
            is SdkWriteResult.NotBroadcast -> {
                // Fall through to the unchanged dashj path.
            }
        }

        // Same WalletSignerCallback → findKeyFromPubKey resolution as the contact request
        // path: backfill identity chain keys that were never issued (SDK-created identities
        // restored into dashj); no-op otherwise.
        platformRepo.ensureIdentityChainKeys(blockchainIdentity.identity, encryptionKey)

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
        val blockchainIdentity = identityRepository.blockchainIdentity
            ?: throw IllegalStateException("blockchain identity not available; ensure identity is loaded before calling PlatformBroadcastService.broadcastUpdatedProfile")

        val displayName = if (dashPayProfile.displayName.isNotEmpty()) dashPayProfile.displayName else null
        val publicMessage = if (dashPayProfile.publicMessage.isNotEmpty()) dashPayProfile.publicMessage else null
        val avatarUrl = if (dashPayProfile.avatarUrl.isNotEmpty()) dashPayProfile.avatarUrl else null

        // Phase 3f: opportunistic background (re)bind while the decrypt key
        // is in scope — see sendContactRequest. Fire-and-forget; inert
        // unless a USE_KOTLIN_SDK_* flag is on.
        sdkWalletBinder.bindInBackground(WalletUnlock.EncryptionKey(encryptionKey))

        // Phase 3e (docs/kotlin-sdk-migration-plan.md): profile write via
        // the Kotlin SDK behind USE_KOTLIN_SDK_DASHPAY_WRITES (default off).
        // NotBroadcast → run the dashj path below unchanged; Broadcast →
        // fetch the committed document and update the database, do NOT run
        // dashj; Ambiguous → surface like a dashj broadcast failure, never
        // retry via dashj in the same call. Profiles carrying an avatar
        // hash/fingerprint always come back NotBroadcast (SDK gap — it
        // recomputes both from raw avatar bytes the app no longer has).
        val sdkResult = sdkDashPayWrites.createOrUpdateProfile(
            ownUserId = blockchainIdentity.uniqueIdString,
            displayName = displayName,
            publicMessage = publicMessage,
            avatarUrl = avatarUrl,
            hasAvatarDigest = dashPayProfile.avatarHash != null || dashPayProfile.avatarFingerprint != null,
            doCreate = dashPayProfile.createdAt == 0L
        )
        when (sdkResult) {
            is SdkWriteResult.Broadcast -> {
                log.info("profile broadcast via Kotlin SDK; reconciling from platform")
                // The SDK write waits for platform confirmation, so the new
                // revision is committed and a plain read returns it.
                val document = platform.profiles.get(blockchainIdentity.uniqueIdString)
                    ?: throw IllegalStateException(
                        "profile was broadcast via the Kotlin SDK but could not be retrieved from " +
                            "platform; local state will reconcile on the next profile sync"
                    )
                val updatedDashPayProfile = DashPayProfile.fromDocument(document, dashPayProfile.username)
                platformRepo.updateDashPayProfile(updatedDashPayProfile)
                return updatedDashPayProfile
            }
            is SdkWriteResult.Ambiguous -> {
                log.error(
                    "SDK profile broadcast outcome ambiguous (may be broadcast); surfacing error " +
                        "without dashj retry"
                )
                throw sdkResult.cause as? Exception ?: RuntimeException(sdkResult.cause)
            }
            is SdkWriteResult.NotBroadcast -> {
                // Fall through to the unchanged dashj path.
            }
        }

        // Legacy Profiles.create/replace sign with the identity's HIGH key through
        // WalletSignerCallback → AuthenticationKeyChain.findKeyFromPubKey, which only knows
        // ISSUED chain keys — backfill any missing ones (SDK-created identities restored
        // into dashj have none); no-op for legacy-created identities.
        platformRepo.ensureIdentityChainKeys(blockchainIdentity.identity, encryptionKey)

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
