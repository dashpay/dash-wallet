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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-M-01: an upgraded wallet and a restore of the same phrase must show the
 * same value for every sent row — the send WITHOUT its fee, as dashj's
 * `TransactionRowView` always rendered it. Run 17, Wallet B: the 20 asset-lock
 * rows read −0.03 after the upgrade and −0.03000241 after the restore, because
 * the SDK store's `fee` column was NULL and the asset-lock row kept the
 * fee-included net.
 */
class SentValueWithoutFeeTest {

    private val resolve: (Int) -> String = { id -> "str:$id" }
    private val now = 1_790_793_165_000L

    private fun wireTxid(firstByte: Int): ByteArray =
        ByteArray(32) { if (it == 0) firstByte.toByte() else 0 }

    private fun displayHex(firstByte: Int): String =
        wireTxid(firstByte).reversedArray().joinToString("") { "%02x".format(it) }

    /** Direction codes: 1 = outgoing, 2 = internal. Context 3 = chainlocked. */
    private fun record(
        firstByte: Int,
        net: Long,
        fee: Long?,
        direction: Int,
        typeKind: Int = TX_TYPE_KIND_STANDARD
    ) = l1TxUiRecord(wireTxid(firstByte), net, fee, 3, direction, now / 1000, 0, typeKind)

    /** The Invitation from the brief's worked example (a0963d1b…): 3 000 000 credit, 241 fee. */
    private fun invitation(fee: Long?) =
        record(firstByte = 1, net = -3_000_241L, fee = fee, direction = 2, typeKind = TX_TYPE_KIND_ASSET_LOCK)

    private fun invitationRow(value: Long) = TxDisplayCacheEntry(
        rowId = displayHex(1),
        title = resolve(R.string.transaction_row_invitation),
        valueSatoshis = value,
        iconType = TxDisplayCacheEntry.ICON_SENT,
        iconBgType = TxDisplayCacheEntry.BG_SENT,
        statusText = "",
        comment = "",
        transactionAmount = 1,
        time = now,
        hasErrors = false,
        service = null,
        exchangeRateFiatCode = null,
        exchangeRateFiatValue = null,
        contactUsername = null,
        contactDisplayName = null,
        contactAvatarUrl = null,
        contactUserId = null,
        filterFlags = TxDisplayCacheEntry.FLAG_SENT
    )

    private fun sync(record: L1TxUiRecord, existing: TxDisplayCacheEntry?, kind: AssetLockKind? = null) =
        planL1DisplaySync(
            records = listOf(record),
            existingByRowId = existing?.let { mapOf(it.rowId to it) } ?: emptyMap(),
            groupedTxIds = emptySet(),
            resolve = resolve,
            nowMs = now,
            kindByTxid = kind?.let { mapOf(record.txidHex to it) } ?: emptyMap()
        )

    // ── The row value ─────────────────────────────────────────────────

    @Test
    fun assetLockRowsShowTheCreditAmountWithoutTheFee() {
        for (kind in listOf(AssetLockKind.INVITE, AssetLockKind.TOPUP, AssetLockKind.UPGRADE)) {
            assertEquals(kind.name, -3_000_000L, planL1TxRow(invitation(fee = 241L), kind).valueDuffs)
        }
    }

    @Test
    fun everySentShapeDropsTheFee() {
        val sent = record(firstByte = 2, net = -10_000_227L, fee = 227L, direction = 1)
        assertEquals(-10_000_000L, planL1TxRow(sent).valueDuffs)
        // A pure self-transfer costs only its fee, so it reads 0 — as on 11.9.0.
        val internal = record(firstByte = 3, net = -227L, fee = 227L, direction = 2)
        assertEquals(0L, planL1TxRow(internal).valueDuffs)
    }

    @Test
    fun anUnknownFeeLeavesTheNetAsItIs() {
        assertEquals(-3_000_241L, planL1TxRow(invitation(fee = null), AssetLockKind.INVITE).valueDuffs)
    }

    // ── Both paths converge ───────────────────────────────────────────

    @Test
    fun restorePathInsertsTheFeeFreeValue() {
        val plan = sync(invitation(fee = 241L), existing = null, kind = AssetLockKind.INVITE)
        assertEquals(-3_000_000L, plan.inserts.single().valueSatoshis)
    }

    @Test
    fun aRowCachedWithTheFeeIsCorrectedOnceTheFeeIsKnown() {
        // A wallet restored before this fix: its row was authored from the NULL-fee record.
        val plan = sync(invitation(fee = 241L), invitationRow(value = -3_000_241L), AssetLockKind.INVITE)
        assertEquals(-3_000_000L, plan.updates.single().valueSatoshis)
    }

    @Test
    fun theUpgradedWalletsDashjRowIsAlreadyRightAndIsLeftAlone() {
        val plan = sync(invitation(fee = 241L), invitationRow(value = -3_000_000L), AssetLockKind.INVITE)
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun theCorrectionNeverFiresWithoutAFee() {
        val plan = sync(invitation(fee = null), invitationRow(value = -3_000_000L), AssetLockKind.INVITE)
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun aServiceTaggedSendIsCorrectedToo() {
        // A merchant send inserted while the fee was unknown, then tagged by metadata.
        // Service rows take only status edges, but the fee-only value fix applies.
        val sent = record(firstByte = 6, net = -10_000_227L, fee = 227L, direction = 1)
        val row = invitationRow(value = -10_000_227L).copy(
            rowId = sent.txidHex,
            title = resolve(R.string.transaction_row_status_sent),
            service = "Uphold"
        )
        val updated = sync(sent, row).updates.single()
        assertEquals(-10_000_000L, updated.valueSatoshis)
        assertEquals(row.copy(valueSatoshis = -10_000_000L), updated) // nothing else moves
    }

    // ── Fee recovery (SdkTxStoreWalker) ───────────────────────────────

    @Test
    fun feeIsRecoveredFromTheChainShapeOfTheWorkedExample() {
        // a0963d1b…: vin 1.00001000, vout 1.00000759 (credit burn + change).
        val payload = TxPayloadFacts(outputsTotalDuffs = 100_000_759L, outputCount = 2, inputCount = 1)
        assertEquals(241L, recoveredFeeDuffs(payload, spentOwnedCount = 1, spentOwnedDuffs = 100_001_000L))
    }

    @Test
    fun noFeeIsRecoveredWhenAnInputWasNotOurs() {
        // A CoinJoin round: other participants funded the rest.
        val payload = TxPayloadFacts(outputsTotalDuffs = 500_000_000L, outputCount = 5, inputCount = 5)
        assertNull(recoveredFeeDuffs(payload, spentOwnedCount = 1, spentOwnedDuffs = 100_001_000L))
    }

    @Test
    fun noFeeIsRecoveredFromAnIncompleteSpendSum() {
        val payload = TxPayloadFacts(outputsTotalDuffs = 100_000_759L, outputCount = 2, inputCount = 1)
        assertNull(recoveredFeeDuffs(payload, spentOwnedCount = 1, spentOwnedDuffs = 100_000_000L))
    }

    @Test
    fun onlySendsMissingAFeeAreRecovered() {
        assertTrue(needsFeeRecovery(invitation(fee = null)))
        assertTrue(needsFeeRecovery(record(firstByte = 2, net = -5L, fee = null, direction = 1)))
        assertFalse(needsFeeRecovery(invitation(fee = 241L)))
        // A receive has no fee to remove; a CoinJoin round's fee is never ours.
        assertFalse(needsFeeRecovery(record(firstByte = 4, net = 5L, fee = null, direction = 0)))
        assertFalse(needsFeeRecovery(record(firstByte = 5, net = -5L, fee = null, direction = 3)))
    }
}
