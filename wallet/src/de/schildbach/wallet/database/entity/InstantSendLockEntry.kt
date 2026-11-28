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

package de.schildbach.wallet.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An APP-OWNED persisted record of one InstantSend lock the SDK engine
 * reported ([de.schildbach.wallet.service.platform.sdk.L1TxEvent.InstantLocked]).
 *
 * Exists because the engine's IS-lock knowledge is otherwise EPHEMERAL on
 * Android: the AAR's Room mirror never records it — `txos.isInstantLocked`
 * is a dead flag (0 on every row ever written) and `transactions.context`
 * goes 0 → 3 at block time without ever passing 1 — so once the in-process
 * event is consumed, no reader can learn the tx was IS-locked until a block
 * confirms it. Persisting the fact app-side (never writing to the SDK's own
 * tables) lets
 * - the asset-lock funding preflight
 *   ([de.schildbach.wallet.service.platform.sdk.SdkAssetLockFundingPreflight])
 *   count IS-locked coins as final instead of failing open, and
 * - the display pipeline
 *   ([de.schildbach.wallet.service.platform.sdk.CutoverUiDataService]) clear
 *   a row's "Processing" state even when the lock event raced or predated the
 *   row insert — including across a process restart.
 *
 * Rows are only *needed* for the pre-block window (the mirror records
 * finality itself once the block lands), so the writer prunes entries past a
 * short retention; the table stays tiny.
 */
@Entity(tableName = "instant_send_locks")
data class InstantSendLockEntry(
    /** Lowercase DISPLAY-order (byte-reversed) txid hex — the same rowId convention `tx_display_cache` uses. */
    @PrimaryKey val txId: String,
    /** Epoch millis when the engine's IS-lock event was observed. */
    val lockedAtMs: Long
)
