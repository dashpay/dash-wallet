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

import de.schildbach.wallet.database.dao.TxDisplayCacheDao
import de.schildbach.wallet.database.dao.TxGroupCacheDao
import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import de.schildbach.wallet.database.entity.TxGroupCacheEntry
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet_test.R
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Coin
import org.dash.wallet.common.data.WalletUIConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the Phase 5d post-cutover UI data source:
 * - the pure SDK-row → neutral-record → display-row mappings (including
 *   the islock→status rules that fix the stuck "Sending" send and the
 *   processing-forever receive),
 * - the pure display-cache sync planner (inserts for SDK-only txs, the
 *   surgical status refresh, the notification decision), and
 * - the data-source switch: pre-cutover = dashj byte-identical
 *   (SDK never touched), post-cutover = SDK-fed balance/rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CutoverUiDataServiceTest {

    private val resolve: (Int) -> String = { id -> "str:$id" }
    private val now = 1_753_000_000_000L

    private fun wireTxid(firstByte: Int): ByteArray =
        ByteArray(32) { if (it == 0) firstByte.toByte() else 0 }

    private fun displayHex(firstByte: Int): String =
        wireTxid(firstByte).reversedArray().joinToString("") { "%02x".format(it) }

    private fun record(
        firstByte: Int = 1,
        net: Long = 500_000L,
        fee: Long? = null,
        context: Int = 1,
        direction: Int = 0,
        firstSeenSec: Long = now / 1000
    ) = l1TxUiRecord(wireTxid(firstByte), net, fee, context, direction, firstSeenSec, 0)

    private fun cacheEntry(
        rowId: String,
        title: String,
        statusText: String = "",
        service: String? = null,
        hasErrors: Boolean = false,
        filterFlags: Int = TxDisplayCacheEntry.FLAG_SENT
    ) = TxDisplayCacheEntry(
        rowId = rowId,
        title = title,
        valueSatoshis = -1_000_000L,
        iconType = TxDisplayCacheEntry.ICON_SENT,
        iconBgType = TxDisplayCacheEntry.BG_SENT,
        statusText = statusText,
        comment = "memo",
        transactionAmount = 1,
        time = now - 60_000,
        hasErrors = hasErrors,
        service = service,
        exchangeRateFiatCode = "USD",
        exchangeRateFiatValue = 42L,
        contactUsername = null,
        contactDisplayName = null,
        contactAvatarUrl = null,
        contactUserId = null,
        filterFlags = filterFlags
    )

    // ── l1TxUiRecord mapping ──────────────────────────────────────────

    @Test
    fun record_txidIsDisplayOrderHex() {
        val r = record(firstByte = 0xab)
        // Wire bytes ab 00 … 00 reverse to 00 … 00 ab.
        assertTrue(r.txidHex.endsWith("ab"))
        assertEquals("00".repeat(31) + "ab", r.txidHex)
    }

    @Test
    fun record_statusCodesMap() {
        assertEquals(L1TxUiStatus.PENDING, record(context = 0).status)
        assertEquals(L1TxUiStatus.INSTANT_LOCKED, record(context = 1).status)
        assertEquals(L1TxUiStatus.IN_BLOCK, record(context = 2).status)
        assertEquals(L1TxUiStatus.CHAINLOCKED, record(context = 3).status)
        // Unknown codes never claim a lock.
        assertEquals(L1TxUiStatus.PENDING, record(context = 99).status)
    }

    @Test
    fun record_directionFallsBackToAmountSign() {
        assertEquals(L1TxUiDirection.OUTGOING, record(direction = 1).direction)
        assertEquals(L1TxUiDirection.INTERNAL, record(direction = 2).direction)
        assertEquals(L1TxUiDirection.INCOMING, record(direction = 99, net = 5).direction)
        assertEquals(L1TxUiDirection.OUTGOING, record(direction = 99, net = -5).direction)
    }

    @Test
    fun record_timestampFallsBackToBlockTimestamp() {
        assertEquals(now, record(firstSeenSec = now / 1000).timestampMs)
        val r = l1TxUiRecord(wireTxid(1), 1, null, 1, 0, 0, 1_700_000_000)
        assertEquals(1_700_000_000_000L, r.timestampMs)
        assertEquals(0L, l1TxUiRecord(wireTxid(1), 1, null, 1, 0, 0, 0).timestampMs)
    }

    // ── planL1TxRow ───────────────────────────────────────────────────

    @Test
    fun rowPlan_outgoingPendingIsSending() {
        val plan = planL1TxRow(record(net = -1_000_146, fee = 146, context = 0, direction = 1))
        assertEquals(R.string.transaction_row_status_sending, plan.titleRes)
        assertEquals(-1, plan.statusRes)
        // The list value excludes the fee (dashj's removeFee rule).
        assertEquals(-1_000_000L, plan.valueDuffs)
        assertEquals(TxDisplayCacheEntry.ICON_SENT, plan.iconType)
        assertEquals(TxDisplayCacheEntry.FLAG_SENT, plan.filterFlags)
        assertFalse(plan.isIncoming)
    }

    @Test
    fun rowPlan_outgoingLockedIsSent() {
        val locked = planL1TxRow(record(net = -1_000_146, fee = 146, context = 1, direction = 1))
        assertEquals(R.string.transaction_row_status_sent, locked.titleRes)
        val mined = planL1TxRow(record(net = -1_000_146, fee = 146, context = 2, direction = 1))
        assertEquals(R.string.transaction_row_status_sent, mined.titleRes)
    }

    @Test
    fun rowPlan_incomingStatuses() {
        val pending = planL1TxRow(record(net = 750_000, context = 0, direction = 0))
        assertEquals(R.string.transaction_row_status_received, pending.titleRes)
        assertEquals(R.string.transaction_row_status_processing, pending.statusRes)
        assertEquals(750_000L, pending.valueDuffs)
        assertEquals(TxDisplayCacheEntry.FLAG_RECEIVED, pending.filterFlags)
        assertTrue(pending.isIncoming)

        val locked = planL1TxRow(record(net = 750_000, context = 1, direction = 0))
        assertEquals(-1, locked.statusRes)
    }

    @Test
    fun rowPlan_internal() {
        val plan = planL1TxRow(record(net = -200, context = 1, direction = 2))
        assertEquals(R.string.transaction_row_status_sent_internally, plan.titleRes)
        assertEquals(TxDisplayCacheEntry.ICON_INTERNAL, plan.iconType)
        assertEquals(0, plan.filterFlags)
    }

    @Test
    fun rowPlan_shieldAndUnshieldCarryTransferIcon() {
        // Shield: an INTERNAL asset lock classified SHIELD — "Shielded" title,
        // the double-arrows transfer icon, still in the Sent filter bucket.
        val shield = planL1TxRow(
            record(net = -300_000, context = 1, direction = 2),
            AssetLockKind.SHIELD
        )
        assertEquals(R.string.transaction_row_shielded, shield.titleRes)
        assertEquals(TxDisplayCacheEntry.ICON_INTERNAL, shield.iconType)
        assertEquals(TxDisplayCacheEntry.BG_SENT, shield.iconBgType)
        assertEquals(TxDisplayCacheEntry.FLAG_SENT, shield.filterFlags)
        assertFalse(shield.isIncoming)

        // Unshield: the INCOMING AssetUnlock — "Unshielded" title, the same
        // transfer treatment, positive value in the Received filter bucket,
        // and NON-incoming so it can never fire a coins-received notification.
        val unshield = planL1TxRow(
            record(net = 300_000, context = 1, direction = 0),
            AssetLockKind.UNSHIELD
        )
        assertEquals(R.string.transaction_row_unshielded, unshield.titleRes)
        assertEquals(TxDisplayCacheEntry.ICON_INTERNAL, unshield.iconType)
        assertEquals(TxDisplayCacheEntry.BG_SENT, unshield.iconBgType)
        assertEquals(TxDisplayCacheEntry.FLAG_RECEIVED, unshield.filterFlags)
        assertEquals(300_000L, unshield.valueDuffs)
        assertFalse(unshield.isIncoming)
    }

    @Test
    fun rowPlan_feeKindsKeepSentArrow() {
        val upgrade = planL1TxRow(
            record(net = -500_000, context = 1, direction = 2),
            AssetLockKind.UPGRADE
        )
        assertEquals(R.string.dashpay_upgrade_fee, upgrade.titleRes)
        assertEquals(TxDisplayCacheEntry.ICON_SENT, upgrade.iconType)
        assertEquals(TxDisplayCacheEntry.BG_SENT, upgrade.iconBgType)
        assertEquals(TxDisplayCacheEntry.FLAG_SENT, upgrade.filterFlags)
    }

    // ── planL1DisplaySync ─────────────────────────────────────────────

    @Test
    fun syncPlan_insertsAndNotifiesNewRecentIncoming() {
        val r = record(firstByte = 7, net = 1_000_000, context = 1, direction = 0)
        val plan = planL1DisplaySync(listOf(r), emptyMap(), emptySet(), resolve, now)

        assertEquals(1, plan.inserts.size)
        val row = plan.inserts.first()
        assertEquals(displayHex(7), row.rowId)
        assertEquals(resolve(R.string.transaction_row_status_received), row.title)
        assertEquals(1_000_000L, row.valueSatoshis)
        assertEquals("", row.statusText)
        assertEquals(TxDisplayCacheEntry.ICON_RECEIVED, row.iconType)
        assertEquals(1, row.transactionAmount)
        assertNull(row.service)
        assertEquals(listOf(displayHex(7) to 1_000_000L), plan.notifyIncoming)
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun syncPlan_oldIncomingInsertedButNotNotified() {
        val old = record(firstByte = 7, net = 1_000_000, firstSeenSec = (now - 3 * 24 * 60 * 60 * 1000L) / 1000)
        val plan = planL1DisplaySync(listOf(old), emptyMap(), emptySet(), resolve, now)
        assertEquals(1, plan.inserts.size)
        assertTrue(plan.notifyIncoming.isEmpty())
    }

    @Test
    fun syncPlan_groupedTxIsNeverTouched() {
        val r = record(firstByte = 7)
        val plan = planL1DisplaySync(listOf(r), emptyMap(), setOf(displayHex(7)), resolve, now)
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.notifyIncoming.isEmpty())
    }

    @Test
    fun syncPlan_stuckSendingFlipsToSentOnLock() {
        val r = record(firstByte = 9, net = -1_000_146, fee = 146, context = 1, direction = 1)
        val existing = cacheEntry(
            rowId = displayHex(9),
            title = resolve(R.string.transaction_row_status_sending)
        )
        val plan = planL1DisplaySync(
            listOf(r), mapOf(existing.rowId to existing), emptySet(), resolve, now
        )
        assertTrue(plan.inserts.isEmpty())
        assertEquals(1, plan.updates.size)
        val updated = plan.updates.first()
        assertEquals(resolve(R.string.transaction_row_status_sent), updated.title)
        // Everything else on the dashj-era row is preserved.
        assertEquals(existing.valueSatoshis, updated.valueSatoshis)
        assertEquals(existing.time, updated.time)
        assertEquals(existing.comment, updated.comment)
        assertEquals(existing.exchangeRateFiatCode, updated.exchangeRateFiatCode)
    }

    @Test
    fun syncPlan_pendingOutgoingRowUntouched() {
        val r = record(firstByte = 9, net = -1_000_000, context = 0, direction = 1)
        val existing = cacheEntry(
            rowId = displayHex(9),
            title = resolve(R.string.transaction_row_status_sending)
        )
        val plan = planL1DisplaySync(
            listOf(r), mapOf(existing.rowId to existing), emptySet(), resolve, now
        )
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun syncPlan_processingClearedOnLockAndOnPlainBlock() {
        // Consistent with the SDK record below (a +1000 receive) so the test
        // isolates the status rule: only statusText may change.
        val existing = cacheEntry(
            rowId = displayHex(5),
            title = resolve(R.string.transaction_row_status_received),
            statusText = resolve(R.string.transaction_row_status_processing),
            filterFlags = TxDisplayCacheEntry.FLAG_RECEIVED
        ).copy(
            valueSatoshis = 1000L,
            iconType = TxDisplayCacheEntry.ICON_RECEIVED,
            iconBgType = TxDisplayCacheEntry.BG_RECEIVED
        )
        val lockedPlan = planL1DisplaySync(
            listOf(record(firstByte = 5, net = 1000, context = 1, direction = 0)),
            mapOf(existing.rowId to existing), emptySet(), resolve, now
        )
        assertEquals(1, lockedPlan.updates.size)
        assertEquals("", lockedPlan.updates.first().statusText)

        // Plain IN_BLOCK (a dropped islock event): a block-confirmed tx is
        // never "Processing" under dashj display semantics either
        // (TxResourceMapper only maps PENDING to "Processing") — cleared.
        val minedPlan = planL1DisplaySync(
            listOf(record(firstByte = 5, net = 1000, context = 2, direction = 0)),
            mapOf(existing.rowId to existing), emptySet(), resolve, now
        )
        assertEquals(1, minedPlan.updates.size)
        assertEquals("", minedPlan.updates.first().statusText)
    }

    @Test
    fun syncPlan_confirmingClearedOnLockButNotOnPlainBlock() {
        val existing = cacheEntry(
            rowId = displayHex(5),
            title = resolve(R.string.transaction_row_status_received),
            statusText = resolve(R.string.transaction_row_status_confirming),
            filterFlags = TxDisplayCacheEntry.FLAG_RECEIVED
        ).copy(
            valueSatoshis = 1000L,
            iconType = TxDisplayCacheEntry.ICON_RECEIVED,
            iconBgType = TxDisplayCacheEntry.BG_RECEIVED
        )
        val lockedPlan = planL1DisplaySync(
            listOf(record(firstByte = 5, net = 1000, context = 3, direction = 0)),
            mapOf(existing.rowId to existing), emptySet(), resolve, now
        )
        assertEquals(1, lockedPlan.updates.size)
        assertEquals("", lockedPlan.updates.first().statusText)

        // Plain IN_BLOCK keeps "Confirming": dashj still shows it for a
        // building, unlocked tx under 6 confirmations.
        val minedPlan = planL1DisplaySync(
            listOf(record(firstByte = 5, net = 1000, context = 2, direction = 0)),
            mapOf(existing.rowId to existing), emptySet(), resolve, now
        )
        assertTrue(minedPlan.updates.isEmpty())
    }

    @Test
    fun syncPlan_richRowsAreNeverTouched() {
        val r = record(firstByte = 9, net = -1_000_000, context = 1, direction = 1)
        val sendingTitle = resolve(R.string.transaction_row_status_sending)
        val giftCard = cacheEntry(
            rowId = displayHex(9), title = sendingTitle,
            filterFlags = TxDisplayCacheEntry.FLAG_GIFT_CARD or TxDisplayCacheEntry.FLAG_SENT
        )
        val withService = cacheEntry(rowId = displayHex(9), title = sendingTitle, service = "CrowdNode")
        val errored = cacheEntry(rowId = displayHex(9), title = sendingTitle, hasErrors = true)

        for (entry in listOf(giftCard, withService, errored)) {
            val plan = planL1DisplaySync(
                listOf(r), mapOf(entry.rowId to entry), emptySet(), resolve, now
            )
            assertTrue(plan.inserts.isEmpty())
            assertTrue(plan.updates.isEmpty())
        }
    }

    @Test
    fun syncPlan_cachedShieldRowRestampedToTransferIcon() {
        // A shield row cached under the old spec: "Shielded" title but the
        // SENT arrow — the kind re-stamp corrects icon/background/flags while
        // preserving value/time/memo/rate.
        val r = record(firstByte = 5, net = -300_000, context = 3, direction = 2)
        val existing = cacheEntry(
            rowId = displayHex(5),
            title = resolve(R.string.transaction_row_shielded)
        ) // helper defaults: ICON_SENT / BG_SENT / FLAG_SENT
        val plan = planL1DisplaySync(
            listOf(r), mapOf(existing.rowId to existing), emptySet(), resolve, now,
            kindByTxid = mapOf(displayHex(5) to AssetLockKind.SHIELD)
        )
        assertEquals(1, plan.updates.size)
        val row = plan.updates.first()
        assertEquals(resolve(R.string.transaction_row_shielded), row.title)
        assertEquals(TxDisplayCacheEntry.ICON_INTERNAL, row.iconType)
        assertEquals(TxDisplayCacheEntry.BG_SENT, row.iconBgType)
        assertEquals(TxDisplayCacheEntry.FLAG_SENT, row.filterFlags)
        assertEquals(existing.valueSatoshis, row.valueSatoshis)
        assertEquals(existing.time, row.time)
        assertEquals(existing.comment, row.comment)
        assertEquals(existing.exchangeRateFiatCode, row.exchangeRateFiatCode)
        assertTrue(plan.notifyIncoming.isEmpty())
    }

    @Test
    fun syncPlan_unshieldCachedAsReceivedIsRestampedAndNeverNotifies() {
        // An unshield row cached "Received" + green arrow before its UNSHIELD
        // classification resolved (the verified on-device S22 state) — the
        // kind re-stamp rewrites title/icon/background while keeping the
        // positive value and the Received filter bucket, and never notifies.
        val r = record(firstByte = 6, net = 300_000, context = 3, direction = 0)
        val existing = cacheEntry(
            rowId = displayHex(6),
            title = resolve(R.string.transaction_row_status_received),
            filterFlags = TxDisplayCacheEntry.FLAG_RECEIVED
        ).copy(
            iconType = TxDisplayCacheEntry.ICON_RECEIVED,
            iconBgType = TxDisplayCacheEntry.BG_RECEIVED,
            valueSatoshis = 300_000L
        )
        val plan = planL1DisplaySync(
            listOf(r), mapOf(existing.rowId to existing), emptySet(), resolve, now,
            kindByTxid = mapOf(displayHex(6) to AssetLockKind.UNSHIELD)
        )
        assertEquals(1, plan.updates.size)
        val row = plan.updates.first()
        assertEquals(resolve(R.string.transaction_row_unshielded), row.title)
        assertEquals(TxDisplayCacheEntry.ICON_INTERNAL, row.iconType)
        assertEquals(TxDisplayCacheEntry.BG_SENT, row.iconBgType)
        assertEquals(TxDisplayCacheEntry.FLAG_RECEIVED, row.filterFlags)
        assertEquals(300_000L, row.valueSatoshis)
        assertTrue(plan.notifyIncoming.isEmpty())

        // Idempotent: the corrected row produces no further writes.
        val second = planL1DisplaySync(
            listOf(r), mapOf(row.rowId to row), emptySet(), resolve, now,
            kindByTxid = mapOf(displayHex(6) to AssetLockKind.UNSHIELD)
        )
        assertTrue(second.inserts.isEmpty())
        assertTrue(second.updates.isEmpty())
        assertTrue(second.notifyIncoming.isEmpty())
    }

    @Test
    fun syncPlan_internalRowRelabeledOnceKindKnown() {
        // The pre-existing relabel race: an asset lock cached "Internal"
        // before its kind resolved is re-stamped to the Platform action.
        val r = record(firstByte = 8, net = -500_000, context = 3, direction = 2)
        val existing = cacheEntry(
            rowId = displayHex(8),
            title = resolve(R.string.transaction_row_status_sent_internally),
            filterFlags = 0
        ).copy(iconType = TxDisplayCacheEntry.ICON_INTERNAL)
        val plan = planL1DisplaySync(
            listOf(r), mapOf(existing.rowId to existing), emptySet(), resolve, now,
            kindByTxid = mapOf(displayHex(8) to AssetLockKind.UPGRADE)
        )
        assertEquals(1, plan.updates.size)
        val row = plan.updates.first()
        assertEquals(resolve(R.string.dashpay_upgrade_fee), row.title)
        assertEquals(TxDisplayCacheEntry.ICON_SENT, row.iconType)
        assertEquals(TxDisplayCacheEntry.FLAG_SENT, row.filterFlags)
    }

    @Test
    fun syncPlan_unshieldFreshInsertNeverNotifies() {
        // A brand-new unshield discovered within the notify window must insert
        // with the transfer treatment and WITHOUT a coins-received notification.
        val r = record(firstByte = 4, net = 250_000, context = 1, direction = 0)
        val plan = planL1DisplaySync(
            listOf(r), emptyMap(), emptySet(), resolve, now,
            kindByTxid = mapOf(displayHex(4) to AssetLockKind.UNSHIELD)
        )
        assertEquals(1, plan.inserts.size)
        val row = plan.inserts.first()
        assertEquals(resolve(R.string.transaction_row_unshielded), row.title)
        assertEquals(TxDisplayCacheEntry.ICON_INTERNAL, row.iconType)
        assertEquals(TxDisplayCacheEntry.BG_SENT, row.iconBgType)
        assertEquals(TxDisplayCacheEntry.FLAG_RECEIVED, row.filterFlags)
        assertEquals(250_000L, row.valueSatoshis)
        assertTrue(plan.notifyIncoming.isEmpty())
    }

    // ── The SDK-definitive re-stamp of PLAIN (non-contact) rows ───────

    /** The exact broken row observed on-device (S22, 11.10.42) for a confirmed max-send. */
    private fun dashjMisreadSendRow(rowId: String) = cacheEntry(
        rowId = rowId,
        // dashj values an SDK-authored send at net 0 (inputs unconnected), so
        // TransactionRowView renders a green RECEIVED icon titled "Sending" and
        // TxResourceMapper stamps the PENDING secondary status "Processing".
        title = resolve(R.string.transaction_row_status_sending),
        statusText = resolve(R.string.transaction_row_status_processing),
        filterFlags = TxDisplayCacheEntry.FLAG_RECEIVED
    ).copy(
        valueSatoshis = 0L,
        iconType = TxDisplayCacheEntry.ICON_RECEIVED,
        iconBgType = TxDisplayCacheEntry.BG_RECEIVED
    )

    @Test
    fun syncPlan_plainOutgoingRowIsRestampedFromTheSdkRecord() {
        // Confirmed −0.96450513 send, no contact, no asset-lock kind.
        val r = record(firstByte = 9, net = -96_450_513, context = 3, direction = 1)
        val existing = dashjMisreadSendRow(displayHex(9))
        val plan = planL1DisplaySync(
            listOf(r), mapOf(existing.rowId to existing), emptySet(), resolve, now
        )
        assertEquals(1, plan.updates.size)
        val row = plan.updates.first()
        assertEquals(resolve(R.string.transaction_row_status_sent), row.title)
        assertEquals(-96_450_513L, row.valueSatoshis)
        assertEquals(TxDisplayCacheEntry.ICON_SENT, row.iconType)
        assertEquals(TxDisplayCacheEntry.BG_SENT, row.iconBgType)
        assertEquals(TxDisplayCacheEntry.FLAG_SENT, row.filterFlags)
        assertEquals("", row.statusText)
        // Memo / rate / time / contact columns survive the correction.
        assertEquals(existing.comment, row.comment)
        assertEquals(existing.time, row.time)
        assertEquals(existing.exchangeRateFiatCode, row.exchangeRateFiatCode)
        assertNull(row.contactUserId)
        // The row is claimed for the dashj-writer preserve-guard.
        assertTrue(displayHex(9) in plan.sdkAuthoritative)

        // Idempotent: replanning the corrected row writes nothing.
        val second = planL1DisplaySync(
            listOf(r), mapOf(row.rowId to row), emptySet(), resolve, now
        )
        assertTrue(second.inserts.isEmpty())
        assertTrue(second.updates.isEmpty())
        assertTrue(displayHex(9) in second.sdkAuthoritative)
    }

    @Test
    fun syncPlan_plainSendWithWrongNonZeroValueIsCorrected() {
        // dashj's other misread class: a non-degenerate but WRONG amount (fee-only).
        // The value-0 repair cannot see this one — only the definitive-record re-stamp.
        val r = record(firstByte = 9, net = -40_000_000, context = 2, direction = 1)
        val existing = cacheEntry(
            rowId = displayHex(9),
            title = resolve(R.string.transaction_row_status_sent)
        ).copy(valueSatoshis = -260L)
        val plan = planL1DisplaySync(
            listOf(r), mapOf(existing.rowId to existing), emptySet(), resolve, now
        )
        assertEquals(1, plan.updates.size)
        assertEquals(-40_000_000L, plan.updates.first().valueSatoshis)
        assertEquals(TxDisplayCacheEntry.ICON_SENT, plan.updates.first().iconType)
    }

    @Test
    fun syncPlan_definitiveRestampSkipsEveryRicherRow() {
        // A definitive OUTGOING record whose amount disagrees with every cached row
        // below — none of them may be re-stamped by the plain path.
        val r = record(firstByte = 9, net = -40_000_000, context = 3, direction = 1)
        val richTitles = listOf(
            R.string.transaction_row_shielded,
            R.string.transaction_row_unshielded,
            R.string.transaction_row_invitation,
            R.string.dashpay_upgrade_fee,
            R.string.dashpay_topup_fee,
            R.string.transaction_row_status_sent_internally,
            R.string.transaction_row_status_coinjoin_mixing,
            R.string.transaction_row_status_coinjoin_mixing_fee,
            R.string.transaction_row_status_masternode_registration,
            R.string.transaction_row_status_mining_reward
        )
        for (titleRes in richTitles) {
            val existing = cacheEntry(rowId = displayHex(9), title = resolve(titleRes))
            val plan = planL1DisplaySync(
                listOf(r), mapOf(existing.rowId to existing), emptySet(), resolve, now
            )
            assertTrue("re-stamped ${resolve(titleRes)}", plan.updates.isEmpty())
        }
        // The service / gift-card / error / CoinJoin-flag carve-outs stay untouchable
        // AND are never claimed as SDK-authoritative.
        val sendingTitle = resolve(R.string.transaction_row_status_sending)
        val carved = listOf(
            cacheEntry(
                rowId = displayHex(9), title = sendingTitle,
                filterFlags = TxDisplayCacheEntry.FLAG_GIFT_CARD or TxDisplayCacheEntry.FLAG_SENT
            ),
            cacheEntry(rowId = displayHex(9), title = sendingTitle, service = "CrowdNode"),
            cacheEntry(rowId = displayHex(9), title = sendingTitle, hasErrors = true),
            cacheEntry(
                rowId = displayHex(9), title = sendingTitle,
                filterFlags = TxDisplayCacheEntry.FLAG_COINJOIN
            )
        )
        for (entry in carved) {
            val plan = planL1DisplaySync(
                listOf(r), mapOf(entry.rowId to entry), emptySet(), resolve, now
            )
            assertTrue(plan.updates.isEmpty())
            assertTrue(plan.sdkAuthoritative.isEmpty())
        }
    }

    @Test
    fun syncPlan_definitiveRestampNeverTouchesAContactRow() {
        // The SDK record's own net is the wrong +change for a friendship send, so a row
        // carrying a contact identity must never be re-shaped from it — even when this
        // pass could not re-resolve the contact (contactByTxid empty).
        val r = record(firstByte = 9, net = 3_830_000, context = 3, direction = 0)
        val existing = cacheEntry(
            rowId = displayHex(9),
            title = resolve(R.string.transaction_row_status_sent)
        ).copy(
            valueSatoshis = -100_000L,
            contactUsername = "alice",
            contactUserId = "id-alice"
        )
        val plan = planL1DisplaySync(
            listOf(r), mapOf(existing.rowId to existing), emptySet(), resolve, now
        )
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun syncPlan_definitiveRestampIgnoresNonDefinitiveRecords() {
        // A NON-degenerate cached send row (so the separate value-0 repair cannot
        // fire) that disagrees with each record below. None of these records is
        // "definitive" for a plain row, so its direction/value must survive.
        val existing = cacheEntry(
            rowId = displayHex(9),
            title = resolve(R.string.transaction_row_status_sent)
        ) // helper defaults: −1_000_000, ICON_SENT, FLAG_SENT
        val nonDefinitive = listOf(
            // Internal / CoinJoin self-moves are not plain sends.
            record(firstByte = 9, net = -5_000_000, context = 3, direction = 2),
            record(firstByte = 9, net = -5_000_000, context = 3, direction = 3),
            // A zero-net record (e.g. a 2-participant testnet mixing round claims nothing).
            record(firstByte = 9, net = 0, context = 3, direction = 1)
        )
        for (r in nonDefinitive) {
            val plan = planL1DisplaySync(
                listOf(r), mapOf(existing.rowId to existing), emptySet(), resolve, now
            )
            assertTrue("re-stamped from direction=${r.direction} net=${r.netAmountDuffs}", plan.updates.isEmpty())
        }
        // Same for a DEFINITIVE record arriving on the engine's per-account event feed:
        // one self-spend emits both an OUTGOING and an INCOMING event for the same txid,
        // so an event may never re-shape an existing row.
        val eventPlan = planL1DisplaySync(
            listOf(record(firstByte = 9, net = 900_000, context = 0, direction = 0)),
            mapOf(existing.rowId to existing), emptySet(), resolve, now,
            restampFromDefinitiveRecord = false
        )
        assertTrue(eventPlan.updates.isEmpty())
    }

    @Test
    fun groupCacheRow_onlyMultiTxWrappersCountAsGroupRows() {
        // THE root cause: a plain single-tx wrapper's groupId is the BASE58 txid while
        // the row/txId columns are hex, so the old `groupId != txId` test classified every
        // ordinary send/receive as a group row and excluded it from all SDK repair.
        val hexTxId = displayHex(9)
        val single = TxGroupCacheEntry(
            groupId = "kfQvADvv5vb5vSykXnjpAUpJjJwABFRyseY86QucMc9",
            txId = hexTxId,
            wrapperType = TxGroupCacheEntry.TYPE_SINGLE,
            groupDate = "2026-07-31",
            sortOrder = 0
        )
        assertFalse(single.isMultiTxGroupRow)
        assertTrue(single.copy(wrapperType = TxGroupCacheEntry.TYPE_COINJOIN).isMultiTxGroupRow)
        assertTrue(single.copy(wrapperType = TxGroupCacheEntry.TYPE_CROWDNODE).isMultiTxGroupRow)
    }

    @Test
    fun syncPlan_settledStateProducesEmptyPlan() {
        val r = record(firstByte = 9, net = -1_000_146, fee = 146, context = 1, direction = 1)
        val existing = cacheEntry(
            rowId = displayHex(9),
            title = resolve(R.string.transaction_row_status_sent)
        )
        val plan = planL1DisplaySync(
            listOf(r), mapOf(existing.rowId to existing), emptySet(), resolve, now
        )
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.notifyIncoming.isEmpty())
    }

    // ── The gate + data-source switch ─────────────────────────────────

    private class FakeSource(
        var boundWalletId: String? = "cd".repeat(32),
        val balanceDuffs: MutableStateFlow<Long> = MutableStateFlow(0L),
        val records: MutableStateFlow<List<L1TxUiRecord>> = MutableStateFlow(emptyList())
    ) : CutoverUiSource {
        var boundCalls = 0
        var balanceSubscriptions = 0
        var recordSubscriptions = 0

        override suspend fun boundWalletIdOrNull(): String? {
            boundCalls++
            return boundWalletId
        }

        override fun observeTotalDuffs(walletIdHex: String): Flow<Long> {
            balanceSubscriptions++
            return balanceDuffs
        }

        var currentBalanceReads = 0

        /** The SDK's live unspent-output count (null = read unavailable). */
        var utxoCount: Int? = 0

        override suspend fun currentSpendableUtxoCount(walletIdHex: String): Int? = utxoCount

        override suspend fun currentTotalDuffs(walletIdHex: String): Long =
            currentBalanceSplitDuffs(walletIdHex).total

        override suspend fun currentBalanceSplitDuffs(walletIdHex: String): SdkBalanceSplitDuffs {
            currentBalanceReads++
            return SdkBalanceSplitDuffs(confirmed = balanceDuffs.value, unconfirmed = 0L)
        }

        override fun observeWalletTxRecords(walletIdHex: String): Flow<List<L1TxUiRecord>> {
            recordSubscriptions++
            return records.map { it }
        }

        /** Txids that funded a CoinJoin-account TXO (historical-mixing classification probe). */
        var coinJoinFunded: Set<String> = emptySet()

        override suspend fun coinJoinFundedTxids(
            walletIdHex: String,
            txidHexes: Collection<String>
        ): Set<String> = coinJoinFunded.intersect(txidHexes.toSet())

        override fun observeSeamTxSnapshots(walletIdHex: String): Flow<SdkSeamTxSnapshot> =
            records.map { SdkSeamTxSnapshot(it, emptyMap(), emptySet(), emptyMap()) }
    }

    private fun configWithState(state: String?): DashPayConfig = mockk {
        every { observe(DashPayConfig.CUTOVER_STATE) } returns flowOf(state)
    }

    private fun buildService(
        source: FakeSource,
        dashPayConfig: DashPayConfig,
        scope: kotlinx.coroutines.CoroutineScope,
        displayDao: TxDisplayCacheDao = mockk(relaxed = true),
        groupDao: TxGroupCacheDao = mockk(relaxed = true),
        walletUIConfig: WalletUIConfig = mockk(relaxed = true),
        notify: (Long) -> Unit = {},
        txEvents: Flow<L1TxEvent> = kotlinx.coroutines.flow.emptyFlow(),
        l1Synced: Flow<Boolean> = flowOf(true)
    ) = CutoverUiDataService(
        source = source,
        dashPayConfig = dashPayConfig,
        scope = scope,
        txDisplayCacheDao = displayDao,
        txGroupCacheDao = groupDao,
        walletUIConfig = walletUIConfig,
        resolveString = resolve,
        notifyCoinsReceived = notify,
        txEvents = txEvents,
        l1Synced = l1Synced,
        nowMs = { now }
    )

    @Test
    fun gate_activeOnlyWhenCutoverCommitted() = runBlocking {
        for ((stored, expected) in listOf(
            null to false,
            "DUAL_RUNNING" to false,
            "READY_OBSERVED" to false,
            "garbage" to false,
            "CUT_OVER" to true,
            "SETTLED" to true
        )) {
            val service = buildService(FakeSource(), configWithState(stored), this)
            assertEquals("stored=$stored", expected, service.cutoverUiActive().first())
        }
    }

    @Test
    fun preCutover_dashjPassesThroughAndSdkUntouched() = runTest {
        val source = FakeSource()
        val service = buildService(source, configWithState("DUAL_RUNNING"), backgroundScope)
        service.start()
        runCurrent()

        assertNull(service.sdkBalanceOrNull())
        assertEquals(0, source.boundCalls)
        assertEquals(0, source.balanceSubscriptions)
        assertEquals(0, source.recordSubscriptions)

        // The overlay is the dashj feed, unchanged.
        val dashj = Coin.valueOf(4242)
        assertEquals(dashj, service.overlayTotalBalance(flowOf(dashj)).first())
    }

    @Test
    fun postCutover_balanceServedFromSdk() = runTest {
        val source = FakeSource(balanceDuffs = MutableStateFlow(123_456L))
        val walletUIConfig = mockk<WalletUIConfig>(relaxed = true)
        val service = buildService(
            source, configWithState("CUT_OVER"), backgroundScope, walletUIConfig = walletUIConfig
        )
        service.start()
        runCurrent()

        assertEquals(Coin.valueOf(123_456), service.sdkBalanceOrNull())
        // The overlay prefers the SDK value over dashj's frozen one.
        assertEquals(
            Coin.valueOf(123_456),
            service.overlayTotalBalance(flowOf(Coin.valueOf(999))).first()
        )
        coVerify { walletUIConfig.set(WalletUIConfig.LAST_TOTAL_BALANCE, 123_456L) }

        // Live update: a new SDK balance lands without any dashj involvement.
        source.balanceDuffs.value = 200_000L
        runCurrent()
        assertEquals(Coin.valueOf(200_000), service.sdkBalanceOrNull())
    }

    @Test
    fun postCutover_midScan_holdsLastKnownBalanceAndDoesNotPersist() = runTest {
        // The SDK's from-scratch filter scan has only found part of the wallet
        // so far; the last launch knew the real figure.
        val source = FakeSource(balanceDuffs = MutableStateFlow(123_456L))
        val walletUIConfig = mockk<WalletUIConfig>(relaxed = true)
        coEvery { walletUIConfig.get(WalletUIConfig.LAST_TOTAL_BALANCE) } returns 900_000L

        val service = buildService(
            source, configWithState("CUT_OVER"), backgroundScope,
            walletUIConfig = walletUIConfig, l1Synced = flowOf(false)
        )
        service.start()
        runCurrent()

        // The header shows the LAST KNOWN figure, not the climbing partial…
        assertEquals(
            Coin.valueOf(900_000),
            service.overlayTotalBalance(flowOf(Coin.valueOf(999))).first()
        )
        // …while the live SDK figure itself is still tracked underneath.
        assertEquals(Coin.valueOf(123_456), service.sdkBalanceOrNull())
        // And the partial is NEVER written back over the seed (the
        // compounding bug: it would poison the next launch's last-known).
        coVerify(exactly = 0) { walletUIConfig.set(WalletUIConfig.LAST_TOTAL_BALANCE, any<Long>()) }
    }

    @Test
    fun postCutover_midScan_withNoLastKnownBalance_showsLiveFigure() = runTest {
        // First ever run: there is nothing to hold, so the live figure wins.
        val source = FakeSource(balanceDuffs = MutableStateFlow(123_456L))
        val walletUIConfig = mockk<WalletUIConfig>(relaxed = true)
        coEvery { walletUIConfig.get(WalletUIConfig.LAST_TOTAL_BALANCE) } returns null

        val service = buildService(
            source, configWithState("CUT_OVER"), backgroundScope,
            walletUIConfig = walletUIConfig, l1Synced = flowOf(false)
        )
        service.start()
        runCurrent()

        assertEquals(
            Coin.valueOf(123_456),
            service.overlayTotalBalance(flowOf(Coin.valueOf(999))).first()
        )
    }

    // ── Spendable-UTXO-count overlay (shielded max-fee reserve) ───────

    @Test
    fun preCutover_spendableUtxoCountKeepsTheDashjValue() = runTest {
        val source = FakeSource().apply { utxoCount = 11 }
        val service = buildService(source, configWithState("DUAL_RUNNING"), backgroundScope)
        service.start()
        runCurrent()

        assertNull(
            "pre-cutover the overlay must not engage at all",
            service.sdkSpendableUtxoCountOrNull()
        )
    }

    @Test
    fun postCutover_spendableUtxoCountServedFromSdk() = runTest {
        // FIX-pin: this count was never overlaid, so post-cutover it reported
        // the HELD dashj wallet's frozen UTXO set — and the shielded max-fee
        // reserve sizes itself at ~148 bytes per input from it.
        val source = FakeSource(balanceDuffs = MutableStateFlow(123_456L)).apply { utxoCount = 11 }
        val service = buildService(source, configWithState("CUT_OVER"), backgroundScope)
        service.start()
        runCurrent()

        assertEquals(11, service.sdkSpendableUtxoCountOrNull())
    }

    @Test
    fun postCutover_midScan_spendableUtxoCountFallsBackToDashj() = runTest {
        // A mid-scan partial UNDER-counts, and under-reserving is the failing
        // direction — so unlike the balance this does not hold a last-known
        // value, it simply does not engage until the scan has caught up.
        val source = FakeSource(balanceDuffs = MutableStateFlow(123_456L)).apply { utxoCount = 3 }
        val service = buildService(
            source, configWithState("CUT_OVER"), backgroundScope, l1Synced = flowOf(false)
        )
        service.start()
        runCurrent()

        assertNull(service.sdkSpendableUtxoCountOrNull())
        // …while the live count itself is still tracked underneath.
        assertEquals(3, service.sdkSpendableUtxoCount.value)
    }

    @Test
    fun postCutover_spendableUtxoCountUnavailableFallsBackToDashj() = runTest {
        val source = FakeSource(balanceDuffs = MutableStateFlow(123_456L)).apply { utxoCount = null }
        val service = buildService(source, configWithState("CUT_OVER"), backgroundScope)
        service.start()
        runCurrent()

        assertNull(service.sdkSpendableUtxoCountOrNull())
    }

    @Test
    fun postCutover_sdkOnlyReceiveInsertedAndNotified() = runTest {
        val incoming = record(firstByte = 7, net = 1_000_000, context = 1, direction = 0)
        val source = FakeSource(records = MutableStateFlow(listOf(incoming)))
        val displayDao = mockk<TxDisplayCacheDao>(relaxed = true)
        coEvery { displayDao.getEntriesByIds(any()) } returns emptyList()
        val groupDao = mockk<TxGroupCacheDao>(relaxed = true)
        coEvery { groupDao.getGroupsForTxIds(any()) } returns emptyList<TxGroupCacheEntry>()
        val notified = mutableListOf<Long>()

        val service = buildService(
            source, configWithState("CUT_OVER"), backgroundScope,
            displayDao = displayDao, groupDao = groupDao, notify = { notified += it }
        )
        service.start()
        runCurrent()

        val inserted = slot<List<TxDisplayCacheEntry>>()
        coVerify { displayDao.insertAll(capture(inserted)) }
        assertEquals(1, inserted.captured.size)
        assertEquals(displayHex(7), inserted.captured.first().rowId)
        assertEquals(resolve(R.string.transaction_row_status_received), inserted.captured.first().title)
        assertEquals(listOf(1_000_000L), notified)
    }

    @Test
    fun postCutover_knownRowsProduceNoWrites() = runTest {
        val sent = record(firstByte = 9, net = -1_000_146, fee = 146, context = 1, direction = 1)
        val existing = cacheEntry(
            rowId = displayHex(9),
            title = resolve(R.string.transaction_row_status_sent)
        )
        val source = FakeSource(records = MutableStateFlow(listOf(sent)))
        val displayDao = mockk<TxDisplayCacheDao>(relaxed = true)
        coEvery { displayDao.getEntriesByIds(any()) } returns listOf(existing)
        val groupDao = mockk<TxGroupCacheDao>(relaxed = true)
        coEvery { groupDao.getGroupsForTxIds(any()) } returns emptyList<TxGroupCacheEntry>()

        val service = buildService(
            source, configWithState("CUT_OVER"), backgroundScope,
            displayDao = displayDao, groupDao = groupDao
        )
        service.start()
        runCurrent()

        coVerify(exactly = 0) { displayDao.insertAll(any()) }
    }

    // ── The engine-event (instant receive) feed ───────────────────────

    @Test
    fun eventRecord_mapsDetectedToPendingIncoming() {
        val event = L1TxEvent.Detected(
            txidHex = displayHex(7),
            netAmountDuffs = 1_000_000L,
            feeDuffs = null,
            contextCode = 0,
            directionCode = 0
        )
        val r = l1TxUiRecordFromEvent(event, now)
        assertEquals(displayHex(7), r.txidHex)
        assertEquals(1_000_000L, r.netAmountDuffs)
        assertNull(r.feeDuffs)
        assertEquals(L1TxUiStatus.PENDING, r.status)
        assertEquals(L1TxUiDirection.INCOMING, r.direction)
        // First seen IS now (second precision, like the Room column).
        assertEquals((now / 1000) * 1000, r.timestampMs)
    }

    @Test
    fun isLockPlan_flipsSendingToSentAndClearsProcessing() {
        val sending = cacheEntry(
            rowId = displayHex(3),
            title = resolve(R.string.transaction_row_status_sending),
            statusText = resolve(R.string.transaction_row_status_processing)
        )
        val updated = planL1InstantLockRowUpdate(sending, resolve)!!
        assertEquals(resolve(R.string.transaction_row_status_sent), updated.title)
        assertEquals("", updated.statusText)
        // Everything else (value, time, metadata) is preserved byte-identically.
        assertEquals(sending.valueSatoshis, updated.valueSatoshis)
        assertEquals(sending.time, updated.time)
        assertEquals(sending.comment, updated.comment)
        assertEquals(sending.exchangeRateFiatCode, updated.exchangeRateFiatCode)
    }

    @Test
    fun isLockPlan_clearsConfirmingOnIncomingRow() {
        val receiving = cacheEntry(
            rowId = displayHex(4),
            title = resolve(R.string.transaction_row_status_received),
            statusText = resolve(R.string.transaction_row_status_confirming),
            filterFlags = TxDisplayCacheEntry.FLAG_RECEIVED
        )
        val updated = planL1InstantLockRowUpdate(receiving, resolve)!!
        assertEquals(resolve(R.string.transaction_row_status_received), updated.title)
        assertEquals("", updated.statusText)
    }

    @Test
    fun isLockPlan_settledRowIsNoOp_andRichRowsAreNeverTouched() {
        // Already settled: nothing to change.
        assertNull(
            planL1InstantLockRowUpdate(
                cacheEntry(rowId = displayHex(5), title = resolve(R.string.transaction_row_status_sent)),
                resolve
            )
        )
        // Rows with richer semantics are untouchable even when they LOOK pending.
        val pendingLook = { flags: Int, service: String?, errors: Boolean ->
            cacheEntry(
                rowId = displayHex(6),
                title = resolve(R.string.transaction_row_status_sending),
                statusText = resolve(R.string.transaction_row_status_processing),
                service = service,
                hasErrors = errors,
                filterFlags = flags
            )
        }
        assertNull(planL1InstantLockRowUpdate(pendingLook(TxDisplayCacheEntry.FLAG_SENT, "crowdnode", false), resolve))
        assertNull(planL1InstantLockRowUpdate(pendingLook(TxDisplayCacheEntry.FLAG_SENT, null, true), resolve))
        assertNull(
            planL1InstantLockRowUpdate(
                pendingLook(TxDisplayCacheEntry.FLAG_SENT or TxDisplayCacheEntry.FLAG_GIFT_CARD, null, false),
                resolve
            )
        )
        assertNull(
            planL1InstantLockRowUpdate(
                pendingLook(TxDisplayCacheEntry.FLAG_SENT or TxDisplayCacheEntry.FLAG_COINJOIN, null, false),
                resolve
            )
        )
    }

    /** A stateful display-cache fake: inserts land in [store], reads see them. */
    private fun statefulDisplayDao(store: MutableMap<String, TxDisplayCacheEntry>): TxDisplayCacheDao {
        val dao = mockk<TxDisplayCacheDao>(relaxed = true)
        coEvery { dao.getEntriesByIds(any()) } coAnswers {
            firstArg<List<String>>().mapNotNull { store[it] }
        }
        coEvery { dao.insertAll(any()) } coAnswers {
            firstArg<List<TxDisplayCacheEntry>>().forEach { store[it.rowId] = it }
        }
        return dao
    }

    @Test
    fun engineEvent_mempoolReceiveRendersAndNotifiesPreBlock_thenBlockAndIsLockDedup() = runTest {
        val txid = displayHex(7)
        val store = mutableMapOf<String, TxDisplayCacheEntry>()
        val displayDao = statefulDisplayDao(store)
        val groupDao = mockk<TxGroupCacheDao>(relaxed = true)
        coEvery { groupDao.getGroupsForTxIds(any()) } returns emptyList<TxGroupCacheEntry>()
        val notified = mutableListOf<Long>()
        val events = kotlinx.coroutines.flow.MutableSharedFlow<L1TxEvent>(extraBufferCapacity = 8)
        val source = FakeSource(records = MutableStateFlow(emptyList()))

        val service = buildService(
            source, configWithState("CUT_OVER"), backgroundScope,
            displayDao = displayDao, groupDao = groupDao,
            notify = { notified += it }, txEvents = events
        )
        service.start()
        runCurrent()

        // 1) Mempool sighting: the row renders IMMEDIATELY as a pending
        //    receive and the coins-received notification fires — no block.
        events.emit(L1TxEvent.Detected(txid, 1_000_000L, null, contextCode = 0, directionCode = 0))
        runCurrent()
        val pending = store.getValue(txid)
        assertEquals(resolve(R.string.transaction_row_status_received), pending.title)
        assertEquals(resolve(R.string.transaction_row_status_processing), pending.statusText)
        assertEquals(1_000_000L, pending.valueSatoshis)
        assertEquals(listOf(1_000_000L), notified)

        // 2) A duplicate detection (multi-account tx / mempool re-emit)
        //    neither duplicates the row nor re-notifies.
        events.emit(L1TxEvent.Detected(txid, 1_000_000L, null, contextCode = 0, directionCode = 0))
        runCurrent()
        assertEquals(1, store.size)
        assertEquals(1, notified.size)

        // 3) The IS lock flips the SAME row pre-block: "Processing" clears.
        events.emit(L1TxEvent.InstantLocked(txid))
        runCurrent()
        assertEquals("", store.getValue(txid).statusText)
        assertEquals(resolve(R.string.transaction_row_status_received), store.getValue(txid).title)

        // 4) The block eventually lands and the Room snapshot re-emits the
        //    tx — still one row, still one notification.
        source.records.value = listOf(record(firstByte = 7, net = 1_000_000L, context = 2, direction = 0))
        runCurrent()
        assertEquals(1, store.size)
        assertEquals(listOf(1_000_000L), notified)
    }

    @Test
    fun engineEvent_isLockFlipsStuckSendingRow() = runTest {
        val txid = displayHex(9)
        val store = mutableMapOf(
            txid to cacheEntry(rowId = txid, title = resolve(R.string.transaction_row_status_sending))
        )
        val displayDao = statefulDisplayDao(store)
        val groupDao = mockk<TxGroupCacheDao>(relaxed = true)
        coEvery { groupDao.getGroupsForTxIds(any()) } returns emptyList<TxGroupCacheEntry>()
        val events = kotlinx.coroutines.flow.MutableSharedFlow<L1TxEvent>(extraBufferCapacity = 8)

        val service = buildService(
            source = FakeSource(records = MutableStateFlow(emptyList())),
            dashPayConfig = configWithState("CUT_OVER"),
            scope = backgroundScope,
            displayDao = displayDao, groupDao = groupDao, txEvents = events
        )
        service.start()
        runCurrent()

        events.emit(L1TxEvent.InstantLocked(txid))
        runCurrent()
        assertEquals(resolve(R.string.transaction_row_status_sent), store.getValue(txid).title)
    }

    @Test
    fun engineEvent_isLockForUnknownOrGroupedTxIsNoOp() = runTest {
        val txid = displayHex(2)
        val store = mutableMapOf<String, TxDisplayCacheEntry>()
        val displayDao = statefulDisplayDao(store)
        val groupDao = mockk<TxGroupCacheDao>(relaxed = true)
        // The tx lives inside a multi-tx group row: never touched.
        coEvery { groupDao.getGroupsForTxIds(any()) } returns listOf(
            TxGroupCacheEntry(
                groupId = "group-1", txId = txid,
                wrapperType = TxGroupCacheEntry.TYPE_CROWDNODE, groupDate = "", sortOrder = 0
            )
        )
        val events = kotlinx.coroutines.flow.MutableSharedFlow<L1TxEvent>(extraBufferCapacity = 8)

        val service = buildService(
            source = FakeSource(records = MutableStateFlow(emptyList())),
            dashPayConfig = configWithState("CUT_OVER"),
            scope = backgroundScope,
            displayDao = displayDao, groupDao = groupDao, txEvents = events
        )
        service.start()
        runCurrent()

        events.emit(L1TxEvent.InstantLocked(txid))
        runCurrent()
        assertTrue(store.isEmpty())
        coVerify(exactly = 0) { displayDao.insertAll(any()) }
    }

    @Test
    fun engineEvent_droppedIsLockBlockSnapshotClearsProcessing() = runTest {
        // Detected(mempool) → islock DROPPED → Room snapshot lands the tx
        // at block time with context=InBlock: "Processing" must clear (a
        // block-confirmed tx is never "Processing" in dashj semantics).
        val txid = displayHex(7)
        val store = mutableMapOf<String, TxDisplayCacheEntry>()
        val displayDao = statefulDisplayDao(store)
        val groupDao = mockk<TxGroupCacheDao>(relaxed = true)
        coEvery { groupDao.getGroupsForTxIds(any()) } returns emptyList<TxGroupCacheEntry>()
        val events = kotlinx.coroutines.flow.MutableSharedFlow<L1TxEvent>(extraBufferCapacity = 8)
        val source = FakeSource(records = MutableStateFlow(emptyList()))

        val service = buildService(
            source, configWithState("CUT_OVER"), backgroundScope,
            displayDao = displayDao, groupDao = groupDao, txEvents = events
        )
        service.start()
        runCurrent()

        events.emit(L1TxEvent.Detected(txid, 1_000_000L, null, contextCode = 0, directionCode = 0))
        runCurrent()
        assertEquals(resolve(R.string.transaction_row_status_processing), store.getValue(txid).statusText)

        // No InstantLocked event ever arrives; the block does.
        source.records.value = listOf(record(firstByte = 7, net = 1_000_000L, context = 2, direction = 0))
        runCurrent()
        assertEquals("", store.getValue(txid).statusText)
        assertEquals(resolve(R.string.transaction_row_status_received), store.getValue(txid).title)
    }

    @Test
    fun engineEvent_selfSpendOutgoingFirstSuppressesReceiveNotification() = runTest {
        // One tx touching two accounts of the same wallet: the engine emits
        // one Detected per account (same txid). Outgoing sibling first →
        // the row is born "Sending" and the Incoming sibling must neither
        // retitle it nor fire a coins-received notification.
        val txid = displayHex(11)
        val store = mutableMapOf<String, TxDisplayCacheEntry>()
        val displayDao = statefulDisplayDao(store)
        val groupDao = mockk<TxGroupCacheDao>(relaxed = true)
        coEvery { groupDao.getGroupsForTxIds(any()) } returns emptyList<TxGroupCacheEntry>()
        val notified = mutableListOf<Long>()
        val events = kotlinx.coroutines.flow.MutableSharedFlow<L1TxEvent>(extraBufferCapacity = 8)
        val source = FakeSource(records = MutableStateFlow(emptyList()))

        val service = buildService(
            source, configWithState("CUT_OVER"), backgroundScope,
            displayDao = displayDao, groupDao = groupDao,
            notify = { notified += it }, txEvents = events
        )
        service.start()
        runCurrent()

        events.emit(L1TxEvent.Detected(txid, -1_000_146L, 146L, contextCode = 0, directionCode = 1))
        runCurrent()
        events.emit(L1TxEvent.Detected(txid, 900_000L, null, contextCode = 0, directionCode = 0))
        runCurrent()

        assertEquals(1, store.size)
        assertEquals(resolve(R.string.transaction_row_status_sending), store.getValue(txid).title)
        assertTrue(notified.isEmpty())

        // Even a later snapshot re-sighting (row briefly dropped by a cache
        // rebuild) can't notify: the txid's notification slot is claimed.
        store.clear()
        source.records.value = listOf(record(firstByte = 11, net = 900_000L, context = 0, direction = 0))
        runCurrent()
        assertTrue(notified.isEmpty())
    }

    @Test
    fun engineEvent_selfSpendIncomingFirstNotifiesOnce_documentedLimitation() = runTest {
        // Incoming sibling first: with the single sequential collector
        // there is no sibling signal to wait on, so the notification fires
        // (documented limitation — unreachable today with a single BIP44
        // account). The Outgoing sibling must still not double anything.
        val txid = displayHex(12)
        val store = mutableMapOf<String, TxDisplayCacheEntry>()
        val displayDao = statefulDisplayDao(store)
        val groupDao = mockk<TxGroupCacheDao>(relaxed = true)
        coEvery { groupDao.getGroupsForTxIds(any()) } returns emptyList<TxGroupCacheEntry>()
        val notified = mutableListOf<Long>()
        val events = kotlinx.coroutines.flow.MutableSharedFlow<L1TxEvent>(extraBufferCapacity = 8)
        val source = FakeSource(records = MutableStateFlow(emptyList()))

        val service = buildService(
            source, configWithState("CUT_OVER"), backgroundScope,
            displayDao = displayDao, groupDao = groupDao,
            notify = { notified += it }, txEvents = events
        )
        service.start()
        runCurrent()

        events.emit(L1TxEvent.Detected(txid, 900_000L, null, contextCode = 0, directionCode = 0))
        runCurrent()
        assertEquals(listOf(900_000L), notified)
        assertEquals(resolve(R.string.transaction_row_status_received), store.getValue(txid).title)

        events.emit(L1TxEvent.Detected(txid, -1_000_146L, 146L, contextCode = 0, directionCode = 1))
        runCurrent()
        assertEquals(1, store.size)
        assertEquals(1, notified.size)
    }

    @Test
    fun txPipeline_recollectsAfterFeedFailure() = runTest {
        // FIX: one upstream exception used to kill the merged feed
        // permanently (single .catch). The pipeline must log, back off and
        // re-collect — then keep processing events.
        val txid = displayHex(8)
        val store = mutableMapOf<String, TxDisplayCacheEntry>()
        val displayDao = statefulDisplayDao(store)
        val groupDao = mockk<TxGroupCacheDao>(relaxed = true)
        coEvery { groupDao.getGroupsForTxIds(any()) } returns emptyList<TxGroupCacheEntry>()
        val live = kotlinx.coroutines.flow.MutableSharedFlow<L1TxEvent>(extraBufferCapacity = 8)
        var failedOnce = false
        val events = kotlinx.coroutines.flow.flow<L1TxEvent> {
            if (!failedOnce) {
                failedOnce = true
                throw IllegalStateException("first tx-feed collection dies")
            }
            live.collect { emit(it) }
        }
        val source = FakeSource(records = MutableStateFlow(emptyList()))

        val service = buildService(
            source, configWithState("CUT_OVER"), backgroundScope,
            displayDao = displayDao, groupDao = groupDao, txEvents = events
        )
        service.start()
        runCurrent()
        assertEquals(1, source.recordSubscriptions) // first collection ran, then died

        // Ride out the retry backoff: the pipeline re-collects both feeds.
        testScheduler.advanceTimeBy(CutoverUiDataService.TX_FEED_RETRY_MS + 1)
        runCurrent()
        assertEquals(2, source.recordSubscriptions)

        // …and events flow again end-to-end.
        live.emit(L1TxEvent.Detected(txid, 500_000L, null, contextCode = 0, directionCode = 0))
        runCurrent()
        assertEquals(resolve(R.string.transaction_row_status_received), store.getValue(txid).title)
    }

    // ── Historical mixing classification + per-day group planning ─────

    @Test
    fun mixing_coinJoinDirectionClassifiesWithoutAccountProbe() {
        // The SDK's Rust classifier affirmatively tagged a mixing round —
        // no CoinJoin-account membership lookup needed.
        assertTrue(isHistoricalMixingRecord(record(net = 0, direction = 3), emptySet()))
    }

    @Test
    fun mixing_internalClassifiesOnlyWhenCoinJoinAccountFunded() {
        val internal = record(firstByte = 5, net = -300, direction = 2)
        // A plain internal move stays an individual "Internal" row…
        assertFalse(isHistoricalMixingRecord(internal, emptySet()))
        // …but a denomination-creation/collateral tx (funded a CoinJoin-account
        // TXO) belongs in the mixing group.
        assertTrue(isHistoricalMixingRecord(internal, setOf(displayHex(5))))
        // Ordinary sends/receives are never mixing, account-funded or not
        // (a spend OF mixed funds keeps its own row — dashj `Send` parity).
        assertFalse(isHistoricalMixingRecord(record(firstByte = 5, direction = 0), setOf(displayHex(5))))
        assertFalse(isHistoricalMixingRecord(record(firstByte = 5, net = -300, direction = 1), setOf(displayHex(5))))
    }

    @Test
    fun mixing_groupIdIsTheDashjWrapperConvention() {
        val utc = java.time.ZoneId.of("UTC")
        // Same id as CoinJoinMixingTxSet ("coinjoin_$groupDate", ISO date) so
        // the SDK writer and a dashj rebuild converge on ONE row per day.
        assertEquals("coinjoin_2025-07-20", mixingGroupIdFor(record(direction = 3), utc, now))
        // A record with no timestamp falls back to nowMs (never epoch-0 grouping).
        assertEquals(
            "coinjoin_2025-07-20",
            mixingGroupIdFor(record(direction = 3, firstSeenSec = 0), utc, now)
        )
    }

    @Test
    fun mixing_freshRecordsCollapsePerLocalDay() {
        val utc = java.time.ZoneId.of("UTC")
        val day1a = record(firstByte = 1, net = 0, direction = 3)
        val day1b = record(firstByte = 2, net = -446, direction = 3, firstSeenSec = now / 1000 + 60)
        val day2 = record(firstByte = 3, net = -300, direction = 3, firstSeenSec = now / 1000 + 86_400)
        val updates = planMixingGroupUpdates(
            listOf(day1b, day1a, day2), emptyMap(), "Mixing Transactions", utc, now
        ).sortedBy { it.groupId }

        assertEquals(2, updates.size)
        val (d1, d2) = updates
        assertEquals("coinjoin_2025-07-20", d1.groupId)
        assertEquals("2025-07-20", d1.groupDateIso)
        // Members oldest-first regardless of input order (group-cache sortOrder).
        assertEquals(listOf(displayHex(1), displayHex(2)), d1.newMemberTxids)
        // The dashj-era CoinJoinMixingTxSet rendering: Σ member nets, group icon
        // on the sent background, the COINJOIN filter bucket (ALL tab only).
        assertEquals(-446L, d1.row.valueSatoshis)
        assertEquals(2, d1.row.transactionAmount)
        assertEquals(TxDisplayCacheEntry.ICON_COINJOIN, d1.row.iconType)
        assertEquals(TxDisplayCacheEntry.BG_SENT, d1.row.iconBgType)
        assertEquals(TxDisplayCacheEntry.FLAG_COINJOIN, d1.row.filterFlags)
        assertEquals("Mixing Transactions", d1.row.title)
        assertEquals((now / 1000 + 60) * 1000, d1.row.time)
        assertEquals("", d1.row.statusText)
        assertFalse(d1.row.hasErrors)
        assertNull(d1.row.service)
        assertNull(d1.row.contactUserId)

        assertEquals("coinjoin_2025-07-21", d2.groupId)
        assertEquals(listOf(displayHex(3)), d2.newMemberTxids)
        assertEquals(1, d2.row.transactionAmount)
        assertEquals(-300L, d2.row.valueSatoshis)
    }

    @Test
    fun mixing_incrementalMergeExtendsExistingGroupRow() {
        // An engine-event pass carries a single record; the existing per-day row
        // (from an earlier pass or a dashj-era rebuild) is EXTENDED, not rebuilt.
        val utc = java.time.ZoneId.of("UTC")
        val groupId = "coinjoin_2025-07-20"
        val existing = TxDisplayCacheEntry(
            rowId = groupId,
            title = "Mixing Transactions",
            valueSatoshis = -1_000L,
            iconType = TxDisplayCacheEntry.ICON_COINJOIN,
            iconBgType = TxDisplayCacheEntry.BG_SENT,
            statusText = "",
            comment = "my memo",
            transactionAmount = 3,
            time = now - 3_600_000,
            hasErrors = false,
            service = null,
            exchangeRateFiatCode = "USD",
            exchangeRateFiatValue = 42L,
            contactUsername = null,
            contactDisplayName = null,
            contactAvatarUrl = null,
            contactUserId = null,
            filterFlags = TxDisplayCacheEntry.FLAG_COINJOIN
        )
        val late = record(firstByte = 9, net = -446, direction = 3)
        val updates = planMixingGroupUpdates(
            listOf(late), mapOf(groupId to existing), "Mixing Transactions", utc, now
        )

        val update = updates.single()
        assertEquals(groupId, update.groupId)
        assertEquals(listOf(displayHex(9)), update.newMemberTxids)
        assertEquals(-1_446L, update.row.valueSatoshis)
        assertEquals(4, update.row.transactionAmount)
        assertEquals(now, update.row.time) // newest member wins
        // The pre-existing row's memo and historical rate survive the merge.
        assertEquals("my memo", update.row.comment)
        assertEquals("USD", update.row.exchangeRateFiatCode)
        assertEquals(42L, update.row.exchangeRateFiatValue)
    }

    @Test
    fun postCutover_historicalMixingCollapsesIntoPerDayGroupRow() = runTest {
        // Two SDK-classified mixing rounds + one CoinJoin-account-funded internal
        // (denomination creation) on the same day, plus one plain internal move.
        val mix1 = record(firstByte = 1, net = 0, context = 3, direction = 3)
        val mix2 = record(firstByte = 2, net = -446, context = 3, direction = 3, firstSeenSec = now / 1000 + 60)
        val denom = record(firstByte = 3, net = -300, context = 3, direction = 2, firstSeenSec = now / 1000 + 120)
        val plainInternal = record(firstByte = 4, net = -200, context = 3, direction = 2)
        val source = FakeSource(records = MutableStateFlow(listOf(mix1, mix2, denom, plainInternal)))
            .apply { coinJoinFunded = setOf(displayHex(3)) }
        val displayDao = mockk<TxDisplayCacheDao>(relaxed = true)
        coEvery { displayDao.getEntriesByIds(any()) } returns emptyList()
        val groupDao = mockk<TxGroupCacheDao>(relaxed = true)
        coEvery { groupDao.getGroupsForTxIds(any()) } returns emptyList<TxGroupCacheEntry>()
        coEvery { groupDao.getGroupEntries(any()) } returns emptyList<TxGroupCacheEntry>()

        val service = buildService(
            source, configWithState("CUT_OVER"), backgroundScope,
            displayDao = displayDao, groupDao = groupDao
        )
        service.start()
        runCurrent()

        // The three mixing txs collapsed into ONE per-day "Mixing" group row…
        val groupRows = slot<List<TxDisplayCacheEntry>>()
        val removedIds = slot<List<String>>()
        coVerify { displayDao.upsertGroupRows(capture(groupRows), capture(removedIds)) }
        val row = groupRows.captured.single()
        assertTrue(row.rowId.startsWith(MIXING_GROUP_ROWID_PREFIX))
        assertEquals(resolve(R.string.coinjoin_mixing_transactions), row.title)
        assertEquals(TxDisplayCacheEntry.ICON_COINJOIN, row.iconType)
        assertEquals(TxDisplayCacheEntry.FLAG_COINJOIN, row.filterFlags)
        assertEquals(3, row.transactionAmount)
        assertEquals(-746L, row.valueSatoshis)
        // …their previously-scattered individual rows are removed…
        assertEquals(setOf(displayHex(1), displayHex(2), displayHex(3)), removedIds.captured.toSet())
        // …their membership is persisted for the group-cache readers
        // (detail-on-tap, later-pass exclusion, dashj-side writers)…
        val members = slot<List<TxGroupCacheEntry>>()
        coVerify { groupDao.insertAll(capture(members)) }
        assertEquals(3, members.captured.size)
        assertTrue(
            members.captured.all {
                it.wrapperType == TxGroupCacheEntry.TYPE_COINJOIN && it.groupId == row.rowId
            }
        )
        // …and the plain internal move keeps its own individual row.
        val inserted = slot<List<TxDisplayCacheEntry>>()
        coVerify { displayDao.insertAll(capture(inserted)) }
        assertEquals(listOf(displayHex(4)), inserted.captured.map { it.rowId })
    }

    @Test
    fun postCutover_alreadyGroupedMixingTxsAreNeverRegrouped() = runTest {
        // A dashj-era rebuild (upgraded install) already grouped this tx: the
        // SDK pass must not double-count it into the group row.
        val mix = record(firstByte = 1, net = -446, context = 3, direction = 3)
        val source = FakeSource(records = MutableStateFlow(listOf(mix)))
        val displayDao = mockk<TxDisplayCacheDao>(relaxed = true)
        coEvery { displayDao.getEntriesByIds(any()) } returns emptyList()
        val groupDao = mockk<TxGroupCacheDao>(relaxed = true)
        coEvery { groupDao.getGroupsForTxIds(any()) } returns listOf(
            TxGroupCacheEntry(
                groupId = "coinjoin_2025-07-20",
                txId = displayHex(1),
                wrapperType = TxGroupCacheEntry.TYPE_COINJOIN,
                groupDate = "2025-07-20",
                sortOrder = 0
            )
        )

        val service = buildService(
            source, configWithState("CUT_OVER"), backgroundScope,
            displayDao = displayDao, groupDao = groupDao
        )
        service.start()
        runCurrent()

        coVerify(exactly = 0) { displayDao.upsertGroupRows(any(), any()) }
        coVerify(exactly = 0) { displayDao.insertAll(any()) }
        coVerify(exactly = 0) { groupDao.insertAll(any()) }
    }
}
