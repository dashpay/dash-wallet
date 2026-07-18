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
    fun syncPlan_processingClearedOnLockButNotOnPlainBlock() {
        val existing = cacheEntry(
            rowId = displayHex(5),
            title = resolve(R.string.transaction_row_status_received),
            statusText = resolve(R.string.transaction_row_status_processing),
            filterFlags = TxDisplayCacheEntry.FLAG_RECEIVED
        )
        val lockedPlan = planL1DisplaySync(
            listOf(record(firstByte = 5, net = 1000, context = 1, direction = 0)),
            mapOf(existing.rowId to existing), emptySet(), resolve, now
        )
        assertEquals(1, lockedPlan.updates.size)
        assertEquals("", lockedPlan.updates.first().statusText)

        // Plain IN_BLOCK (no islock/chainlock): dashj would still show its
        // own confirming logic — leave the row alone.
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

        override fun observeWalletTxRecords(walletIdHex: String): Flow<List<L1TxUiRecord>> {
            recordSubscriptions++
            return records.map { it }
        }
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
        notify: (Long) -> Unit = {}
    ) = CutoverUiDataService(
        source = source,
        dashPayConfig = dashPayConfig,
        scope = scope,
        txDisplayCacheDao = displayDao,
        txGroupCacheDao = groupDao,
        walletUIConfig = walletUIConfig,
        resolveString = resolve,
        notifyCoinsReceived = notify,
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
}
