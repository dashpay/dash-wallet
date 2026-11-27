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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/** What the Platform activity detail sheet shows at a given moment. */
sealed class PlatformActivityDetailState {
    /** The entry, as of the latest activity emission. */
    data class Shown(val detail: PlatformActivityDetail) : PlatformActivityDetailState()

    /**
     * Terminal: the entry cannot be shown (malformed key, no wallet became
     * ready, the entry disappeared — e.g. it failed — the wallet changed, it no
     * longer maps to a row, or the feed failed). The sheet closes.
     */
    object Unavailable : PlatformActivityDetailState()
}

private val log = LoggerFactory.getLogger("PlatformActivityDetailFeed")

/** How long the sheet waits for a ready shielded wallet before giving up. */
internal const val PLATFORM_ACTIVITY_READY_WAIT_MS = 30_000L

/** Pause before the sheet re-reads after a failed store read. */
internal const val PLATFORM_ACTIVITY_READ_RETRY_MS = 5_000L

/**
 * The live detail of the shielded-activity row [rowId]: follows the ACTIVE
 * wallet's activity feed so a pending entry flips to confirmed in place and a
 * failed (removed) entry closes the sheet, re-resolving the counterparty
 * ownership on every emission. Bound to the wallet of the first ready
 * snapshot; a wallet change or a not-ready emission after that ends the feed
 * with [PlatformActivityDetailState.Unavailable] (the entry belonged to that
 * wallet). A failed store read ([ShieldedStoreReadException]) keeps the last
 * shown state and retries after [readRetryDelayMs]; any other failure ends the
 * feed as unavailable. Never emits a detail built from partial data. Collect it off the
 * main thread (the ownership lookups read the SDK store).
 */
fun platformActivityDetailFeed(
    service: ShieldedBalanceService,
    rowId: String,
    readyWaitMs: Long = PLATFORM_ACTIVITY_READY_WAIT_MS,
    readRetryDelayMs: Long = PLATFORM_ACTIVITY_READ_RETRY_MS
): Flow<PlatformActivityDetailState> = flow {
    val key = parseShieldedHistoryRowId(rowId)
    if (key == null) {
        emit(PlatformActivityDetailState.Unavailable)
        return@flow
    }
    val first = withTimeoutOrNull(readyWaitMs) {
        service.observeShieldedActivityOrNotReady().filterNotNull().first()
    }
    if (first == null) {
        emit(PlatformActivityDetailState.Unavailable)
        return@flow
    }
    val walletIdHex = first.walletIdHex
    emitAll(
        service.observeShieldedActivityOrNotReady().transformWhile { snapshot ->
            val state = detailStateFor(service, snapshot, walletIdHex, key)
            emit(state)
            state !is PlatformActivityDetailState.Unavailable
        }
    )
}
    // A failed ownership read is transient, not "unavailable": keep what is
    // shown (nothing is emitted) and re-run the feed after a pause.
    .retryWhen { cause, _ ->
        if (cause !is ShieldedStoreReadException) return@retryWhen false
        log.warn("shielded activity detail: store read failed; retrying")
        delay(readRetryDelayMs)
        true
    }
    .catch { t ->
        if (t is CancellationException) throw t
        log.warn("shielded activity detail feed failed ({})", t.javaClass.simpleName)
        emit(PlatformActivityDetailState.Unavailable)
    }
    .distinctUntilChanged()

private suspend fun detailStateFor(
    service: ShieldedBalanceService,
    snapshot: ShieldedActivitySnapshot?,
    walletIdHex: String,
    key: ShieldedActivityRowKey
): PlatformActivityDetailState {
    if (snapshot == null || !snapshot.walletIdHex.equals(walletIdHex, ignoreCase = true)) {
        return PlatformActivityDetailState.Unavailable
    }
    val entry = snapshot.entries.firstOrNull { it.accountIndex == key.accountIndex && it.id == key.entryIdHex }
        ?: return PlatformActivityDetailState.Unavailable
    val detail = platformActivityDetailOf(entry, service.counterpartyOwnership(walletIdHex, entry))
        ?: return PlatformActivityDetailState.Unavailable
    return PlatformActivityDetailState.Shown(detail)
}
