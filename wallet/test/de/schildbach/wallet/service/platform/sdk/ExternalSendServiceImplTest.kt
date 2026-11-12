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

import de.schildbach.wallet.payments.FundingSelectionLock
import de.schildbach.wallet.payments.SendEngineNotSyncedException
import de.schildbach.wallet.ui.shielded.assetLockMaxFeeReserve
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.Base58
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.common.services.WalletSession
import org.dash.wallet.common.util.retryReadErrors
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashfoundation.dashsdk.credits.FundingInput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSendServiceImplTest {

    private val walletA = "ab".repeat(32)
    private val walletB = "cd".repeat(32)
    private val hash20 = ByteArray(20) { (it + 1).toByte() }
    private val ownId = ByteArray(32) { 3 }
    private val otherId = ByteArray(32) { 9 }
    private val ownIdBase58 = Base58.encode(ownId)
    private val otherIdBase58 = Base58.encode(otherId)

    private val core = SendDestination.Core(Base58.encodeChecked(AddressNetwork.DASH_TESTNET.addressHeader, hash20))
    private val shielded = SendDestination.Shielded(
        requireNotNull(Bech32m.encode("tdash", byteArrayOf(0x10) + ByteArray(43) { 5 }))
    )
    private val platform = SendDestination.Platform(
        requireNotNull(Bech32m.encode("tdash", byteArrayOf(0xb0.toByte()) + hash20))
    )
    private val platformP2sh = SendDestination.Platform(
        requireNotNull(Bech32m.encode("tdash", byteArrayOf(0x80.toByte()) + hash20))
    )
    private val ownIdentity = SendDestination.Identity(ownIdBase58)
    private val otherIdentity = SendDestination.Identity(otherIdBase58)

    /** 0.001 DASH = 100_000 duffs = 100_000_000 credits. */
    private val amount = Dash(100_000L)
    private val amountCredits = 100_000_000L

    private class FakeSource(
        var bound: MutableList<String?>,
        var ownIdentity: String?,
        var managed: List<ByteArray>
    ) : ExternalSendSource {
        val calls = mutableListOf<String>()
        var cutover: () -> Boolean = { true }
        var shieldedOn: () -> Boolean = { true }
        var coreMax: Long? = 5_000_000L
        var coreEligible: Long? = 5_000_000L
        var utxoCount: Int? = 2
        var shieldedBalance: Dash? = Dash(1_000_000L)
        var platformBalances: () -> List<FundingInput> = { emptyList() }
        var shieldedResult: (Dash) -> SdkWriteResult<Unit> = { SdkWriteResult.Broadcast(Unit) }
        var shieldFromCoreResult: (Dash) -> SdkWriteResult<ShieldToAddressOutcome> =
            { SdkWriteResult.Broadcast(ShieldToAddressOutcome(ShieldFromWalletOutcome.COMPLETED, SHIELD_LOCK_TXID)) }
        var coreTopUpResult: () -> SdkWriteResult<TopUpOutcome> =
            { SdkWriteResult.Broadcast(TopUpOutcome(1L, TOP_UP_LOCK_TXID)) }
        var coreSend: () -> String = { "txid" }
        var nativeWrite: suspend () -> Unit = {}
        val shieldedAmounts = mutableListOf<Dash>()
        var lastCoreEmptyWallet: Boolean? = null
        var lastSession: WalletSession? = null
        var lastCredits: Long? = null
        var lastPlatformTo: PlatformAddressBytes? = null
        var lastInputs: List<FundingInput>? = null
        var lastToId: ByteArray? = null
        var lastFromId: ByteArray? = null
        var lastDuffs: Long? = null
        val session = WalletSession(Any())

        private fun nextBound(): String? = if (bound.size > 1) bound.removeAt(0) else bound.firstOrNull()

        override fun currentCoreSession(): WalletSession? = session
        override suspend fun boundWalletIdOrNull(): String? = nextBound()
        override fun currentBoundWalletIdOrNull(): String? = bound.firstOrNull()
        override suspend fun cutoverCommittedOrThrow(): Boolean = cutover()
        override suspend fun shieldedEnabledOrThrow(): Boolean = shieldedOn()
        override suspend fun ownIdentityIdOrThrow(): String? = ownIdentity
        var identityFlow: Flow<Result<String?>>? = null
        override fun observeOwnIdentityId(): Flow<Result<String?>> = identityFlow ?: flowOf(Result.success(ownIdentity))
        override suspend fun managedIdentityIds(walletIdHex: String): List<ByteArray> = managed
        override fun observeCoreMaxSendableDuffs(): Flow<Long?> = flowOf(coreMax)
        override suspend fun coreAssetLockEligibleDuffs(): Long? = coreEligible
        var shieldedFlow: Flow<Dash?>? = null
        override fun observeShieldedBalance(): Flow<Dash?> = shieldedFlow ?: flowOf(shieldedBalance)
        override suspend fun platformAddressBalances(walletIdHex: String): List<FundingInput> = platformBalances()
        var identityBalanceReads = 0
        override suspend fun identityBalanceCredits(identityIdBase58: String): Long? {
            identityBalanceReads++
            return 2_000_000_000L
        }
        override suspend fun identityExists(identityIdBase58: String): Boolean = identityIdBase58 == ownIdentity
        override suspend fun usernameOf(identityIdBase58: String): String? = "alice"
        override suspend fun shieldedFeeCredits(kind: ShieldedFeeKind): Long? = 2_000_500L
        override suspend fun spendableUtxoCount(): Int? = utxoCount

        override suspend fun sendCore(address: String, amount: Dash, emptyWallet: Boolean, session: WalletSession?): String {
            calls += "sendCore"
            lastCoreEmptyWallet = emptyWallet
            lastSession = session
            return coreSend()
        }

        private fun shielded(name: String, amount: Dash): SdkWriteResult<Unit> {
            calls += name
            shieldedAmounts += amount
            return shieldedResult(amount)
        }

        override suspend fun shieldedWithdrawToCore(walletIdHex: String, address: String, amount: Dash) =
            shielded("shieldedWithdrawToCore", amount)
        override suspend fun shieldedTransfer(walletIdHex: String, address: String, amount: Dash) =
            shielded("shieldedTransfer", amount)
        override suspend fun shieldedUnshield(walletIdHex: String, address: String, amount: Dash) =
            shielded("shieldedUnshield", amount)
        override suspend fun shieldedTopUpIdentity(walletIdHex: String, identityId: ByteArray, amount: Dash): SdkWriteResult<Unit> {
            lastToId = identityId
            return shielded("shieldedTopUpIdentity", amount)
        }

        override suspend fun shieldFromCore(
            walletIdHex: String,
            address: String,
            amount: Dash
        ): SdkWriteResult<ShieldToAddressOutcome> {
            calls += "shieldFromCore"
            shieldedAmounts += amount
            return shieldFromCoreResult(amount)
        }

        override suspend fun coreTopUpIdentity(
            identityIdBase58: String,
            amountDuffs: Long
        ): SdkWriteResult<TopUpOutcome> {
            calls += "coreTopUpIdentity"
            lastDuffs = amountDuffs
            return coreTopUpResult()
        }

        override suspend fun platformTransfer(walletIdHex: String, to: PlatformAddressBytes, amountCredits: Long) {
            calls += "platformTransfer"
            lastPlatformTo = to
            lastCredits = amountCredits
            nativeWrite()
        }

        override suspend fun platformTopUpIdentity(walletIdHex: String, identityId: ByteArray, inputs: List<FundingInput>) {
            calls += "platformTopUpIdentity"
            lastToId = identityId
            lastInputs = inputs
            nativeWrite()
        }

        override suspend fun identityWithdraw(walletIdHex: String, fromId: ByteArray, amountCredits: Long, coreAddress: String) {
            calls += "identityWithdraw"
            lastFromId = fromId
            lastCredits = amountCredits
            nativeWrite()
        }

        override suspend fun identityTransferToAddress(
            walletIdHex: String,
            fromId: ByteArray,
            to: PlatformAddressBytes,
            amountCredits: Long
        ) {
            calls += "identityTransferToAddress"
            lastFromId = fromId
            lastPlatformTo = to
            lastCredits = amountCredits
            nativeWrite()
        }

        override suspend fun identityTransfer(walletIdHex: String, fromId: ByteArray, toId: ByteArray, amountCredits: Long) {
            calls += "identityTransfer"
            lastFromId = fromId
            lastToId = toId
            lastCredits = amountCredits
            nativeWrite()
        }

        // Platform → Core, → Shielded (third party)
        var withdrawalPreflight: () -> PlatformWithdrawalPreflight = { PlatformWithdrawalPreflight(true, 400_500_000L) }
        var minInput: () -> Long = { 100_000L }
        var lastCoreAddress: String? = null
        var lastShieldAddress: String? = null

        override suspend fun platformWithdrawalPreflight(walletIdHex: String) = withdrawalPreflight()
        override suspend fun platformMinInputCredits(walletIdHex: String): Long = minInput()

        override suspend fun platformWithdraw(walletIdHex: String, coreAddress: String, inputs: List<FundingInput>) {
            calls += "platformWithdraw"
            lastCoreAddress = coreAddress
            lastInputs = inputs
            nativeWrite()
        }

        override suspend fun platformShieldTo(walletIdHex: String, address: String, amount: Dash): SdkWriteResult<Unit> {
            lastShieldAddress = address
            return shielded("platformShieldTo", amount)
        }

        override suspend fun identityShieldTo(
            walletIdHex: String,
            fromId: ByteArray,
            address: String,
            amount: Dash
        ): SdkWriteResult<Unit> {
            lastFromId = fromId
            lastShieldAddress = address
            return shielded("identityShieldTo", amount)
        }

        // Core → Platform
        var gateOpen: () -> Boolean = { true }
        var freshFundingAllowed = true
        var remainder: PlatformAddressBytes? = PlatformAddressBytes(0, remainderHash)
        var lastRemainderExclude: ByteArray? = null
        var fund: (PendingPlatformFunding, (String) -> Unit) -> Unit = { _, built -> built(OUT_POINT) }
        var resume: (PendingPlatformFunding) -> Unit = {}
        var lockStatus: () -> Int? = { 2 }
        var unconsumedLocks: () -> Boolean = { false }
        var lastExcludedOutPoints: Set<String>? = null
        val funded = mutableListOf<PendingPlatformFunding>()
        val resumed = mutableListOf<PendingPlatformFunding>()

        override fun coreFundingGateOpen(): Boolean = gateOpen()
        override suspend fun <T> withFreshCoreFunding(build: suspend () -> T): T? =
            if (freshFundingAllowed) build() else null

        override suspend fun ownPlatformRemainderAddress(walletIdHex: String, excludeHash: ByteArray): PlatformAddressBytes? {
            lastRemainderExclude = excludeHash
            return remainder
        }

        override suspend fun fundPlatformExternal(record: PendingPlatformFunding, onAssetLockBuilt: (String) -> Unit) {
            calls += "fundPlatformExternal"
            funded += record
            fund(record, onAssetLockBuilt)
        }

        override suspend fun resumePlatformFundingExternal(record: PendingPlatformFunding) {
            calls += "resumePlatformFundingExternal"
            resumed += record
            resume(record)
        }

        override suspend fun trackedAssetLockStatus(walletIdHex: String, outPointHex: String): Int? = lockStatus()
        override suspend fun hasUnconsumedAddressFundingLocks(walletIdHex: String, excludingOutPoints: Set<String>): Boolean {
            lastExcludedOutPoints = excludingOutPoints
            return unconsumedLocks()
        }
    }

    private class FakeStore : PlatformFundingStore {
        var record: PendingPlatformFunding? = null
        var failRead = false
        var failWrite = false
        var failBlocking = false
        val writes = mutableListOf<PendingPlatformFunding>()

        override suspend fun read(): PendingPlatformFunding? {
            if (failRead) throw java.io.IOException("datastore")
            return record
        }

        override suspend fun write(record: PendingPlatformFunding) {
            if (failWrite) throw java.io.IOException("datastore")
            this.record = record
            writes += record
        }

        override fun writeBlocking(record: PendingPlatformFunding) {
            if (failBlocking) throw java.io.IOException("datastore")
            this.record = record
            writes += record
        }

        override suspend fun clear(id: String) {
            if (record?.id == id) record = null
        }

        // Stored encoded, like DashPayConfig, so a "restart" reads them back through decode.
        val unconfirmed = mutableSetOf<String>()
        var failMark = false
        var failReadUnconfirmed = false

        override suspend fun markCompletionUnconfirmed(id: String) {
            if (failMark) throw java.io.IOException("datastore")
            val current = record
            if (current != null && current.id == id && current.outPointHex != null) {
                unconfirmed += current.asCompletionUnconfirmed().encode()
                record = null
            }
        }

        override suspend fun readCompletionUnconfirmed(): List<PendingPlatformFunding> {
            if (failReadUnconfirmed) throw java.io.IOException("datastore")
            return unconfirmed.map(PendingPlatformFunding::decode)
        }
    }

    private fun source(
        bound: List<String?> = listOf(walletA),
        ownIdentity: String? = ownIdBase58,
        managed: List<ByteArray> = listOf(ownId)
    ) = FakeSource(bound.toMutableList(), ownIdentity, managed)

    private fun service(
        source: FakeSource,
        store: PlatformFundingStore = FakeStore(),
        fundingSelection: FundingSelectionLock = FundingSelectionLock()
    ) = ExternalSendServiceImpl(
        source,
        AddressNetwork.DASH_TESTNET,
        store,
        ioDispatcher = Dispatchers.Unconfined,
        fundingSelection = fundingSelection
    )

    /** A send confirmed while [walletA] was bound (the ViewModel's capture). */
    private suspend fun ExternalSendServiceImpl.send(
        source: SendBalance,
        destination: SendDestination,
        amount: Dash,
        isMax: Boolean
    ): ExternalSendResult = send(source, destination, amount, isMax, walletA)

    // ── Each supported pair: right seam, right units ──────────────────

    @Test
    fun coreToCore_sendsThroughTheCoreFunnel_withTheCapturedSession_andMaxAsSendAll() = runBlocking {
        val source = source()
        // A Max is the whole max-sendable balance the user confirmed.
        val result = service(source).send(SendBalance.CORE, core, Dash(5_000_000L), isMax = true)
        assertEquals(ExternalSendResult.Sent("txid"), result)
        assertEquals(listOf("sendCore"), source.calls)
        assertEquals(true, source.lastCoreEmptyWallet)
        assertTrue(source.lastSession === source.session)
    }

    @Test
    fun coreToShielded_completed_isSent_pendingRetry_isInterrupted() = runBlocking {
        val source = source()
        assertEquals(
            ExternalSendResult.Sent(SHIELD_LOCK_TXID),
            service(source).send(SendBalance.CORE, shielded, amount, false)
        )
        // The lock is the amount plus the pool fee (ceil(2_000_500 credits) + 50_000 duffs).
        assertEquals(listOf(amount + Dash(52_001L)), source.shieldedAmounts)

        source.shieldFromCoreResult = {
            SdkWriteResult.Broadcast(ShieldToAddressOutcome(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY))
        }
        assertEquals(
            ExternalSendResult.InterruptedFundsReturnToShielded,
            service(source).send(SendBalance.CORE, shielded, amount, false)
        )
    }

    @Test
    fun coreToShielded_lockTxIdMissingOrMalformed_isStillSent_withoutATxId() = runBlocking {
        for (txId in listOf(null, "", "not-a-txid", SHIELD_LOCK_TXID.dropLast(1), "zz" + SHIELD_LOCK_TXID.drop(2))) {
            val source = source().apply {
                shieldFromCoreResult = {
                    SdkWriteResult.Broadcast(ShieldToAddressOutcome(ShieldFromWalletOutcome.COMPLETED, txId))
                }
            }
            assertEquals(
                "txid $txId",
                ExternalSendResult.Sent(null),
                service(source).send(SendBalance.CORE, shielded, amount, false)
            )
        }
    }

    @Test
    fun coreToOwnIdentity_topsUpInDuffs_reportingTheTopUpLockTxId() = runBlocking {
        val source = source()
        assertEquals(
            ExternalSendResult.Sent(TOP_UP_LOCK_TXID),
            service(source).send(SendBalance.CORE, ownIdentity, amount, false)
        )
        assertEquals(listOf("coreTopUpIdentity"), source.calls)
        assertEquals(100_000L, source.lastDuffs)
    }

    @Test
    fun coreToOwnIdentity_lockTxIdUnknown_isStillSent_andFailuresKeepTheirClass() = runBlocking {
        val source = source().apply { coreTopUpResult = { SdkWriteResult.Broadcast(TopUpOutcome(1L, null)) } }
        assertEquals(ExternalSendResult.Sent(null), service(source).send(SendBalance.CORE, ownIdentity, amount, false))

        source.coreTopUpResult = { SdkWriteResult.Ambiguous(IllegalStateException("unknown")) }
        assertEquals(
            ExternalSendResult.MayHaveGoneThrough,
            service(source).send(SendBalance.CORE, ownIdentity, amount, false)
        )
        source.coreTopUpResult = { SdkWriteResult.NotBroadcast("a top-up is already in progress") }
        assertTrue(service(source).send(SendBalance.CORE, ownIdentity, amount, false) is ExternalSendResult.NotSent)
    }

    @Test
    fun coreToOwnIdentity_anEarlierTopUpCompletedInstead_isNotSent_neverReportedAsThisSend() = runBlocking {
        val source = source().apply {
            coreTopUpResult = {
                SdkWriteResult.Broadcast(TopUpOutcome(1L, TOP_UP_LOCK_TXID, resumedEarlier = true))
            }
        }
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.EARLIER_TOP_UP_COMPLETED),
            service(source).send(SendBalance.CORE, ownIdentity, amount, false)
        )
        assertEquals(listOf("coreTopUpIdentity"), source.calls)
    }

    @Test
    fun shieldedToEachDestination_callsItsShieldedWrite() = runBlocking {
        val expected = mapOf(
            core to "shieldedWithdrawToCore",
            shielded to "shieldedTransfer",
            platform to "shieldedUnshield",
            otherIdentity to "shieldedTopUpIdentity"
        )
        for ((dest, call) in expected) {
            val source = source()
            assertEquals(ExternalSendResult.Sent(null), service(source).send(SendBalance.SHIELDED, dest, amount, false))
            assertEquals(listOf(call), source.calls)
            // To Core the asset unlock's Core fee is withdrawn on top: the recipient gets `amount`.
            val expectedValue = if (dest == core) Dash(amount.duffs + WITHDRAWAL_CORE_FEE_DUFFS) else amount
            assertEquals(listOf(expectedValue), source.shieldedAmounts)
        }
    }

    @Test
    fun shieldedToCoreMax_withdrawsTheMaxItself_andIsAvailableOnlyAboveTheWithdrawalMinimum() = runBlocking {
        val source = source()
        assertEquals(ExternalSendResult.Sent(null), service(source).send(SendBalance.SHIELDED, core, amount, isMax = true))
        assertEquals(listOf(amount), source.shieldedAmounts)

        // Pool fee 2 001 duffs: 3 191 − 2 001 = 1 190 duffs, exactly DPP's gross minimum.
        source.shieldedBalance = Dash(3_191L)
        assertEquals(Dash(1_190L), service(source).maxSendable(SendBalance.SHIELDED, balanceKindOf(core)))
        source.shieldedBalance = Dash(3_190L)
        assertNull(service(source).maxSendable(SendBalance.SHIELDED, balanceKindOf(core)))
        // Other destinations keep the plain positive bound.
        assertEquals(Dash(1_189L), service(source).maxSendable(SendBalance.SHIELDED, balanceKindOf(shielded)))
    }

    @Test
    fun shieldedToIdentity_passesThe32ByteId() = runBlocking {
        val source = source()
        service(source).send(SendBalance.SHIELDED, otherIdentity, amount, false)
        assertArrayEquals(otherId, source.lastToId)
    }

    @Test
    fun platformToPlatform_transfersCredits_toTheDecodedAddress() = runBlocking {
        val source = source()
        assertEquals(ExternalSendResult.Sent(null), service(source).send(SendBalance.PLATFORM, platform, amount, false))
        assertEquals(amountCredits, source.lastCredits)
        assertEquals(0, source.lastPlatformTo?.addressType)
        assertArrayEquals(hash20, source.lastPlatformTo?.hash)
    }

    @Test
    fun platformToIdentity_spendsExactlyTheAmount_fromTheAddresses() = runBlocking {
        val source = source().apply {
            platformBalances = {
                listOf(FundingInput(0, ByteArray(20) { 1 }, 60_000_000L), FundingInput(0, ByteArray(20) { 2 }, 70_000_000L))
            }
        }
        assertEquals(ExternalSendResult.Sent(null), service(source).send(SendBalance.PLATFORM, otherIdentity, amount, false))
        assertEquals(amountCredits, source.lastInputs?.sumOf { it.credits })
        assertArrayEquals(otherId, source.lastToId)
    }

    @Test
    fun platformToIdentity_cannotCover_isInsufficient_noCall() = runBlocking {
        val source = source().apply { platformBalances = { listOf(FundingInput(0, ByteArray(20) { 1 }, 1_000L)) } }
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.INSUFFICIENT_FUNDS),
            service(source).send(SendBalance.PLATFORM, otherIdentity, amount, false)
        )
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun identitySource_callsEachCreditsWrite_fromTheOwnIdentity() = runBlocking {
        val expected = mapOf(core to "identityWithdraw", platform to "identityTransferToAddress", otherIdentity to "identityTransfer")
        for ((dest, call) in expected) {
            val source = source()
            assertEquals(ExternalSendResult.Sent(null), service(source).send(SendBalance.IDENTITY, dest, amount, false))
            assertEquals(listOf(call), source.calls)
            assertArrayEquals(ownId, source.lastFromId)
            // A withdrawal to Core withdraws the Core fee on top: the recipient gets `amount`.
            val expectedCredits = if (dest == core) amountCredits + WITHDRAWAL_CORE_FEE_CREDITS else amountCredits
            assertEquals(expectedCredits, source.lastCredits)
        }
    }

    @Test
    fun identityToCore_outsideTheWithdrawalBounds_isNotSent_withNoCall() = runBlocking {
        // 999 duffs + the 190-duff Core fee is below 1 000 000 + 190 000 credits.
        for (outside in listOf(Dash(999L), Dash(PLATFORM_MAX_WITHDRAWAL_CREDITS / 1_000L))) {
            val source = source()
            assertEquals(
                ExternalSendResult.NotSent(NotSentReason.GENERIC),
                service(source).send(SendBalance.IDENTITY, core, outside, false)
            )
            assertTrue(source.calls.isEmpty())
        }
        val source = source()
        assertEquals(ExternalSendResult.Sent(null), service(source).send(SendBalance.IDENTITY, core, Dash(1_000L), false))
        assertEquals(PLATFORM_MIN_WITHDRAWAL_CREDITS, source.lastCredits)
    }

    @Test
    fun coreWithdrawalCredits_addsTheCoreFee_andBoundsTheGrossAmount() {
        assertEquals(190L, WITHDRAWAL_CORE_FEE_DUFFS)
        assertEquals(190_000L, WITHDRAWAL_CORE_FEE_CREDITS)
        assertEquals(amountCredits + 190_000L, coreWithdrawalCreditsOrNull(amountCredits))
        assertEquals(PLATFORM_MIN_WITHDRAWAL_CREDITS, coreWithdrawalCreditsOrNull(1_000_000L))
        assertNull(coreWithdrawalCreditsOrNull(999_999L))
        assertEquals(
            PLATFORM_MAX_WITHDRAWAL_CREDITS,
            coreWithdrawalCreditsOrNull(PLATFORM_MAX_WITHDRAWAL_CREDITS - WITHDRAWAL_CORE_FEE_CREDITS)
        )
        assertNull(coreWithdrawalCreditsOrNull(PLATFORM_MAX_WITHDRAWAL_CREDITS - WITHDRAWAL_CORE_FEE_CREDITS + 1))
        assertNull(coreWithdrawalCreditsOrNull(Long.MAX_VALUE))
    }

    // ── int37 pairs: Platform → Core, Platform / Identity → Shielded ──

    /** A P2PKH Platform-address balance whose 20-byte hash is all [b]. */
    private fun address(b: Int, credits: Long) = FundingInput(0, ByteArray(20) { b.toByte() }, credits)

    @Test
    fun platformToCore_withdrawsExactlyTheAmount_feePayerKeepsTheReserve() = runBlocking {
        val reserve = 400_500_000L
        val source = source().apply {
            // Hash 0x01 sorts first: it pays the fee, so it must keep the reserve.
            platformBalances = { listOf(address(1, 450_000_000L), address(2, 80_000_000L)) }
        }
        assertEquals(ExternalSendResult.Sent(null), service(source).send(SendBalance.PLATFORM, core, amount, false))
        assertEquals(listOf("platformWithdraw"), source.calls)
        assertEquals(core.address, source.lastCoreAddress)
        val inputs = requireNotNull(source.lastInputs)
        // The amount plus the Core fee DPP takes out of the withdrawal: the recipient gets `amount`.
        assertEquals(amountCredits + WITHDRAWAL_CORE_FEE_CREDITS, inputs.sumOf { it.credits })
        val payer = inputs.first { it.hash[0] == 1.toByte() }
        assertTrue(450_000_000L - payer.credits >= reserve)
        assertTrue(inputs.all { input -> input.credits <= (if (input.hash[0] == 1.toByte()) 450_000_000L else 80_000_000L) })
    }

    @Test
    fun platformToCore_refusals_beforeAnythingIsSigned_areNotSent_withNoCall() = runBlocking {
        val funded = listOf(address(1, 900_000_000L))
        val cases = listOf<Pair<(FakeSource) -> Unit, ExternalSendResult>>(
            // Cannot cover amount + the fee payer's reserve.
            { s: FakeSource -> s.platformBalances = { listOf(address(1, 450_000_000L)) } } to
                ExternalSendResult.NotSent(NotSentReason.INSUFFICIENT_FUNDS),
            { s: FakeSource ->
                s.platformBalances = { funded }
                s.withdrawalPreflight = { PlatformWithdrawalPreflight(false, 0L) }
            } to ExternalSendResult.NotSent(NotSentReason.INSUFFICIENT_FUNDS),
            // A zero fee estimate is unknown, never free.
            { s: FakeSource ->
                s.platformBalances = { funded }
                s.withdrawalPreflight = { PlatformWithdrawalPreflight(true, 0L) }
            } to ExternalSendResult.NotSent(NotSentReason.GENERIC),
            { s: FakeSource ->
                s.platformBalances = { funded }
                s.withdrawalPreflight = { throw java.io.IOException("dapi") }
            } to ExternalSendResult.NotSent(NotSentReason.GENERIC),
            { s: FakeSource ->
                s.platformBalances = { funded }
                s.minInput = { throw IllegalStateException("SDK wallet not loaded") }
            } to ExternalSendResult.NotSent(NotSentReason.GENERIC),
            { s: FakeSource -> s.platformBalances = { throw IllegalStateException("SDK wallet not loaded") } } to
                ExternalSendResult.NotSent(NotSentReason.GENERIC)
        )
        for ((setUp, expected) in cases) {
            val source = source().apply(setUp)
            assertEquals(expected, service(source).send(SendBalance.PLATFORM, core, amount, false))
            assertTrue(source.calls.isEmpty())
        }
    }

    @Test
    fun platformToCore_belowTheWithdrawalMinimum_isNotSent_withNoRead() = runBlocking {
        var read = false
        val source = source().apply {
            platformBalances = {
                read = true
                listOf(address(1, 900_000_000L))
            }
        }
        // 999 duffs + the 190-duff Core fee withdrawn on top = 1 189 000 credits < 1 000 000 + 190 × 1 000.
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.GENERIC),
            service(source).send(SendBalance.PLATFORM, core, Dash(999L), false)
        )
        // Above 500 DASH gross (500 DASH + the Core fee).
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.GENERIC),
            service(source).send(SendBalance.PLATFORM, core, Dash(PLATFORM_MAX_WITHDRAWAL_CREDITS / 1_000L), false)
        )
        assertTrue(source.calls.isEmpty())
        assertTrue(!read)
        // Exactly at the minimum (gross) is sent.
        assertEquals(ExternalSendResult.Sent(null), service(source).send(SendBalance.PLATFORM, core, Dash(1_000L), false))
        assertEquals(PLATFORM_MIN_WITHDRAWAL_CREDITS, source.lastInputs?.sumOf { it.credits })
    }

    @Test
    fun platformToCore_outcomes_mapLikeTheOtherCreditsWrites() = runBlocking {
        val source = source().apply {
            platformBalances = { listOf(address(1, 900_000_000L)) }
            nativeWrite = { throw RuntimeException("connection reset") }
        }
        assertEquals(ExternalSendResult.MayHaveGoneThrough, service(source).send(SendBalance.PLATFORM, core, amount, false))

        source.nativeWrite = { throw DashSdkError.PlatformWallet.TransactionBroadcastUnconfirmed("unconfirmed") }
        assertEquals(ExternalSendResult.MayHaveGoneThrough, service(source).send(SendBalance.PLATFORM, core, amount, false))

        source.nativeWrite = { throw DashSdkError.InvalidParameter("bad address") }
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.GENERIC),
            service(source).send(SendBalance.PLATFORM, core, amount, false)
        )
    }

    @Test
    fun platformAndIdentityToShielded_callTheirShieldWrite_withTheRecipient() = runBlocking {
        val platformSource = source()
        assertEquals(ExternalSendResult.Sent(null), service(platformSource).send(SendBalance.PLATFORM, shielded, amount, false))
        assertEquals(listOf("platformShieldTo"), platformSource.calls)
        assertEquals(shielded.address, platformSource.lastShieldAddress)
        assertEquals(listOf(amount), platformSource.shieldedAmounts)

        val identitySource = source()
        assertEquals(ExternalSendResult.Sent(null), service(identitySource).send(SendBalance.IDENTITY, shielded, amount, false))
        assertEquals(listOf("identityShieldTo"), identitySource.calls)
        assertEquals(shielded.address, identitySource.lastShieldAddress)
        assertArrayEquals(ownId, identitySource.lastFromId)
        assertEquals(listOf(amount), identitySource.shieldedAmounts)
    }

    @Test
    fun toShielded_outcomes_ambiguousIsMayHaveGoneThrough_notBroadcastIsNotSent_neverRetried() = runBlocking {
        for (from in listOf(SendBalance.PLATFORM, SendBalance.IDENTITY)) {
            val ambiguous = source().apply {
                shieldedResult = {
                    SdkWriteResult.Ambiguous(DashSdkError.PlatformWallet.ShieldedSpendUnconfirmed("unconfirmed"))
                }
            }
            // Even a Max is never retried for these sources.
            assertEquals(ExternalSendResult.MayHaveGoneThrough, service(ambiguous).send(from, shielded, amount, true))
            assertEquals(1, ambiguous.shieldedAmounts.size)

            val notBroadcast = source().apply {
                shieldedResult = {
                    SdkWriteResult.NotBroadcast(
                        "pre-broadcast",
                        RuntimeException("Insufficient shielded balance: available 1, required 2")
                    )
                }
            }
            assertEquals(
                ExternalSendResult.NotSent(NotSentReason.INSUFFICIENT_FUNDS),
                service(notBroadcast).send(from, shielded, amount, true)
            )
            assertEquals(1, notBroadcast.shieldedAmounts.size)
        }
        val debitPending = source().apply {
            shieldedResult = { classifyBroadcastFailure(DashSdkError.PlatformWallet.ShieldedIdentityDebitPending("pending")) }
        }
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.PAYMENT_STILL_CONFIRMING),
            service(debitPending).send(SendBalance.IDENTITY, shielded, amount, false)
        )
    }

    @Test
    fun toShielded_gates_shieldedFlagOff_orWalletChanged_callNothing() = runBlocking {
        for (from in listOf(SendBalance.PLATFORM, SendBalance.IDENTITY)) {
            val flagOff = source().apply { shieldedOn = { false } }
            assertEquals(ExternalSendResult.NotSent(NotSentReason.NOT_SUPPORTED), service(flagOff).send(from, shielded, amount, false))
            assertTrue(flagOff.calls.isEmpty())

            val changed = source(bound = listOf(walletA, walletB))
            assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), service(changed).send(from, shielded, amount, false))
            assertTrue(changed.calls.isEmpty())
        }
        val changed = source(bound = listOf(walletA, walletB)).apply { platformBalances = { listOf(address(1, 900_000_000L)) } }
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), service(changed).send(SendBalance.PLATFORM, core, amount, false))
        assertTrue(changed.calls.isEmpty())
    }

    @Test
    fun int37Pairs_haveNoPreSendFee_andNoMax() = runBlocking {
        val service = service(source().apply { platformBalances = { listOf(address(1, 900_000_000L)) } })
        for ((from, to) in listOf(SendBalance.PLATFORM to core, SendBalance.PLATFORM to shielded, SendBalance.IDENTITY to shielded)) {
            assertNull("$from → $to", service.estimateFee(from, balanceKindOf(to)))
            assertNull("$from → $to", service.maxSendable(from, balanceKindOf(to)))
        }
    }

    // ── selectPlatformWithdrawalInputs ────────────────────────────────

    @Test
    fun withdrawalSelection_singleInput_whenThePayerCoversAmountAndReserve() {
        val inputs = requireNotNull(
            selectPlatformWithdrawalInputs(listOf(address(5, 1_000L), address(9, 300L)), 600L, 400L, 10L)
        )
        assertEquals(1, inputs.size)
        assertEquals(600L, inputs.single().credits)
        assertEquals(5.toByte(), inputs.single().hash[0])
    }

    @Test
    fun withdrawalSelection_theFirstSortingInputPays_andKeepsTheReserve_othersMayBeDrained() {
        // Neither address covers 1 000 + reserve alone. Hash 0x01 sorts first, so it pays;
        // 0x02 is drained in full.
        val inputs = requireNotNull(
            selectPlatformWithdrawalInputs(listOf(address(2, 600L), address(1, 900L)), 1_000L, 400L, 10L)
        )
        assertEquals(1_000L, inputs.sumOf { it.credits })
        val payer = inputs.first()
        assertEquals(1.toByte(), payer.hash[0])
        assertTrue(900L - payer.credits >= 400L)
        assertEquals(600L, inputs.first { it.hash[0] == 2.toByte() }.credits)
    }

    @Test
    fun withdrawalSelection_unsignedHashOrder_decidesThePayer() {
        // 0x80 sorts AFTER 0x01 unsigned (signed it would sort before). Only 0x01 can keep the
        // reserve, so it must be the first-sorting input of the set.
        val inputs = requireNotNull(
            selectPlatformWithdrawalInputs(listOf(address(0x80, 500L), address(1, 700L)), 700L, 400L, 10L)
        )
        assertEquals(1.toByte(), inputs.first().hash[0])
        assertTrue(700L - inputs.first().credits >= 400L)
        assertEquals(700L, inputs.sumOf { it.credits })
    }

    @Test
    fun withdrawalSelection_refuses_whatItCannotCoverExactly() {
        // Not enough once the payer keeps its reserve.
        assertNull(selectPlatformWithdrawalInputs(listOf(address(1, 1_000L)), 700L, 400L, 10L))
        // Below the minimum input, below the minimum per input, P2SH only, non-positive target.
        assertNull(selectPlatformWithdrawalInputs(listOf(address(1, 1_000L)), 5L, 400L, 10L))
        assertNull(selectPlatformWithdrawalInputs(listOf(address(1, 1_000L), address(2, 9L)), 605L, 400L, 10L))
        assertNull(selectPlatformWithdrawalInputs(listOf(FundingInput(1, ByteArray(20) { 1 }, 1_000L)), 100L, 0L, 10L))
        assertNull(selectPlatformWithdrawalInputs(listOf(address(1, 1_000L)), 0L, 0L, 10L))
        // At most maxInputs inputs.
        val many = (1..5).map { address(it, 100L) }
        assertNull(selectPlatformWithdrawalInputs(many, 450L, 0L, 10L, maxInputs = 4))
        assertEquals(450L, selectPlatformWithdrawalInputs(many, 450L, 0L, 10L, maxInputs = 5)?.sumOf { it.credits })
    }

    @Test
    fun withdrawalSelection_neverTakesMoreThanABalance_andEveryInputClearsTheMinimum() {
        val candidates = listOf(address(3, 250L), address(1, 520L), address(2, 120L), address(4, 75L))
        val balances = candidates.associate { it.hash[0] to it.credits }
        for (target in 10L..560L step 7L) {
            val inputs = selectPlatformWithdrawalInputs(candidates, target, 100L, 20L) ?: continue
            assertEquals(target, inputs.sumOf { it.credits })
            assertTrue(inputs.all { it.credits >= 20L && it.credits <= requireNotNull(balances[it.hash[0]]) })
            val payer = inputs.minWith { a, b -> (a.hash[0].toInt() and 0xff) - (b.hash[0].toInt() and 0xff) }
            assertTrue(requireNotNull(balances[payer.hash[0]]) - payer.credits >= 100L)
            assertTrue(inputs.first() === payer)
        }
    }

    // ── Outcome classification ────────────────────────────────────────

    @Test
    fun notBroadcast_isNotSent_withAMappedReason() = runBlocking {
        val source = source().apply {
            shieldedResult = { SdkWriteResult.NotBroadcast("shielded runtime not ready") }
        }
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.NOT_SYNCED),
            service(source).send(SendBalance.SHIELDED, core, amount, false)
        )
    }

    @Test
    fun ambiguous_isMayHaveGoneThrough() = runBlocking {
        val source = source().apply { shieldedResult = { SdkWriteResult.Ambiguous(RuntimeException("timeout")) } }
        assertEquals(ExternalSendResult.MayHaveGoneThrough, service(source).send(SendBalance.SHIELDED, core, amount, false))
    }

    @Test
    fun nativeWrite_unknownThrow_isMayHaveGoneThrough_preBroadcastThrow_isNotSent() = runBlocking {
        val source = source().apply { nativeWrite = { throw RuntimeException("connection reset") } }
        assertEquals(ExternalSendResult.MayHaveGoneThrough, service(source).send(SendBalance.PLATFORM, platform, amount, false))

        source.nativeWrite = { throw org.dashfoundation.dashsdk.errors.DashSdkError.InvalidParameter("bad") }
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.GENERIC),
            service(source).send(SendBalance.PLATFORM, platform, amount, false)
        )
    }

    @Test
    fun coreSend_pendingSubmission_isMayHaveGoneThrough_notSynced_isNotSent() = runBlocking {
        val source = source().apply { coreSend = { throw PaymentSubmissionPendingException("unknown", null) } }
        assertEquals(ExternalSendResult.MayHaveGoneThrough, service(source).send(SendBalance.CORE, core, amount, false))

        source.coreSend = { throw SendEngineNotSyncedException("gate closed") }
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.NOT_SYNCED),
            service(source).send(SendBalance.CORE, core, amount, false)
        )
    }

    @Test
    fun shieldedMax_shortByTheFee_retriesOnceWithTheDeficit() = runBlocking {
        val source = source().apply {
            shieldedResult = { value ->
                if (value == amount) {
                    SdkWriteResult.NotBroadcast(
                        "pre-broadcast",
                        RuntimeException("Insufficient shielded balance: available 100000000, required 100500000")
                    )
                } else {
                    SdkWriteResult.Broadcast(Unit)
                }
            }
        }
        assertEquals(ExternalSendResult.Sent(null), service(source).send(SendBalance.SHIELDED, core, amount, isMax = true))
        assertEquals(listOf(amount, Dash(99_500L)), source.shieldedAmounts)
    }

    @Test
    fun shieldedNotMax_shortByTheFee_doesNotRetry() = runBlocking {
        val source = source().apply {
            shieldedResult = {
                SdkWriteResult.NotBroadcast(
                    "pre-broadcast",
                    RuntimeException("Insufficient shielded balance: available 100000000, required 100500000")
                )
            }
        }
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.INSUFFICIENT_FUNDS),
            service(source).send(SendBalance.SHIELDED, core, amount, isMax = false)
        )
        assertEquals(1, source.shieldedAmounts.size)
    }

    // ── Gates: nothing is called ──────────────────────────────────────

    @Test
    fun unsupportedPairs_areNotSupported_withNoSeamCall() = runBlocking {
        val pairs = listOf(
            // The SDK funds P2PKH Platform addresses only.
            SendBalance.CORE to platformP2sh,
            SendBalance.IDENTITY to ownIdentity,
            // Someone else's identity from Core: the resume gate cannot protect it.
            SendBalance.CORE to otherIdentity
        )
        for ((from, to) in pairs) {
            val source = source()
            assertEquals(
                "$from → $to",
                ExternalSendResult.NotSent(NotSentReason.NOT_SUPPORTED),
                service(source).send(from, to, amount, false)
            )
            assertTrue("$from → $to", source.calls.isEmpty())
        }
    }

    @Test
    fun identitySource_withoutAnIdentity_isNotSupported() = runBlocking {
        val source = source(ownIdentity = null)
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.NOT_SUPPORTED),
            service(source).send(SendBalance.IDENTITY, core, amount, false)
        )
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun walletChangedBetweenCaptureAndSubmit_isNotSent_withNoNativeCall() = runBlocking {
        for ((from, to) in listOf(
            SendBalance.CORE to core,
            SendBalance.CORE to shielded,
            SendBalance.CORE to platform,
            SendBalance.SHIELDED to core,
            SendBalance.PLATFORM to platform,
            SendBalance.IDENTITY to core
        )) {
            // Captured A; the re-check right before the write reads B.
            val source = source(bound = listOf(walletA, walletB))
            assertEquals("$from → $to", ExternalSendResult.NotSent(NotSentReason.GENERIC), service(source).send(from, to, amount, false))
            assertTrue("$from → $to", source.calls.isEmpty())
        }
    }

    @Test
    fun cutoverUnreadable_orNotCommitted_isNotSent_withNoCall() = runBlocking {
        val unreadable = source().apply { cutover = { throw java.io.IOException("datastore") } }
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), service(unreadable).send(SendBalance.CORE, core, amount, false))
        assertTrue(unreadable.calls.isEmpty())

        val notCommitted = source().apply { cutover = { false } }
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.NOT_SUPPORTED),
            service(notCommitted).send(SendBalance.CORE, core, amount, false)
        )
        assertTrue(notCommitted.calls.isEmpty())
    }

    @Test
    fun shieldedFlagOff_blocksEveryShieldedLeg() = runBlocking {
        for ((from, to) in listOf(SendBalance.SHIELDED to core, SendBalance.CORE to shielded)) {
            val source = source().apply { shieldedOn = { false } }
            assertEquals(ExternalSendResult.NotSent(NotSentReason.NOT_SUPPORTED), service(source).send(from, to, amount, false))
            assertTrue(source.calls.isEmpty())
        }
    }

    @Test
    fun wrongNetworkDestination_isRefused_withNoCall() = runBlocking {
        val source = source()
        val mainnetPlatform = SendDestination.Platform(
            requireNotNull(Bech32m.encode("dash", byteArrayOf(0xb0.toByte()) + hash20))
        )
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.GENERIC),
            service(source).send(SendBalance.PLATFORM, mainnetPlatform, amount, false)
        )
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun concurrentSend_isInProgress() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = source()
        source.shieldedResult = { SdkWriteResult.Broadcast(Unit) }
        val service = service(source)
        val slow = object : ExternalSendSource by source {
            override suspend fun shieldedTransfer(walletIdHex: String, address: String, amount: Dash): SdkWriteResult<Unit> {
                entered.complete(Unit)
                release.await()
                return SdkWriteResult.Broadcast(Unit)
            }
        }
        val gated = ExternalSendServiceImpl(
            slow,
            AddressNetwork.DASH_TESTNET,
            FakeStore(),
            ioDispatcher = Dispatchers.Unconfined,
            fundingSelection = FundingSelectionLock()
        )
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                gated.send(SendBalance.SHIELDED, shielded, amount, false)
            }
            entered.await()
            assertEquals(
                ExternalSendResult.NotSent(NotSentReason.IN_PROGRESS),
                gated.send(SendBalance.SHIELDED, core, amount, false)
            )
            release.complete(Unit)
            assertEquals(ExternalSendResult.Sent(null), first.await())
        } finally {
            release.complete(Unit)
        }
        // The other instance is unaffected.
        assertEquals(ExternalSendResult.Sent(null), service.send(SendBalance.SHIELDED, core, amount, false))
    }

    // ── Reads ─────────────────────────────────────────────────────────

    @Test
    fun unknownBalances_areNull_neverZero() = runBlocking {
        val source = source().apply {
            coreMax = null
            shieldedBalance = null
            platformBalances = { throw IllegalStateException("SDK wallet not loaded") }
        }
        val service = service(source)
        assertNull(service.observeBalance(SendBalance.CORE).first())
        assertNull(service.observeBalance(SendBalance.SHIELDED).first())
        assertNull(service.observeBalance(SendBalance.PLATFORM).first())
        assertNull(service(source(ownIdentity = null)).observeBalance(SendBalance.IDENTITY).first())
        assertNull(service.maxSendable(SendBalance.SHIELDED, balanceKindOf(core)))
    }

    @Test
    fun knownBalances_areConvertedWithTheRightUnits() = runBlocking {
        val source = source().apply { platformBalances = { listOf(FundingInput(0, hash20, 1_500_999L)) } }
        val service = service(source)
        assertEquals(Dash(5_000_000L), service.observeBalance(SendBalance.CORE).first())
        // Credits floor to duffs.
        assertEquals(Dash(1_500L), service.observeBalance(SendBalance.PLATFORM).first())
        assertEquals(Dash(2_000_000L), service.observeBalance(SendBalance.IDENTITY).first())
        // Shielded max: balance − ceil(2_000_500 credits) = 1_000_000 − 2_001 duffs.
        assertEquals(Dash(997_999L), service.maxSendable(SendBalance.SHIELDED, balanceKindOf(core)))
        assertNull(service.maxSendable(SendBalance.PLATFORM, balanceKindOf(platform)))
    }

    @Test
    fun fees_areNullWhereNoPreSendFigureExists() = runBlocking {
        val service = service(source())
        // The size-based reserve over the spendable UTXO count (2).
        assertEquals(FeeEstimate(Dash(sendAllFeeReserveDuffs(2)), exact = false), service.estimateFee(SendBalance.CORE, balanceKindOf(core)))
        // Shielded → Core: the pool fee plus the asset unlock's Core fee withdrawn on top.
        assertEquals(FeeEstimate(Dash(2_191L), exact = false), service.estimateFee(SendBalance.SHIELDED, balanceKindOf(core)))
        assertEquals(FeeEstimate(Dash(2_001L), exact = false), service.estimateFee(SendBalance.SHIELDED, balanceKindOf(platform)))
        assertNull(service.estimateFee(SendBalance.PLATFORM, balanceKindOf(platform)))
        assertNull(service.estimateFee(SendBalance.IDENTITY, balanceKindOf(core)))
        assertNull(service.estimateFee(SendBalance.CORE, balanceKindOf(ownIdentity)))
    }

    @Test
    fun identityLookups() = runBlocking {
        val service = service(source())
        assertEquals(IdentityResolution.Found("alice"), service.resolveIdentity(ownIdBase58))
        assertEquals(IdentityResolution.NotFound, service.resolveIdentity(otherIdBase58))
        assertEquals(true, service.isOwnIdentity(ownIdBase58))
        assertEquals(false, service.isOwnIdentity(otherIdBase58))
        assertEquals(true, service.observeHasIdentity().first())
        assertEquals(false, service(source(ownIdentity = null)).observeHasIdentity().first())
    }

    // ── Review round 1 regressions ────────────────────────────────────

    @Test
    fun coreToShielded_theRecipientReceivesTheConfirmedAmount_feeAndMaxAreConsistent() = runBlocking {
        val source = source()
        val service = service(source)
        val poolFee = Dash(52_001L)
        // The fee shown is exactly what the lock adds on top of the amount.
        assertEquals(FeeEstimate(poolFee, exact = false), service.estimateFee(SendBalance.CORE, balanceKindOf(shielded)))
        // Max: the eligible balance less the L1 reserve is locked; the recipient receives it
        // minus the pool fee.
        val max = requireNotNull(service.maxSendable(SendBalance.CORE, balanceKindOf(shielded)))
        assertEquals(Dash(5_000_000L) - assetLockMaxFeeReserve(2) - poolFee, max)
        assertEquals(
            ExternalSendResult.Sent(SHIELD_LOCK_TXID),
            service.send(SendBalance.CORE, shielded, max, isMax = true)
        )
        assertEquals(listOf(Dash(5_000_000L) - assetLockMaxFeeReserve(2)), source.shieldedAmounts)
    }

    @Test
    fun coreToShieldedMax_comesFromTheAssetLockEligibleBalance_notThePooledCoreFigure() = runBlocking {
        val poolFee = Dash(52_001L)
        // Unconfirmed / receival-account funds inflate the pooled figure; Max ignores them.
        val source = source().apply {
            coreMax = 9_000_000L
            coreEligible = 3_000_000L
        }
        val service = service(source)
        assertEquals(
            Dash(3_000_000L) - assetLockMaxFeeReserve(2) - poolFee,
            service.maxSendable(SendBalance.CORE, balanceKindOf(shielded))
        )
        // Fail closed: an unknown eligible balance or UTXO count is no Max.
        source.coreEligible = null
        assertNull(service.maxSendable(SendBalance.CORE, balanceKindOf(shielded)))
        source.coreEligible = 3_000_000L
        source.utxoCount = null
        assertNull(service.maxSendable(SendBalance.CORE, balanceKindOf(shielded)))
        // Too small to cover the reserve and the pool fee: no Max, never negative.
        source.utxoCount = 2
        source.coreEligible = assetLockMaxFeeReserve(2).duffs + poolFee.duffs
        assertNull(service.maxSendable(SendBalance.CORE, balanceKindOf(shielded)))
    }

    @Test
    fun coreToShieldedMax_selectionShort_isNotSent_withoutARetry() = runBlocking {
        val source = source().apply {
            shieldFromCoreResult = { SdkWriteResult.NotBroadcast("asset lock coin selection is short") }
        }
        val service = service(source)
        val max = requireNotNull(service.maxSendable(SendBalance.CORE, balanceKindOf(shielded)))
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.INSUFFICIENT_FUNDS),
            service.send(SendBalance.CORE, shielded, max, isMax = true)
        )
        assertEquals(1, source.shieldedAmounts.size)
    }

    @Test
    fun coreToCoreMax_tinyBalance_isUnavailable_soTheRecipientAmountIsNeverZeroOrNegative() = runBlocking {
        val source = source()
        val service = service(source)
        val reserve = sendAllFeeReserveDuffs(2)
        source.coreMax = reserve
        assertNull(service.maxSendable(SendBalance.CORE, balanceKindOf(core)))
        source.coreMax = 1L
        assertNull(service.maxSendable(SendBalance.CORE, balanceKindOf(core)))
        // Just above the reserve: Max is the gross figure, and amount − fee stays positive.
        source.coreMax = reserve + 1
        val max = requireNotNull(service.maxSendable(SendBalance.CORE, balanceKindOf(core)))
        val fee = requireNotNull(service.estimateFee(SendBalance.CORE, balanceKindOf(core))).fee
        assertEquals(Dash(reserve + 1), max)
        assertTrue((max - fee).isPositive)
        // Unknown UTXO count: the flat reserve applies to both the fee and the Max gate.
        source.utxoCount = null
        source.coreMax = SEND_ALL_FEE_RESERVE_DUFFS
        assertNull(service.maxSendable(SendBalance.CORE, balanceKindOf(core)))
        assertEquals(
            FeeEstimate(Dash(SEND_ALL_FEE_RESERVE_DUFFS), exact = false),
            service.estimateFee(SendBalance.CORE, balanceKindOf(core))
        )
    }

    @Test
    fun coreToShielded_poolFeeUnknown_isNotSent_withNoCall() = runBlocking {
        val source = object : ExternalSendSource by source() {
            override suspend fun shieldedFeeCredits(kind: ShieldedFeeKind): Long? = null
        }
        val result = ExternalSendServiceImpl(
            source,
            AddressNetwork.DASH_TESTNET,
            FakeStore(),
            ioDispatcher = Dispatchers.Unconfined,
            fundingSelection = FundingSelectionLock()
        )
            .send(SendBalance.CORE, shielded, amount, false, walletA)
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), result)
    }

    @Test
    fun coreMax_drainsOnlyWhileTheBalanceIsWhatTheUserConfirmed() = runBlocking {
        // Confirmed 1 DASH as Max; 5 DASH more arrived meanwhile: nothing is drained.
        val source = source().apply { coreMax = 600_000_000L }
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.BALANCE_CHANGED),
            service(source).send(SendBalance.CORE, core, Dash(100_000_000L), isMax = true)
        )
        assertTrue(source.calls.isEmpty())

        source.coreMax = null
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.BALANCE_CHANGED),
            service(source).send(SendBalance.CORE, core, Dash(100_000_000L), isMax = true)
        )
        assertTrue(source.calls.isEmpty())

        // A non-Max send of the same amount is not a drain and is not gated on it.
        source.coreMax = 600_000_000L
        assertEquals(ExternalSendResult.Sent("txid"), service(source).send(SendBalance.CORE, core, amount, isMax = false))
        assertEquals(false, source.lastCoreEmptyWallet)
    }

    @Test
    fun theWalletCapturedAtConfirmation_mustBeTheBoundOne_beforeAnything() = runBlocking {
        val pairs = listOf(
            SendBalance.CORE to core,
            SendBalance.CORE to shielded,
            SendBalance.CORE to ownIdentity,
            SendBalance.CORE to platform,
            SendBalance.SHIELDED to core,
            SendBalance.PLATFORM to platform,
            SendBalance.PLATFORM to otherIdentity,
            SendBalance.IDENTITY to core
        )
        for ((from, to) in pairs) {
            // Confirmed with wallet A; B is bound by the time the send runs (and stays bound).
            val replaced = source(bound = listOf(walletB))
            assertEquals(
                "$from → $to",
                ExternalSendResult.NotSent(NotSentReason.GENERIC),
                service(replaced).send(from, to, amount, false, walletA)
            )
            assertTrue("$from → $to", replaced.calls.isEmpty())

            val none = source()
            assertEquals(
                "$from → $to",
                ExternalSendResult.NotSent(NotSentReason.GENERIC),
                service(none).send(from, to, amount, false, null)
            )
            assertTrue("$from → $to", none.calls.isEmpty())
        }
        assertEquals(walletA, service(source()).currentWalletIdOrNull())
    }

    @Test
    fun observations_recoverAfterATransientReadError() = runBlocking {
        var identityReads = 0
        var shieldedReads = 0
        val source = source().apply {
            identityFlow = flow {
                if (identityReads++ == 0) throw java.io.IOException("datastore")
                emit(Result.success(ownIdBase58))
            }
            shieldedFlow = flow {
                if (shieldedReads++ == 0) throw java.io.IOException("datastore")
                emit(Dash(7L))
            }
        }
        val service = ExternalSendServiceImpl(
            source,
            AddressNetwork.DASH_TESTNET,
            FakeStore(),
            ioDispatcher = Dispatchers.Unconfined,
            retryDelayMs = { 0L },
            fundingSelection = FundingSelectionLock()
        )
        assertEquals(listOf(null, true), service.observeHasIdentity().take(2).toList())
        assertEquals(listOf(null, Dash(7L)), service.observeBalance(SendBalance.SHIELDED).take(2).toList())
    }

    @Test
    fun identityBalance_aReEmittedSameIdentity_doesNotRestartThePoll() = runBlocking {
        val reEmitted = CompletableDeferred<Unit>()
        val source = source().apply {
            identityFlow = flow {
                // An unrelated config write re-emits the unchanged identity record.
                repeat(3) {
                    emit(Result.success(ownIdBase58))
                    delay(50)
                }
                reEmitted.complete(Unit)
                awaitCancellation()
            }
        }
        val service = ExternalSendServiceImpl(
            source,
            AddressNetwork.DASH_TESTNET,
            FakeStore(),
            ioDispatcher = Dispatchers.Unconfined,
            balancePollMs = 60_000L,
            fundingSelection = FundingSelectionLock()
        )
        val values = mutableListOf<Dash?>()
        val job = launch { service.observeBalance(SendBalance.IDENTITY).collect { values += it } }
        reEmitted.await()
        job.cancelAndJoin()
        assertEquals(listOf<Dash?>(Dash(2_000_000L)), values)
        assertEquals(1, source.identityBalanceReads)
    }

    @Test
    fun retryReadErrors_emitsUnknown_thenResubscribes_andNeverCompletesOnAnError() = runBlocking {
        var reads = 0
        val values = flow {
            reads++
            if (reads <= 2) throw IllegalStateException("unknown creation state")
            emit(Result.success<String?>("id"))
        }.retryReadErrors(
            Result.failure(IllegalStateException("unknown")),
            retryDelayMs = { 0L },
            onError = logReadRetry("identity record") { 0L }
        )
            .take(3)
            .toList()
        assertTrue(values[0].isFailure)
        assertTrue(values[1].isFailure)
        assertEquals("id", values[2].getOrNull())
    }
    // ── Core → Platform (fundFromAssetLockExternal) ───────────────────

    private fun pendingRecord(outPointHex: String? = OUT_POINT, wallet: String = walletA) = PendingPlatformFunding(
        id = "earlier",
        walletIdHex = wallet,
        lockDuffs = 162_000L,
        destination = PlatformAddressBytes(0, hash20),
        explicitCredits = amountCredits,
        remainder = PlatformAddressBytes(0, remainderHash),
        outPointHex = outPointHex
    )

    @Test
    fun coreToPlatform_paysTheExactAmount_remainderToAnOwnAddress_lockCarriesTheMargin() = runBlocking {
        val source = source()
        val store = FakeStore()
        var persistedBeforeTheCall: PendingPlatformFunding? = null
        var outPointDurableInTheCallback: String? = null
        source.fund = { record, built ->
            persistedBeforeTheCall = store.record
            assertTrue(requireNotNull(persistedBeforeTheCall).sameAs(record))
            built(OUT_POINT)
            outPointDurableInTheCallback = store.record?.outPointHex
        }
        val result = service(source, store).send(SendBalance.CORE, platform, amount, false)

        // The lock's display txid: the outpoint without its vout.
        assertEquals(ExternalSendResult.Sent(OUT_POINT.substringBefore(':')), result)
        assertEquals(listOf("fundPlatformExternal"), source.calls)
        val record = source.funded.single()
        // Persisted before the call, without an outpoint; the outpoint was durable before the callback returned.
        assertNull(requireNotNull(persistedBeforeTheCall).outPointHex)
        assertEquals(OUT_POINT, outPointDurableInTheCallback)
        // The payee gets exactly the amount; the lock adds the Platform-fee margin.
        assertEquals(100_000L + PLATFORM_FUNDING_FEE_MARGIN_DUFFS, record.lockDuffs)
        assertEquals(walletA, record.walletIdHex)
        assertEquals(
            listOf(
                org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet.FundRecipient(0, hash20, amountCredits),
                org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet.FundRecipient(0, remainderHash, null)
            ),
            record.recipients
        )
        // The remainder is never the payee's address.
        assertArrayEquals(hash20, source.lastRemainderExclude)
        // Success clears the record.
        assertNull(store.record)
    }

    @Test
    fun coreToPlatform_seedsThePlatformKindForTheBuiltLock_inTheCallback() = runBlocking {
        val seeds = mutableListOf<Pair<String, AssetLockKind>>()
        var seededInTheCallback = false
        val source = source().apply {
            fund = { _, built ->
                built(OUT_POINT)
                seededInTheCallback = seeds.isNotEmpty()
            }
        }
        val service = ExternalSendServiceImpl(
            source,
            AddressNetwork.DASH_TESTNET,
            FakeStore(),
            ioDispatcher = Dispatchers.Unconfined,
            seedAssetLockKind = { hex, kind -> seeds += hex to kind },
            fundingSelection = FundingSelectionLock()
        )
        assertEquals(ExternalSendResult.Sent(LOCK_TXID), service.send(SendBalance.CORE, platform, amount, false))
        // Keyed by the lock's display txid (the outpoint without its vout), before broadcast.
        assertEquals(listOf(OUT_POINT.substringBefore(':') to AssetLockKind.PLATFORM), seeds)
        assertTrue(seededInTheCallback)
    }

    @Test
    fun coreToPlatform_aFailingKindSeed_neverFailsTheSend() = runBlocking {
        val source = source().apply { fund = { _, built -> built(OUT_POINT) } }
        val store = FakeStore()
        val service = ExternalSendServiceImpl(
            source,
            AddressNetwork.DASH_TESTNET,
            store,
            ioDispatcher = Dispatchers.Unconfined,
            seedAssetLockKind = { _, _ -> throw IllegalStateException("seed") },
            fundingSelection = FundingSelectionLock()
        )
        assertEquals(ExternalSendResult.Sent(LOCK_TXID), service.send(SendBalance.CORE, platform, amount, false))
        assertNull(store.record)
    }

    @Test
    fun coreToPlatform_malformedOutPoint_isStillSent_withoutATxId() = runBlocking {
        for (outPoint in listOf("garbage", ":1", "0123:1", "zz" + OUT_POINT.drop(2))) {
            val source = source().apply { fund = { _, built -> built(outPoint) } }
            val store = FakeStore()
            assertEquals(
                "outpoint $outPoint",
                ExternalSendResult.Sent(null),
                service(source, store).send(SendBalance.CORE, platform, amount, false)
            )
            assertNull(store.record)
        }
    }

    @Test
    fun coreToPlatform_p2shDestination_isNotSent_beforeAnythingIsBuiltOrPersisted() = runBlocking {
        val source = source()
        val store = FakeStore()
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.NOT_SUPPORTED),
            service(source, store).send(SendBalance.CORE, platformP2sh, amount, false)
        )
        assertTrue(source.calls.isEmpty())
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun coreToPlatform_failureAfterTheLockWasBuilt_isNeverNotSent_andKeepsTheRecord() = runBlocking {
        // A pre-broadcast-looking error AFTER onAssetLockBuilt: the lock exists all the same.
        for (error in listOf(RuntimeException("no private key stored"), RuntimeException("platform timeout"))) {
            val source = source().apply {
                fund = { _, built ->
                    built(OUT_POINT)
                    throw error
                }
            }
            val store = FakeStore()
            assertEquals(ExternalSendResult.MayHaveGoneThrough, service(source, store).send(SendBalance.CORE, platform, amount, false))
            val kept = requireNotNull(store.record)
            assertEquals(OUT_POINT, kept.outPointHex)
            assertTrue(kept.sameAs(source.funded.single().withOutPoint(OUT_POINT)))
        }
    }

    @Test
    fun coreToPlatform_outPointWriteFailedInTheCallback_isRecordedAfterTheCall() = runBlocking {
        val source = source().apply {
            fund = { _, built ->
                built(OUT_POINT)
                throw RuntimeException("platform timeout")
            }
        }
        val store = FakeStore().apply { failBlocking = true }
        assertEquals(ExternalSendResult.MayHaveGoneThrough, service(source, store).send(SendBalance.CORE, platform, amount, false))
        assertEquals(OUT_POINT, store.record?.outPointHex)
    }

    @Test
    fun coreToPlatform_failureBeforeTheLockWasBuilt_isNotSent_andClearsTheRecord() = runBlocking {
        val source = source().apply { fund = { _, _ -> throw RuntimeException("no private key stored") } }
        val store = FakeStore()
        val result = service(source, store).send(SendBalance.CORE, platform, amount, false)
        assertTrue(result is ExternalSendResult.NotSent)
        assertEquals(1, store.writes.size)
        assertNull(store.record)
    }

    @Test
    fun coreToPlatform_ambiguousBeforeTheLockWasBuilt_isMayHaveGoneThrough_andKeepsTheRecordForTheSweep() = runBlocking {
        val source = source().apply { fund = { _, _ -> throw RuntimeException("platform timeout") } }
        val store = FakeStore()
        assertEquals(ExternalSendResult.MayHaveGoneThrough, service(source, store).send(SendBalance.CORE, platform, amount, false))
        assertNull(requireNotNull(store.record).outPointHex)
    }

    @Test
    fun coreToPlatform_aPendingRecord_blocksTheNextSend_withNothingBuilt() = runBlocking {
        val source = source()
        val store = FakeStore().apply { record = pendingRecord() }
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.PAYMENT_STILL_CONFIRMING),
            service(source, store).send(SendBalance.CORE, platform, amount, false)
        )
        assertTrue(source.calls.isEmpty())
        assertEquals("earlier", store.record?.id)

        // Unreadable (or malformed) is not "none": fail closed.
        val unreadable = FakeStore().apply { failRead = true }
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.GENERIC),
            service(source, unreadable).send(SendBalance.CORE, platform, amount, false)
        )
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun coreToPlatform_refusals_beforeTheCall_persistNothingOrClearIt() = runBlocking {
        // The recipients cannot be persisted: nothing is built.
        var source = source()
        var store = FakeStore().apply { failWrite = true }
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), service(source, store).send(SendBalance.CORE, platform, amount, false))
        assertTrue(source.calls.isEmpty())

        // L1 funding gate closed.
        source = source().apply { gateOpen = { false } }
        store = FakeStore()
        assertEquals(ExternalSendResult.NotSent(NotSentReason.NOT_SYNCED), service(source, store).send(SendBalance.CORE, platform, amount, false))
        assertTrue(source.calls.isEmpty())
        assertTrue(store.writes.isEmpty())

        // No own Platform address for the remainder.
        source = source().apply { remainder = null }
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), service(source, store).send(SendBalance.CORE, platform, amount, false))
        assertTrue(source.calls.isEmpty())
        assertTrue(store.writes.isEmpty())

        // Below Platform's minimum output (500 000 credits = 500 duffs).
        source = source()
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.GENERIC),
            service(source, store).send(SendBalance.CORE, platform, Dash(499L), false)
        )
        assertTrue(source.calls.isEmpty())
        assertTrue(store.writes.isEmpty())

        // An unprotected earlier payment blocks the fresh build: the record is cleared again.
        source = source().apply { freshFundingAllowed = false }
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.PAYMENT_STILL_CONFIRMING),
            service(source, store).send(SendBalance.CORE, platform, amount, false)
        )
        assertTrue(source.calls.isEmpty())
        assertEquals(1, store.writes.size)
        assertNull(store.record)
    }

    @Test
    fun coreToPlatform_feeAndMax_carryTheL1ReserveAndThePlatformMargin() = runBlocking {
        val source = source()
        val service = service(source)
        val fee = Dash(assetLockMaxFeeReserve(2).duffs + PLATFORM_FUNDING_FEE_MARGIN_DUFFS)
        assertEquals(FeeEstimate(fee, exact = false), service.estimateFee(SendBalance.CORE, balanceKindOf(platform)))
        assertEquals(Dash(5_000_000L) - fee, service.maxSendable(SendBalance.CORE, balanceKindOf(platform)))

        // Unknown figures are no Max / no fee, never zero.
        source.coreEligible = null
        assertNull(service.maxSendable(SendBalance.CORE, balanceKindOf(platform)))
        source.coreEligible = 5_000_000L
        source.utxoCount = null
        assertNull(service.maxSendable(SendBalance.CORE, balanceKindOf(platform)))
        assertNull(service.estimateFee(SendBalance.CORE, balanceKindOf(platform)))
        // Too small to cover the reserve and the margin (or Platform's minimum output): no Max.
        source.utxoCount = 2
        source.coreEligible = fee.duffs
        assertNull(service.maxSendable(SendBalance.CORE, balanceKindOf(platform)))
        source.coreEligible = fee.duffs + 499L
        assertNull(service.maxSendable(SendBalance.CORE, balanceKindOf(platform)))
        source.coreEligible = fee.duffs + 500L
        assertEquals(Dash(500L), service.maxSendable(SendBalance.CORE, balanceKindOf(platform)))
    }

    @Test
    fun resume_usesExactlyThePersistedRecipients_andClearsOnSuccess() = runBlocking {
        val source = source()
        val pending = pendingRecord()
        val store = FakeStore().apply { record = pending }
        val service = service(source, store)
        assertEquals(1, service.resumePendingPlatformFunding())
        assertEquals(listOf("resumePlatformFundingExternal"), source.calls)
        assertTrue(source.resumed.single().sameAs(pending))
        assertEquals(pending.recipients, source.resumed.single().recipients)
        assertNull(store.record)
        // Nothing left: the next sweep does nothing.
        assertEquals(0, service.resumePendingPlatformFunding())
        assertEquals(1, source.resumed.size)
    }

    @Test
    fun resume_consumedLock_clearsTheRecord() = runBlocking {
        val source = source().apply { lockStatus = { ASSET_LOCK_STATUS_CONSUMED } }
        val store = FakeStore().apply { record = pendingRecord() }
        assertEquals(0, service(source, store).resumePendingPlatformFunding())
        assertTrue(source.calls.isEmpty())
        assertNull(store.record)
    }

    @Test
    fun resume_alreadyConsumedAnswer_keepsTheRecordAsCompletionUnconfirmed_neverResumedAgain_andStopsBlocking() = runBlocking {
        val source = source().apply {
            resume = { throw DashSdkError.PlatformWallet.AssetLockAlreadyConsumed("already consumed") }
        }
        val store = FakeStore().apply { record = pendingRecord() }
        val service = service(source, store)
        assertEquals(0, service.resumePendingPlatformFunding())
        assertEquals(0, service.resumePendingPlatformFunding())
        assertEquals(1, source.resumed.size)
        // Kept, marked, out of the pending slot: never deleted for an unauthenticated answer.
        assertNull(store.record)
        val kept = store.readCompletionUnconfirmed().single()
        assertTrue(kept.completionUnconfirmed)
        assertTrue(kept.sameAs(pendingRecord().asCompletionUnconfirmed()))

        // A later process (new service instance, same store) does not resume it either.
        val restarted = service(source, store)
        assertEquals(0, restarted.resumePendingPlatformFunding())
        assertEquals(1, source.resumed.size)

        // The next Core → Platform send is not refused; its own record is the pending one.
        assertEquals(ExternalSendResult.Sent(LOCK_TXID), restarted.send(SendBalance.CORE, platform, amount, false))
        assertEquals(1, source.funded.size)
        assertEquals(1, store.readCompletionUnconfirmed().size)
    }

    @Test
    fun resume_alreadyConsumedAnswer_thatCannotBeMarked_staysPendingAndBlocking() = runBlocking {
        val source = source().apply {
            resume = { throw DashSdkError.PlatformWallet.AssetLockAlreadyConsumed("already consumed") }
        }
        val store = FakeStore().apply {
            record = pendingRecord()
            failMark = true
        }
        val service = service(source, store)
        assertEquals(0, service.resumePendingPlatformFunding())
        assertEquals(0, service.resumePendingPlatformFunding())
        assertEquals(1, source.resumed.size)
        assertEquals("earlier", store.record?.id)
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.PAYMENT_STILL_CONFIRMING),
            service.send(SendBalance.CORE, platform, amount, false)
        )
        assertTrue(source.funded.isEmpty())
    }

    @Test
    fun resume_withoutAnOutPoint_ignoresOnlyTheCompletionUnconfirmedLocks_ofThisWallet() = runBlocking {
        val otherOutPoint = "fe".repeat(32) + ":0"
        val store = FakeStore().apply {
            record = pendingRecord(outPointHex = null)
            unconfirmed += pendingRecord().asCompletionUnconfirmed().encode()
            unconfirmed += PendingPlatformFunding(
                "other-wallet", walletB, 162_000L, PlatformAddressBytes(0, hash20), amountCredits,
                PlatformAddressBytes(0, remainderHash), otherOutPoint, completionUnconfirmed = true
            ).encode()
        }
        // Another (genuinely open) funding lock still keeps the record.
        var source = source().apply { unconsumedLocks = { true } }
        assertEquals(0, service(source, store).resumePendingPlatformFunding())
        assertEquals(setOf(OUT_POINT), source.lastExcludedOutPoints)
        assertEquals("earlier", store.record?.id)

        source = source().apply { unconsumedLocks = { false } }
        assertEquals(0, service(source, store).resumePendingPlatformFunding())
        assertNull(store.record)

        // Their set unreadable: fail closed, the record is kept and the SDK is not asked.
        store.record = pendingRecord(outPointHex = null)
        store.failReadUnconfirmed = true
        source = source().apply { unconsumedLocks = { false } }
        assertEquals(0, service(source, store).resumePendingPlatformFunding())
        assertNull(source.lastExcludedOutPoints)
        assertEquals("earlier", store.record?.id)
    }

    @Test
    fun resume_unconfirmedOutcome_keepsTheRecord_untilALaterResumeProvesIt() = runBlocking {
        // dashpay/platform#5556: Tenderdash's "tx already exists in cache" for the identical
        // transition no longer surfaces as "Object already exists"; the SDK waits for the
        // proved result and, when that fails, reports the outcome as unconfirmed.
        val unconfirmed = listOf<Throwable>(
            DashSdkError.PlatformWallet.TransactionBroadcastUnconfirmed(
                "state transition outcome is unconfirmed; reconcile the original operation " +
                    "before submitting a replacement: Object already exists: tx already exists in cache"
            ),
            DashSdkError.StateTransitionResultUnconfirmed("wait timeout"),
            RuntimeException("wrapped", DashSdkError.StateTransitionResultUnconfirmed("wait timeout"))
        )
        for (error in unconfirmed) {
            var fail = true
            val source = source().apply {
                resume = { if (fail) throw error }
            }
            val store = FakeStore().apply { record = pendingRecord() }
            val service = service(source, store)
            assertEquals(0, service.resumePendingPlatformFunding())
            assertEquals("earlier", store.record?.id)
            // Kept, so a new Core → Platform send is still refused, with nothing built.
            assertEquals(
                ExternalSendResult.NotSent(NotSentReason.PAYMENT_STILL_CONFIRMING),
                service.send(SendBalance.CORE, platform, amount, false)
            )
            assertTrue(source.funded.isEmpty())

            // The next sweep resubmits the same transition; its proved success clears it.
            fail = false
            assertEquals(1, service.resumePendingPlatformFunding())
            assertEquals(2, source.resumed.size)
            assertTrue(source.resumed.all { it.sameAs(pendingRecord()) })
            assertNull(store.record)
            assertEquals(ExternalSendResult.Sent(LOCK_TXID), service.send(SendBalance.CORE, platform, amount, false))
        }
    }

    @Test
    fun coreToPlatform_unconfirmedOutcome_isMayHaveGoneThrough_neverNotSent_andKeepsTheRecord() = runBlocking {
        // The typed error carries an arbitrary SDK diagnostic; a pre-broadcast-looking one must
        // not turn it into NotSent, whether or not the lock callback has fired.
        val diagnostics = listOf("no private key stored", "Insufficient funds", "Invalid identity data")
        for (built in listOf(false, true)) {
            for (diagnostic in diagnostics) {
                val source = source().apply {
                    fund = { _, onBuilt ->
                        if (built) onBuilt(OUT_POINT)
                        throw DashSdkError.StateTransitionResultUnconfirmed(diagnostic)
                    }
                }
                val store = FakeStore()
                assertEquals(
                    ExternalSendResult.MayHaveGoneThrough,
                    service(source, store).send(SendBalance.CORE, platform, amount, false)
                )
                val kept = requireNotNull(store.record)
                assertEquals(if (built) OUT_POINT else null, kept.outPointHex)
            }
        }
    }

    @Test
    fun resume_failure_keepsTheRecord_andAttemptsAreCappedPerProcess() = runBlocking {
        val source = source().apply { resume = { throw RuntimeException("platform timeout") } }
        val store = FakeStore().apply { record = pendingRecord() }
        val service = service(source, store)
        repeat(ExternalSendServiceImpl.MAX_PLATFORM_RESUME_ATTEMPTS_PER_PROCESS + 2) {
            assertEquals(0, service.resumePendingPlatformFunding())
        }
        assertEquals(ExternalSendServiceImpl.MAX_PLATFORM_RESUME_ATTEMPTS_PER_PROCESS, source.resumed.size)
        assertEquals("earlier", store.record?.id)
    }

    @Test
    fun resume_withoutAnOutPoint_clearsOnlyWhenNoFundingLockIsOpen() = runBlocking {
        var source = source().apply { unconsumedLocks = { true } }
        val store = FakeStore().apply { record = pendingRecord(outPointHex = null) }
        assertEquals(0, service(source, store).resumePendingPlatformFunding())
        assertTrue(source.calls.isEmpty())
        assertEquals("earlier", store.record?.id)

        source = source().apply { unconsumedLocks = { false } }
        assertEquals(0, service(source, store).resumePendingPlatformFunding())
        assertTrue(source.calls.isEmpty())
        assertNull(store.record)
    }

    @Test
    fun resume_keepsTheRecord_forAnotherWallet_anUntrackedLock_orAnUnreadableStatus() = runBlocking {
        for (setup in listOf<(FakeSource) -> Unit>(
            { it.bound = mutableListOf(walletB) },
            { it.lockStatus = { null } },
            { it.lockStatus = { throw java.io.IOException("room") } },
            { it.cutover = { false } }
        )) {
            val source = source().also(setup)
            val store = FakeStore().apply { record = pendingRecord() }
            assertEquals(0, service(source, store).resumePendingPlatformFunding())
            assertTrue(source.calls.isEmpty())
            assertEquals("earlier", store.record?.id)
        }
    }

    @Test
    fun resume_doesNotRunAlongsideASend() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = source().apply {
            fund = { _, built ->
                built(OUT_POINT)
                runBlocking {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        val store = FakeStore()
        val service = service(source, store)
        try {
            val send = async(Dispatchers.Default) { service.send(SendBalance.CORE, platform, amount, false) }
            entered.await()
            // The send owns its record (outpoint recorded, lock not done): the sweep keeps off it.
            assertEquals(0, service.resumePendingPlatformFunding())
            assertTrue(source.resumed.isEmpty())
            release.complete(Unit)
            assertEquals(ExternalSendResult.Sent(LOCK_TXID), send.await())
        } finally {
            release.complete(Unit)
        }
        assertNull(store.record)
    }

    // ── Reset Wallet: the wipe barrier ───────────────────────────────

    /** Every SDK write route except Core → Core (its funnel holds the barrier itself). */
    private val barrierRoutes: List<Pair<SendBalance, SendDestination>>
        get() = listOf(
            SendBalance.CORE to shielded,
            SendBalance.CORE to ownIdentity,
            SendBalance.CORE to platform,
            SendBalance.SHIELDED to core,
            SendBalance.SHIELDED to shielded,
            SendBalance.SHIELDED to platform,
            SendBalance.SHIELDED to otherIdentity,
            SendBalance.PLATFORM to platform,
            SendBalance.PLATFORM to otherIdentity,
            SendBalance.PLATFORM to core,
            SendBalance.PLATFORM to shielded,
            SendBalance.IDENTITY to core,
            SendBalance.IDENTITY to platform,
            SendBalance.IDENTITY to otherIdentity,
            SendBalance.IDENTITY to shielded
        )

    private fun barrierSource() = source().apply {
        platformBalances = { listOf(address(1, 900_000_000L), address(2, 900_000_000L)) }
    }

    @Test
    fun aWipeUnderWay_refusesEveryWrite_withNothingCalled_andNothingRecorded() = runBlocking {
        for ((from, to) in barrierRoutes) {
            val lock = FundingSelectionLock().apply { raiseWipeBarrier() }
            val source = barrierSource()
            val store = FakeStore()
            val result = service(source, store, lock).send(from, to, amount, false)
            assertEquals("$from → $to", ExternalSendResult.NotSent(NotSentReason.GENERIC), result)
            assertTrue("$from → $to: ${source.calls}", source.calls.isEmpty())
            assertNull("$from → $to", store.record)

            // The next wallet's sends go through once the wipe is done.
            lock.lowerWipeBarrier()
            val next = service(barrierSource(), FakeStore(), lock).send(from, to, amount, false)
            assertTrue("$from → $to", next is ExternalSendResult.Sent)
        }
    }

    @Test
    fun theFinalWalletCheck_runsInsideTheHold_soARefusalComesBeforeIt() = runBlocking {
        // One bound read before the hold (sendInner) and none inside it: the wipe refuses first.
        val lock = FundingSelectionLock().apply { raiseWipeBarrier() }
        var reads = 0
        val source = object : ExternalSendSource by barrierSource() {
            override suspend fun boundWalletIdOrNull(): String? {
                reads++
                return walletA
            }
        }
        val service = ExternalSendServiceImpl(
            source,
            AddressNetwork.DASH_TESTNET,
            FakeStore(),
            ioDispatcher = Dispatchers.Unconfined,
            fundingSelection = lock
        )
        assertEquals(
            ExternalSendResult.NotSent(NotSentReason.GENERIC),
            service.send(SendBalance.PLATFORM, platform, amount, false)
        )
        assertEquals(1, reads)
        lock.lowerWipeBarrier()
        assertEquals(ExternalSendResult.Sent(null), service.send(SendBalance.PLATFORM, platform, amount, false))
        assertEquals(3, reads)
    }

    @Test
    fun aWipeThatCancelsACreditsWrite_afterItsSubmission_isMayHaveGoneThrough_neverACancellation() = runBlocking {
        val lock = FundingSelectionLock()
        val source = source().apply {
            nativeWrite = {
                // Reset Wallet starts while the SDK call runs; the call then notices the cancel.
                lock.raiseWipeBarrier()
                yield()
            }
        }
        assertEquals(
            ExternalSendResult.MayHaveGoneThrough,
            service(source, fundingSelection = lock).send(SendBalance.PLATFORM, platform, amount, false)
        )
        assertEquals(listOf("platformTransfer"), source.calls)
    }

    @Test
    fun aWipeThatCancelsACoreToPlatformFunding_keepsItsBuiltLock_andIsMayHaveGoneThrough() = runBlocking {
        val lock = FundingSelectionLock()
        val store = FakeStore()
        val source = source().apply {
            fund = { _, built -> built(OUT_POINT) }
        }
        val gated = object : ExternalSendSource by source {
            override suspend fun fundPlatformExternal(
                record: PendingPlatformFunding,
                onAssetLockBuilt: (String) -> Unit
            ) {
                source.fundPlatformExternal(record, onAssetLockBuilt)
                lock.raiseWipeBarrier()
                yield()
            }
        }
        val service = ExternalSendServiceImpl(
            gated,
            AddressNetwork.DASH_TESTNET,
            store,
            ioDispatcher = Dispatchers.Unconfined,
            fundingSelection = lock
        )
        assertEquals(ExternalSendResult.MayHaveGoneThrough, service.send(SendBalance.CORE, platform, amount, false))
        // The lock was built: its record (with the outpoint) stays for the wipe to clear.
        assertEquals(OUT_POINT, store.record?.outPointHex)
    }

    @Test
    fun resume_refusedByAWipeUnderWay_callsNothing_andKeepsTheRecord() = runBlocking {
        val lock = FundingSelectionLock().apply { raiseWipeBarrier() }
        val source = source()
        val pending = pendingRecord()
        val store = FakeStore().apply { record = pending }
        assertEquals(0, service(source, store, lock).resumePendingPlatformFunding())
        assertTrue(source.resumed.isEmpty())
        assertTrue(requireNotNull(store.record).sameAs(pending))

        lock.lowerWipeBarrier()
        assertEquals(1, service(source, store, lock).resumePendingPlatformFunding())
        assertNull(store.record)
    }

    @Test
    fun theAddressFundingLockType_isNotOneTheShieldedOrIdentitySweepsRead() {
        assertTrue(ASSET_LOCK_FUNDING_TYPE_ADDRESS_TOPUP != ASSET_LOCK_FUNDING_TYPE_SHIELDED_TOPUP)
        assertTrue(
            org.dashfoundation.dashsdk.wallet.TrackedAssetLock.FundingType.values()
                .none { it.raw == ASSET_LOCK_FUNDING_TYPE_ADDRESS_TOPUP }
        )
    }

    private companion object {
        const val OUT_POINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef:1"
        const val LOCK_TXID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SHIELD_LOCK_TXID = "aa23456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val TOP_UP_LOCK_TXID = "bb23456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val remainderHash = ByteArray(20) { 7 }
    }
}
