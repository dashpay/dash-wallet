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
import de.schildbach.wallet.service.platform.sdk.planL1InstantLockRowUpdate
import de.schildbach.wallet_test.R
import org.dash.wallet.common.data.PresentableTxMetadata
import org.dash.wallet.common.data.TxId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM regression tests for the LATE metadata decoration of a home-screen row
 * whose transaction the held dashj wallet cannot render.
 *
 * The bug these pin down (verified on-device from field logs): on a restored
 * post-cutover device the display rows are planned by
 * [de.schildbach.wallet.service.platform.sdk.CutoverUiDataService] during the L1 scan,
 * and platform tx metadata syncs in HOURS later as "SDK fallback rows"
 * ([WalletTransactionMetadataProvider]) for txs with no dashj wallet transaction. The
 * metadata-change observer's only re-decoration path rebuilt rows from a dashj
 * [org.dash.wallet.common.transactions.TransactionWrapper], which never resolves for
 * those rows — so "platform metadata merged for <txid>: memo=N chars" proved DB arrival
 * while the render-layer "row … bound with metadata" log fired ZERO times all session.
 * [planMetadataRowDecorations] closes the gap by decorating the display-cache row
 * directly, keyed by txid, no wrapper involved.
 */
class MetadataRowDecorationTest {

    private val txHex = "6b1f0d5a11c8ab90b4bfcf1704a9e2d8c07331f5a2f9db6ce85c990c33ca8f01"
    private val txId = TxId.wrap(txHex)
    private val iconId = TxId.wrap(ByteArray(32) { 0x2a })
    private val now = 1_787_000_000_000L

    /** The row the SDK planner inserted during the restore's L1 scan: no metadata yet. */
    private fun sdkPlannedRow(
        comment: String = "",
        service: String? = null,
        customIconId: String? = null
    ) = TxDisplayCacheEntry(
        rowId = txHex,
        title = "Sent",
        valueSatoshis = -96_450_000L,
        iconType = TxDisplayCacheEntry.ICON_SENT,
        iconBgType = TxDisplayCacheEntry.BG_SENT,
        statusText = "",
        comment = comment,
        transactionAmount = 1,
        time = now - 3_600_000,
        hasErrors = false,
        service = service,
        exchangeRateFiatCode = "USD",
        exchangeRateFiatValue = 42L,
        contactUsername = null,
        contactDisplayName = null,
        contactAvatarUrl = null,
        contactUserId = null,
        filterFlags = TxDisplayCacheEntry.FLAG_SENT,
        customIconId = customIconId
    )

    private fun metadata(
        memo: String = "",
        service: String? = null,
        customIconId: TxId? = null
    ) = PresentableTxMetadata(txId = txId, memo = memo, service = service, customIconId = customIconId)

    @Test
    fun lateMetadata_decoratesRowWithoutWrapper() {
        val existing = sdkPlannedRow()
        val decorated = planMetadataRowDecorations(
            mapOf(txHex to metadata(memo = "rent, august", service = "CrowdNode", customIconId = iconId)),
            mapOf(txHex to existing)
        ).single()

        assertEquals("rent, august", decorated.comment)
        assertEquals("CrowdNode", decorated.service)
        assertEquals(iconId.toString(), decorated.customIconId)
        // Decoration only: the SDK-planned shape survives untouched.
        assertEquals(existing.title, decorated.title)
        assertEquals(existing.valueSatoshis, decorated.valueSatoshis)
        assertEquals(existing.iconType, decorated.iconType)
        assertEquals(existing.iconBgType, decorated.iconBgType)
        assertEquals(existing.statusText, decorated.statusText)
        assertEquals(existing.time, decorated.time)
        assertEquals(existing.filterFlags, decorated.filterFlags)
        assertEquals(existing.exchangeRateFiatCode, decorated.exchangeRateFiatCode)
        assertNull(decorated.contactUserId)
    }

    @Test
    fun lateMetadata_missingRowPlansNothing() {
        val plan = planMetadataRowDecorations(
            mapOf(txHex to metadata(memo = "rent, august")),
            emptyMap()
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun lateMetadata_settledRowPlansNothing() {
        val settled = sdkPlannedRow(
            comment = "rent, august",
            service = "CrowdNode",
            customIconId = iconId.toString()
        )
        val plan = planMetadataRowDecorations(
            mapOf(txHex to metadata(memo = "rent, august", service = "CrowdNode", customIconId = iconId)),
            mapOf(txHex to settled)
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun lateMetadata_neverUnclassifiesService() {
        val existing = sdkPlannedRow(service = "CrowdNode", customIconId = iconId.toString())
        val decorated = planMetadataRowDecorations(
            mapOf(txHex to metadata(memo = "monthly deposit")),
            mapOf(txHex to existing)
        ).single()

        assertEquals("monthly deposit", decorated.comment)
        assertEquals("CrowdNode", decorated.service)
        assertEquals(iconId.toString(), decorated.customIconId)
    }

    @Test
    fun metadataRemoval_clearsMemoKeepsClassification() {
        val existing = sdkPlannedRow(comment = "rent, august", service = "CrowdNode")
        val decorated = planMetadataRowDecorations(
            mapOf(txHex to null),
            mapOf(txHex to existing)
        ).single()

        assertEquals("", decorated.comment)
        assertEquals("CrowdNode", decorated.service)
    }

    @Test
    fun lateMetadata_decoratedPendingRowStillSettlesOnLock() {
        // CodeRabbit #1545 regression, end to end: metadata classifies the row
        // while it is still "Sending"/"Processing", and the IS lock lands AFTER —
        // the service tag must not trip the SDK planner's never-touch guard, or
        // the row sticks pending forever.
        val resolve: (Int) -> String = { id -> "str:$id" }
        val pending = sdkPlannedRow().copy(
            title = resolve(R.string.transaction_row_status_sending),
            statusText = resolve(R.string.transaction_row_status_processing)
        )
        val decorated = planMetadataRowDecorations(
            mapOf(txHex to metadata(memo = "top-up", service = "CrowdNode")),
            mapOf(txHex to pending)
        ).single()
        assertEquals("CrowdNode", decorated.service)

        val settled = planL1InstantLockRowUpdate(decorated, resolve)!!
        assertEquals(resolve(R.string.transaction_row_status_sent), settled.title)
        assertEquals("", settled.statusText)
        // The decoration itself survives the settle.
        assertEquals("CrowdNode", settled.service)
        assertEquals("top-up", settled.comment)
    }

    // ── Gift-card decoration of a row the dashj wallet cannot render ──────

    private val giftCardTitle: (String?) -> String = { merchant ->
        if (merchant.isNullOrEmpty()) "Gift card" else "Gift card · $merchant"
    }

    private fun giftCardMetadata(merchant: String? = "CVS", service: String? = "ctxspend") =
        metadata(memo = "", service = service).also { it.title = merchant }

    @Test
    fun giftCard_sdkPlannedSendBecomesAGiftCardRow() {
        // Restored post-cutover wallet: the SDK inserted the purchase as a plain send.
        val existing = sdkPlannedRow()
        val decorated = planGiftCardRowDecorations(
            listOf(giftCardMetadata()), mapOf(txHex to existing), giftCardTitle
        ).single()

        val expected = existing.copy(
            title = "Gift card · CVS",
            iconType = TxDisplayCacheEntry.ICON_GIFT_CARD,
            iconBgType = TxDisplayCacheEntry.BG_ORANGE,
            service = "ctxspend",
            filterFlags = TxDisplayCacheEntry.FLAG_SENT or TxDisplayCacheEntry.FLAG_GIFT_CARD
        )
        // Value, status, time, rate and memo are the SDK row's own.
        assertEquals(expected, decorated)
        // Settled: a second pass writes nothing.
        assertTrue(
            planGiftCardRowDecorations(listOf(giftCardMetadata()), mapOf(txHex to decorated), giftCardTitle).isEmpty()
        )
    }

    @Test
    fun giftCard_titleFollowsTheMerchantNameOnceKnown() {
        val bare = planGiftCardRowDecorations(
            listOf(giftCardMetadata(merchant = null)), mapOf(txHex to sdkPlannedRow()), giftCardTitle
        ).single()
        assertEquals("Gift card", bare.title)

        val named = planGiftCardRowDecorations(
            listOf(giftCardMetadata()), mapOf(txHex to bare), giftCardTitle
        ).single()
        assertEquals(bare.copy(title = "Gift card · CVS"), named)
    }

    @Test
    fun giftCard_piggyCardsIsAGiftCardToo() {
        val decorated = planGiftCardRowDecorations(
            listOf(giftCardMetadata(service = "piggycards")), mapOf(txHex to sdkPlannedRow()), giftCardTitle
        ).single()
        assertEquals(TxDisplayCacheEntry.ICON_GIFT_CARD, decorated.iconType)
        assertEquals("piggycards", decorated.service)
    }

    @Test
    fun giftCard_otherRowsAreLeftAlone() {
        val giftCard = listOf(giftCardMetadata())
        // No cached row yet: whichever writer inserts it signals the bus, which re-runs this.
        assertTrue(planGiftCardRowDecorations(giftCard, emptyMap(), giftCardTitle).isEmpty())
        // An errored row keeps its error shape, a swap row its swap decoration.
        val errored = sdkPlannedRow().copy(hasErrors = true, iconType = TxDisplayCacheEntry.ICON_ERROR)
        assertTrue(planGiftCardRowDecorations(giftCard, mapOf(txHex to errored), giftCardTitle).isEmpty())
        val swap = sdkPlannedRow().copy(swapStatus = "PENDING")
        assertTrue(planGiftCardRowDecorations(giftCard, mapOf(txHex to swap), giftCardTitle).isEmpty())
        // Metadata of another service is not a gift card.
        val crowdNode = listOf(giftCardMetadata(service = "CrowdNode"))
        assertTrue(planGiftCardRowDecorations(crowdNode, mapOf(txHex to sdkPlannedRow()), giftCardTitle).isEmpty())
    }

    @Test
    fun giftCard_decoratedRowThenFollowsTheStoreRecordAsAGiftCardRow() {
        // The flag this planner sets is what puts the row on the SDK planner's
        // gift-card path: the lock event leaves it alone, title included.
        val resolve: (Int) -> String = { id -> "str:$id" }
        val pending = sdkPlannedRow().copy(title = resolve(R.string.transaction_row_status_sending))
        val decorated = planGiftCardRowDecorations(
            listOf(giftCardMetadata()), mapOf(txHex to pending), giftCardTitle
        ).single()
        assertEquals("Gift card · CVS", decorated.title)
        assertNull(planL1InstantLockRowUpdate(decorated, resolve))
    }
}
