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
import de.schildbach.wallet.service.platform.VerifyLinkObligation
import de.schildbach.wallet.service.platform.VerifyLinkObligationStore
import de.schildbach.wallet.ui.dashpay.work.BroadcastIdentityVerifyOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
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
    /** Whether the IdentityVerify document exists: null when it cannot be read. */
    private val documentPresent: suspend (identityIdBase58: String, normalizedLabel: String) -> Boolean?,
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
            val routing = config.retryRouting()
            CurrentRequest(
                routing.identityId,
                routing.username,
                config.getPreservingErrors(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK)
            )
        },
        ownsOrContends = { identityId, label -> identityOwnsOrContendsFor(platformRepo.get(), identityId, label) },
        documentPresent = { identityId, normalizedLabel ->
            // the lookup recovery and the voting sync use: the SDK search, else dashj
            val owner = Identifier.from(identityId)
            val sdk = sdkIdentityVerifyQueries.getVerificationUrl(owner, normalizedLabel)
            if (sdk != null) {
                sdk.isPresent
            } else {
                IdentityVerify(platformRepo.get().platform.platform).get(owner, normalizedLabel) != null
            }
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

    data class CurrentRequest(val identityId: String?, val username: String?, val link: String?)

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
        val obligation = store.verifyObligation() ?: return false
        val current = currentRequest()
        if (current.identityId != obligation.identityIdBase58 ||
            current.username?.let { Names.normalizeString(it) } != obligation.normalizedLabel ||
            current.link.isNullOrBlank()
        ) {
            log.info("verification link for '{}' no longer requested; dropping it", obligation.label)
            store.clearVerifyObligation(obligation)
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
        when (documentPresent(obligation.identityIdBase58, obligation.normalizedLabel)) {
            true -> {
                log.info("verification link for '{}' is published; done", obligation.label)
                store.clearVerifyObligation(obligation)
                return false
            }
            null -> return false
            false -> Unit
        }
        if (obligation.uncertain) {
            log.info("the uncertain publication for '{}' did not land; publishing it again", obligation.label)
        }
        // recorded first: a death after the enqueue still counts the attempt (and settles)
        store.markVerifyAttempt(obligation, now)
        enqueuePublication(obligation.label, obligation.link)
        return true
    }

    companion object {
        private val log = LoggerFactory.getLogger(VerifyLinkReconciler::class.java)

        /** How long an attempt settles before an absent document is trusted. */
        const val SETTLE_MS = 10 * 60 * 1000L

        /** The least time between two passes that read Platform. */
        const val MIN_PASS_INTERVAL_MS = 60 * 1000L
    }
}
