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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldedHistoryRowsTest {

    private val resolve: (Int) -> String = { "res:$it" }

    private val entryHex = "ab".repeat(32)

    // The pre-ownership cases: every counterparty is the wallet's own.
    private fun planRow(e: ShieldedActivityEntry, r: (Int) -> String, own: CounterpartyOwnership = CounterpartyOwnership.OWN) =
        (planShieldedHistoryRowOrSkip(e, own, r) as? ShieldedRowPlan.Row)?.entry

    private fun planOrSkip(e: ShieldedActivityEntry, r: (Int) -> String, own: CounterpartyOwnership = CounterpartyOwnership.OWN) =
        planShieldedHistoryRowOrSkip(e, own, r)

    // Withdrawals: already paid on Core (the L1 row renders them).
    private fun planRows(entries: List<ShieldedActivityEntry>, r: (Int) -> String) = planShieldedHistoryRows(
        entries,
        {
            if (it.kindTag == ShieldedActivityKindTag.WITHDRAWAL) CounterpartyOwnership.OWN_PAID_ON_CORE else CounterpartyOwnership.OWN
        },
        r
    )

    private fun detailOf(e: ShieldedActivityEntry, own: CounterpartyOwnership = CounterpartyOwnership.OWN) =
        platformActivityDetailOf(e, own)

    private fun entry(
        kindTag: Int,
        amountCredits: Long = 5_000_000L,
        feeCredits: Long? = 1_000_000L,
        status: ShieldedActivityStatus = ShieldedActivityStatus.CONFIRMED,
        id: String = entryHex,
        accountIndex: Int = 0,
        memo: String? = null,
        timestampMs: Long = 1_700_000_000_000L,
        counterparty: ByteArray = ByteArray(0),
        identityId: ByteArray = ByteArray(0)
    ) = ShieldedActivityEntry(
        id = id,
        direction = ShieldedActivityDirection.OUT,
        timestampMs = timestampMs,
        memo = memo,
        kindTag = kindTag,
        accountIndex = accountIndex,
        amountCredits = amountCredits,
        feeCredits = feeCredits,
        status = status,
        counterparty = counterparty,
        identityId = identityId
    )

    // ── The QA case (D-19T1-05) ────────────────────────────────────────

    @Test
    fun identityCreate_qaCase_isUpgradeFeeOfExactlyPointZeroThree() {
        val row = planRow(
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
            val row = planRow(entry(tag, amountCredits = 5_000_000L, feeCredits = 1_000_000L), resolve)
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
        assertNull(planRow(entry(ShieldedActivityKindTag.SHIELD_FROM_ASSET_LOCK), resolve))
        assertNull(planRow(entry(ShieldedActivityKindTag.WITHDRAWAL), resolve, CounterpartyOwnership.OWN_PAID_ON_CORE))
        // 7: own spend with only self-change, no honest amount.
        assertNull(planRow(entry(ShieldedActivityKindTag.SHIELDED_SPEND), resolve))
        // Unknown future tags.
        assertNull(planRow(entry(10), resolve))
        assertNull(planRow(entry(-1), resolve))
        assertEquals(
            ShieldedRowPlan.Skipped(ShieldedRowSkip.KIND_NOT_SHOWN),
            planOrSkip(entry(ShieldedActivityKindTag.WITHDRAWAL), resolve, CounterpartyOwnership.OWN_PAID_ON_CORE)
        )
    }

    // ── Status ─────────────────────────────────────────────────────────

    @Test
    fun pendingAndUnknown_renderInFlight_confirmedHasNoStatus() {
        for (status in listOf(ShieldedActivityStatus.PENDING, ShieldedActivityStatus.UNKNOWN)) {
            val send = planRow(entry(ShieldedActivityKindTag.SENT, status = status), resolve)!!
            assertEquals(resolve(R.string.transaction_row_status_sending), send.title)
            assertEquals("", send.statusText)

            val fee = planRow(entry(ShieldedActivityKindTag.IDENTITY_CREATE, status = status), resolve)!!
            assertEquals(resolve(R.string.dashpay_upgrade_fee), fee.title)
            assertEquals(resolve(R.string.transaction_row_status_processing), fee.statusText)

            val received = planRow(entry(ShieldedActivityKindTag.RECEIVED, status = status), resolve)!!
            assertEquals(resolve(R.string.transaction_row_status_received), received.title)
            assertEquals(resolve(R.string.transaction_row_status_processing), received.statusText)
        }
        val sent = planRow(entry(ShieldedActivityKindTag.SENT), resolve)!!
        assertEquals(resolve(R.string.transaction_row_status_sent), sent.title)
        assertEquals("", sent.statusText)
    }

    // ── Money: flooring, fail-closed, overflow ─────────────────────────

    @Test
    fun credits_floorToDuffs() {
        val received = planRow(
            entry(ShieldedActivityKindTag.RECEIVED, amountCredits = 1_999L, feeCredits = null),
            resolve
        )!!
        assertEquals(1L, received.valueSatoshis)
        val sent = planRow(
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
                planOrSkip(entry(tag, feeCredits = null), resolve)
            )
        }
        for (tag in listOf(
            ShieldedActivityKindTag.SHIELD,
            ShieldedActivityKindTag.RECEIVED,
            ShieldedActivityKindTag.SHIELD_FROM_IDENTITY
        )) {
            assertNotNull("tag $tag", planRow(entry(tag, feeCredits = null), resolve))
        }
    }

    @Test
    fun overflowingSum_producesNoRow() {
        assertEquals(
            ShieldedRowPlan.Skipped(ShieldedRowSkip.OVERFLOW),
            planOrSkip(
                entry(ShieldedActivityKindTag.SENT, amountCredits = Long.MAX_VALUE, feeCredits = 1L),
                resolve
            )
        )
        assertNull(
            detailOf(entry(ShieldedActivityKindTag.SENT, amountCredits = Long.MAX_VALUE, feeCredits = 1L))
        )
    }

    // ── Planning a snapshot / de-dup / ids ─────────────────────────────

    @Test
    fun planRows_countsSkipsAndNeverCollidesWithAnL1Txid() {
        val l1Txid = "cd".repeat(32)
        val plan = planRows(
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
        val detail = detailOf(
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
            val detail = detailOf(entry(tag))!!
            assertEquals("tag $tag from", fromTo.first, detail.fromBalanceRes)
            assertEquals("tag $tag to", fromTo.second, detail.toBalanceRes)
        }
        assertNull(detailOf(entry(ShieldedActivityKindTag.SHIELD_FROM_ASSET_LOCK)))
        assertNull(detailOf(entry(ShieldedActivityKindTag.WITHDRAWAL), CounterpartyOwnership.OWN_PAID_ON_CORE))
        assertNull(detailOf(entry(ShieldedActivityKindTag.SHIELDED_SPEND)))
        assertNull(detailOf(entry(ShieldedActivityKindTag.SENT, feeCredits = null)))
        assertTrue(detailOf(entry(ShieldedActivityKindTag.SENT, status = ShieldedActivityStatus.UNKNOWN))!!.pending)
        assertNull(detailOf(entry(ShieldedActivityKindTag.RECEIVED, feeCredits = null))!!.feeDuffs)
    }

    // ── Counterparty ownership (tags 4 / 5 / 9) ────────────────────────

    @Test
    fun ownershipMatrix_unshieldWithdrawalTopUp() {
        val sent = resolve(R.string.transaction_row_status_sent)
        data class Case(val tag: Int, val own: CounterpartyOwnership, val title: String?, val icon: Int?, val skip: ShieldedRowSkip?)
        val cases = listOf(
            Case(ShieldedActivityKindTag.UNSHIELD, CounterpartyOwnership.OWN, resolve(R.string.transaction_row_unshielded), TxDisplayCacheEntry.ICON_INTERNAL, null),
            Case(ShieldedActivityKindTag.UNSHIELD, CounterpartyOwnership.EXTERNAL, sent, TxDisplayCacheEntry.ICON_SENT, null),
            Case(ShieldedActivityKindTag.UNSHIELD, CounterpartyOwnership.UNKNOWN, sent, TxDisplayCacheEntry.ICON_SENT, null),
            // Own withdrawal still in Platform's queue: an own move until the payout is on Core.
            Case(ShieldedActivityKindTag.WITHDRAWAL, CounterpartyOwnership.OWN, resolve(R.string.transaction_row_unshielded), TxDisplayCacheEntry.ICON_INTERNAL, null),
            Case(ShieldedActivityKindTag.WITHDRAWAL, CounterpartyOwnership.OWN_PAID_ON_CORE, null, null, ShieldedRowSkip.KIND_NOT_SHOWN),
            Case(ShieldedActivityKindTag.WITHDRAWAL, CounterpartyOwnership.EXTERNAL, sent, TxDisplayCacheEntry.ICON_SENT, null),
            Case(ShieldedActivityKindTag.WITHDRAWAL, CounterpartyOwnership.UNKNOWN, null, null, ShieldedRowSkip.OWNERSHIP_UNKNOWN),
            Case(ShieldedActivityKindTag.IDENTITY_TOP_UP, CounterpartyOwnership.OWN, resolve(R.string.dashpay_topup_fee), TxDisplayCacheEntry.ICON_SENT, null),
            Case(ShieldedActivityKindTag.IDENTITY_TOP_UP, CounterpartyOwnership.EXTERNAL, sent, TxDisplayCacheEntry.ICON_SENT, null),
            Case(ShieldedActivityKindTag.IDENTITY_TOP_UP, CounterpartyOwnership.UNKNOWN, sent, TxDisplayCacheEntry.ICON_SENT, null)
        )
        for (c in cases) {
            val label = "tag ${c.tag} ${c.own}"
            val plan = planOrSkip(entry(c.tag), resolve, c.own)
            val detail = detailOf(entry(c.tag), c.own)
            if (c.skip != null) {
                assertEquals(label, ShieldedRowPlan.Skipped(c.skip), plan)
                assertNull(label, detail)
                continue
            }
            val row = (plan as ShieldedRowPlan.Row).entry
            assertEquals(label, c.title, row.title)
            assertEquals(label, c.icon, row.iconType)
            assertEquals(label, TxDisplayCacheEntry.BG_SENT, row.iconBgType)
            assertEquals(label, TxDisplayCacheEntry.FLAG_SENT, row.filterFlags)
            // −(principal + fee) whoever the counterparty is.
            assertEquals(label, -6_000L, row.valueSatoshis)
            detail!!
            assertEquals(label, R.string.external_send_balance_name_shielded, detail.fromBalanceRes)
            val expectedTo = when {
                c.own != CounterpartyOwnership.OWN -> null
                c.tag == ShieldedActivityKindTag.UNSHIELD -> R.string.external_send_balance_name_platform
                c.tag == ShieldedActivityKindTag.WITHDRAWAL -> R.string.external_send_balance_name_core
                else -> R.string.external_send_balance_name_identity
            }
            assertEquals(label, expectedTo, detail.toBalanceRes)
            if (c.title == sent) assertEquals(label, R.string.transaction_details_amount_sent, detail.titleRes)
        }
        // A pending send to someone else reads "Sending".
        assertEquals(
            resolve(R.string.transaction_row_status_sending),
            planRow(
                entry(ShieldedActivityKindTag.WITHDRAWAL, status = ShieldedActivityStatus.PENDING),
                resolve,
                CounterpartyOwnership.EXTERNAL
            )!!.title
        )
    }

    @Test
    fun ownershipDoesNotChangeTheOwnByConstructionKinds() {
        for (tag in listOf(
            ShieldedActivityKindTag.SHIELD,
            ShieldedActivityKindTag.IDENTITY_CREATE,
            ShieldedActivityKindTag.SHIELD_FROM_IDENTITY,
            ShieldedActivityKindTag.RECEIVED,
            ShieldedActivityKindTag.SENT
        )) {
            val own = planRow(entry(tag), resolve, CounterpartyOwnership.OWN)
            for (other in listOf(CounterpartyOwnership.EXTERNAL, CounterpartyOwnership.UNKNOWN)) {
                assertEquals("tag $tag $other", own, planRow(entry(tag), resolve, other))
            }
        }
    }

    // ── Counterparty decoding ──────────────────────────────────────────

    private val testnet = org.bitcoinj.params.TestNet3Params.get()
    private val decode: (ByteArray) -> String? = { decodeCoreScriptAddress(it, testnet) }
    private val hash = ByteArray(20) { (it + 1).toByte() }

    @Test
    fun coreScript_decodesP2pkhAndP2shOnly() {
        val p2pkh = byteArrayOf(0x76, 0xa9.toByte(), 0x14) + hash + byteArrayOf(0x88.toByte(), 0xac.toByte())
        val p2sh = byteArrayOf(0xa9.toByte(), 0x14) + hash + byteArrayOf(0x87.toByte())
        val p2pkhAddress = decode(p2pkh)!!
        val p2shAddress = decode(p2sh)!!
        // Round-trip through Base58Check: version byte + the script's hash160 (testnet 140 / 19).
        assertTrue(org.bitcoinj.core.Base58.decodeChecked(p2pkhAddress).contentEquals(byteArrayOf(140.toByte()) + hash))
        assertTrue(org.bitcoinj.core.Base58.decodeChecked(p2shAddress).contentEquals(byteArrayOf(19) + hash))
        assertTrue(p2pkhAddress.startsWith("y")) // testnet P2PKH
        // The same hash on the testnet params the app uses.
        assertEquals(org.bitcoinj.core.Address.fromPubKeyHash(testnet, hash).toString(), p2pkhAddress)

        assertNull(decode(p2pkh.copyOf(24)))
        assertNull(decode(byteArrayOf(0x6a, 0x01, 0x00))) // OP_RETURN
        val wrongOp = p2pkh.copyOf().also { it[23] = 0x87.toByte() }
        assertNull(decode(wrongOp))
        assertNull(decode(ByteArray(0)))
        // Non-minimal pushes of the hash are not the P2PKH / P2SH templates.
        val pushData1 = byteArrayOf(0x76, 0xa9.toByte(), 0x4c, 0x14) + hash + byteArrayOf(0x88.toByte(), 0xac.toByte())
        assertNull(decode(pushData1))
        assertNull(decode(byteArrayOf(0xa9.toByte(), 0x4c, 0x14) + hash + byteArrayOf(0x87.toByte())))
        // An unparseable script (push longer than the script) and a trailing byte.
        assertNull(decode(byteArrayOf(0x76, 0xa9.toByte(), 0x14) + ByteArray(5)))
        assertNull(decode(p2pkh + byteArrayOf(0x00)))
        assertNull(decode(p2sh + byteArrayOf(0x00)))
    }

    @Test
    fun platformAddress_decodesTheStorageEncodingOnly() {
        assertTrue(platformAddressHashOf(byteArrayOf(0x00) + hash)!!.contentEquals(hash))
        assertTrue(platformAddressHashOf(byteArrayOf(0x01) + hash)!!.contentEquals(hash))
        assertNull(platformAddressHashOf(byteArrayOf(0xb0.toByte()) + hash))
        assertNull(platformAddressHashOf(hash))
        assertNull(platformAddressHashOf(ByteArray(43)))
    }

    @Test
    fun counterpartyQuery_perKind() {
        val p2pkh = byteArrayOf(0x76, 0xa9.toByte(), 0x14) + hash + byteArrayOf(0x88.toByte(), 0xac.toByte())
        assertTrue(
            counterpartyQueryFor(entry(ShieldedActivityKindTag.WITHDRAWAL, counterparty = p2pkh), decode)
                is CounterpartyQuery.CoreAddress
        )
        assertEquals(
            CounterpartyQuery.Undecodable,
            counterpartyQueryFor(entry(ShieldedActivityKindTag.WITHDRAWAL, counterparty = ByteArray(43)), decode)
        )
        assertTrue(
            counterpartyQueryFor(entry(ShieldedActivityKindTag.UNSHIELD, counterparty = byteArrayOf(0) + hash), decode)
                is CounterpartyQuery.PlatformAddressHash
        )
        assertEquals(
            CounterpartyQuery.Undecodable,
            counterpartyQueryFor(entry(ShieldedActivityKindTag.UNSHIELD, counterparty = p2pkh), decode)
        )
        assertTrue(
            counterpartyQueryFor(entry(ShieldedActivityKindTag.IDENTITY_TOP_UP, identityId = ByteArray(32)), decode)
                is CounterpartyQuery.Identity
        )
        assertEquals(
            CounterpartyQuery.Undecodable,
            counterpartyQueryFor(entry(ShieldedActivityKindTag.IDENTITY_TOP_UP), decode)
        )
        for (tag in listOf(0, 1, 2, 3, 6, 7, 8)) {
            assertEquals("tag $tag", CounterpartyQuery.NotNeeded, counterpartyQueryFor(entry(tag, counterparty = p2pkh), decode))
        }
        // Address-bearing queries never print the address.
        assertFalse(
            counterpartyQueryFor(entry(ShieldedActivityKindTag.WITHDRAWAL, counterparty = p2pkh), decode)
                .toString().contains(decode(p2pkh)!!)
        )
    }
}
