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

package de.schildbach.wallet.service

import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the dashj-side display-cache PRESERVE GUARD
 * ([mergeDisplayEntryPreservingSdkStamped]) and the SDK-authority register on
 * [DisplayCacheRefreshBus] that identifies a NON-contact SDK-stamped row.
 *
 * Post-cutover the dashj wrapper cannot value an SDK-authored send (its inputs are
 * unconnected, so `tx.getValue(wallet)` degenerates to 0 or to a fee-only figure) and
 * its rebuild writers would otherwise revert an SDK-corrected row to a green
 * "Received"/"Sending 0" misread on every memo edit, live-tx batch or contact resolve.
 */
class TxDisplayCacheMergeGuardTest {

    private val sendingTitle = "Sending"
    private val sentTitle = "Sent"
    private val rowId = "ab".repeat(32)

    private fun entry(
        title: String,
        valueSatoshis: Long,
        iconType: Int,
        iconBgType: Int = TxDisplayCacheEntry.BG_SENT,
        statusText: String = "",
        filterFlags: Int = TxDisplayCacheEntry.FLAG_SENT,
        comment: String = "",
        service: String? = null,
        contactUserId: String? = null,
        exchangeRateFiatCode: String? = null,
        swapStatus: String? = null
    ) = TxDisplayCacheEntry(
        rowId = rowId,
        title = title,
        valueSatoshis = valueSatoshis,
        iconType = iconType,
        iconBgType = iconBgType,
        statusText = statusText,
        comment = comment,
        transactionAmount = 1,
        time = 1_700_000_000_000L,
        hasErrors = false,
        service = service,
        exchangeRateFiatCode = exchangeRateFiatCode,
        exchangeRateFiatValue = if (exchangeRateFiatCode != null) 42L else null,
        contactUsername = if (contactUserId != null) "alice" else null,
        contactDisplayName = null,
        contactAvatarUrl = null,
        contactUserId = contactUserId,
        filterFlags = filterFlags,
        swapStatus = swapStatus
    )

    /** The SDK-corrected row for a confirmed plain send (no contact identity on it). */
    private val sdkCorrected = entry(
        title = sentTitle,
        valueSatoshis = -96_450_513L,
        iconType = TxDisplayCacheEntry.ICON_SENT,
        exchangeRateFiatCode = "USD",
        comment = "memo"
    )

    /** What the dashj rebuild computes for that same tx: net 0 → a RECEIVED misread. */
    private val dashjMisread = entry(
        title = sendingTitle,
        valueSatoshis = 0L,
        iconType = TxDisplayCacheEntry.ICON_RECEIVED,
        iconBgType = TxDisplayCacheEntry.BG_RECEIVED,
        statusText = "Processing",
        filterFlags = TxDisplayCacheEntry.FLAG_RECEIVED
    )

    private fun merge(
        entry: TxDisplayCacheEntry,
        existing: TxDisplayCacheEntry?,
        sdkAuthoritative: Boolean
    ) = mergeDisplayEntryPreservingSdkStamped(
        entry, existing, sendingTitle, sentTitle, sdkAuthoritative
    )

    @Test
    fun sdkAuthoritativeNonContactRowIsFrozenAgainstTheDashjRebuild() {
        val merged = merge(dashjMisread, sdkCorrected, sdkAuthoritative = true)
        assertEquals(-96_450_513L, merged.valueSatoshis)
        assertEquals(TxDisplayCacheEntry.ICON_SENT, merged.iconType)
        assertEquals(TxDisplayCacheEntry.BG_SENT, merged.iconBgType)
        assertEquals(sentTitle, merged.title)
        assertEquals("", merged.statusText)
        assertEquals(TxDisplayCacheEntry.FLAG_SENT, merged.filterFlags)
        // The rate the SDK stamped is not cleared either.
        assertEquals("USD", merged.exchangeRateFiatCode)
    }

    @Test
    fun sdkAuthoritativeRowStillTakesTheDashjMemo() {
        // The guard freezes the display SHAPE only — dashj's legitimate columns
        // (memo, service classification, custom icon, time) still flow through.
        val rebuild = dashjMisread.copy(
            valueSatoshis = -96_450_513L,
            iconType = TxDisplayCacheEntry.ICON_SENT,
            iconBgType = TxDisplayCacheEntry.BG_SENT,
            filterFlags = TxDisplayCacheEntry.FLAG_SENT,
            title = sentTitle,
            statusText = "",
            comment = "new memo"
        )
        val merged = merge(rebuild, sdkCorrected, sdkAuthoritative = true)
        assertEquals("new memo", merged.comment)
        assertEquals(-96_450_513L, merged.valueSatoshis)
    }

    @Test
    fun sameDirectionStatusProgressStillFlowsThrough() {
        val cached = entry(
            title = sendingTitle,
            valueSatoshis = -1_000_000L,
            iconType = TxDisplayCacheEntry.ICON_SENT,
            statusText = "Processing"
        )
        val rebuild = cached.copy(title = sentTitle, statusText = "")
        val merged = merge(rebuild, cached, sdkAuthoritative = true)
        assertEquals(sentTitle, merged.title)
        assertEquals("", merged.statusText)
        assertEquals(-1_000_000L, merged.valueSatoshis)
    }

    @Test
    fun unregisteredNonContactRowIsUntouched_preCutoverBehaviour() {
        // Pre-cutover nothing registers rows, so a plain rebuild passes through
        // byte-for-byte (the degenerate-value rule is the only legacy exception,
        // exercised below).
        val cached = entry(
            title = sentTitle,
            valueSatoshis = -1_000_000L,
            iconType = TxDisplayCacheEntry.ICON_SENT
        )
        val rebuild = cached.copy(valueSatoshis = -2_000_000L, statusText = "Confirming")
        val merged = merge(rebuild, cached, sdkAuthoritative = false)
        assertEquals(rebuild, merged)
    }

    @Test
    fun degenerateRebuildIsStillFrozenWithoutRegistration() {
        val merged = merge(dashjMisread, sdkCorrected, sdkAuthoritative = false)
        assertEquals(-96_450_513L, merged.valueSatoshis)
        assertEquals(TxDisplayCacheEntry.ICON_SENT, merged.iconType)
        assertEquals(sentTitle, merged.title)
    }

    @Test
    fun contactRowIsFrozenRegardlessOfRegistration() {
        val cachedContact = sdkCorrected.copy(
            contactUserId = "id-alice",
            contactUsername = "alice",
            valueSatoshis = -40_000_000L
        )
        // dashj's fee-only rewrite of a friendship send: same direction, non-zero.
        val rebuild = entry(
            title = sentTitle,
            valueSatoshis = -260L,
            iconType = TxDisplayCacheEntry.ICON_SENT
        )
        val merged = merge(rebuild, cachedContact, sdkAuthoritative = false)
        assertEquals(-40_000_000L, merged.valueSatoshis)
        assertEquals("id-alice", merged.contactUserId)
    }

    @Test
    fun newlyErroredRebuildIsNeverFrozen() {
        // dashj owns the error classification (dead/conflicting/double-spent); the SDK
        // display path has none, so a failed tx must be allowed to relabel itself.
        val errored = dashjMisread.copy(
            hasErrors = true,
            title = "Conflicting",
            iconType = TxDisplayCacheEntry.ICON_ERROR,
            iconBgType = TxDisplayCacheEntry.BG_ERROR,
            valueSatoshis = -96_450_513L
        )
        val merged = merge(errored, sdkCorrected, sdkAuthoritative = true)
        assertTrue(merged.hasErrors)
        assertEquals(TxDisplayCacheEntry.ICON_ERROR, merged.iconType)
        assertEquals("Conflicting", merged.title)
    }

    @Test
    fun missingExistingRowPassesThrough() {
        assertEquals(dashjMisread, merge(dashjMisread, null, sdkAuthoritative = true))
    }

    @Test
    fun mergeIsIdempotent() {
        val once = merge(dashjMisread, sdkCorrected, sdkAuthoritative = true)
        val twice = merge(dashjMisread, once, sdkAuthoritative = true)
        assertEquals(once, twice)
    }

    // ── The SDK-authority register ────────────────────────────────────

    @Test
    fun refreshBusRegistersAndReportsSdkAuthority() {
        val bus = DisplayCacheRefreshBus()
        assertFalse(bus.isSdkAuthoritative(rowId))
        bus.markSdkAuthoritative(emptySet())
        assertFalse(bus.isSdkAuthoritative(rowId))
        bus.markSdkAuthoritative(setOf(rowId))
        assertTrue(bus.isSdkAuthoritative(rowId))
        // Idempotent re-marking.
        bus.markSdkAuthoritative(listOf(rowId, rowId))
        assertTrue(bus.isSdkAuthoritative(rowId))
        assertFalse(bus.isSdkAuthoritative("cd".repeat(32)))
    }

    @Test
    fun refreshBusRegisterIsBoundedAndEvictsEldest() {
        val bus = DisplayCacheRefreshBus()
        val overflow = DisplayCacheRefreshBus.SDK_AUTHORITATIVE_MAX + 16
        bus.markSdkAuthoritative((0 until overflow).map { "row-$it" })
        // Eldest evicted, newest retained — an evicted row is simply re-claimed
        // by the next SDK sync pass.
        assertFalse(bus.isSdkAuthoritative("row-0"))
        assertTrue(bus.isSdkAuthoritative("row-${overflow - 1}"))
    }

    // ── Swap decoration vs. the freeze ────────────────────────────────────
    // The swap-orders table is metadata-authoritative (the tracking service
    // flips PENDING→COMPLETED long after the row was SDK-stamped), so a
    // swap-decorated rebuild must update the SHAPE while the value stays
    // frozen — the 2026-08-05 Maya field test showed the home row pinned at
    // its stale title until a manual cache wipe.

    /** A swap-decorated rebuild: dashj-degenerate value, fresh swap shape. */
    private fun swapRebuild(status: String, title: String) = entry(
        title = title,
        valueSatoshis = 0L,
        iconType = TxDisplayCacheEntry.ICON_CONVERT,
        service = "swapkit",
        swapStatus = status
    )

    @Test
    fun swapDecorationPassesTheFreezeOnAnSdkStampedRow() {
        // SDK-stamped plain "Sent" row; the swap order then lands (PENDING).
        val merged = merge(
            swapRebuild("PENDING", "Conversion: DASH → RUNE"),
            sdkCorrected,
            sdkAuthoritative = true
        )
        assertEquals("Conversion: DASH → RUNE", merged.title)
        assertEquals(TxDisplayCacheEntry.ICON_CONVERT, merged.iconType)
        // The dashj-degenerate value never clobbers the SDK-stamped one.
        assertEquals(-96_450_513L, merged.valueSatoshis)
        assertEquals(TxDisplayCacheEntry.FLAG_SENT, merged.filterFlags)
    }

    @Test
    fun swapStatusProgressUpdatesTheFrozenTitle() {
        val existingSwapRow = entry(
            title = "Conversion: DASH → RUNE",
            valueSatoshis = -5_319_295L,
            iconType = TxDisplayCacheEntry.ICON_CONVERT,
            service = "swapkit",
            swapStatus = "PENDING"
        )
        val merged = merge(
            swapRebuild("COMPLETED", "Converted: DASH → RUNE"),
            existingSwapRow,
            sdkAuthoritative = true
        )
        assertEquals("Converted: DASH → RUNE", merged.title)
        assertEquals("COMPLETED", merged.swapStatus)
        assertEquals(-5_319_295L, merged.valueSatoshis)
    }

    @Test
    fun rebuildWithoutSwapMetadataNeverUndressesASwapRow() {
        val existingSwapRow = entry(
            title = "Converted: DASH → RUNE",
            valueSatoshis = -5_319_295L,
            iconType = TxDisplayCacheEntry.ICON_CONVERT,
            service = "swapkit",
            swapStatus = "COMPLETED"
        )
        // A live-tx batch rebuild that missed the metadata join keeps the shape.
        val merged = merge(dashjMisread, existingSwapRow, sdkAuthoritative = true)
        assertEquals("Converted: DASH → RUNE", merged.title)
        assertEquals(TxDisplayCacheEntry.ICON_CONVERT, merged.iconType)
        assertEquals(-5_319_295L, merged.valueSatoshis)
    }
}
