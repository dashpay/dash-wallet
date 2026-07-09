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

import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.money.Dash
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashfoundation.dashsdk.persistence.entities.ShieldedActivityEntity
import org.dashfoundation.dashsdk.persistence.entities.ShieldedNoteEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the Phase 4 shielded-balances service layer: the
 * flag/lifecycle decision table (inert-when-off, single-flight bring-up,
 * latch/retry), the write orchestration under the [SdkWriteResult]
 * no-double-broadcast contract, and the pure credits/activity/address
 * mapping with fixture data. No native calls; the SDK shielded surface is
 * faked via [ShieldedSource].
 */
class ShieldedBalanceServiceTest {

    private val walletIdHex = "ab".repeat(32)
    private val walletIdBytes = ByteArray(32) { 0xab.toByte() }
    private val hrp = "tdash"
    private val dbPath = "/data/files/shielded_tree_testnet.sqlite"

    /** A valid Orchard recipient for transfer tests. */
    private val recipientRaw43 = ByteArray(43) { (it + 1).toByte() }
    private val recipientAddress: String = encodeOrchardAddress(recipientRaw43, hrp)!!

    private class FakeSource(
        var hasSupport: Boolean = true,
        var boundWalletId: () -> String? = { null },
        var syncRunning: Boolean = false
    ) : ShieldedSource {
        val events = mutableListOf<String>()
        var supportCalls = 0
        var configureCalls = 0
        var bindCalls = 0
        var startCalls = 0
        var stopCalls = 0
        var warmUpCalls = 0
        var broadcastCalls = 0
        var lastDbPath: String? = null
        var lastBoundWallet: ByteArray? = null
        var lastBoundAccounts: List<Int>? = null
        var lastAmountCredits: Long? = null
        var lastRecipientRaw43: ByteArray? = null
        var lastMemo: String? = null
        var lastPlatformTarget: String? = null
        var lastCoreAddress: String? = null
        var lastCoreFeePerByte: Int? = null

        var onConfigure: (String) -> Unit = {}
        var onBind: () -> Unit = {}
        var onStart: () -> Unit = {}
        var onShield: () -> Unit = {}
        var onTransfer: () -> Unit = {}
        var onUnshield: () -> Unit = {}
        var onWithdraw: () -> Unit = {}

        var defaultAddress: ByteArray? = ByteArray(43) { 7 }
        var platformAddress: () -> String? = { "tdash1ownplatformaddress" }

        val notesFlow = MutableStateFlow<List<ShieldedNoteEntity>>(emptyList())
        val activityFlow = MutableStateFlow<List<ShieldedActivityEntity>>(emptyList())

        fun interactions() = supportCalls + configureCalls + bindCalls + startCalls +
            stopCalls + warmUpCalls + broadcastCalls

        override suspend fun hasShieldedSupport(): Boolean {
            supportCalls++
            events += "support"
            return hasSupport
        }

        override suspend fun boundWalletIdOrNull(): String? {
            events += "bound"
            return boundWalletId()
        }

        override suspend fun configureShielded(dbPath: String) {
            configureCalls++
            lastDbPath = dbPath
            events += "configure"
            onConfigure(dbPath)
        }

        override suspend fun bindShielded(walletId: ByteArray, accounts: List<Int>) {
            bindCalls++
            lastBoundWallet = walletId
            lastBoundAccounts = accounts
            events += "bind"
            onBind()
        }

        override suspend fun isShieldedSyncRunning(): Boolean {
            events += "isRunning"
            return syncRunning
        }

        override suspend fun startShieldedSync() {
            startCalls++
            events += "start"
            onStart()
        }

        override suspend fun stopShieldedSync() {
            stopCalls++
            events += "stop"
        }

        override suspend fun warmUpProver() {
            warmUpCalls++
            events += "warmUp"
        }

        override fun observeUnspentNotes(walletId: ByteArray): Flow<List<ShieldedNoteEntity>> = notesFlow

        override fun observeActivity(walletId: ByteArray): Flow<List<ShieldedActivityEntity>> = activityFlow

        override suspend fun shieldedDefaultAddress(walletId: ByteArray): ByteArray? = defaultAddress

        override suspend fun ownPlatformAddressOrNull(walletId: ByteArray): String? = platformAddress()

        override suspend fun shield(walletId: ByteArray, amountCredits: Long) {
            broadcastCalls++
            lastAmountCredits = amountCredits
            onShield()
        }

        override suspend fun transfer(
            walletId: ByteArray,
            recipientRaw43: ByteArray,
            amountCredits: Long,
            memo: String?
        ) {
            broadcastCalls++
            lastRecipientRaw43 = recipientRaw43
            lastAmountCredits = amountCredits
            lastMemo = memo
            onTransfer()
        }

        override suspend fun unshield(walletId: ByteArray, toPlatformAddress: String, amountCredits: Long) {
            broadcastCalls++
            lastPlatformTarget = toPlatformAddress
            lastAmountCredits = amountCredits
            onUnshield()
        }

        override suspend fun withdraw(
            walletId: ByteArray,
            toCoreAddress: String,
            amountCredits: Long,
            coreFeePerByte: Int
        ) {
            broadcastCalls++
            lastCoreAddress = toCoreAddress
            lastAmountCredits = amountCredits
            lastCoreFeePerByte = coreFeePerByte
            onWithdraw()
        }

        // ── shieldFromWallet / staged-retry surface ────────────────────

        var fundCalls = 0
        var resumeCalls = 0
        var lockQueries = 0
        var lastFundRecipient: ByteArray? = null
        var lastFundAmountDuffs: Long? = null
        val resumedOutPoints = mutableListOf<Pair<ByteArray, Int>>()
        var lastResumeRecipient: ByteArray? = null
        var onFund: () -> Unit = {}
        var onResume: (ByteArray, Int) -> Unit = { _, _ -> }
        var shieldLocks: () -> List<PendingWalletShieldLock> = { emptyList() }

        /** 10k duffs of flat shielded fee by default. */
        var feeCredits: () -> Long = { 10_000_000L }

        override suspend fun fundFromAssetLock(
            walletId: ByteArray,
            recipientRaw43: ByteArray,
            amountDuffs: Long
        ) {
            fundCalls++
            broadcastCalls++
            lastFundRecipient = recipientRaw43
            lastFundAmountDuffs = amountDuffs
            onFund()
        }

        override suspend fun resumeFundFromAssetLock(
            walletId: ByteArray,
            outPointTxid: ByteArray,
            outPointVout: Int,
            recipientRaw43: ByteArray
        ) {
            resumeCalls++
            broadcastCalls++
            resumedOutPoints += outPointTxid to outPointVout
            lastResumeRecipient = recipientRaw43
            onResume(outPointTxid, outPointVout)
        }

        override suspend fun walletShieldLocks(walletId: ByteArray): List<PendingWalletShieldLock> {
            lockQueries++
            events += "locks"
            return shieldLocks()
        }

        override suspend fun estimateShieldFeeCredits(): Long = feeCredits()
    }

    private fun config(enabled: Boolean?, l1ShadowEnabled: Boolean = true): DashPayConfig = mockk {
        if (enabled == null) {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } throws
                IllegalStateException("datastore unavailable")
        } else {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } returns enabled
        }
        coEvery { get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns l1ShadowEnabled
    }

    /** A source in the fully-ready state: shielded build, wallet bound. */
    private fun readySource() = FakeSource(
        hasSupport = true,
        boundWalletId = { walletIdHex }
    )

    private val now = 1_000_000_000L

    /** A fresh full-MATCH parity report — the open L1 funding gate. */
    private fun matchParity(ageMs: Long = 0L) = buildParityReport(
        sdkConfirmedDuffs = 100, sdkUnconfirmedDuffs = 0,
        dashjEstimatedDuffs = 100, dashjAvailableDuffs = 100,
        sdkTxCount = 3, dashjTxCount = 3,
        sdkSynced = true, timestampMs = now - ageMs
    )

    private fun service(
        source: FakeSource,
        enabled: Boolean? = true,
        l1ShadowEnabled: Boolean = true,
        parity: () -> ParityReport? = { null }
    ) = ShieldedBalanceServiceImpl(
        source = source,
        dashPayConfig = config(enabled, l1ShadowEnabled),
        shieldedDbPath = { dbPath },
        displayHrp = { hrp },
        l1Parity = parity,
        nowMs = { now }
    )

    // ── Inertness: flag off means NOTHING touches the SDK ─────────────────

    @Test
    fun flagOff_everyEntryPoint_isInert() = runBlocking {
        val source = readySource()
        val service = service(source, enabled = false)

        assertFalse(service.ensureShieldedReady())
        assertEquals(Dash.ZERO, service.observeShieldedBalance().first())
        assertTrue(service.observeShieldedActivity().first().isEmpty())
        assertNull(service.shieldedReceiveAddress())
        assertTrue(service.shieldFromCredits(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertTrue(
            service.transferShielded(recipientAddress, Dash.COIN, null) is SdkWriteResult.NotBroadcast
        )
        assertTrue(service.unshieldToCredits(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertTrue(service.withdrawToCore("XyZ", Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertFalse(service.isWalletShieldingAvailable())
        assertEquals(0, service.resumePendingWalletShields())
        service.stop()

        assertEquals(0, source.interactions())
        assertTrue(source.events.isEmpty())
    }

    @Test
    fun flagReadFailure_isTreatedAsOff() = runBlocking {
        val source = readySource()
        val service = service(source, enabled = null)

        assertFalse(service.ensureShieldedReady())
        assertTrue(service.shieldFromCredits(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.interactions())
    }

    // ── Lifecycle decision table ──────────────────────────────────────────

    @Test
    fun ensureReady_noShieldedSupport_isFalse_withoutConfigureOrBind() = runBlocking {
        val source = FakeSource(hasSupport = false, boundWalletId = { walletIdHex })
        assertFalse(service(source).ensureShieldedReady())
        assertEquals(0, source.configureCalls + source.bindCalls + source.startCalls)
    }

    @Test
    fun ensureReady_walletNotBound_isFalse_withoutConfigureOrBind() = runBlocking {
        val source = FakeSource(hasSupport = true, boundWalletId = { null })
        assertFalse(service(source).ensureShieldedReady())
        assertEquals(0, source.configureCalls + source.bindCalls + source.startCalls)
    }

    @Test
    fun ensureReady_happyPath_configuresBindsStartsWarms_inOrder() = runBlocking {
        val source = readySource()
        assertTrue(service(source).ensureShieldedReady())

        assertEquals(
            listOf("support", "bound", "configure", "bind", "isRunning", "start", "warmUp"),
            source.events
        )
        assertEquals(dbPath, source.lastDbPath)
        assertArrayEquals(walletIdBytes, source.lastBoundWallet)
        assertEquals(listOf(0), source.lastBoundAccounts)
    }

    @Test
    fun ensureReady_syncAlreadyRunning_doesNotStartAgain() = runBlocking {
        val source = readySource().apply { syncRunning = true }
        assertTrue(service(source).ensureShieldedReady())
        assertEquals(0, source.startCalls)
    }

    @Test
    fun ensureReady_secondCall_latches_withoutRepeatingBringUp() = runBlocking {
        val source = readySource()
        val service = service(source)

        assertTrue(service.ensureShieldedReady())
        assertTrue(service.ensureShieldedReady())

        assertEquals(1, source.configureCalls)
        assertEquals(1, source.bindCalls)
        assertEquals(1, source.startCalls)
    }

    @Test
    fun ensureReady_configureFailure_isFalse_andRetrySucceeds() = runBlocking {
        val source = readySource()
        var failNext = true
        source.onConfigure = { if (failNext) throw DashSdkError.InvalidState("db path locked") }
        val service = service(source)

        assertFalse(service.ensureShieldedReady())
        assertEquals(0, source.bindCalls)

        failNext = false
        assertTrue(service.ensureShieldedReady())
        assertEquals(2, source.configureCalls)
        assertEquals(1, source.bindCalls)
        assertEquals(1, source.startCalls)
    }

    @Test
    fun ensureReady_warmUpFailure_doesNotBlockReadiness() = runBlocking {
        val source = readySource()
        var warmUpFailed = false
        val service = ShieldedBalanceServiceImpl(
            source = object : ShieldedSource by source {
                override suspend fun warmUpProver() {
                    warmUpFailed = true
                    throw DashSdkError.InternalError("prover init failed")
                }
            },
            dashPayConfig = config(true),
            shieldedDbPath = { dbPath },
            displayHrp = { hrp }
        )
        assertTrue(service.ensureShieldedReady())
        assertTrue(warmUpFailed)
    }

    @Test
    fun stop_stopsSyncAndDropsLatch_thenRebindWorks() = runBlocking {
        val source = readySource()
        val service = service(source)

        assertTrue(service.ensureShieldedReady())
        service.stop()
        assertEquals(1, source.stopCalls)
        assertEquals(Dash.ZERO, service.observeShieldedBalance().first())

        assertTrue(service.ensureShieldedReady())
        assertEquals(2, source.configureCalls)
        assertEquals(2, source.bindCalls)
    }

    @Test
    fun stop_whenNeverReady_isNoOp() = runBlocking {
        val source = readySource()
        service(source).stop()
        assertEquals(0, source.stopCalls)
    }

    // ── Reads: balance + activity flows ───────────────────────────────────

    @Test
    fun balanceFlow_zeroBeforeReady_thenSumsUnspentNotesFloored() = runBlocking {
        val source = readySource()
        val service = service(source)

        assertEquals(Dash.ZERO, service.observeShieldedBalance().first())

        source.notesFlow.value = listOf(
            note(value = 150_000_000_000L), // 1.5 DASH in credits
            note(value = 1_999L) // sub-duff dust: floors to 1 duff
        )
        assertTrue(service.ensureShieldedReady())

        // 150_000_001_999 credits / 1000 = 150_000_001 duffs.
        assertEquals(Dash(150_000_001L), service.observeShieldedBalance().first())
    }

    @Test
    fun activityFlow_emptyBeforeReady_thenMapsSortsAndFilters() = runBlocking {
        val source = readySource()
        val service = service(source)

        assertTrue(service.observeShieldedActivity().first().isEmpty())

        source.activityFlow.value = listOf(
            activity(entryId = 1, direction = 1, status = 1, amount = 2_000L, createdAtMs = 100L),
            activity(
                entryId = 2, direction = 0, status = 0, amount = 5_000L, createdAtMs = 300L,
                memo = textMemo("thanks!")
            ),
            activity(entryId = 3, direction = 2, status = 1, amount = 1_000L, createdAtMs = 200L),
            // Filtered: failed status.
            activity(entryId = 4, direction = 0, status = 2, amount = 9_000L, createdAtMs = 400L),
            // Filtered: unknown direction tag.
            activity(entryId = 5, direction = 9, status = 1, amount = 9_000L, createdAtMs = 500L)
        )
        assertTrue(service.ensureShieldedReady())

        val entries = service.observeShieldedActivity().first()
        assertEquals(3, entries.size)
        // Newest first.
        assertEquals(listOf(300L, 200L, 100L), entries.map { it.timestampMs })

        val received = entries[0]
        assertEquals(ShieldedActivityDirection.IN, received.direction)
        assertEquals(Dash(5L), received.amount)
        assertEquals("thanks!", received.memo)
        assertTrue(received.pending)
        assertEquals("02" + "00".repeat(31), received.id)

        val internal = entries[1]
        assertEquals(ShieldedActivityDirection.INTERNAL, internal.direction)
        assertFalse(internal.pending)
        assertNull(internal.memo)

        assertEquals(ShieldedActivityDirection.OUT, entries[2].direction)
    }

    // ── Receive address ───────────────────────────────────────────────────

    @Test
    fun receiveAddress_encodesDefaultOrchardAddress_roundTrip() = runBlocking {
        val source = readySource().apply { defaultAddress = recipientRaw43 }
        val address = service(source).shieldedReceiveAddress()

        assertNotNull(address)
        assertTrue(address!!.startsWith("tdash1"))
        assertArrayEquals(recipientRaw43, decodeOrchardAddress(address, hrp))
    }

    @Test
    fun receiveAddress_nullWhenNoBoundShieldedSubWallet() = runBlocking {
        val source = readySource().apply { defaultAddress = null }
        assertNull(service(source).shieldedReceiveAddress())
    }

    @Test
    fun receiveAddress_nullWhenNotReady() = runBlocking {
        val source = FakeSource(hasSupport = true, boundWalletId = { null })
        assertNull(service(source).shieldedReceiveAddress())
    }

    // ── Writes: preflights are NotBroadcast without a broadcast attempt ───

    @Test
    fun writes_nonPositiveAmount_isNotBroadcast_withoutSourceCalls() = runBlocking {
        val source = readySource()
        val service = service(source)

        assertTrue(service.shieldFromCredits(Dash.ZERO) is SdkWriteResult.NotBroadcast)
        assertTrue(service.unshieldToCredits(Dash(-1)) is SdkWriteResult.NotBroadcast)
        assertTrue(
            service.transferShielded(recipientAddress, Dash.ZERO, null) is SdkWriteResult.NotBroadcast
        )
        assertEquals(0, source.interactions())
    }

    @Test
    fun writes_runtimeNotReady_isNotBroadcast_withoutBroadcastAttempt() = runBlocking {
        val source = FakeSource(hasSupport = true, boundWalletId = { null })
        val result = service(source).shieldFromCredits(Dash.COIN)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun write_autoRunsBringUp_whenNotReadyYet() = runBlocking {
        val source = readySource()
        val result = service(source).shieldFromCredits(Dash.COIN)

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(1, source.configureCalls)
        assertEquals(1, source.bindCalls)
        // 1 DASH = 1e8 duffs = 1e11 credits.
        assertEquals(100_000_000_000L, source.lastAmountCredits)
    }

    @Test
    fun transfer_malformedOrWrongNetworkAddress_isNotBroadcast_withoutSourceCalls() = runBlocking {
        val source = readySource()
        val service = service(source)

        // Garbage, a wrong-HRP (mainnet) encoding, and a platform-style
        // payload under the right HRP must all be rejected app-side.
        val mainnetAddress = encodeOrchardAddress(recipientRaw43, "dash")!!
        val platformPayload = Bech32m.encode(hrp, ByteArray(21) { 0xb0.toByte() })!!

        for (bad in listOf("not-an-address", mainnetAddress, platformPayload)) {
            assertTrue(
                service.transferShielded(bad, Dash.COIN, null) is SdkWriteResult.NotBroadcast
            )
        }
        assertEquals(0, source.interactions())
    }

    @Test
    fun transfer_memoOverLimit_isNotBroadcast_withoutSourceCalls() = runBlocking {
        val source = readySource()
        val result = service(source)
            .transferShielded(recipientAddress, Dash.COIN, "x".repeat(MAX_SHIELDED_MEMO_BYTES + 1))

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.interactions())
    }

    @Test
    fun transfer_success_mapsAddressAmountAndMemo() = runBlocking {
        val source = readySource()
        val memo = "m".repeat(MAX_SHIELDED_MEMO_BYTES) // exactly at the limit
        val result = service(source).transferShielded(recipientAddress, Dash(250), " $memo ")

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(1, source.broadcastCalls)
        assertArrayEquals(recipientRaw43, source.lastRecipientRaw43)
        assertEquals(250_000L, source.lastAmountCredits)
        assertEquals(memo, source.lastMemo)
    }

    @Test
    fun transfer_blankMemo_isSentAsNull() = runBlocking {
        val source = readySource()
        assertTrue(
            service(source).transferShielded(recipientAddress, Dash.COIN, "  ")
                is SdkWriteResult.Broadcast
        )
        assertNull(source.lastMemo)
    }

    @Test
    fun unshield_targetsOwnPlatformAddress() = runBlocking {
        val source = readySource().apply { platformAddress = { "tdash1qown" } }
        val result = service(source).unshieldToCredits(Dash(7))

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals("tdash1qown", source.lastPlatformTarget)
        assertEquals(7_000L, source.lastAmountCredits)
    }

    @Test
    fun unshield_noPlatformAddress_isNotBroadcast_withoutBroadcastAttempt() = runBlocking {
        val source = readySource().apply { platformAddress = { null } }
        val result = service(source).unshieldToCredits(Dash.COIN)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun unshield_platformAddressLookupFailure_isNotBroadcast() = runBlocking {
        val source = readySource().apply {
            platformAddress = { throw DashSdkError.InternalError("room read failed") }
        }
        val result = service(source).unshieldToCredits(Dash.COIN)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun withdraw_emptyAddress_isNotBroadcast_withoutSourceCalls() = runBlocking {
        val source = readySource()
        val result = service(source).withdrawToCore("   ", Dash.COIN)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.interactions())
    }

    @Test
    fun withdraw_success_pinsFibonacciCoreFee() = runBlocking {
        val source = readySource()
        val result = service(source).withdrawToCore(" yWithdrawTarget ", Dash(3))

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals("yWithdrawTarget", source.lastCoreAddress)
        assertEquals(3_000L, source.lastAmountCredits)
        assertEquals(1, source.lastCoreFeePerByte)
    }

    // ── Writes: broadcast-failure classification ──────────────────────────

    @Test
    fun spendUnconfirmed_isAmbiguous_neverRetried() = runBlocking {
        val source = readySource().apply {
            onTransfer = { throw DashSdkError.PlatformWallet.ShieldedSpendUnconfirmed("unconfirmed") }
        }
        val result = service(source).transferShielded(recipientAddress, Dash.COIN, null)

        assertTrue(result is SdkWriteResult.Ambiguous)
        assertEquals(1, source.broadcastCalls)
    }

    @Test
    fun definitiveNonExecutionCodes_areNotBroadcast() = runBlocking {
        val outcomes = listOf<() -> Unit>(
            { throw DashSdkError.PlatformWallet.ShieldedBroadcastFailed("relay rejected") },
            { throw DashSdkError.PlatformWallet.ShieldedNoRecordedAnchor("tree mid-block") },
            { throw DashSdkError.InvalidParameter("bad amount") }
        )
        for (outcome in outcomes) {
            val source = readySource().apply { onShield = outcome }
            val result = service(source).shieldFromCredits(Dash.COIN)
            assertTrue(result is SdkWriteResult.NotBroadcast)
            assertEquals(1, source.broadcastCalls)
        }
    }

    @Test
    fun unprovableFailures_areAmbiguous() = runBlocking {
        val outcomes = listOf<() -> Unit>(
            { throw DashSdkError.NetworkError("conn dropped mid-submit") },
            { throw DashSdkError.Timeout("dapi timeout") },
            { throw RuntimeException("JNI blew up") }
        )
        for (outcome in outcomes) {
            val source = readySource().apply { onWithdraw = outcome }
            val result = service(source).withdrawToCore("yTarget", Dash.COIN)
            assertTrue(result is SdkWriteResult.Ambiguous)
            assertEquals(1, source.broadcastCalls)
        }
    }

    @Test
    fun everyOutcome_makesAtMostOneBroadcastAttempt() = runBlocking {
        val outcomes = listOf<() -> Unit>(
            { },
            { throw DashSdkError.InvalidParameter("x") },
            { throw DashSdkError.PlatformWallet.ShieldedSpendUnconfirmed("x") },
            { throw RuntimeException("x") }
        )
        for (outcome in outcomes) {
            val source = readySource().apply { onShield = outcome }
            service(source).shieldFromCredits(Dash.COIN)
            assertEquals(1, source.broadcastCalls)
        }
    }

    // ── Pure mapping: credits ↔ Dash ──────────────────────────────────────

    @Test
    fun creditsToDash_floors() {
        assertEquals(Dash.ZERO, creditsToDash(0))
        assertEquals(Dash.ZERO, creditsToDash(999))
        assertEquals(Dash(1), creditsToDash(1_000))
        assertEquals(Dash(1), creditsToDash(1_999))
        assertEquals(Dash.COIN, creditsToDash(100_000_000_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun creditsToDash_rejectsNegative() {
        creditsToDash(-1)
    }

    @Test
    fun dashToCredits_isExact() {
        assertEquals(1_000L, dashToCredits(Dash(1)))
        assertEquals(100_000_000_000L, dashToCredits(Dash.COIN))
    }

    @Test(expected = ArithmeticException::class)
    fun dashToCredits_throwsOnOverflow() {
        dashToCredits(Dash(Long.MAX_VALUE / 10))
    }

    // ── Pure mapping: memo decoding ───────────────────────────────────────

    @Test
    fun memo_textKind_decodesAndTrimsPadding() {
        assertEquals("hello", decodeShieldedMemo(textMemo("hello")))
        val full = "y".repeat(32)
        assertEquals(full, decodeShieldedMemo(textMemo(full)))
    }

    @Test
    fun memo_nonTextOrMalformed_decodesToNull() {
        assertNull(decodeShieldedMemo(ByteArray(0))) // absent
        assertNull(decodeShieldedMemo(ByteArray(36))) // kind 0 = empty
        assertNull(decodeShieldedMemo(ByteArray(35))) // wrong length
        assertNull(decodeShieldedMemo(textMemo("").also { it[0] = 1 })) // text kind, empty payload
        // Unknown kind round-trips as "no readable memo".
        val unknownKind = textMemo("hidden").also { it[0] = 2 }
        assertNull(decodeShieldedMemo(unknownKind))
        // Text kind with invalid UTF-8.
        val invalid = textMemo("").also { it[0] = 1; it[4] = 0xff.toByte() }
        assertNull(decodeShieldedMemo(invalid))
    }

    // ── Pure mapping: activity entity ─────────────────────────────────────

    @Test
    fun activityEntity_failedOrUnknownDirection_isNotSurfaced() {
        assertNull(toShieldedActivityEntry(activity(entryId = 1, direction = 0, status = 2)))
        assertNull(toShieldedActivityEntry(activity(entryId = 1, direction = 3, status = 1)))
        assertNull(toShieldedActivityEntry(activity(entryId = 1, direction = -1, status = 1)))
    }

    @Test
    fun activityEntity_mapsAllFields() {
        val entry = toShieldedActivityEntry(
            activity(
                entryId = 0x1f, direction = 1, status = 1,
                amount = 42_000L, createdAtMs = 1_234L, memo = textMemo("note")
            )
        )
        assertNotNull(entry)
        assertEquals("1f" + "00".repeat(31), entry!!.id)
        assertEquals(ShieldedActivityDirection.OUT, entry.direction)
        assertEquals(Dash(42), entry.amount)
        assertEquals(1_234L, entry.timestampMs)
        assertEquals("note", entry.memo)
        assertFalse(entry.pending)
    }

    // ── Pure mapping: Orchard address codec ───────────────────────────────

    @Test
    fun orchardAddress_roundTripsThroughBech32m() {
        val encoded = encodeOrchardAddress(recipientRaw43, "dash")
        assertNotNull(encoded)
        assertArrayEquals(recipientRaw43, decodeOrchardAddress(encoded!!, "dash"))
        // Wrong network HRP does not decode.
        assertNull(decodeOrchardAddress(encoded, "tdash"))
    }

    @Test
    fun orchardAddress_rejectsWrongShapes() {
        assertNull(encodeOrchardAddress(ByteArray(42), hrp))
        assertNull(decodeOrchardAddress("garbage", hrp))
        // Right HRP but a 21-byte platform payload.
        assertNull(decodeOrchardAddress(Bech32m.encode(hrp, ByteArray(21) { 0xb0.toByte() })!!, hrp))
        // Right HRP and length but a non-Orchard type byte.
        val wrongType = Bech32m.encode(hrp, ByteArray(44) { if (it == 0) 0x11 else 1 })!!
        assertNull(decodeOrchardAddress(wrongType, hrp))
    }

    // ── shieldFromWallet: the L1 funding gate ─────────────────────────────

    @Test
    fun fundingGate_pure_decisionTable() {
        val fresh = matchParity()
        assertTrue(evaluateWalletFundingGate(fresh, now, 300_000).allowed)

        // No report — shadow not running.
        assertFalse(evaluateWalletFundingGate(null, now, 300_000).allowed)
        // Stale report — probe loop stopped.
        assertFalse(evaluateWalletFundingGate(matchParity(ageMs = 300_001), now, 300_000).allowed)
        // Not synced — MISMATCH-PRESYNC is not evidence.
        assertFalse(
            evaluateWalletFundingGate(fresh.copy(sdkSynced = false), now, 300_000).allowed
        )
        // Any parity mismatch closes the gate.
        assertFalse(
            evaluateWalletFundingGate(fresh.copy(balancesMatch = false), now, 300_000).allowed
        )
        assertFalse(
            evaluateWalletFundingGate(fresh.copy(confirmedBalancesMatch = false), now, 300_000).allowed
        )
        assertFalse(
            evaluateWalletFundingGate(fresh.copy(sdkTxCount = 4), now, 300_000).allowed
        )
    }

    @Test
    fun shieldFromWallet_gateClosed_isNotBroadcast_beforeAnySpend() = runBlocking {
        val source = readySource()
        // No parity report at all — the funds-safe default.
        val service = service(source, parity = { null })

        val result = service.shieldFromWallet(Dash.COIN)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.fundCalls)
        assertFalse(service.isWalletShieldingAvailable())
    }

    @Test
    fun shieldFromWallet_l1FlagOff_isNotBroadcast() = runBlocking {
        val source = readySource()
        val service = service(source, l1ShadowEnabled = false, parity = { matchParity() })

        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.fundCalls)
        assertFalse(service.isWalletShieldingAvailable())
    }

    @Test
    fun shieldFromWallet_belowPoolFee_isNotBroadcast() = runBlocking {
        val source = readySource()
        val service = service(source, parity = { matchParity() })

        // Fee floor: 10k duffs shielded fee + 50k duffs asset-lock base cost.
        assertTrue(service.shieldFromWallet(Dash(60_000)) is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.fundCalls)

        // Just above the floor goes through.
        assertTrue(service.shieldFromWallet(Dash(60_001)) is SdkWriteResult.Broadcast)
        assertEquals(1, source.fundCalls)
    }

    @Test
    fun shieldFromWallet_happyPath_fundsWithDuffsAndOwnAddress() = runBlocking {
        val source = readySource()
        val service = service(source, parity = { matchParity() })

        val result = service.shieldFromWallet(Dash.COIN)

        assertEquals(
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED),
            result
        )
        assertEquals(1, source.fundCalls)
        // L1 duffs, NOT credits — the SDK converts internally.
        assertEquals(Dash.COIN.duffs, source.lastFundAmountDuffs)
        // Shield-to-self: the wallet's own default Orchard address.
        assertArrayEquals(source.defaultAddress, source.lastFundRecipient)
    }

    // ── shieldFromWallet: staged-failure classification ───────────────────

    @Test
    fun shieldFromWallet_failureWithNewTrackedLock_isLockPendingRetry() = runBlocking {
        val source = readySource()
        val service = service(source, parity = { matchParity() })

        // Before the attempt: no locks. After: the lock the failed attempt
        // broadcast (the Rust side tracks it before broadcasting).
        var funded = false
        source.shieldLocks = {
            if (funded) listOf(PendingWalletShieldLock("aa".repeat(32) + ":0", 1, 100_000)) else emptyList()
        }
        source.onFund = { funded = true; throw RuntimeException("transition timed out") }

        val result = service.shieldFromWallet(Dash.COIN)

        assertEquals(
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY),
            result
        )
    }

    @Test
    fun shieldFromWallet_definitiveRejection_noNewLock_isNotBroadcast() = runBlocking {
        val source = readySource()
        val service = service(source, parity = { matchParity() })
        source.onFund = { throw DashSdkError.InvalidParameter("insufficient funds") }

        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun shieldFromWallet_unknownFailure_noNewLock_isAmbiguous() = runBlocking {
        val source = readySource()
        val service = service(source, parity = { matchParity() })
        source.onFund = { throw RuntimeException("connection reset") }

        // The persistence bridge is async: no row is NOT proof of no
        // broadcast — must stay Ambiguous, never NotBroadcast.
        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.Ambiguous)
    }

    @Test
    fun shieldFromWallet_preexistingLock_doesNotMaskDefinitiveRejection() = runBlocking {
        val source = readySource()
        val service = service(source, parity = { matchParity() })

        // A lock from an EARLIER interrupted attempt exists before this
        // call — it must not be misread as this call's evidence.
        source.shieldLocks = { listOf(PendingWalletShieldLock("bb".repeat(32) + ":1", 2, 5_000)) }
        source.onFund = { throw DashSdkError.InvalidParameter("bad params") }

        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun shieldFromWallet_unreadableLockStateBefore_refusesToSpend() = runBlocking {
        val source = readySource()
        val service = service(source, parity = { matchParity() })
        source.shieldLocks = { throw RuntimeException("db closed") }

        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.fundCalls)
    }

    // ── The staged-retry sweep ────────────────────────────────────────────

    @Test
    fun resumeSweep_resumesOnlyUnconsumedLocks_withReversedTxid() = runBlocking {
        val source = readySource()
        val service = service(source, parity = { matchParity() })

        val txidDisplayHex = (1..32).joinToString("") { "%02x".format(it) }
        source.shieldLocks = {
            listOf(
                PendingWalletShieldLock("$txidDisplayHex:2", 1, 100_000), // Broadcast → resumable
                PendingWalletShieldLock("cc".repeat(32) + ":0", 4, 100_000) // Consumed → skipped
            )
        }

        assertEquals(1, service.resumePendingWalletShields())
        assertEquals(1, source.resumeCalls)

        val (txid, vout) = source.resumedOutPoints.single()
        assertEquals(2, vout)
        // Display hex is byte-reversed from the raw wire-order txid.
        assertArrayEquals(ByteArray(32) { (32 - it).toByte() }, txid)
        assertArrayEquals(source.defaultAddress, source.lastResumeRecipient)
    }

    @Test
    fun resumeSweep_isolatesPerLockFailures() = runBlocking {
        val source = readySource()
        val service = service(source, parity = { matchParity() })
        source.shieldLocks = {
            listOf(
                PendingWalletShieldLock("aa".repeat(32) + ":0", 1, 1),
                PendingWalletShieldLock("bb".repeat(32) + ":0", 2, 1)
            )
        }
        source.onResume = { txid, _ ->
            if (txid[0] == 0xaa.toByte()) throw RuntimeException("still no chainlock")
        }

        // The first lock fails, the second still resumes.
        assertEquals(1, service.resumePendingWalletShields())
        assertEquals(2, source.resumeCalls)
    }

    @Test
    fun resumeSweep_capsAttemptsPerOutpointPerProcess() = runBlocking {
        val source = readySource()
        val service = service(source, parity = { matchParity() })
        source.shieldLocks = { listOf(PendingWalletShieldLock("aa".repeat(32) + ":0", 1, 1)) }
        source.onResume = { _, _ -> throw RuntimeException("permanently stuck") }

        repeat(5) { assertEquals(0, service.resumePendingWalletShields()) }

        // 3 attempts max per process, later sweeps skip the outpoint.
        assertEquals(3, source.resumeCalls)
    }

    @Test
    fun resumeSweep_nothingPending_isCheap() = runBlocking {
        val source = readySource()
        val service = service(source, parity = { matchParity() })

        assertEquals(0, service.resumePendingWalletShields())
        assertEquals(1, source.lockQueries)
        assertEquals(0, source.resumeCalls)
    }

    // ── Pure helpers ──────────────────────────────────────────────────────

    @Test
    fun parseOutPointHex_parsesAndReversesToWireOrder() {
        val displayHex = (1..32).joinToString("") { "%02x".format(it) }
        val parsed = parseOutPointHex("$displayHex:7")
        assertNotNull(parsed)
        assertEquals(7, parsed!!.second)
        assertArrayEquals(ByteArray(32) { (32 - it).toByte() }, parsed.first)
    }

    @Test
    fun parseOutPointHex_rejectsMalformedInput() {
        assertNull(parseOutPointHex(""))
        assertNull(parseOutPointHex("aa:0"))
        assertNull(parseOutPointHex("zz".repeat(32) + ":0")) // not hex
        assertNull(parseOutPointHex("aa".repeat(32))) // no vout
        assertNull(parseOutPointHex("aa".repeat(32) + ":x"))
        assertNull(parseOutPointHex("aa".repeat(32) + ":-1"))
    }

    @Test
    fun ceilCreditsToDuffs_roundsUp() {
        assertEquals(0L, ceilCreditsToDuffs(0))
        assertEquals(1L, ceilCreditsToDuffs(1))
        assertEquals(1L, ceilCreditsToDuffs(1_000))
        assertEquals(2L, ceilCreditsToDuffs(1_001))
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private fun note(value: Long, isSpent: Boolean = false) = ShieldedNoteEntity(
        nullifier = ByteArray(32) { value.toByte() },
        walletId = walletIdBytes,
        accountIndex = 0,
        position = 0L,
        cmx = ByteArray(32),
        blockHeight = 1L,
        isSpent = isSpent,
        value = value,
        noteData = ByteArray(0)
    )

    private fun activity(
        entryId: Int,
        direction: Int,
        status: Int,
        amount: Long = 1_000L,
        createdAtMs: Long = 0L,
        memo: ByteArray = ByteArray(0)
    ) = ShieldedActivityEntity(
        walletId = walletIdBytes,
        accountIndex = 0,
        entryId = ByteArray(32).also { it[0] = entryId.toByte() },
        kindTag = 3,
        direction = direction,
        status = status,
        amount = amount,
        fee = 0L,
        hasFee = false,
        blockHeight = 0L,
        hasBlockHeight = false,
        createdAtMs = createdAtMs,
        memo = memo
    )

    /** Build a 36-byte on-chain text memo: LE u32 kind 1 + zero-padded UTF-8. */
    private fun textMemo(text: String): ByteArray {
        val out = ByteArray(36)
        out[0] = 1
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32)
        bytes.copyInto(out, destinationOffset = 4)
        return out
    }
}
