/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.service.platform.sdk

import android.app.Application
import androidx.work.WorkManager
import androidx.work.await
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.service.platform.VerifyLinkObligation
import de.schildbach.wallet.service.platform.VerifyLinkObligationStore
import de.schildbach.wallet.ui.dashpay.work.BroadcastIdentityVerifyOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.sdk.platform.Names
import org.dashj.platform.wallet.IdentityVerify
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The one owner of a contested name's verification-link publication ([VerifyLinkObligation]).
 *
 * The SDK registration owners only RECORD the obligation when they accept a contested name with
 * a link (a fresh transparent or shielded creation, a name-only retry, a recovery's registration).
 * This reconcile, run from the platform sync tick, does the rest, whichever path registered the
 * name and whatever the process went through meanwhile:
 * - a request that no longer applies (another identity's record, a different name on it, the
 *   link removed) is dropped;
 * - nothing happens until the identity owns or contends for the name, while a publication is in
 *   flight, or while an earlier attempt is settling ([SETTLE_MS]: a document just submitted may
 *   not be readable yet, and an uncertain one may still land);
 * - then the IdentityVerify document is looked up by owner and normalized label: found → done,
 *   the obligation is cleared; absent → one publication is enqueued.
 *
 * An uncertain outcome (the SDK's Ambiguous, recorded by the worker) is never permission to
 * create again: only a lookup that proves the document absent, after the attempt settled, is. A
 * transient worker failure leaves the obligation in place, and a later pass publishes it. Any
 * unreadable input skips the pass, changing nothing.
 */
@Singleton
class VerifyLinkReconciler internal constructor(
    private val store: VerifyLinkObligationStore,
    /** The identity record's identity id, username and requested link, from one strict read. */
    private val currentRequest: suspend () -> CurrentRequest,
    /** True when the identity owns or contends for the label; throws when unreadable. */
    private val ownsOrContends: suspend (identityIdBase58: String, label: String) -> Boolean,
    /** The IdentityVerify document, or its absence: null when it cannot be read. */
    private val documentPresent: suspend (identityIdBase58: String, normalizedLabel: String) -> VerifyDocument?,
    /** Whether a publication for the label is queued or running; throws when unreadable. */
    private val publicationInFlight: suspend (label: String) -> Boolean,
    /** Enqueues one publication, returning once it is durably enqueued. */
    private val enqueuePublication: suspend (label: String, link: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(
        config: BlockchainIdentityConfig,
        application: Application,
        platformRepo: Provider<de.schildbach.wallet.ui.dashpay.PlatformRepo>,
        sdkIdentityVerifyQueries: SdkIdentityVerifyQueries
    ) : this(
        store = config,
        currentRequest = {
            val record = config.verifyLinkRecord()
            CurrentRequest(
                record.identityId,
                record.username,
                record.link,
                record.creationState,
                record.errorMessage,
                record.usernameRequested
            )
        },
        ownsOrContends = { identityId, label -> identityOwnsOrContendsFor(platformRepo.get(), identityId, label) },
        documentPresent = { identityId, normalizedLabel ->
            // the lookup recovery and the voting sync use: the SDK search, else dashj
            val owner = Identifier.from(identityId)
            verifyDocumentPresence(
                { sdkIdentityVerifyQueries.getVerificationUrl(owner, normalizedLabel) },
                {
                    IdentityVerify(platformRepo.get().platform.platform).get(owner, normalizedLabel)
                        ?.let { VerifyDocument.Present(it.url) } ?: VerifyDocument.Absent
                }
            )
        },
        publicationInFlight = { label ->
            WorkManager.getInstance(application)
                .getWorkInfosForUniqueWork(BroadcastIdentityVerifyOperation.uniqueWorkName(label))
                .await()
                .any { !it.state.isFinished }
        },
        enqueuePublication = { label, link ->
            BroadcastIdentityVerifyOperation(application).create(label, link).enqueue().await()
        }
    )

    /** The identity record as the reconcile reads it, from one strict read. */
    data class CurrentRequest(
        val identityId: String?,
        val username: String?,
        val link: String?,
        val creationState: IdentityCreationState = IdentityCreationState.NONE,
        val errorMessage: String? = null,
        val usernameRequested: UsernameRequestStatus? = null
    )

    private val running = Mutex()

    /** When the last pass read Platform; guarded by [running]. */
    private var lastPlatformPassAt: Long? = null

    /** One pass; see the class doc. Returns true when it enqueued a publication. */
    suspend fun reconcile(): Boolean {
        if (!running.tryLock()) return false
        try {
            return reconcileNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("verification-link reconcile skipped; retrying on a later pass", e)
            return false
        } finally {
            running.unlock()
        }
    }

    private suspend fun reconcileNow(): Boolean {
        val current = currentRequest()
        val obligation = store.verifyObligation() ?: rebuilt(current) ?: return false
        if (current.identityId != obligation.identityIdBase58 ||
            current.username?.let { Names.normalizeString(it) } != obligation.normalizedLabel ||
            current.link.isNullOrBlank()
        ) {
            log.info("verification link for '{}' no longer requested; dropping it", obligation.label)
            store.clearVerifyObligation(obligation)
            return false
        }
        if (current.link != obligation.link) {
            // the user verified again with another link: the older one is never published
            log.info("verification link for '{}' changed; replacing the pending one", obligation.label)
            store.clearVerifyObligation(obligation)
            if (SdkTransparentUsernameCreation.isVerificationLink(current.link)) {
                store.recordVerifyObligation(obligation.identityIdBase58, obligation.label, current.link)
            }
            return false
        }
        val now = clock()
        val lastAttempt = obligation.attemptAtMs
        if (lastAttempt != null && now - lastAttempt < SETTLE_MS) return false
        // the Platform reads below at most once per [MIN_PASS_INTERVAL_MS], not on every tick
        lastPlatformPassAt?.let { if (now - it < MIN_PASS_INTERVAL_MS) return false }
        lastPlatformPassAt = now
        if (!ownsOrContends(obligation.identityIdBase58, obligation.label)) return false
        if (publicationInFlight(obligation.label)) return false
        when (val document = documentPresent(obligation.identityIdBase58, obligation.normalizedLabel)) {
            is VerifyDocument.Present -> {
                if (document.url != null && document.url != obligation.link) {
                    // another link is published for the name; not this obligation's (follow-up)
                    log.warn("a verification document for '{}' carries another link; not settling it", obligation.label)
                    return false
                }
                log.info("verification link for '{}' is published; done", obligation.label)
                store.settleVerifyObligation(obligation)
                return false
            }
            null -> return false
            VerifyDocument.Absent -> Unit
        }
        if (obligation.uncertain) {
            log.info("the uncertain publication for '{}' did not land; publishing it again", obligation.label)
        }
        // recorded first: a death after the enqueue still counts the attempt (and settles); and
        // only while the obligation is still this one (a newer link may have replaced it while
        // this pass read Platform)
        if (!store.markVerifyAttempt(obligation, now)) {
            log.info("verification link for '{}' changed during the pass; not publishing it", obligation.label)
            return false
        }
        enqueuePublication(obligation.label, obligation.link)
        return true
    }

    /**
     * The obligation a registration path failed to record (its store write is best effort on a
     * funded creation), rebuilt from the identity record: a contested name with a valid link on
     * record, past the creation flow's own link step (the legacy path publishes it inline at
     * REQUESTED_NAME_LINK_SAVING), not failed, lost or locked, and not already settled. Null
     * otherwise, without any Platform read.
     */
    private suspend fun rebuilt(current: CurrentRequest): VerifyLinkObligation? {
        val identityId = current.identityId ?: return null
        val username = current.username ?: return null
        val link = current.link ?: return null
        if (!SdkTransparentUsernameCreation.isVerificationLink(link) ||
            !Names.isUsernameContestable(username) ||
            current.creationState < IdentityCreationState.REQUESTED_NAME_LINK_SAVED ||
            current.errorMessage != null ||
            current.usernameRequested == UsernameRequestStatus.LOST_VOTE ||
            current.usernameRequested == UsernameRequestStatus.LOCKED
        ) {
            return null
        }
        val candidate = VerifyLinkObligation(identityId, username, link)
        if (store.settledVerifyRequest()?.sameRequest(candidate) == true) return null
        log.info("rebuilding the verification-link obligation for '{}' from the identity record", username)
        store.recordVerifyObligation(identityId, username, link)
        return store.verifyObligation()
    }

    companion object {
        private val log = LoggerFactory.getLogger(VerifyLinkReconciler::class.java)

        /** How long an attempt settles before an absent document is trusted. */
        const val SETTLE_MS = 10 * 60 * 1000L

        /** The least time between two passes that read Platform. */
        const val MIN_PASS_INTERVAL_MS = 60 * 1000L
    }
}

/** An IdentityVerify document lookup that completed: found (with its link, when read) or absent. */
sealed class VerifyDocument {
    data class Present(val url: String?) : VerifyDocument()
    object Absent : VerifyDocument()
}

/**
 * Whether the IdentityVerify document exists: [VerifyDocument] only from a read that completed,
 * null when it is unknown (the caller must not take it as absent).
 *
 * The SDK search's own answer is definite. Without one (its flag off, or the SDK read failed)
 * the dashj query decides: it returns null only for a query that succeeded with no document, and
 * throws on any failed read (dash-sdk-kotlin 5.0.0: `DapiClient.getDocuments` rethrows or ends in
 * `MaxRetriesReachedException`, `Result.Failure.unwrap` throws, and `Documents.get` rethrows after
 * logging), which is unknown here.
 */
internal suspend fun verifyDocumentPresence(
    sdkSearch: suspend () -> java.util.Optional<String>?,
    dashjQuery: suspend () -> VerifyDocument
): VerifyDocument? = try {
    val sdk = sdkSearch()
    when {
        sdk == null -> dashjQuery()
        sdk.isPresent -> VerifyDocument.Present(sdk.get())
        else -> VerifyDocument.Absent
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    LoggerFactory.getLogger(VerifyLinkReconciler::class.java).warn("verification document lookup failed", e)
    null
}

/**
 * "Update the verification" of a name already in its vote, through the same obligation the
 * registrations record: the link is put on record (the reconcile drops an obligation whose link is
 * gone), the obligation recorded, and one reconcile pass kicked (best effort; the sync tick runs
 * it anyway). The reconcile publishes only when no document is found, so a request that already
 * has one is not published twice. False, recording nothing, without an identity, a name or a
 * valid link.
 */
internal suspend fun requestVerificationLinkPublication(
    identityIdBase58: String?,
    username: String?,
    link: String?,
    saveLink: suspend (String) -> Unit,
    store: VerifyLinkObligationStore,
    reconcile: suspend () -> Unit
): Boolean {
    if (identityIdBase58 == null || username == null || link == null ||
        !SdkTransparentUsernameCreation.isVerificationLink(link)
    ) {
        return false
    }
    saveLink(link)
    store.recordVerifyObligation(identityIdBase58, username, link)
    try {
        reconcile()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoggerFactory.getLogger(VerifyLinkReconciler::class.java)
            .warn("verification-link reconcile kick failed; the sync tick runs it", e)
    }
    return true
}
