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
import de.schildbach.wallet.service.platform.sdk.l1TxUiRecord
import de.schildbach.wallet.service.platform.sdk.planL1DisplaySync
import de.schildbach.wallet.service.platform.sdk.planL1InstantLockRowUpdate
import de.schildbach.wallet_test.R
import org.dash.wallet.common.data.PresentableTxMetadata
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.entity.SwapOrder
import org.dash.wallet.common.data.entity.SwapOrderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM regression tests for the home-screen row of a DEX SWAP transaction.
 *
 * The bug these pin down (verified on-device, 2026-08-07 Maya/SwapKit MAX-sell field
 * test): the row stayed titled "Sending" forever and never became
 * "Conversion · DASH/RUNE", while the transaction-details screen identified the swap
 * correctly the whole time. Two independent display-layer faults combined:
 *
 *  1. The swap decoration was only ever derived by the dashj-side writers, which need a
 *     renderable dashj transaction and only fire on a metadata DIFF. The row for a MAX
 *     sell was authored by the SDK writer
 *     ([de.schildbach.wallet.service.platform.sdk.CutoverUiDataService]) — which knows
 *     nothing about swap orders — and no later metadata diff ever arrived to re-decorate
 *     it. [planSwapRowDecorations] fixes this by re-deriving the decoration from the
 *     `swap_orders` record by txid, exactly like the details screen, with no dashj
 *     transaction involved.
 *  2. Even once decorated, the SDK planner's definitive plain-send re-stamp would
 *     re-title the row from the SDK record — and for a Maya drain that record's `context`
 *     never leaves the mempool, so the re-title was permanently "Sending". Swap rows are
 *     now part of the planner's never-touch set.
 */
class SwapRowDisplayCacheTest {

    /** The drain transaction from the field test, for traceability. */
    private val txHex = "a5c99aec2d535f71c1f65a12b1d893f0c3a53a9b252bf8335a941639cddac873"
    private val txId = TxId.wrap(txHex)

    /** Duffs the engine reported for the drain — the value the SDK row carries. */
    private val sdkNetDuffs = -7_443_157L
    private val now = 1_786_123_074_707L

    private val sendingTitle = "Sending"
    private val sentTitle = "Sent"

    // ── fixtures ──────────────────────────────────────────────────────────

    private fun order(status: SwapOrderStatus) = SwapOrder(
        txId = txId,
        service = "swapkit",
        provider = "MAYACHAIN_STREAMING",
        fromAsset = "DASH",
        toAsset = "RUNE",
        toAddress = "thor1cxyvsphuzx8mx8tkv7hrv0uru7fj6n0q4mpea8",
        depositAddress = "XhzzCcWvvx3rFfbEgkf39rE5Bqt7TP66hR",
        status = status,
        timestamp = now
    )

    private fun metadata(
        swapOrder: SwapOrder?,
        service: String? = "swapkit",
        memo: String = ""
    ) = PresentableTxMetadata(txId = txId, memo = memo, service = service)
        .also { it.swapOrder = swapOrder }

    /** Mirrors the real title resolution ([TransactionRowView.swapTitleRes] + assets). */
    private fun title(order: SwapOrder): String = when (order.status) {
        SwapOrderStatus.COMPLETED -> "Converted · ${order.fromAsset}/${order.toAsset}"
        else -> "Conversion · ${order.fromAsset}/${order.toAsset}"
    }

    /**
     * The row the SDK writer inserts for a freshly-broadcast MAX sell: plain send shape,
     * no service, no swap status, and titled "Sending" because the SDK record is still in
     * the mempool.
     */
    private fun plainSendingRow(
        title: String = sendingTitle,
        statusText: String = "",
        service: String? = null,
        swapStatus: String? = null,
        iconType: Int = TxDisplayCacheEntry.ICON_SENT,
        iconBgType: Int = TxDisplayCacheEntry.BG_SENT
    ) = TxDisplayCacheEntry(
        rowId = txHex,
        title = title,
        valueSatoshis = sdkNetDuffs,
        iconType = iconType,
        iconBgType = iconBgType,
        statusText = statusText,
        comment = "sold the lot",
        transactionAmount = 1,
        time = now,
        hasErrors = false,
        service = service,
        swapStatus = swapStatus,
        exchangeRateFiatCode = "USD",
        exchangeRateFiatValue = 3_097_720_000L,
        contactUsername = null,
        contactDisplayName = null,
        contactAvatarUrl = null,
        contactUserId = null,
        filterFlags = TxDisplayCacheEntry.FLAG_SENT
    )

    private fun decorate(
        metadata: PresentableTxMetadata,
        vararg rows: TxDisplayCacheEntry
    ) = planSwapRowDecorations(listOf(metadata), rows.associateBy { it.rowId }, ::title)

    /** The already-correct row: what the decoration converges on for a [status] order. */
    private fun decoratedRow(status: SwapOrderStatus) = plainSendingRow(
        title = title(order(status)),
        service = "swapkit",
        swapStatus = status.name,
        iconType = TxDisplayCacheEntry.ICON_CONVERT,
        iconBgType = TxDisplayCacheEntry.BG_ORANGE
    )

    // ── the decoration itself ─────────────────────────────────────────────

    @Test
    fun aPlainSendingRowIsRedecoratedFromThePendingSwapOrder() {
        val decorated = decorate(metadata(order(SwapOrderStatus.PENDING)), plainSendingRow())
        assertEquals(1, decorated.size)
        val row = decorated.single()
        assertEquals("Conversion · DASH/RUNE", row.title)
        assertEquals(TxDisplayCacheEntry.ICON_CONVERT, row.iconType)
        assertEquals(TxDisplayCacheEntry.BG_ORANGE, row.iconBgType)
        assertEquals(SwapOrderStatus.PENDING.name, row.swapStatus)
        assertEquals("swapkit", row.service)
    }

    @Test
    fun aCompletedOrderTitlesTheRowConverted() {
        val row = decorate(metadata(order(SwapOrderStatus.COMPLETED)), plainSendingRow()).single()
        assertEquals("Converted · DASH/RUNE", row.title)
        assertEquals(SwapOrderStatus.COMPLETED.name, row.swapStatus)
    }

    @Test
    fun aStaleSecondaryStatusIsClearedSoOnlyTheSwapChipShows() {
        val row = decorate(
            metadata(order(SwapOrderStatus.PENDING)),
            plainSendingRow(statusText = "Processing")
        ).single()
        assertEquals("", row.statusText)
    }

    @Test
    fun anAlreadyDecoratedRowProducesNoWrite() {
        for (status in SwapOrderStatus.entries) {
            assertTrue(
                "settled $status row must not be rewritten",
                decorate(metadata(order(status)), decoratedRow(status)).isEmpty()
            )
        }
    }

    @Test
    fun decorationPreservesEverythingItHasNoAuthorityOver() {
        val existing = plainSendingRow()
        val row = decorate(metadata(order(SwapOrderStatus.COMPLETED)), existing).single()
        assertEquals(existing.valueSatoshis, row.valueSatoshis)
        assertEquals(existing.exchangeRateFiatCode, row.exchangeRateFiatCode)
        assertEquals(existing.exchangeRateFiatValue, row.exchangeRateFiatValue)
        assertEquals(existing.comment, row.comment)
        assertEquals(existing.time, row.time)
        assertEquals(existing.filterFlags, row.filterFlags)
        assertEquals(existing.contactUserId, row.contactUserId)
    }

    @Test
    fun aSwapWithNoCachedRowYetIsSkipped() {
        assertTrue(planSwapRowDecorations(
            listOf(metadata(order(SwapOrderStatus.PENDING))),
            emptyMap(),
            ::title
        ).isEmpty())
    }

    @Test
    fun metadataWithoutASwapOrderIsNeverDecorated() {
        assertTrue(decorate(metadata(swapOrder = null, service = null), plainSendingRow()).isEmpty())
    }

    @Test
    fun anExistingServiceIsKeptWhenTheMetadataRowCarriesNone() {
        // The swap_orders record can land before setTransactionService, so the metadata
        // row is briefly service-less; the decoration must not un-classify the row.
        val row = decorate(
            metadata(order(SwapOrderStatus.PENDING), service = null),
            plainSendingRow(service = "swapkit")
        ).single()
        assertEquals("swapkit", row.service)
    }

    // ── the SDK planner must not re-author a swap row ─────────────────────

    private val resolve: (Int) -> String = { id ->
        when (id) {
            R.string.transaction_row_status_sending -> sendingTitle
            R.string.transaction_row_status_sent -> sentTitle
            R.string.transaction_row_status_received -> "Received"
            R.string.transaction_row_status_processing -> "Processing"
            R.string.transaction_row_status_confirming -> "Confirming"
            else -> "str:$id"
        }
    }

    /** An SDK `transactions` record for this txid at the given [contextCode]. */
    private fun sdkRecord(contextCode: Int) = l1TxUiRecord(
        txidWireBytes = ByteArray(32) { i -> txHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            .reversedArray(),
        netAmountDuffs = sdkNetDuffs,
        feeDuffs = null,
        contextCode = contextCode,
        directionCode = 1, // OUTGOING
        firstSeenSec = now / 1000,
        blockTimestampSec = 0
    )

    private fun syncAgainst(row: TxDisplayCacheEntry, contextCode: Int) = planL1DisplaySync(
        records = listOf(sdkRecord(contextCode)),
        existingByRowId = mapOf(row.rowId to row),
        groupedTxIds = emptySet(),
        resolve = resolve,
        nowMs = now
    )

    @Test
    fun sdkPlannerNeverReauthorsADecoratedSwapRow() {
        // context 0 = still in the mempool (the Maya drain's permanent state until the
        // compact-filter fix lands), 2 = in a block, 3 = chainlocked. In every case the
        // planner must leave the conversion row byte-identical rather than re-titling it
        // "Sending"/"Sent" from its own record.
        for (contextCode in listOf(0, 1, 2, 3)) {
            val plan = syncAgainst(decoratedRow(SwapOrderStatus.PENDING), contextCode)
            assertTrue("context=$contextCode must not update a swap row", plan.updates.isEmpty())
            assertTrue("context=$contextCode must not insert", plan.inserts.isEmpty())
        }
    }

    @Test
    fun sdkPlannerStillReauthorsAPlainRowWithTheSameShape() {
        // Guard against over-broad carve-out: the same row WITHOUT swap decoration is
        // still corrected, so the fix did not disable the plain-send re-stamp.
        val plain = plainSendingRow(iconType = TxDisplayCacheEntry.ICON_RECEIVED)
        assertTrue(syncAgainst(plain, contextCode = 2).updates.isNotEmpty())
    }

    @Test
    fun instantLockRefreshLeavesASwapRowAlone() {
        assertNull(planL1InstantLockRowUpdate(decoratedRow(SwapOrderStatus.PENDING), resolve))
        // …while a plain "Sending" row still flips to "Sent" on the lock.
        assertEquals(
            sentTitle,
            planL1InstantLockRowUpdate(plainSendingRow(), resolve)?.title
        )
    }

    // ── the whole story: mempool → in-block ───────────────────────────────

    @Test
    fun aSwapRowBornInTheMempoolEndsUpTitledAsAConversionAndIsNotPinnedToIt() {
        // 1. The SDK writer inserts the row for the freshly-broadcast drain: context 0,
        //    so a plain "Sending", with no idea a swap order exists.
        var row = plainSendingRow()
        assertEquals(sendingTitle, row.title)

        // 2. The swap order lands (PENDING). The reconciler decorates the row from
        //    swap_orders alone — no dashj transaction is available for this tx, which is
        //    exactly why the old wrapper-based path wrote nothing here.
        row = decorate(metadata(order(SwapOrderStatus.PENDING)), row).single()
        assertEquals("Conversion · DASH/RUNE", row.title)
        assertEquals(SwapOrderStatus.PENDING.name, row.swapStatus)

        // 3. The transaction confirms — the SDK record advances 0 → IN_BLOCK. The planner
        //    must not drag the row back to a plain send title.
        assertTrue(syncAgainst(row, contextCode = 2).updates.isEmpty())
        assertEquals("Conversion · DASH/RUNE", row.title)

        // 4. The tracker flips the order to COMPLETED. The row is NOT pinned to its stale
        //    "Conversion" rendering: the reconciler re-titles it "Converted".
        val completed = decorate(metadata(order(SwapOrderStatus.COMPLETED)), row).single()
        assertEquals("Converted · DASH/RUNE", completed.title)
        assertEquals(SwapOrderStatus.COMPLETED.name, completed.swapStatus)
        assertEquals(TxDisplayCacheEntry.ICON_CONVERT, completed.iconType)

        // 5. And it settles: another pass of either writer changes nothing.
        assertTrue(decorate(metadata(order(SwapOrderStatus.COMPLETED)), completed).isEmpty())
        assertTrue(syncAgainst(completed, contextCode = 3).updates.isEmpty())
    }
}
