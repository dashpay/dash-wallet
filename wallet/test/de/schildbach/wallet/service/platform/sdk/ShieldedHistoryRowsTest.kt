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

import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import de.schildbach.wallet_test.R
import org.dash.wallet.common.money.Dash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldedHistoryRowsTest {

    private val resolve: (Int) -> String = { "res:$it" }

    private val entryHex = "ab".repeat(32)

    private fun entry(
        kindTag: Int,
        amountCredits: Long = 5_000_000L,
        feeCredits: Long? = 1_000_000L,
        status: ShieldedActivityStatus = ShieldedActivityStatus.CONFIRMED,
        id: String = entryHex,
        accountIndex: Int = 0,
        memo: String? = null,
        timestampMs: Long = 1_700_000_000_000L
    ) = ShieldedActivityEntry(
        id = id,
        direction = ShieldedActivityDirection.OUT,
        amount = Dash(amountCredits / 1_000L),
        timestampMs = timestampMs,
        memo = memo,
        pending = status == ShieldedActivityStatus.PENDING,
        kindTag = kindTag,
        accountIndex = accountIndex,
        amountCredits = amountCredits,
        feeCredits = feeCredits,
        status = status
    )

    // ── The QA case (D-19T1-05) ────────────────────────────────────────

    @Test
    fun identityCreate_qaCase_isUpgradeFeeOfExactlyPointZeroThree() {
        val row = planShieldedHistoryRow(
            entry(ShieldedActivityKindTag.IDENTITY_CREATE, amountCredits = 2_796_148_800L, feeCredits = 203_851_200L),
            resolve
        )
        assertNotNull(row)
        row!!
        // Summed in credits, then floored: flooring each part first would read 2_999_999.
        assertEquals(-3_000_000L, row.valueSatoshis)
        assertEquals(resolve(R.string.dashpay_upgrade_fee), row.title)
        assertEquals(TxDisplayCacheEntry.ICON_SENT, row.iconType)
        assertEquals(TxDisplayCacheEntry.BG_SENT, row.iconBgType)
        assertEquals(TxDisplayCacheEntry.FLAG_SENT, row.filterFlags)
        assertEquals(TxDisplayCacheEntry.SOURCE_SHIELDED, row.source)
        assertEquals("shielded:0:$entryHex", row.rowId)
        assertEquals(1, row.transactionAmount)
        assertEquals(1_700_000_000_000L, row.time)
        assertEquals("", row.statusText)
        assertFalse(row.hasErrors)
        assertNull(row.service)
        assertNull(row.exchangeRateFiatCode)
        assertNull(row.exchangeRateFiatValue)
        assertNull(row.contactUserId)
    }

    // ── Every kind tag ─────────────────────────────────────────────────

    private data class Expect(val title: Int, val icon: Int, val bg: Int, val sign: Int, val flag: Int)

    @Test
    fun everySurfacedKind_mapsTitleIconSignAndFlag() {
        val expected = mapOf(
            ShieldedActivityKindTag.SHIELD to Expect(
                R.string.transaction_row_shielded, TxDisplayCacheEntry.ICON_INTERNAL, TxDisplayCacheEntry.BG_SENT,
                +1, TxDisplayCacheEntry.FLAG_RECEIVED
            ),
            ShieldedActivityKindTag.RECEIVED to Expect(
                R.string.transaction_row_status_received, TxDisplayCacheEntry.ICON_RECEIVED,
                TxDisplayCacheEntry.BG_RECEIVED, +1, TxDisplayCacheEntry.FLAG_RECEIVED
            ),
            ShieldedActivityKindTag.SENT to Expect(
                R.string.transaction_row_status_sent, TxDisplayCacheEntry.ICON_SENT, TxDisplayCacheEntry.BG_SENT,
                -1, TxDisplayCacheEntry.FLAG_SENT
            ),
            ShieldedActivityKindTag.UNSHIELD to Expect(
                R.string.transaction_row_unshielded, TxDisplayCacheEntry.ICON_INTERNAL, TxDisplayCacheEntry.BG_SENT,
                -1, TxDisplayCacheEntry.FLAG_SENT
            ),
            ShieldedActivityKindTag.IDENTITY_CREATE to Expect(
                R.string.dashpay_upgrade_fee, TxDisplayCacheEntry.ICON_SENT, TxDisplayCacheEntry.BG_SENT,
                -1, TxDisplayCacheEntry.FLAG_SENT
            ),
            ShieldedActivityKindTag.SHIELD_FROM_IDENTITY to Expect(
                R.string.transaction_row_shielded, TxDisplayCacheEntry.ICON_INTERNAL, TxDisplayCacheEntry.BG_SENT,
                +1, TxDisplayCacheEntry.FLAG_RECEIVED
            ),
            ShieldedActivityKindTag.IDENTITY_TOP_UP to Expect(
                R.string.dashpay_topup_fee, TxDisplayCacheEntry.ICON_SENT, TxDisplayCacheEntry.BG_SENT,
                -1, TxDisplayCacheEntry.FLAG_SENT
            )
        )
        for ((tag, e) in expected) {
            val row = planShieldedHistoryRow(entry(tag, amountCredits = 5_000_000L, feeCredits = 1_000_000L), resolve)
            assertNotNull("tag $tag", row)
            row!!
            assertEquals("tag $tag title", resolve(e.title), row.title)
            assertEquals("tag $tag icon", e.icon, row.iconType)
            assertEquals("tag $tag bg", e.bg, row.iconBgType)
            assertEquals("tag $tag flag", e.flag, row.filterFlags)
            // Incoming: +principal only. Outgoing: −(principal + fee).
            val expectedValue = if (e.sign > 0) 5_000L else -6_000L
            assertEquals("tag $tag value", expectedValue, row.valueSatoshis)
        }
    }

    @Test
    fun deDupAndNoHonestAmountKinds_produceNoRow() {
        // 1 and 5 are Core transactions, already rendered as L1 rows.
        assertNull(planShieldedHistoryRow(entry(ShieldedActivityKindTag.SHIELD_FROM_ASSET_LOCK), resolve))
        assertNull(planShieldedHistoryRow(entry(ShieldedActivityKindTag.WITHDRAWAL), resolve))
        // 7: own spend with only self-change, no honest amount.
        assertNull(planShieldedHistoryRow(entry(ShieldedActivityKindTag.SHIELDED_SPEND), resolve))
        // Unknown future tags.
        assertNull(planShieldedHistoryRow(entry(10), resolve))
        assertNull(planShieldedHistoryRow(entry(-1), resolve))
        assertEquals(
            ShieldedRowPlan.Skipped(ShieldedRowSkip.KIND_NOT_SHOWN),
            planShieldedHistoryRowOrSkip(entry(ShieldedActivityKindTag.WITHDRAWAL), resolve)
        )
    }

    // ── Status ─────────────────────────────────────────────────────────

    @Test
    fun pendingAndUnknown_renderInFlight_confirmedHasNoStatus() {
        for (status in listOf(ShieldedActivityStatus.PENDING, ShieldedActivityStatus.UNKNOWN)) {
            val send = planShieldedHistoryRow(entry(ShieldedActivityKindTag.SENT, status = status), resolve)!!
            assertEquals(resolve(R.string.transaction_row_status_sending), send.title)
            assertEquals("", send.statusText)

            val fee = planShieldedHistoryRow(entry(ShieldedActivityKindTag.IDENTITY_CREATE, status = status), resolve)!!
            assertEquals(resolve(R.string.dashpay_upgrade_fee), fee.title)
            assertEquals(resolve(R.string.transaction_row_status_processing), fee.statusText)

            val received = planShieldedHistoryRow(entry(ShieldedActivityKindTag.RECEIVED, status = status), resolve)!!
            assertEquals(resolve(R.string.transaction_row_status_received), received.title)
            assertEquals(resolve(R.string.transaction_row_status_processing), received.statusText)
        }
        val sent = planShieldedHistoryRow(entry(ShieldedActivityKindTag.SENT), resolve)!!
        assertEquals(resolve(R.string.transaction_row_status_sent), sent.title)
        assertEquals("", sent.statusText)
    }

    // ── Money: flooring, fail-closed, overflow ─────────────────────────

    @Test
    fun credits_floorToDuffs() {
        val received = planShieldedHistoryRow(
            entry(ShieldedActivityKindTag.RECEIVED, amountCredits = 1_999L, feeCredits = null),
            resolve
        )!!
        assertEquals(1L, received.valueSatoshis)
        val sent = planShieldedHistoryRow(
            entry(ShieldedActivityKindTag.SENT, amountCredits = 1_000L, feeCredits = 999L),
            resolve
        )!!
        assertEquals(-1L, sent.valueSatoshis)
    }

    @Test
    fun outgoingWithUnknownFee_producesNoRow_incomingDoesNotNeedAFee() {
        for (tag in listOf(
            ShieldedActivityKindTag.SENT,
            ShieldedActivityKindTag.UNSHIELD,
            ShieldedActivityKindTag.IDENTITY_CREATE,
            ShieldedActivityKindTag.IDENTITY_TOP_UP
        )) {
            assertEquals(
                "tag $tag",
                ShieldedRowPlan.Skipped(ShieldedRowSkip.FEE_UNKNOWN),
                planShieldedHistoryRowOrSkip(entry(tag, feeCredits = null), resolve)
            )
        }
        for (tag in listOf(
            ShieldedActivityKindTag.SHIELD,
            ShieldedActivityKindTag.RECEIVED,
            ShieldedActivityKindTag.SHIELD_FROM_IDENTITY
        )) {
            assertNotNull("tag $tag", planShieldedHistoryRow(entry(tag, feeCredits = null), resolve))
        }
    }

    @Test
    fun overflowingSum_producesNoRow() {
        assertEquals(
            ShieldedRowPlan.Skipped(ShieldedRowSkip.OVERFLOW),
            planShieldedHistoryRowOrSkip(
                entry(ShieldedActivityKindTag.SENT, amountCredits = Long.MAX_VALUE, feeCredits = 1L),
                resolve
            )
        )
        assertNull(
            platformActivityDetailOf(entry(ShieldedActivityKindTag.SENT, amountCredits = Long.MAX_VALUE, feeCredits = 1L))
        )
    }

    // ── Planning a snapshot / de-dup / ids ─────────────────────────────

    @Test
    fun planRows_countsSkipsAndNeverCollidesWithAnL1Txid() {
        val l1Txid = "cd".repeat(32)
        val plan = planShieldedHistoryRows(
            listOf(
                // Same 32-byte value as the L1 txid, by construction.
                entry(ShieldedActivityKindTag.IDENTITY_CREATE, id = l1Txid),
                entry(ShieldedActivityKindTag.SHIELD_FROM_ASSET_LOCK, id = "01".repeat(32)),
                entry(ShieldedActivityKindTag.WITHDRAWAL, id = "02".repeat(32)),
                entry(ShieldedActivityKindTag.SENT, id = "03".repeat(32), feeCredits = null),
                // Same entry id on another account: a distinct row.
                entry(ShieldedActivityKindTag.RECEIVED, id = l1Txid, accountIndex = 1)
            ),
            resolve
        )
        assertEquals(listOf("shielded:0:$l1Txid", "shielded:1:$l1Txid"), plan.rows.map { it.rowId })
        assertTrue(plan.rows.none { it.rowId == l1Txid })
        assertTrue(plan.rows.all { it.source == TxDisplayCacheEntry.SOURCE_SHIELDED })
        assertEquals(mapOf(ShieldedRowSkip.KIND_NOT_SHOWN to 2, ShieldedRowSkip.FEE_UNKNOWN to 1), plan.skipped)
    }

    @Test
    fun rowIdPredicateAndParser() {
        val id = shieldedHistoryRowId(3, entryHex.uppercase())
        assertEquals("shielded:3:$entryHex", id)
        assertTrue(isShieldedActivityRowId(id))
        assertEquals(ShieldedActivityRowKey(3, entryHex), parseShieldedHistoryRowId(id))

        assertFalse(isShieldedActivityRowId(entryHex))
        assertFalse(isShieldedActivityRowId("coinjoin_2026-11-09"))
        assertFalse(isShieldedActivityRowId("crowdnode"))
        assertNull(parseShieldedHistoryRowId(entryHex))
        assertNull(parseShieldedHistoryRowId("shielded:"))
        assertNull(parseShieldedHistoryRowId("shielded:x:$entryHex"))
        assertNull(parseShieldedHistoryRowId("shielded:-1:$entryHex"))
        assertNull(parseShieldedHistoryRowId("shielded:0:${entryHex.dropLast(2)}"))
        assertNull(parseShieldedHistoryRowId("shielded:0:${entryHex.uppercase()}"))
        assertNull(parseShieldedHistoryRowId("shielded:0:$entryHex:extra"))
        assertNull(parseShieldedHistoryRowId("shielded:99999999999:$entryHex"))
    }

    // ── Detail mapping ─────────────────────────────────────────────────

    @Test
    fun detail_qaCase_showsPrincipalAndFeeSeparately() {
        val detail = platformActivityDetailOf(
            entry(
                ShieldedActivityKindTag.IDENTITY_CREATE,
                amountCredits = 2_796_148_800L,
                feeCredits = 203_851_200L,
                memo = "hi"
            )
        )!!
        assertEquals(R.string.dashpay_upgrade_fee, detail.titleRes)
        assertEquals(2_796_148L, detail.principalDuffs)
        assertEquals(203_851L, detail.feeDuffs)
        assertTrue(detail.outgoing)
        assertFalse(detail.pending)
        assertEquals("hi", detail.memo)
        assertEquals(R.string.external_send_balance_name_shielded, detail.fromBalanceRes)
        assertEquals(R.string.external_send_balance_name_identity, detail.toBalanceRes)
        // toString never carries amounts or the memo.
        assertFalse(detail.toString().contains("2796148"))
        assertFalse(detail.toString().contains("hi)"))
    }

    @Test
    fun detail_fromToPerKind_andNoDetailWhereNoRow() {
        val shielded = R.string.external_send_balance_name_shielded
        val platform = R.string.external_send_balance_name_platform
        val identity = R.string.external_send_balance_name_identity
        val expected = mapOf(
            ShieldedActivityKindTag.SHIELD to (platform to shielded),
            ShieldedActivityKindTag.RECEIVED to (null to shielded),
            ShieldedActivityKindTag.SENT to (shielded to null),
            ShieldedActivityKindTag.UNSHIELD to (shielded to platform),
            ShieldedActivityKindTag.IDENTITY_CREATE to (shielded to identity),
            ShieldedActivityKindTag.SHIELD_FROM_IDENTITY to (identity to shielded),
            ShieldedActivityKindTag.IDENTITY_TOP_UP to (shielded to identity)
        )
        for ((tag, fromTo) in expected) {
            val detail = platformActivityDetailOf(entry(tag))!!
            assertEquals("tag $tag from", fromTo.first, detail.fromBalanceRes)
            assertEquals("tag $tag to", fromTo.second, detail.toBalanceRes)
        }
        assertNull(platformActivityDetailOf(entry(ShieldedActivityKindTag.SHIELD_FROM_ASSET_LOCK)))
        assertNull(platformActivityDetailOf(entry(ShieldedActivityKindTag.WITHDRAWAL)))
        assertNull(platformActivityDetailOf(entry(ShieldedActivityKindTag.SHIELDED_SPEND)))
        assertNull(platformActivityDetailOf(entry(ShieldedActivityKindTag.SENT, feeCredits = null)))
        assertTrue(platformActivityDetailOf(entry(ShieldedActivityKindTag.SENT, status = ShieldedActivityStatus.UNKNOWN))!!.pending)
        assertNull(platformActivityDetailOf(entry(ShieldedActivityKindTag.RECEIVED, feeCredits = null))!!.feeDuffs)
    }
}
