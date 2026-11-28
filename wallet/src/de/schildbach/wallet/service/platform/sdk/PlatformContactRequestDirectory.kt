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

import de.schildbach.wallet.service.platform.PlatformService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Utils
import org.dashj.platform.dashpay.ContactRequest
import org.dashj.platform.dpp.identifier.Identifier
import javax.inject.Inject

/**
 * Who an identity's contact requests are with: contact identity ids in hex.
 * [sentTo] are the recipients of the requests it sent, [receivedFrom] the
 * senders of the requests it received.
 */
data class ContactRequestCounterparties(
    val sentTo: Set<String>,
    val receivedFrom: Set<String>
)

/**
 * An identity's contact requests as Platform holds them, read WITHOUT the
 * Kotlin SDK — what the SDK's own contact discovery is checked against
 * ([contactDiscoveryUnverifiedBecause], PR #1608 review, round 13).
 *
 * The SDK's discovery cannot vouch for itself: `sync_contact_requests` (and
 * the sweep that runs it) returns normally when it ingested the received
 * requests but the fetch of the SENT ones failed, and fails only when not one
 * identity could be read. An omitted sent request means a receiving account
 * the SDK never registered, so a coverage check over the SDK's store would
 * pass without it.
 */
interface PlatformContactRequestDirectory {
    /**
     * Every contact request [identityId] sent or received, from Platform.
     * Throws when any part of either query failed: a partial answer is never
     * returned. Blocking work runs off the caller's thread.
     */
    suspend fun counterparties(identityId: ByteArray): ContactRequestCounterparties

    companion object {
        /** Nothing wired: discovery can never be verified, so it is never confirmed. */
        internal val UNAVAILABLE = object : PlatformContactRequestDirectory {
            override suspend fun counterparties(identityId: ByteArray): ContactRequestCounterparties =
                throw IllegalStateException("no Platform contact request directory")
        }
    }
}

/**
 * [PlatformContactRequestDirectory] over the app's dashj Platform client —
 * the same queries `PlatformSyncService.updateContactRequests` runs, but
 * always from the beginning (`$createdAt > 0`), never from a cursor.
 * `ContactRequests.get(retrieveAll = true)` pages until a short page and
 * rethrows any page's failure.
 */
class DashjPlatformContactRequestDirectory @Inject constructor(
    private val platformService: PlatformService
) : PlatformContactRequestDirectory {
    override suspend fun counterparties(identityId: ByteArray): ContactRequestCounterparties =
        withContext(Dispatchers.IO) {
            val id = Identifier.from(identityId)
            val requests = platformService.contactRequests
            val sent = requests.get(id, toUserId = false, afterTime = 0, retrieveAll = true)
            val received = requests.get(id, toUserId = true, afterTime = 0, retrieveAll = true)
            ContactRequestCounterparties(
                sentTo = sent.mapTo(HashSet()) { Utils.HEX.encode(ContactRequest(it).toUserId.toBuffer()) },
                receivedFrom = received.mapTo(HashSet()) { Utils.HEX.encode(it.ownerId.toBuffer()) }
            )
        }
}

/**
 * Why the SDK's store cannot yet be trusted to hold every contact request
 * Platform held at [onPlatform]'s read, or null when it can: every recipient
 * of a sent request has an outgoing row ([StoredDashPayContactRequests.outgoing]),
 * and every sender of a received request an incoming row or an entry in the
 * ignored-sender list (an ignored sender's requests are never stored).
 * Platform documents are never deleted, so a store that holds all of them
 * after a fetch that started later has nothing missing from that read.
 */
internal fun contactDiscoveryUnverifiedBecause(
    onPlatform: ContactRequestCounterparties,
    stored: StoredDashPayContactRequests
): String? {
    val missingSent = onPlatform.sentTo - stored.outgoing
    val missingReceived = onPlatform.receivedFrom - stored.incoming - stored.ignored
    if (missingSent.isEmpty() && missingReceived.isEmpty()) return null
    return buildList {
        if (missingSent.isNotEmpty()) add("${missingSent.size} sent request(s)")
        if (missingReceived.isNotEmpty()) add("${missingReceived.size} received request(s)")
    }.joinToString(" and ") + " on Platform missing from the SDK's store"
}
