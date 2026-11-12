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
import de.schildbach.wallet.service.platform.sdk.SendBalance.CORE
import de.schildbach.wallet.service.platform.sdk.SendBalance.IDENTITY
import de.schildbach.wallet.service.platform.sdk.SendBalance.PLATFORM
import de.schildbach.wallet.service.platform.sdk.SendBalance.SHIELDED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.Base58
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class InternalTransferServiceImplTest {

    private companion object {
        const val WALLET_A = "wallet-a"
        const val WALLET_B = "wallet-b"
        const val POOL_FEE_CREDITS = 1_234_567L
    }

    private val network = AddressNetwork.DASH_TESTNET
    private val unadvertised = Base58.encodeChecked(network.addressHeader, ByteArray(20) { (it + 1).toByte() })
    private val ownPlatform = "tdash1k-own-platform"
    private val ownIdentity = Base58.encode(ByteArray(32) { (255 - it).toByte() })
    private val amount = Dash.COIN

    /** One External Send call, as the fake records it. */
    private data class Send(
        val source: SendBalance,
        val destination: SendDestination,
        val amount: Dash,
        val isMax: Boolean,
        val walletId: String?
    )

    private class FakeExternal : ExternalSendService {
        val sends = mutableListOf<Send>()
        var result: ExternalSendResult = ExternalSendResult.Sent("txid")
        var walletId: String? = WALLET_A
        val pairFees = mutableMapOf<Pair<SendBalance, SendBalance>, FeeEstimate?>()
        val pairMax = mutableMapOf<Pair<SendBalance, SendBalance>, Dash?>()

        override fun observeBalance(source: SendBalance): Flow<Dash?> = flowOf(Dash.COIN)
        override fun observeHasIdentity(): Flow<Boolean?> = flowOf(true)
        override fun currentWalletIdOrNull(): String? = walletId
        override suspend fun isOwnIdentity(identityIdBase58: String): Boolean? = true
        override suspend fun resolveIdentity(identityIdBase58: String) = IdentityResolution.Found(null)
        override suspend fun estimateFee(source: SendBalance, destinationKind: SendBalance) =
            pairFees[source to destinationKind]
        override suspend fun maxSendable(source: SendBalance, destinationKind: SendBalance) =
            pairMax[source to destinationKind]
        override suspend fun send(
            source: SendBalance,
            destination: SendDestination,
            amount: Dash,
            isMax: Boolean,
            expectedWalletIdHex: String?
        ): ExternalSendResult {
            sends += Send(source, destination, amount, isMax, expectedWalletIdHex)
            return result
        }
        override suspend fun resumePendingPlatformFunding(): Int = 0
    }

    private inner class FakeSource : InternalTransferSource {
        var committed: Boolean? = true
        var shielded: Boolean? = true
        var identity: Result<String?> = Result.success(ownIdentity)
        val boundWallets = ArrayDeque<String?>()
        var defaultBound: String? = WALLET_A
        var coreAddress: () -> String = { unadvertised }
        var platformAddress: String? = ownPlatform
        var platformAddressThrows: Throwable? = null
        var feeCredits: Long? = POOL_FEE_CREDITS
        var shieldFromWalletResult: SdkWriteResult<ShieldFromWalletOutcome> =
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED)
        var shieldFromWalletThrows: Throwable? = null
        var creditsResult: SdkWriteResult<Unit> = SdkWriteResult.Broadcast(Unit)
        val unshieldResults = ArrayDeque<SdkWriteResult<Unit>>()
        val writes = mutableListOf<String>()
        var boundReads = 0

        /** Runs inside every self-route write, after it is recorded (e.g. a wipe mid-call). */
        var duringWrite: suspend () -> Unit = {}
        var flag: Flow<Boolean?> = flowOf(true)

        override suspend fun cutoverCommittedOrThrow(): Boolean = committed ?: throw IOException("unreadable")
        override suspend fun shieldedEnabledOrThrow(): Boolean = shielded ?: throw IOException("unreadable")
        override fun observeShieldedFlag(): Flow<Boolean?> = flag
        override suspend fun boundWalletIdOrNull(): String? {
            boundReads++
            return boundWallets.removeFirstOrNull() ?: defaultBound
        }
        override suspend fun ownIdentityIdOrThrow(): String? = identity.getOrThrow()
        override fun unadvertisedCoreAddress(): String = coreAddress()
        val platformAddressReads = mutableListOf<String>()
        override suspend fun ownPlatformAddress(walletIdHex: String): String? {
            platformAddressReads += walletIdHex
            platformAddressThrows?.let { throw it }
            return platformAddress
        }
        override suspend fun shieldedFeeCredits(kind: ShieldedFeeKind): Long? = feeCredits

        override suspend fun shieldFromWallet(amount: Dash, walletIdHex: String): SdkWriteResult<ShieldFromWalletOutcome> {
            shieldFromWalletThrows?.let { throw it }
            writes += "shieldFromWallet(${amount.duffs}, $walletIdHex)"
            duringWrite()
            return shieldFromWalletResult
        }

        override suspend fun shieldFromCredits(amount: Dash, walletIdHex: String): SdkWriteResult<Unit> {
            writes += "shieldFromCredits(${amount.duffs}, $walletIdHex)"
            duringWrite()
            return creditsResult
        }

        override suspend fun unshieldToCredits(amount: Dash, walletIdHex: String): SdkWriteResult<Unit> {
            writes += "unshieldToCredits(${amount.duffs}, $walletIdHex)"
            duringWrite()
            return unshieldResults.removeFirstOrNull() ?: creditsResult
        }
    }

    private val external = FakeExternal()
    private val source = FakeSource()
    private val fundingSelection = FundingSelectionLock()
    private val service = InternalTransferServiceImpl(
        external,
        source,
        Dispatchers.Unconfined,
        retryDelayMs = { 0L },
        fundingSelection = fundingSelection
    )

    private fun transfer(from: SendBalance, to: SendBalance, isMax: Boolean = false, wallet: String? = WALLET_A) =
        runBlocking { service.transfer(from, to, amount, isMax, wallet) }

    private fun assertNothingSubmitted() {
        assertEquals(emptyList<Send>(), external.sends)
        assertEquals(emptyList<String>(), source.writes)
    }

    // ── own destinations ─────────────────────────────────────────────────────

    @Test
    fun `Core destinations are the unadvertised address`() {
        assertEquals(ExternalSendResult.Sent("txid"), transfer(SHIELDED, CORE))
        assertEquals(ExternalSendResult.Sent("txid"), transfer(IDENTITY, CORE, isMax = true))
        assertEquals(ExternalSendResult.Sent("txid"), transfer(PLATFORM, CORE))
        assertEquals(
            listOf(
                Send(SHIELDED, SendDestination.Core(unadvertised), amount, false, WALLET_A),
                Send(IDENTITY, SendDestination.Core(unadvertised), amount, true, WALLET_A),
                Send(PLATFORM, SendDestination.Core(unadvertised), amount, false, WALLET_A)
            ),
            external.sends
        )
    }

    @Test
    fun `Platform to Core withdraws to the unadvertised address, pinned, with the shielded flag off and no identity`() {
        source.shielded = null // unreadable: Platform → Core has no shielded leg, so it is never read
        source.identity = Result.failure(IOException("unreadable")) // nor the identity record
        assertEquals(ExternalSendResult.Sent("txid"), transfer(PLATFORM, CORE))
        assertEquals(listOf(Send(PLATFORM, SendDestination.Core(unadvertised), amount, false, WALLET_A)), external.sends)
        assertEquals(emptyList<String>(), source.writes)
    }

    @Test
    fun `Platform to Core passes the withdrawal's outcome through unchanged`() {
        for (outcome in listOf(
            ExternalSendResult.NotSent(NotSentReason.GENERIC),
            ExternalSendResult.NotSent(NotSentReason.INSUFFICIENT_FUNDS),
            ExternalSendResult.MayHaveGoneThrough
        )) {
            external.result = outcome
            assertEquals(outcome, transfer(PLATFORM, CORE))
        }
    }

    @Test
    fun `no unadvertised Core address fails closed before anything is submitted`() {
        source.coreAddress = { throw IllegalStateException("no unadvertised destination post-cutover") }
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(SHIELDED, CORE))
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(PLATFORM, CORE))
        assertNothingSubmitted()
    }

    @Test
    fun `identity to Platform pays the own Platform address, and none fails closed`() {
        assertEquals(ExternalSendResult.Sent("txid"), transfer(IDENTITY, PLATFORM))
        assertEquals(listOf(Send(IDENTITY, SendDestination.Platform(ownPlatform), amount, false, WALLET_A)), external.sends)

        external.sends.clear()
        source.platformAddress = null
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(IDENTITY, PLATFORM))
        assertNothingSubmitted()
    }

    @Test
    fun `identity to Platform works with the shielded flag off, pinned to the confirmed wallet`() {
        source.shielded = false
        assertEquals(ExternalSendResult.Sent("txid"), transfer(IDENTITY, PLATFORM))
        assertEquals(listOf(WALLET_A), source.platformAddressReads)
        assertEquals(listOf(Send(IDENTITY, SendDestination.Platform(ownPlatform), amount, false, WALLET_A)), external.sends)
    }

    @Test
    fun `Core to Platform funds the own Platform address through the External Send, pinned, isMax passed through`() {
        source.shielded = null // no shielded leg: never read
        source.identity = Result.failure(IOException("unreadable")) // nor the identity record
        assertEquals(ExternalSendResult.Sent("txid"), transfer(CORE, PLATFORM))
        assertEquals(ExternalSendResult.Sent("txid"), transfer(CORE, PLATFORM, isMax = true))
        assertEquals(listOf(WALLET_A, WALLET_A), source.platformAddressReads)
        assertEquals(
            listOf(
                Send(CORE, SendDestination.Platform(ownPlatform), amount, false, WALLET_A),
                Send(CORE, SendDestination.Platform(ownPlatform), amount, true, WALLET_A)
            ),
            external.sends
        )
        // The External Send's own route (fundFromAssetLockExternal) does the write, not a self route.
        assertEquals(emptyList<String>(), source.writes)
    }

    @Test
    fun `Core to Platform with no own Platform address, or an unreadable one, fails closed`() {
        source.platformAddress = null
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(CORE, PLATFORM))
        source.platformAddress = ownPlatform
        source.platformAddressThrows = IOException("address store unreadable")
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(CORE, PLATFORM, isMax = true))
        assertNothingSubmitted()
    }

    @Test
    fun `Core to Platform passes the External Send's outcomes through unchanged`() {
        for (outcome in listOf(
            ExternalSendResult.NotSent(NotSentReason.PAYMENT_STILL_CONFIRMING),
            ExternalSendResult.NotSent(NotSentReason.NOT_SYNCED),
            ExternalSendResult.NotSent(NotSentReason.GENERIC),
            ExternalSendResult.MayHaveGoneThrough
        )) {
            external.result = outcome
            assertEquals(outcome, transfer(CORE, PLATFORM))
        }
    }

    @Test
    fun `identity destinations are the own identity`() {
        transfer(CORE, IDENTITY)
        transfer(SHIELDED, IDENTITY)
        transfer(PLATFORM, IDENTITY)
        assertEquals(
            listOf(CORE, SHIELDED, PLATFORM).map { Send(it, SendDestination.Identity(ownIdentity), amount, false, WALLET_A) },
            external.sends
        )
        assertEquals(emptyList<String>(), source.writes)
    }

    // ── self routes ──────────────────────────────────────────────────────────

    @Test
    fun `Core to Shielded self-shields amount plus the pool fee, pinned`() {
        assertEquals(ExternalSendResult.Sent(null), transfer(CORE, SHIELDED))
        val lock = amount.duffs + shieldPoolFeeDuffs(POOL_FEE_CREDITS)
        assertEquals(listOf("shieldFromWallet($lock, $WALLET_A)"), source.writes)
        assertEquals(emptyList<Send>(), external.sends)
    }

    @Test
    fun `Core to Shielded outcomes map without a retry`() {
        source.shieldFromWalletResult = SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY)
        assertEquals(ExternalSendResult.CompletesAutomatically, transfer(CORE, SHIELDED))
        source.shieldFromWalletResult = SdkWriteResult.Ambiguous(IllegalStateException("unknown"))
        assertEquals(ExternalSendResult.MayHaveGoneThrough, transfer(CORE, SHIELDED))
        source.shieldFromWalletResult = SdkWriteResult.NotBroadcast("shielded runtime not ready")
        assertEquals(ExternalSendResult.NotSent(NotSentReason.NOT_SYNCED), transfer(CORE, SHIELDED))
        assertEquals(3, source.writes.size)
    }

    @Test
    fun `Core to Shielded with an unknown pool fee is not sent`() {
        source.feeCredits = null
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(CORE, SHIELDED))
        assertNothingSubmitted()
    }

    @Test
    fun `Platform to Shielded shields from credits, Shielded to Platform unshields, both pinned`() {
        assertEquals(ExternalSendResult.Sent(null), transfer(PLATFORM, SHIELDED))
        assertEquals(ExternalSendResult.Sent(null), transfer(SHIELDED, PLATFORM))
        assertEquals(
            listOf("shieldFromCredits(${amount.duffs}, $WALLET_A)", "unshieldToCredits(${amount.duffs}, $WALLET_A)"),
            source.writes
        )
        assertEquals(emptyList<Send>(), external.sends)
    }

    @Test
    fun `a Max unshield retries once with the reported fee deficit`() {
        val credits = dashToCredits(amount)
        source.unshieldResults += SdkWriteResult.NotBroadcast(
            "Insufficient shielded balance: available $credits, required ${credits + 1000}"
        )
        transfer(SHIELDED, PLATFORM, isMax = true)
        assertEquals(2, source.writes.size)
    }

    // ── gating ───────────────────────────────────────────────────────────────

    @Test
    fun `the matrix is enforced by the service`() {
        for ((from, to) in listOf(IDENTITY to SHIELDED, CORE to CORE)) {
            assertEquals("$from → $to", ExternalSendResult.NotSent(NotSentReason.NOT_SUPPORTED), transfer(from, to))
        }
        assertNothingSubmitted()
    }

    @Test
    fun `shielded flag off or unreadable, no identity, or no cutover refuse`() {
        source.shielded = false
        assertEquals(ExternalSendResult.NotSent(NotSentReason.NOT_SUPPORTED), transfer(CORE, SHIELDED))
        source.shielded = null
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(SHIELDED, CORE))
        source.shielded = true
        source.identity = Result.success(null)
        assertEquals(ExternalSendResult.NotSent(NotSentReason.NOT_SUPPORTED), transfer(CORE, IDENTITY))
        source.identity = Result.failure(IOException("unreadable"))
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(IDENTITY, CORE))
        source.identity = Result.success(ownIdentity)
        source.committed = false
        assertEquals(ExternalSendResult.NotSent(NotSentReason.NOT_SUPPORTED), transfer(IDENTITY, CORE))
        source.committed = null
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(IDENTITY, CORE))
        assertNothingSubmitted()
    }

    @Test
    fun `a wallet other than the confirmed one is not sent`() {
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(SHIELDED, CORE, wallet = WALLET_B))
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(SHIELDED, CORE, wallet = null))
        source.defaultBound = null
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(CORE, SHIELDED))
        assertNothingSubmitted()
    }

    @Test
    fun `the wallet changing after the first check is caught before the write`() {
        source.boundWallets += listOf(WALLET_A, WALLET_B)
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(CORE, SHIELDED))
        source.boundWallets += listOf(WALLET_A, WALLET_B)
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(SHIELDED, CORE))
        assertNothingSubmitted()
    }

    @Test
    fun `non-positive amounts are refused`() {
        val result = runBlocking { service.transfer(SHIELDED, CORE, Dash.ZERO, false, WALLET_A) }
        assertEquals(ExternalSendResult.NotSent(NotSentReason.GENERIC), result)
        assertNothingSubmitted()
    }

    @Test
    fun `cancellation is rethrown`() {
        source.shieldFromWalletThrows = CancellationException("cancelled")
        try {
            transfer(CORE, SHIELDED)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
        // The single-flight latch was released.
        source.shieldFromWalletThrows = null
        assertEquals(ExternalSendResult.Sent(null), transfer(CORE, SHIELDED))
    }

    @Test
    fun `an unclassified failure in a write may have gone through`() {
        source.shieldFromWalletThrows = IllegalStateException("boom")
        assertEquals(ExternalSendResult.MayHaveGoneThrough, transfer(CORE, SHIELDED))
    }

    // ── Reset Wallet: the wipe barrier ───────────────────────────────────────

    /** The self routes this service submits itself (the rest hold the barrier in the External Send). */
    private val selfRoutes = listOf(CORE to SHIELDED, SHIELDED to PLATFORM, PLATFORM to SHIELDED)

    @Test
    fun `a wipe under way refuses every self route with nothing called`() {
        for ((from, to) in selfRoutes) {
            fundingSelection.raiseWipeBarrier()
            assertEquals("$from → $to", ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(from, to))
            assertNothingSubmitted()

            // The next wallet's transfers go through once the wipe is done.
            fundingSelection.lowerWipeBarrier()
            assertEquals("$from → $to", ExternalSendResult.Sent(null), transfer(from, to))
            source.writes.clear()
        }
    }

    @Test
    fun `the final wallet check runs inside the hold, so a refusal comes before it`() {
        for ((from, to) in selfRoutes) {
            // One bound read before the hold (transferInner) and none inside it: the wipe refuses first.
            fundingSelection.raiseWipeBarrier()
            source.boundReads = 0
            assertEquals("$from → $to", ExternalSendResult.NotSent(NotSentReason.GENERIC), transfer(from, to))
            assertEquals("$from → $to", 1, source.boundReads)

            fundingSelection.lowerWipeBarrier()
            source.boundReads = 0
            assertEquals("$from → $to", ExternalSendResult.Sent(null), transfer(from, to))
            assertEquals("$from → $to", 2, source.boundReads)
        }
    }

    @Test
    fun `delegated routes do not take the barrier here`() {
        // The External Send holds it around its own write; a raised barrier is its to refuse.
        fundingSelection.raiseWipeBarrier()
        assertEquals(ExternalSendResult.Sent("txid"), transfer(SHIELDED, CORE))
        assertEquals(1, external.sends.size)
        fundingSelection.lowerWipeBarrier()
    }

    @Test
    fun `a wipe that cancels a credits write after its submission may have gone through, never a cancellation`() {
        for ((from, to) in listOf(SHIELDED to PLATFORM, PLATFORM to SHIELDED)) {
            val lock = FundingSelectionLock()
            val gated = InternalTransferServiceImpl(
                external,
                source,
                Dispatchers.Unconfined,
                retryDelayMs = { 0L },
                fundingSelection = lock
            )
            source.duringWrite = {
                // Reset Wallet starts while the SDK call runs; the call then notices the cancel.
                lock.raiseWipeBarrier()
                yield()
            }
            val result = runBlocking { gated.transfer(from, to, amount, false, WALLET_A) }
            assertEquals("$from → $to", ExternalSendResult.MayHaveGoneThrough, result)
        }
    }

    // ── fee, max and reads ───────────────────────────────────────────────────

    @Test
    fun `fee and max come from the per-pair estimate, null for unsupported pairs`() = runBlocking {
        val fee = FeeEstimate(Dash(1000), exact = false)
        external.pairFees[SHIELDED to CORE] = fee
        external.pairMax[SHIELDED to CORE] = Dash(5000)
        external.pairFees[CORE to PLATFORM] = fee
        external.pairMax[CORE to PLATFORM] = Dash(5000)
        assertEquals(fee, service.estimateFee(SHIELDED, CORE))
        assertEquals(Dash(5000), service.maxSendable(SHIELDED, CORE))
        // Core → Platform: the External Send's lock reserve + margin fee and its Max.
        assertEquals(fee, service.estimateFee(CORE, PLATFORM))
        assertEquals(Dash(5000), service.maxSendable(CORE, PLATFORM))
        // An unsupported pair never asks the External Send.
        external.pairFees[IDENTITY to SHIELDED] = fee
        external.pairMax[IDENTITY to SHIELDED] = Dash(5000)
        assertNull(service.estimateFee(IDENTITY, SHIELDED))
        assertNull(service.maxSendable(IDENTITY, SHIELDED))
        assertNull(service.estimateFee(PLATFORM, SHIELDED))
        // Platform → Core asks the External Send's own figures (null today: metered by Rust).
        assertNull(service.estimateFee(PLATFORM, CORE))
        assertNull(service.maxSendable(PLATFORM, CORE))
        external.pairFees[PLATFORM to CORE] = fee
        external.pairMax[PLATFORM to CORE] = Dash(5000)
        assertEquals(fee, service.estimateFee(PLATFORM, CORE))
        assertEquals(Dash(5000), service.maxSendable(PLATFORM, CORE))
    }

    @Test
    fun `an unreadable shielded flag reads as unknown and keeps observing`() = runBlocking {
        var reads = 0
        source.flag = flow {
            if (reads++ == 0) throw IOException("datastore")
            emit(true)
        }
        assertEquals(listOf(null, true), service.observeShieldedEnabled().take(2).toList())
        assertTrue(reads >= 2)
        source.flag = MutableStateFlow<Boolean?>(null)
        assertEquals(false, service.observeShieldedEnabled().first())
    }
}
