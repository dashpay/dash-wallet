/*
 * Copyright 2024 Dash Core Group.
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

package de.schildbach.wallet.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Belt-and-suspenders refresh bus for the SDK-corrected transaction display cache
 * ([de.schildbach.wallet.database.dao.TxDisplayCacheDao] / `tx_display_cache`).
 *
 * Room's [androidx.room.InvalidationTracker] is supposed to re-fire the reactive readers
 * (the home tx-list [androidx.paging.PagingSource] and the contact-detail
 * `observeByContactUserId` Flow) whenever [de.schildbach.wallet.service.platform.sdk.CutoverUiDataService]
 * upserts a corrected row — but on-device that auto-invalidation is unreliable: a
 * pending→confirmed flip or a direction/amount correction can linger as a stale
 * "Sending"/"Processing" for minutes even though the cache row is already correct.
 *
 * This bus gives the writer an EXPLICIT, in-memory signal to force those readers to
 * re-read/refresh, independent of Room's tracker. It is purely additive:
 *  - When nothing writes the cache, no signals fire, so pre-cutover behaviour is unchanged.
 *  - No persistence, no schema change — a pure in-memory [SharedFlow].
 *  - Emission is fire-and-forget (never blocks the writer / sync pipeline).
 *
 * Producer: [de.schildbach.wallet.service.platform.sdk.CutoverUiDataService] calls
 * [signalChanged] immediately after every `insertAll` that writes/updates display rows.
 * Consumers: [de.schildbach.wallet.service.TxDisplayCacheService] (home Paging invalidation)
 * and [de.schildbach.wallet.ui.DashPayUserActivityViewModel] (contact-detail fresh re-query).
 */
@Singleton
class DisplayCacheRefreshBus @Inject constructor() {
    // replay = 0: late subscribers do not get a stale historic tick — each consumer seeds
    // its own initial read via onStart. extraBufferCapacity = 1 + DROP_OLDEST makes
    // tryEmit non-suspending and non-failing: coalesced signals are fine because every
    // consumer re-reads the full current state on any tick.
    private val _changes = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Emits [Unit] each time the display cache is written/updated. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /**
     * Signal that display-cache rows just changed. Fire-and-forget: never suspends,
     * never throws, never blocks the caller (safe to call from the sync pipeline).
     */
    fun signalChanged() {
        _changes.tryEmit(Unit)
    }

    // ── SDK-authority register (see [markSdkAuthoritative]) ───────────────────────
    //
    // Insertion-ordered, eldest-evicted and synchronized: the producer is
    // CutoverUiDataService's single sequential collector, the consumer is
    // TxDisplayCacheService's serviceScope — different threads.
    private val sdkAuthoritativeRowIds: MutableSet<String> = java.util.Collections.newSetFromMap(
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Boolean>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
                    size > SDK_AUTHORITATIVE_MAX
            }
        )
    )

    /**
     * Record that the SDK has a DEFINITIVE record for these display rowIds and has
     * planned/verified their shape this pass — the "SDK-stamped" signal that works for
     * NON-contact rows (a contact row is additionally self-identifying via its
     * `contactUserId` column, which survives a process restart; this register does not).
     *
     * [de.schildbach.wallet.service.TxDisplayCacheService] consults it via
     * [isSdkAuthoritative] so its dashj-side rebuild writers never rewrite such a row's
     * direction/value/title/status: post-cutover dashj cannot value an SDK-authored send
     * (unconnected inputs → net 0 → a green RECEIVED row titled "Sending"), and a memo or
     * metadata edit must never change a transaction's direction or amount.
     *
     * Bounded ([SDK_AUTHORITATIVE_MAX], eldest evicted) so a very large wallet cannot grow
     * it unboundedly — an evicted row is simply re-claimed on the next sync pass, and the
     * SDK planner re-corrects it if a rebuild got there first (idempotent either way).
     * Never throws; a no-op for an empty collection.
     */
    fun markSdkAuthoritative(rowIds: Collection<String>) {
        if (rowIds.isEmpty()) return
        sdkAuthoritativeRowIds.addAll(rowIds)
    }

    /** Whether [rowId] is under SDK authority this process — see [markSdkAuthoritative]. */
    fun isSdkAuthoritative(rowId: String): Boolean = sdkAuthoritativeRowIds.contains(rowId)

    companion object {
        /** Cap of the SDK-authority register; ~64 chars/txid keeps this well under a MB. */
        internal const val SDK_AUTHORITATIVE_MAX = 8_192
    }
}
