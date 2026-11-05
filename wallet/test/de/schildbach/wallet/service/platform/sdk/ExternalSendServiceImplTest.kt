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

import de.schildbach.wallet.payments.SendEngineNotSyncedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.Base58
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.common.services.WalletSession
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
        var shieldedBalance: Dash? = Dash(1_000_000L)
        var platformBalances: () -> List<FundingInput> = { emptyList() }
        var shieldedResult: (Dash) -> SdkWriteResult<Unit> = { SdkWriteResult.Broadcast(Unit) }
        var shieldFromCoreResult: (Dash) -> SdkWriteResult<ShieldFromWalletOutcome> =
            { SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED) }
        var coreSend: () -> String = { "txid" }
        var nativeWrite: () -> Unit = {}
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
        override suspend fun cutoverCommittedOrThrow(): Boolean = cutover()
        override suspend fun shieldedEnabledOrThrow(): Boolean = shieldedOn()
        override suspend fun ownIdentityIdOrThrow(): String? = ownIdentity
        override fun observeOwnIdentityId(): Flow<Result<String?>> = flowOf(Result.success(ownIdentity))
        override suspend fun managedIdentityIds(walletIdHex: String): List<ByteArray> = managed
        override fun observeCoreMaxSendableDuffs(): Flow<Long?> = flowOf(coreMax)
        override fun observeShieldedBalance(): Flow<Dash?> = flowOf(shieldedBalance)
        override suspend fun platformAddressBalances(walletIdHex: String): List<FundingInput> = platformBalances()
        override suspend fun identityBalanceCredits(identityIdBase58: String): Long? = 2_000_000_000L
        override suspend fun identityExists(identityIdBase58: String): Boolean = identityIdBase58 == ownIdentity
        override suspend fun usernameOf(identityIdBase58: String): String? = "alice"
        override suspend fun shieldedFeeCredits(kind: ShieldedFeeKind): Long? = 2_000_500L
        override suspend fun spendableUtxoCount(): Int? = 2

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

        override suspend fun shieldFromCore(walletIdHex: String, address: String, amount: Dash): SdkWriteResult<ShieldFromWalletOutcome> {
            calls += "shieldFromCore"
            shieldedAmounts += amount
            return shieldFromCoreResult(amount)
        }

        override suspend fun coreTopUpIdentity(identityIdBase58: String, amountDuffs: Long): SdkWriteResult<Long> {
            calls += "coreTopUpIdentity"
            lastDuffs = amountDuffs
            return SdkWriteResult.Broadcast(1L)
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
    }

    private fun source(
        bound: List<String?> = listOf(walletA),
        ownIdentity: String? = ownIdBase58,
        managed: List<ByteArray> = listOf(ownId)
    ) = FakeSource(bound.toMutableList(), ownIdentity, managed)

    private fun service(source: FakeSource) =
        ExternalSendServiceImpl(source, AddressNetwork.DASH_TESTNET, ioDispatcher = Dispatchers.Unconfined)

    // ── Each supported pair: right seam, right units ──────────────────

    @Test
    fun coreToCore_sendsThroughTheCoreFunnel_withTheCapturedSession_andMaxAsSendAll() = runBlocking {
        val source = source()
        val result = service(source).send(SendBalance.CORE, core, amount, isMax = true)
        assertEquals(ExternalSendResult.Sent("txid"), result)
        assertEquals(listOf("sendCore"), source.calls)
        assertEquals(true, source.lastCoreEmptyWallet)
        assertTrue(source.lastSession === source.session)
    }

    @Test
    fun coreToShielded_completed_isSent_pendingRetry_isInterrupted() = runBlocking {
        val source = source()
        assertEquals(ExternalSendResult.Sent(null), service(source).send(SendBalance.CORE, shielded, amount, false))
        assertEquals(listOf(amount), source.shieldedAmounts)

        source.shieldFromCoreResult = { SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY) }
        assertEquals(
            ExternalSendResult.InterruptedFundsReturnToShielded,
            service(source).send(SendBalance.CORE, shielded, amount, false)
        )
    }

    @Test
    fun coreToOwnIdentity_topsUpInDuffs() = runBlocking {
        val source = source()
        assertEquals(ExternalSendResult.Sent(null), service(source).send(SendBalance.CORE, ownIdentity, amount, false))
        assertEquals(listOf("coreTopUpIdentity"), source.calls)
        assertEquals(100_000L, source.lastDuffs)
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
            assertEquals(listOf(amount), source.shieldedAmounts)
        }
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
            assertEquals(amountCredits, source.lastCredits)
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
            SendBalance.CORE to platform,
            SendBalance.PLATFORM to core,
            SendBalance.PLATFORM to shielded,
            SendBalance.IDENTITY to shielded,
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
        val gated = ExternalSendServiceImpl(slow, AddressNetwork.DASH_TESTNET, ioDispatcher = Dispatchers.Unconfined)
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
        assertNull(service.maxSendable(SendBalance.SHIELDED, core))
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
        assertEquals(Dash(997_999L), service.maxSendable(SendBalance.SHIELDED, core))
        assertNull(service.maxSendable(SendBalance.PLATFORM, platform))
    }

    @Test
    fun fees_areNullWhereNoPreSendFigureExists() = runBlocking {
        val service = service(source())
        assertEquals(FeeEstimate(Dash(SEND_ALL_FEE_RESERVE_DUFFS), exact = false), service.estimateFee(SendBalance.CORE, core, amount))
        assertEquals(FeeEstimate(Dash(2_001L), exact = false), service.estimateFee(SendBalance.SHIELDED, core, amount))
        assertNull(service.estimateFee(SendBalance.PLATFORM, platform, amount))
        assertNull(service.estimateFee(SendBalance.IDENTITY, core, amount))
        assertNull(service.estimateFee(SendBalance.CORE, ownIdentity, amount))
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
}
