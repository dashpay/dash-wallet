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

import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState

/** The name(s) a creation or a name-only retry requested for one identity. */
data class RequestedNameContext(
    val identityIdBase58: String,
    val username: String,
    val secondaryUsername: String?
)

/** A name-only retry that was started and has not yet handed its outcome to a durable owner. */
data class PendingNameOnlyRetry(
    val request: RequestedNameContext,
    /** The identity record's creation state now. */
    val creationState: IdentityCreationState,
    /** The identity record's identity id now. */
    val recordIdentityId: String?,
    /** The identity record's error now, from the same read. */
    val errorMessage: String? = null,
    /** The identity record's username now, from the same read. */
    val recordUsername: String? = null
)

/**
 * Durable record of the names requested for an identity, kept apart from the identity record
 * (which recovery rebuilds from the chain):
 * - the [RequestedNameContext], read by recovery so a retried recovery of the same identity keeps
 *   the requested names ([recoverySeedRecord]);
 * - a name-only retry in flight, from before it changes anything until its outcome has a durable
 *   owner (the recovery handoff, or a retryable error), so a retry the process lost can be
 *   reconciled on restart.
 */
interface RequestedNameJournal {
    suspend fun requestedNames(): RequestedNameContext?

    suspend fun recordRequestedNames(request: RequestedNameContext)

    /**
     * In one write: records [request], marks a name-only retry pending, and puts the identity
     * record on the name step for [request]'s identity and names (USERNAME_REGISTERING, no error,
     * not restoring, the old name's request status cleared; a registered secondary stays when no
     * secondary is requested).
     */
    suspend fun beginNameOnlyRetry(request: RequestedNameContext)

    /** The pending name-only retry, or null when none is pending. */
    suspend fun pendingNameOnlyRetry(): PendingNameOnlyRetry?

    /** Clears the pending mark; the requested names stay. */
    suspend fun finishNameOnlyRetry()

    /**
     * In one write: records the retry's terminal [errorMessage] on the identity record (at
     * USERNAME_REGISTERING) and clears the pending mark, so no state has one without the other.
     */
    suspend fun failNameOnlyRetry(errorMessage: String)
}

/** In-memory [RequestedNameJournal] for host tests; not durable. */
class InMemoryRequestedNameJournal : RequestedNameJournal {
    @Volatile private var request: RequestedNameContext? = null

    @Volatile private var pending = false

    @Volatile var creationState: IdentityCreationState = IdentityCreationState.NONE

    @Volatile var errorMessage: String? = null

    @Volatile var username: String? = null

    override suspend fun requestedNames(): RequestedNameContext? = request

    override suspend fun recordRequestedNames(request: RequestedNameContext) {
        this.request = request
    }

    override suspend fun beginNameOnlyRetry(request: RequestedNameContext) {
        this.request = request
        pending = true
        creationState = IdentityCreationState.USERNAME_REGISTERING
        errorMessage = null
        username = request.username
    }

    override suspend fun pendingNameOnlyRetry(): PendingNameOnlyRetry? =
        request?.takeIf { pending }?.let { PendingNameOnlyRetry(it, creationState, it.identityIdBase58, errorMessage, username) }

    override suspend fun finishNameOnlyRetry() {
        pending = false
    }

    override suspend fun failNameOnlyRetry(errorMessage: String) {
        creationState = IdentityCreationState.USERNAME_REGISTERING
        this.errorMessage = errorMessage
        pending = false
    }
}

/**
 * The record [de.schildbach.wallet.service.platform.work.RestoreIdentityWorker] starts its walk
 * from, or null for an empty one.
 *
 * - A creation handoff ([fromCreation]) starts from the [persisted] record, which carries the
 *   requested names.
 * - Any other run starts from it too when the [requested] names are this identity's
 *   ([identityIdBase58]) and the record is this identity's: a retry of a recovery that failed
 *   before it finished. An empty record would let the recovered names replace the requested ones:
 *   a registered secondary would become the name and a replacement still in its vote would be
 *   dropped. The requested names are put back on the record.
 * - A genuine restore (no requested names, or another identity's) starts empty.
 */
fun recoverySeedRecord(
    persisted: BlockchainIdentityData?,
    requested: RequestedNameContext?,
    identityIdBase58: String,
    fromCreation: Boolean
): BlockchainIdentityData? {
    if (fromCreation) return persisted
    if (persisted == null || requested == null) return null
    if (requested.identityIdBase58 != identityIdBase58 || persisted.userId != identityIdBase58) return null
    return persisted.also { record ->
        record.username = requested.username
        requested.secondaryUsername?.let { record.usernameSecondary = it }
    }
}
