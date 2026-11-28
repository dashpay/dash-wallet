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

package de.schildbach.wallet.service.platform

import org.dashj.platform.sdk.platform.Names

/**
 * A contested name's verification link that still has to be published as its IdentityVerify
 * document: recorded when the name is accepted for registration, cleared only once the document
 * is found on Platform (or the request no longer applies). See
 * [de.schildbach.wallet.service.platform.sdk.VerifyLinkReconciler], its one owner.
 */
data class VerifyLinkObligation(
    val identityIdBase58: String,
    /** The requested label, as the user typed it (the publisher's input). */
    val label: String,
    val link: String,
    /** When a publication was last enqueued, or null when none was. */
    val attemptAtMs: Long? = null,
    /** The last publication failed after submitting: its document may have landed. */
    val uncertain: Boolean = false
) {
    val normalizedLabel: String get() = Names.normalizeString(label)

    /** The same request: identity, name and link (attempt bookkeeping aside). */
    fun sameRequest(other: VerifyLinkObligation): Boolean =
        identityIdBase58 == other.identityIdBase58 && normalizedLabel == other.normalizedLabel && link == other.link
}

/** Durable store of the one pending [VerifyLinkObligation]. */
interface VerifyLinkObligationStore {
    /**
     * Records the obligation for [identityIdBase58]'s [label] and [link], replacing any other.
     * The same request already pending keeps its attempt bookkeeping (an uncertain attempt stays
     * uncertain).
     */
    suspend fun recordVerifyObligation(identityIdBase58: String, label: String, link: String)

    /** The pending obligation, or null; throws when the store cannot be read. */
    suspend fun verifyObligation(): VerifyLinkObligation?

    /**
     * Records an enqueued publication of [expected], when it is still the pending one (identity,
     * name and link). Returns whether it was: a false answer means the request changed meanwhile
     * and must not be published.
     */
    suspend fun markVerifyAttempt(expected: VerifyLinkObligation, atMs: Long): Boolean

    /**
     * The publication for [identityIdBase58]'s [label] failed after submitting (its outcome is
     * unknown), when that is still the pending obligation.
     */
    suspend fun markVerifyUncertain(identityIdBase58: String, label: String, atMs: Long)

    /** Clears the obligation, when it is still [expected]'s request. */
    suspend fun clearVerifyObligation(expected: VerifyLinkObligation)

    /**
     * In one write: clears the obligation, when it is still [expected]'s request, and remembers
     * the request as settled (its document was found), so it is never rebuilt.
     */
    suspend fun settleVerifyObligation(expected: VerifyLinkObligation)

    /** The last request whose document was found, or null; throws when unreadable. */
    suspend fun settledVerifyRequest(): VerifyLinkObligation?
}

/** In-memory [VerifyLinkObligationStore] for host tests; not durable. */
class InMemoryVerifyLinkObligationStore : VerifyLinkObligationStore {
    @Volatile var obligation: VerifyLinkObligation? = null
    private val lock = Any()

    override suspend fun recordVerifyObligation(identityIdBase58: String, label: String, link: String) {
        val recorded = VerifyLinkObligation(identityIdBase58, label, link)
        synchronized(lock) { if (obligation?.sameRequest(recorded) != true) obligation = recorded }
    }

    override suspend fun verifyObligation(): VerifyLinkObligation? = obligation

    override suspend fun markVerifyAttempt(expected: VerifyLinkObligation, atMs: Long): Boolean = synchronized(lock) {
        val pending = obligation?.takeIf { it.sameRequest(expected) } ?: return@synchronized false
        obligation = pending.copy(attemptAtMs = atMs, uncertain = false)
        true
    }

    override suspend fun markVerifyUncertain(identityIdBase58: String, label: String, atMs: Long) = synchronized(lock) {
        obligation?.takeIf {
            it.identityIdBase58 == identityIdBase58 && it.normalizedLabel == Names.normalizeString(label)
        }?.let { obligation = it.copy(attemptAtMs = atMs, uncertain = true) }
        Unit
    }

    override suspend fun clearVerifyObligation(expected: VerifyLinkObligation) = synchronized(lock) {
        if (obligation?.sameRequest(expected) == true) obligation = null
    }

    @Volatile var settled: VerifyLinkObligation? = null

    override suspend fun settleVerifyObligation(expected: VerifyLinkObligation) = synchronized(lock) {
        if (obligation?.sameRequest(expected) == true) {
            obligation = null
            settled = VerifyLinkObligation(expected.identityIdBase58, expected.label, expected.link)
        }
    }

    override suspend fun settledVerifyRequest(): VerifyLinkObligation? = settled
}

/**
 * Whether a verification-link publication for [label] and [link] is still the pending
 * [obligation]'s: the worker re-reads it right before submitting, and publishes nothing for a
 * request that was replaced (a newer link), settled or dropped since it was enqueued.
 */
fun verifyWorkStillCurrent(obligation: VerifyLinkObligation?, label: String, link: String): Boolean =
    obligation != null && obligation.normalizedLabel == Names.normalizeString(label) && obligation.link == link
