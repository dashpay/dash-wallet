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

package de.schildbach.wallet.ui.payments.send

import androidx.lifecycle.SavedStateHandle
import de.schildbach.wallet.service.platform.sdk.Bech32m
import de.schildbach.wallet.service.platform.sdk.DestinationParse
import de.schildbach.wallet.service.platform.sdk.ExternalSendResult
import de.schildbach.wallet.service.platform.sdk.ExternalSendService
import de.schildbach.wallet.service.platform.sdk.FeeEstimate
import de.schildbach.wallet.service.platform.sdk.IdentityResolution
import de.schildbach.wallet.service.platform.sdk.NotSentReason
import de.schildbach.wallet.service.platform.sdk.SendBalance
import de.schildbach.wallet.service.platform.sdk.SendDestination
import de.schildbach.wallet.ui.shielded.toKeypadString
import de.schildbach.wallet_test.R
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.Base58
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalSendViewModelTest {

    private val network = AddressNetwork.DASH_TESTNET
    private val hash20 = ByteArray(20) { (it + 1).toByte() }
    private val coreAddress = Base58.encodeChecked(network.addressHeader, hash20)
    private val mainnetCoreAddress = Base58.encodeChecked(AddressNetwork.DASH_MAINNET.addressHeader, hash20)
    private val platformAddress = requireNotNull(Bech32m.encode("tdash", byteArrayOf(0xb0.toByte()) + hash20))

    /** A P2SH Platform address: the one destination Core cannot pay (P2PKH-only funding). */
    private val platformP2shAddress = requireNotNull(Bech32m.encode("tdash", byteArrayOf(0x80.toByte()) + hash20))
    private val shieldedAddress =
        requireNotNull(Bech32m.encode("tdash", byteArrayOf(0x10) + ByteArray(43) { (it * 3 + 7).toByte() }))
    private val identityId = Base58.encode(ByteArray(32) { (255 - it).toByte() })

    private val oneDash = Dash.COIN
    private val twoDash = Dash.COIN * 2L

    /** A fake service: every answer is a field the test sets; every send is recorded. */
    private class FakeService : ExternalSendService {
        val balances = SendBalance.entries.associateWith { MutableStateFlow<Dash?>(null) }
        val hasIdentity = MutableStateFlow<Boolean?>(false)
        var ownIdentity: Boolean? = false
        var resolution: IdentityResolution = IdentityResolution.Found("alice")
        var fee: FeeEstimate? = null
        val max = mutableMapOf<SendBalance, Dash?>()

        /** When set for a source, its [maxSendable] suspends on it (a slow Max read). */
        val maxGates = mutableMapOf<SendBalance, CompletableDeferred<Unit>>()
        var sendGate: CompletableDeferred<ExternalSendResult>? = null
        var sendResult: ExternalSendResult = ExternalSendResult.Sent(null)
        val sends = mutableListOf<ExternalSendRequest>()
        var walletId: String? = WALLET_A

        override fun currentWalletIdOrNull(): String? = walletId
        override fun observeBalance(source: SendBalance): Flow<Dash?> = balances.getValue(source)
        override fun observeHasIdentity(): Flow<Boolean?> = hasIdentity
        override suspend fun isOwnIdentity(identityIdBase58: String): Boolean? = ownIdentity
        override suspend fun resolveIdentity(identityIdBase58: String): IdentityResolution = resolution
        override suspend fun estimateFee(source: SendBalance, destination: SendDestination, amount: Dash) = fee
        override suspend fun maxSendable(source: SendBalance, destination: SendDestination): Dash? {
            maxGates[source]?.await()
            return max[source]
        }
        override suspend fun send(
            source: SendBalance,
            destination: SendDestination,
            amount: Dash,
            isMax: Boolean,
            expectedWalletIdHex: String?
        ): ExternalSendResult {
            sends += ExternalSendRequest(source, destination, amount, isMax, expectedWalletIdHex)
            return sendGate?.await() ?: sendResult
        }

        var platformResumes = 0
        override suspend fun resumePendingPlatformFunding(): Int {
            platformResumes++
            return 0
        }
    }

    /** The persisted in-flight flag; [failReads]/[failWrites] make the store throw. */
    private class FakeMarker : ExternalSendInFlightMarker {
        var value = false
        var failReads = false
        var failWrites = false
        val log = mutableListOf<String>()

        /** When set, [clear] suspends on it (after counting the call in [clearCalls]). */
        var clearGate: CompletableDeferred<Unit>? = null
        var clearCalls = 0

        override suspend fun isSet(): Boolean {
            if (failReads) throw java.io.IOException("datastore")
            return value
        }

        override suspend fun set() {
            if (failWrites) throw java.io.IOException("datastore")
            log += "set"
            value = true
        }

        override suspend fun clear() {
            clearCalls++
            clearGate?.await()
            if (failWrites) throw java.io.IOException("datastore")
            log += "clear"
            value = false
        }
    }

    private companion object {
        const val WALLET_A = "wallet-a"
    }

    private val service = FakeService()
    private val marker = FakeMarker()
    private lateinit var executor: ExternalSendExecutor

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        executor = newExecutor()
    }

    private fun newExecutor() = ExternalSendExecutor(service, marker, CoroutineScope(UnconfinedTestDispatcher()))

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(
        savedState: SavedStateHandle = SavedStateHandle(),
        rate: ExchangeRate? = null
    ): ExternalSendViewModel {
        val walletUIConfig = mockk<WalletUIConfig> {
            every { observe(WalletUIConfig.SELECTED_CURRENCY) } returns flowOf("USD")
        }
        val rates = mockk<ExchangeRatesProvider> {
            every { observeExchangeRate(any()) } returns flowOf(rate)
        }
        return ExternalSendViewModel(service, executor, walletUIConfig, rates, savedState, network)
    }

    private fun ExternalSendViewModel.goToAmount(address: String) {
        onAddressChanged(address)
        onAddressContinue()
    }

    private fun ExternalSendViewModel.type(amount: String) {
        amount.forEach { onKeyInput(it.toString()) }
    }

    // ── address step ─────────────────────────────────────────────────────────

    @Test
    fun address_states_gateContinue_andShowAnErrorOnlyForBadInput() {
        val viewModel = buildViewModel()
        with(viewModel.uiState.value) {
            assertEquals(DestinationParse.Empty, addressParse)
            assertFalse(addressError)
            assertFalse(canContinueAddress)
        }

        viewModel.onAddressChanged("not an address")
        assertTrue(viewModel.uiState.value.addressError)
        assertFalse(viewModel.uiState.value.canContinueAddress)

        viewModel.onAddressChanged(mainnetCoreAddress)
        assertEquals(DestinationParse.WrongNetwork, viewModel.uiState.value.addressParse)
        assertTrue(viewModel.uiState.value.addressError)
        assertFalse(viewModel.uiState.value.canContinueAddress)

        viewModel.onAddressContinue()
        assertEquals(ExternalSendStep.ADDRESS, viewModel.uiState.value.step)

        viewModel.onAddressChanged(coreAddress)
        assertFalse(viewModel.uiState.value.addressError)
        assertTrue(viewModel.uiState.value.canContinueAddress)
        viewModel.onAddressContinue()
        assertEquals(ExternalSendStep.AMOUNT, viewModel.uiState.value.step)
        assertEquals(SendDestination.Core(coreAddress), viewModel.uiState.value.destination)
    }

    @Test
    fun clipboard_offersOnlyDestinationsOfThisNetwork_andPastesTheTappedOne() {
        val viewModel = buildViewModel()
        viewModel.onClipboardRead("pay $shieldedAddress or $mainnetCoreAddress please")
        assertTrue(viewModel.uiState.value.clipboardShown)
        assertEquals(listOf(shieldedAddress), viewModel.uiState.value.clipboardEntries)

        viewModel.onClipboardEntrySelected(shieldedAddress)
        assertEquals(shieldedAddress, viewModel.uiState.value.addressInput)
        assertFalse(viewModel.uiState.value.clipboardShown)
        assertTrue(viewModel.uiState.value.canContinueAddress)
    }

    @Test
    fun clipboard_withNoAddress_showsNoEntries() {
        val viewModel = buildViewModel()
        viewModel.onClipboardRead(null)
        assertTrue(viewModel.uiState.value.clipboardShown)
        assertTrue(viewModel.uiState.value.clipboardEntries.isEmpty())
    }

    // ── source choice ────────────────────────────────────────────────────────

    @Test
    fun initialSource_isTheFirstSupported_inCoreShieldedPlatformIdentityOrder() {
        val core = buildViewModel().apply { goToAmount(coreAddress) }
        assertEquals(SendBalance.CORE, core.uiState.value.source)

        // Core pays a P2PKH Platform address (fundFromAssetLockExternal) ...
        val platform = buildViewModel().apply { goToAmount(platformAddress) }
        assertEquals(SendBalance.CORE, platform.uiState.value.source)

        // ... but not a P2SH one; Shielded can.
        val p2sh = buildViewModel().apply { goToAmount(platformP2shAddress) }
        assertEquals(SendBalance.SHIELDED, p2sh.uiState.value.source)
    }

    @Test
    fun initialSource_forAnIdentity_isCoreOnlyWhenTheIdentityIsOurOwn() {
        service.hasIdentity.value = true
        service.ownIdentity = false
        val other = buildViewModel().apply { goToAmount(identityId) }
        assertEquals(SendBalance.SHIELDED, other.uiState.value.source)

        service.ownIdentity = true
        val own = buildViewModel().apply { goToAmount(identityId) }
        assertEquals(SendBalance.CORE, own.uiState.value.source)
    }

    @Test
    fun sourceSelection_ignoresUnsupportedAndUnlistedSources() {
        val viewModel = buildViewModel()
        viewModel.goToAmount(platformP2shAddress)

        viewModel.onSourceSelected(SendBalance.CORE)
        assertEquals(SendBalance.SHIELDED, viewModel.uiState.value.source)

        // No identity: the Identity row is not listed and cannot be picked.
        assertFalse(SendBalance.IDENTITY in viewModel.uiState.value.sources)
        viewModel.onSourceSelected(SendBalance.IDENTITY)
        assertEquals(SendBalance.SHIELDED, viewModel.uiState.value.source)

        viewModel.onSourceSelected(SendBalance.PLATFORM)
        assertEquals(SendBalance.PLATFORM, viewModel.uiState.value.source)
        assertTrue(viewModel.uiState.value.sourceChosenByUser)

        service.hasIdentity.value = true
        assertTrue(SendBalance.IDENTITY in viewModel.uiState.value.sources)
        viewModel.onSourceSelected(SendBalance.IDENTITY)
        assertEquals(SendBalance.IDENTITY, viewModel.uiState.value.source)
    }

    @Test
    fun switchingSource_dropsTheOldBalanceAndMax() {
        service.balances.getValue(SendBalance.SHIELDED).value = twoDash
        service.max[SendBalance.SHIELDED] = oneDash
        val viewModel = buildViewModel()
        viewModel.goToAmount(platformP2shAddress)
        assertEquals(twoDash, viewModel.uiState.value.balance)
        assertEquals(oneDash, viewModel.uiState.value.maxSendable)

        viewModel.onSourceSelected(SendBalance.PLATFORM)
        assertNull(viewModel.uiState.value.balance)
        assertNull(viewModel.uiState.value.maxSendable)
    }

    // ── Max ──────────────────────────────────────────────────────────────────

    @Test
    fun max_isUnavailableWhileUnknown_andSetsTheExactMaxWhenKnown() {
        service.balances.getValue(SendBalance.CORE).value = twoDash
        service.max[SendBalance.CORE] = null
        val viewModel = buildViewModel()
        viewModel.goToAmount(coreAddress)
        assertNull(viewModel.uiState.value.maxSendable)
        viewModel.onMaxClick()
        assertFalse(viewModel.uiState.value.isMax)
        assertEquals(Dash.ZERO, viewModel.uiState.value.amount)

        service.max[SendBalance.CORE] = Dash(199_990_000)
        service.balances.getValue(SendBalance.CORE).value = Dash(200_000_001)
        viewModel.onMaxClick()
        assertTrue(viewModel.uiState.value.isMax)
        assertEquals(Dash(199_990_000), viewModel.uiState.value.amount)

        viewModel.onKeyInput("back")
        assertFalse(viewModel.uiState.value.isMax)
    }

    // ── Continue gating ──────────────────────────────────────────────────────

    @Test
    fun continue_needsAKnownBalance_andAnAmountWithinIt() {
        val viewModel = buildViewModel()
        viewModel.goToAmount(coreAddress)
        viewModel.type("1")
        assertFalse("unknown balance", viewModel.uiState.value.canContinueAmount)

        service.balances.getValue(SendBalance.CORE).value = twoDash
        assertTrue(viewModel.uiState.value.canContinueAmount)

        viewModel.type("0")
        assertEquals(Dash.COIN * 10L, viewModel.uiState.value.amount)
        assertFalse("over the balance", viewModel.uiState.value.canContinueAmount)
    }

    @Test
    fun continue_isBoundedByTheMaxSendable_whenKnown() {
        service.balances.getValue(SendBalance.CORE).value = twoDash
        service.max[SendBalance.CORE] = oneDash
        val viewModel = buildViewModel()
        viewModel.goToAmount(coreAddress)
        viewModel.type("1.5")
        assertFalse(viewModel.uiState.value.canContinueAmount)
    }

    @Test
    fun continue_isBlockedForAnUnsupportedPair() {
        val state = ExternalSendUIState(
            step = ExternalSendStep.AMOUNT,
            destination = SendDestination.Platform(platformP2shAddress),
            source = SendBalance.CORE,
            amountText = "1",
            balance = twoDash
        )
        assertFalse(state.canContinueAmount)
        // A P2PKH Platform address is payable from Core.
        assertTrue(state.copy(destination = SendDestination.Platform(platformAddress)).canContinueAmount)
        assertTrue(state.copy(source = SendBalance.SHIELDED).canContinueAmount)
    }

    @Test
    fun continue_isAllowedForThePairsTheInt37SdkAdded() {
        val state = ExternalSendUIState(
            step = ExternalSendStep.AMOUNT,
            destination = SendDestination.Core(coreAddress),
            source = SendBalance.PLATFORM,
            amountText = "1",
            balance = twoDash,
            hasIdentity = true
        )
        // Platform → Core (partial withdrawal), Platform / Identity → Shielded (third-party shields).
        assertTrue(state.canContinueAmount)
        assertTrue(state.copy(destination = SendDestination.Shielded(shieldedAddress)).canContinueAmount)
        assertTrue(
            state.copy(source = SendBalance.IDENTITY, destination = SendDestination.Shielded(shieldedAddress))
                .canContinueAmount
        )
    }

    @Test
    fun continue_toAnIdentity_needsItToBeFound_andUnknownCanBeRetried() {
        service.balances.getValue(SendBalance.SHIELDED).value = twoDash
        service.resolution = IdentityResolution.NotFound
        val viewModel = buildViewModel()
        viewModel.goToAmount(identityId)
        viewModel.type("1")
        assertEquals(IdentityResolution.NotFound, viewModel.uiState.value.identityResolution)
        assertFalse(viewModel.uiState.value.canContinueAmount)

        service.resolution = IdentityResolution.Unknown
        viewModel.onRetryIdentity()
        assertEquals(IdentityResolution.Unknown, viewModel.uiState.value.identityResolution)
        assertFalse(viewModel.uiState.value.canContinueAmount)

        service.resolution = IdentityResolution.Found(null)
        viewModel.onRetryIdentity()
        assertTrue(viewModel.uiState.value.canContinueAmount)
    }

    // ── confirm and send ─────────────────────────────────────────────────────

    @Test
    fun confirm_carriesTheExactRequest_andTheFee() {
        service.balances.getValue(SendBalance.CORE).value = twoDash
        service.max[SendBalance.CORE] = twoDash
        service.fee = FeeEstimate(Dash(1_000), exact = false)
        val viewModel = buildViewModel()
        viewModel.goToAmount(coreAddress)
        viewModel.onMaxClick()
        viewModel.onAmountContinue()

        val confirm = requireNotNull(viewModel.uiState.value.confirm)
        assertEquals(
            ExternalSendRequest(SendBalance.CORE, SendDestination.Core(coreAddress), twoDash, isMax = true, WALLET_A),
            confirm.request
        )
        assertEquals(service.fee, confirm.fee)
        assertFalse(confirm.feeLoading)
        // A Core Max is the drain: the fee comes out of the amount (Basic's sendAllBalance).
        assertTrue(confirm.feeIncluded)
        assertEquals(twoDash - Dash(1_000), confirm.recipientAmount)
        assertEquals(twoDash, confirm.total)
        assertEquals(confirm.request, viewModel.confirmedRequest())
    }

    @Test
    fun anUnknownFee_hasNoTotal() {
        service.balances.getValue(SendBalance.PLATFORM).value = twoDash
        service.fee = null
        val viewModel = buildViewModel()
        viewModel.goToAmount(platformAddress)
        viewModel.onSourceSelected(SendBalance.PLATFORM)
        viewModel.type("1")
        viewModel.onAmountContinue()
        val confirm = requireNotNull(viewModel.uiState.value.confirm)
        assertNull(confirm.fee)
        assertFalse(confirm.feeLoading)
        assertNull(confirm.total)
    }

    @Test
    fun submit_sendsTheCapturedRequest_andRefusesAnyOther() {
        service.balances.getValue(SendBalance.CORE).value = twoDash
        val viewModel = buildViewModel()
        viewModel.goToAmount(coreAddress)
        viewModel.type("1")
        viewModel.onAmountContinue()
        val captured = requireNotNull(viewModel.confirmedRequest())

        assertFalse(viewModel.submit(captured.copy(amount = twoDash)))
        assertTrue(service.sends.isEmpty())

        assertTrue(viewModel.submit(captured))
        assertEquals(listOf(captured), service.sends)
        assertNull(viewModel.uiState.value.confirm)
    }

    @Test
    fun aRunningSend_showsSending_andTheResultWhenItLands_evenOnALaterVisit() {
        service.balances.getValue(SendBalance.SHIELDED).value = twoDash
        val gate = CompletableDeferred<ExternalSendResult>()
        service.sendGate = gate
        val viewModel = buildViewModel()
        viewModel.goToAmount(shieldedAddress)
        viewModel.onSourceSelected(SendBalance.SHIELDED)
        viewModel.type("1")
        viewModel.onAmountContinue()
        assertTrue(viewModel.submit(requireNotNull(viewModel.confirmedRequest())))
        assertEquals(ExternalSendStatus.Sending(shieldedLeg = true), viewModel.uiState.value.status)
        assertFalse(viewModel.uiState.value.canContinueAmount)
        // Leaving while sending is allowed; it does not cancel the send.
        assertFalse(viewModel.onBack())

        // A later visit (new ViewModel) picks the same send up.
        val later = buildViewModel()
        assertEquals(ExternalSendStatus.Sending(shieldedLeg = true), later.uiState.value.status)
        gate.complete(ExternalSendResult.Sent("txid"))
        assertEquals(ExternalSendStatus.Done(ExternalSendResult.Sent("txid")), later.uiState.value.status)
    }

    @Test
    fun eachResult_mapsToItsOwnOutcome() {
        val results = listOf(
            ExternalSendResult.Sent(null),
            ExternalSendResult.NotSent(NotSentReason.INSUFFICIENT_FUNDS),
            ExternalSendResult.MayHaveGoneThrough,
            ExternalSendResult.InterruptedFundsReturnToShielded
        )
        results.forEach { result ->
            service.sendResult = result
            service.balances.getValue(SendBalance.CORE).value = twoDash
            val viewModel = buildViewModel()
            viewModel.goToAmount(coreAddress)
            viewModel.type("1")
            viewModel.onAmountContinue()
            assertTrue(viewModel.submit(requireNotNull(viewModel.confirmedRequest())))
            assertEquals(ExternalSendStatus.Done(result), viewModel.uiState.value.status)
            viewModel.onResultAcknowledged()
            assertEquals(ExternalSendStatus.Idle, viewModel.uiState.value.status)
        }

        assertEquals(R.string.external_send_sent_title, resultContentFor(results[0]).title)
        assertEquals(R.string.external_send_not_sent_insufficient_funds, resultContentFor(results[1]).message)
        assertEquals(R.string.external_send_interrupted_message, resultContentFor(results[3]).message)
    }

    @Test
    fun mayHaveGoneThrough_isTerminal_withNoRetry() {
        service.sendResult = ExternalSendResult.MayHaveGoneThrough
        service.balances.getValue(SendBalance.CORE).value = twoDash
        val viewModel = buildViewModel()
        viewModel.goToAmount(coreAddress)
        viewModel.type("1")
        viewModel.onAmountContinue()
        val request = requireNotNull(viewModel.confirmedRequest())
        assertTrue(viewModel.submit(request))

        val content = resultContentFor(ExternalSendResult.MayHaveGoneThrough)
        assertEquals(R.string.external_send_ambiguous_message, content.message)
        assertEquals(R.string.external_send_close, content.button)
        assertNotEquals(R.string.button_retry, content.button)
        // Unacknowledged: nothing can be confirmed or sent again.
        assertFalse(viewModel.uiState.value.canContinueAmount)
        assertNull(viewModel.confirmedRequest())
        assertFalse(executor.submit(request))
        assertEquals(1, service.sends.size)
    }

    @Test
    fun notSent_returnsToEditing() {
        service.sendResult = ExternalSendResult.NotSent(NotSentReason.NOT_SYNCED)
        service.balances.getValue(SendBalance.CORE).value = twoDash
        val viewModel = buildViewModel()
        viewModel.goToAmount(coreAddress)
        viewModel.type("1")
        viewModel.onAmountContinue()
        viewModel.submit(requireNotNull(viewModel.confirmedRequest()))
        assertEquals(R.string.external_send_edit, resultContentFor(ExternalSendResult.NotSent(NotSentReason.NOT_SYNCED)).button)

        viewModel.onResultAcknowledged()
        assertEquals(ExternalSendStep.AMOUNT, viewModel.uiState.value.step)
        assertFalse(viewModel.uiState.value.finished)
        assertTrue(viewModel.uiState.value.formEditable)
        assertTrue(viewModel.uiState.value.canContinueAmount)
    }

    @Test
    fun anAcknowledgedOutcome_endsTheFlow_andTheFormStaysInert_onceTheExecutorIsIdle() {
        for (result in listOf(
            ExternalSendResult.Sent("txid"),
            ExternalSendResult.MayHaveGoneThrough,
            ExternalSendResult.InterruptedFundsReturnToShielded
        )) {
            executor = newExecutor()
            service.sendResult = result
            service.max[SendBalance.CORE] = oneDash
            val saved = SavedStateHandle()
            val clearGate = CompletableDeferred<Unit>()
            marker.clearGate = clearGate
            val viewModel = sendOnce(buildViewModel(saved))
            assertEquals(ExternalSendStatus.Done(result), viewModel.uiState.value.status)

            viewModel.onResultAcknowledged()
            // Acknowledging (the marker still clearing): inert.
            assertTrue(viewModel.uiState.value.finished)
            assertFalse(viewModel.uiState.value.canContinueAmount)

            clearGate.complete(Unit)
            marker.clearGate = null
            assertEquals(ExternalSendExecution.Idle, executor.state.value)
            with(viewModel.uiState.value) {
                assertEquals(ExternalSendStatus.Idle, status)
                assertFalse(formEditable)
                assertFalse(canContinueAmount)
                assertFalse(canContinueAddress)
            }
            // Nothing in the form reacts, and back leaves instead of reopening the address page.
            val amountText = viewModel.uiState.value.amountText
            viewModel.onKeyInput("5")
            viewModel.onMaxClick()
            viewModel.onShowSourceSheet()
            viewModel.onAmountContinue()
            with(viewModel.uiState.value) {
                assertEquals(amountText, this.amountText)
                assertFalse(isMax)
                assertFalse(showSourceSheet)
                assertNull(confirm)
            }
            assertNull(viewModel.confirmedRequest())
            assertFalse(viewModel.onBack())

            // A process restore after acknowledging cannot re-enable the form.
            val restored = buildViewModel(saved)
            assertTrue(restored.uiState.value.finished)
            assertFalse(restored.uiState.value.canContinueAmount)
            service.sends.clear()
            marker.value = false
        }
    }

    // ── prefill and restore ──────────────────────────────────────────────────

    @Test
    fun aScannedDashUri_opensTheAmountStep_withItsAmount() {
        val viewModel = buildViewModel(
            SavedStateHandle(mapOf(ExternalSendViewModel.ARG_PREFILL to "dash:$coreAddress?amount=0.5"))
        )
        with(viewModel.uiState.value) {
            assertEquals(ExternalSendStep.AMOUNT, step)
            assertEquals(SendDestination.Core(coreAddress), destination)
            assertEquals(Dash(50_000_000), amount)
        }
    }

    @Test
    fun savedState_restoresAddressAmountAndSource() {
        val saved = SavedStateHandle()
        val first = buildViewModel(saved)
        first.goToAmount(platformAddress)
        first.onSourceSelected(SendBalance.PLATFORM)
        first.type("1.25")

        val restored = buildViewModel(saved)
        with(restored.uiState.value) {
            assertEquals(ExternalSendStep.AMOUNT, step)
            assertEquals(platformAddress, addressInput)
            assertEquals(SendDestination.Platform(platformAddress), destination)
            assertEquals("1.25", amountText)
            assertEquals(SendBalance.PLATFORM, source)
            assertTrue(sourceChosenByUser)
        }
    }

    @Test
    fun savedState_restoresAMaxAmountOnAUserChosenSource() {
        service.balances.getValue(SendBalance.PLATFORM).value = twoDash
        service.max[SendBalance.PLATFORM] = oneDash
        val saved = SavedStateHandle()
        val first = buildViewModel(saved)
        first.goToAmount(platformAddress)
        first.onSourceSelected(SendBalance.PLATFORM)
        first.onMaxClick()

        val restored = buildViewModel(saved)
        assertEquals(SendBalance.PLATFORM, restored.uiState.value.source)
        assertTrue(restored.uiState.value.isMax)
        assertEquals(oneDash, restored.uiState.value.amount)
    }

    @Test
    fun back_closesSheets_thenReturnsToTheAddress_thenLeaves() {
        service.balances.getValue(SendBalance.CORE).value = twoDash
        val viewModel = buildViewModel()
        viewModel.goToAmount(coreAddress)
        viewModel.onShowSourceSheet()
        assertTrue(viewModel.onBack())
        assertFalse(viewModel.uiState.value.showSourceSheet)
        assertTrue(viewModel.onBack())
        assertEquals(ExternalSendStep.ADDRESS, viewModel.uiState.value.step)
        assertFalse(viewModel.onBack())
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @Test
    fun shorten_keepsTheFirst7AndLast8() {
        assertEquals("tdash1z...lrs27eg4", ExternalSendViewModel.shorten("tdash1zqumn3c48sdenak7qr0f8jplrs27eg4"))
        assertEquals("short", ExternalSendViewModel.shorten("short"))
    }

    @Test
    fun scanAction_routesEachKindOfCode() {
        val connect = { input: String -> input.startsWith("dash-key:") }
        assertEquals(
            AdvancedScanAction.DashConnect("dash-key:abc"),
            AdvancedSendViewModel.scanActionFor("dash-key:abc", network, supportsConnect = true, isDashConnect = connect)
        )
        assertEquals(
            AdvancedScanAction.Error(wrongNetwork = false),
            AdvancedSendViewModel.scanActionFor("dash-key:abc", network, supportsConnect = false, isDashConnect = connect)
        )
        val bip70 = "dash:$coreAddress?r=https://example.com/invoice"
        assertEquals(
            AdvancedScanAction.PaymentRequest(bip70),
            AdvancedSendViewModel.scanActionFor(bip70, network, true, connect)
        )
        val uri = "dash:$coreAddress?amount=1"
        assertEquals(AdvancedScanAction.ExternalSend(uri), AdvancedSendViewModel.scanActionFor(uri, network, true, connect))
        assertEquals(
            AdvancedScanAction.ExternalSend(identityId),
            AdvancedSendViewModel.scanActionFor(identityId, network, true, connect)
        )
        assertEquals(
            AdvancedScanAction.Error(wrongNetwork = true),
            AdvancedSendViewModel.scanActionFor(mainnetCoreAddress, network, true, connect)
        )
    }

    // ── review round 1 regressions ───────────────────────────────────────────

    @Test
    fun confirm_aNonMaxSend_paysTheFeeOnTop_andOnlyACoreMaxIsFeeInclusive() {
        service.balances.getValue(SendBalance.CORE).value = twoDash
        service.fee = FeeEstimate(Dash(1_000), exact = false)
        val viewModel = buildViewModel()
        viewModel.goToAmount(coreAddress)
        viewModel.type("1")
        viewModel.onAmountContinue()
        val confirm = requireNotNull(viewModel.uiState.value.confirm)
        assertFalse(confirm.feeIncluded)
        assertEquals(oneDash, confirm.recipientAmount)
        assertEquals(oneDash + Dash(1_000), confirm.total)

        // A Max to a Shielded address is not a drain: the pool fee is paid on top.
        val shieldMax = ExternalSendConfirm(
            ExternalSendRequest(SendBalance.CORE, SendDestination.Shielded(shieldedAddress), oneDash, true, WALLET_A),
            FeeEstimate(Dash(52_001), exact = false),
            feeLoading = false
        )
        assertFalse(shieldMax.feeIncluded)
        assertEquals(oneDash, shieldMax.recipientAmount)
        assertEquals(oneDash + Dash(52_001), shieldMax.total)
    }

    @Test
    fun confirm_capturesTheBoundWallet_andTheSendCarriesIt_evenIfAnotherIsBoundLater() {
        service.balances.getValue(SendBalance.CORE).value = twoDash
        val viewModel = buildViewModel()
        viewModel.goToAmount(coreAddress)
        viewModel.type("1")
        viewModel.onAmountContinue()
        val request = requireNotNull(viewModel.confirmedRequest())
        assertEquals(WALLET_A, request.expectedWalletIdHex)

        service.walletId = "wallet-b"
        assertTrue(viewModel.submit(request))
        assertEquals(WALLET_A, service.sends.single().expectedWalletIdHex)
    }

    private fun sendOnce(viewModel: ExternalSendViewModel = buildViewModel()): ExternalSendViewModel {
        service.balances.getValue(SendBalance.CORE).value = twoDash
        viewModel.goToAmount(coreAddress)
        viewModel.type("1")
        viewModel.onAmountContinue()
        assertTrue(viewModel.submit(requireNotNull(viewModel.confirmedRequest())))
        return viewModel
    }

    @Test
    fun inFlightMarker_isWrittenBeforeTheSend_keptOnSentUntilAcknowledged_clearedOnNotSent() {
        val gate = CompletableDeferred<ExternalSendResult>()
        service.sendGate = gate
        val viewModel = sendOnce()
        assertEquals(1, service.sends.size)
        assertTrue("set before the service runs", marker.value)
        gate.complete(ExternalSendResult.Sent("txid"))
        // Sent keeps the restart protection until the user has seen it.
        assertTrue(marker.value)
        assertEquals(listOf("set"), marker.log)
        // A process death now restarts into the conservative warning, never the editable form.
        assertEquals(ExternalSendExecution.InterruptedByRestart, newExecutor().state.value)

        viewModel.onResultAcknowledged()
        assertFalse(marker.value)
        assertEquals(listOf("set", "clear"), marker.log)
        assertEquals(ExternalSendExecution.Idle, executor.state.value)
        service.sendGate = null
        service.sendResult = ExternalSendResult.NotSent(NotSentReason.NOT_SYNCED)
        marker.log.clear()
        sendOnce()
        assertFalse(marker.value)
        assertEquals(listOf("set", "clear"), marker.log)
    }

    @Test
    fun inFlightMarker_survivesAnUnknownOutcome_untilAcknowledged() {
        for (result in listOf(ExternalSendResult.MayHaveGoneThrough, ExternalSendResult.InterruptedFundsReturnToShielded)) {
            marker.value = false
            executor = newExecutor()
            service.sendResult = result
            val viewModel = sendOnce()
            assertTrue(marker.value)
            viewModel.onResultAcknowledged()
            assertFalse(marker.value)
            assertEquals(ExternalSendExecution.Idle, executor.state.value)
        }
    }

    @Test
    fun aDoubleAcknowledgement_clearsOnce_andCannotClearALaterSendsMarker() {
        service.sendResult = ExternalSendResult.Sent("txid")
        val viewModel = sendOnce()
        val clearGate = CompletableDeferred<Unit>()
        marker.clearGate = clearGate

        executor.acknowledge()
        // Claimed: not Idle (no new send yet), no result dialog, the form inert.
        assertEquals(ExternalSendExecution.Acknowledging, executor.state.value)
        assertEquals(ExternalSendStatus.Restoring, viewModel.uiState.value.status)
        executor.acknowledge()
        viewModel.onResultAcknowledged()
        assertEquals(1, marker.clearCalls)
        assertFalse(executor.submit(service.sends.single()))

        clearGate.complete(Unit)
        marker.clearGate = null
        assertEquals(ExternalSendExecution.Idle, executor.state.value)
        assertEquals(1, marker.clearCalls)

        // A later send's marker survives a stale acknowledgement.
        val gate = CompletableDeferred<ExternalSendResult>()
        service.sendGate = gate
        assertTrue(executor.submit(service.sends.single()))
        executor.acknowledge()
        assertTrue(marker.value)
        assertEquals(1, marker.clearCalls)
        gate.complete(ExternalSendResult.MayHaveGoneThrough)
        assertTrue(marker.value)
    }

    // ── the transaction result screen (a Sent with its txid) ─────────────────

    private val lockTxId = "0123456789abcdef".repeat(4)

    @Test
    fun sentWithItsTxId_opensTheTransactionResult_onceUntilAcknowledged() {
        val gate = CompletableDeferred<ExternalSendResult>()
        service.sendGate = gate
        val viewModel = sendOnce()
        assertNull("nothing while sending", viewModel.openTransactionResult.value)

        gate.complete(ExternalSendResult.Sent(lockTxId))
        assertEquals(lockTxId, viewModel.uiState.value.openTransactionResult)
        assertEquals(lockTxId, viewModel.openTransactionResult.value)
        assertTrue(viewModel.takeTransactionResult(lockTxId))
        assertFalse("another txid is not the outcome", viewModel.takeTransactionResult("ab".repeat(32)))

        viewModel.onResultAcknowledged()
        // Cleared at once, before the executor settles: never opened twice.
        assertNull(viewModel.uiState.value.openTransactionResult)
        assertNull(viewModel.openTransactionResult.value)
        assertFalse(viewModel.takeTransactionResult(lockTxId))
        assertTrue(viewModel.uiState.value.finished)
        assertEquals(ExternalSendExecution.Idle, executor.state.value)
        assertFalse(marker.value)
    }

    @Test
    fun sentWithItsTxId_isOfferedAgain_toAScreenCreatedBeforeItWasAcknowledged_butNotAfter() {
        service.sendResult = ExternalSendResult.Sent(lockTxId)
        val saved = SavedStateHandle()
        sendOnce(buildViewModel(saved))
        // The user left while it was sending (the executor is app-scoped): a new screen opens it.
        val returned = buildViewModel()
        assertEquals(lockTxId, returned.openTransactionResult.value)

        returned.onResultAcknowledged()
        // A recreation (config change) of the acknowledged screen restores finished: nothing to open.
        val recreated = buildViewModel(SavedStateHandle(mapOf(ExternalSendViewModel.KEY_FINISHED to true)))
        assertNull(recreated.openTransactionResult.value)
        assertNull(buildViewModel().openTransactionResult.value)
    }

    @Test
    fun everyOtherOutcome_keepsTheInScreenResult() {
        val others = listOf(
            ExternalSendResult.Sent(null),
            ExternalSendResult.Sent("txid"),
            ExternalSendResult.Sent(lockTxId.dropLast(1) + "z"),
            ExternalSendResult.NotSent(NotSentReason.NOT_SYNCED),
            ExternalSendResult.MayHaveGoneThrough,
            ExternalSendResult.InterruptedFundsReturnToShielded
        )
        for (result in others) {
            executor = newExecutor()
            service.sendResult = result
            val viewModel = sendOnce()
            assertEquals(ExternalSendStatus.Done(result), viewModel.uiState.value.status)
            assertNull("$result", viewModel.uiState.value.openTransactionResult)
            assertNull("$result", viewModel.openTransactionResult.value)
            viewModel.onResultAcknowledged()
            marker.value = false
        }
        // The restart warning has no txid either.
        marker.value = true
        executor = newExecutor()
        assertNull(buildViewModel().uiState.value.openTransactionResult)
    }

    @Test
    fun inFlightMarker_unwritable_sendsNothing() {
        marker.failWrites = true
        sendOnce()
        assertTrue(service.sends.isEmpty())
        assertEquals(
            ExternalSendExecution.Finished(
                ExternalSendRequest(SendBalance.CORE, SendDestination.Core(coreAddress), oneDash, false, WALLET_A),
                ExternalSendResult.NotSent(NotSentReason.GENERIC)
            ),
            executor.state.value
        )
    }

    @Test
    fun aFreshProcess_withTheMarkerSet_showsMayHaveGoneThrough_insteadOfTheEditableForm() {
        // The previous process restored the form; the send it ran left the marker behind.
        val saved = SavedStateHandle()
        service.balances.getValue(SendBalance.CORE).value = twoDash
        buildViewModel(saved).apply {
            goToAmount(coreAddress)
            type("1")
        }
        marker.value = true
        executor = newExecutor()

        val restored = buildViewModel(saved)
        assertEquals(
            ExternalSendStatus.Done(ExternalSendResult.MayHaveGoneThrough, afterRestart = true),
            restored.uiState.value.status
        )
        assertFalse(restored.uiState.value.canContinueAmount)
        restored.onAmountContinue()
        assertNull(restored.confirmedRequest())
        assertEquals(
            R.string.external_send_ambiguous_after_restart_message,
            resultContentFor(ExternalSendResult.MayHaveGoneThrough, afterRestart = true).message
        )

        restored.onResultAcknowledged()
        assertFalse(marker.value)
        assertEquals(ExternalSendStatus.Idle, restored.uiState.value.status)
    }

    @Test
    fun aFreshProcess_withAnUnreadableMarker_failsClosed() {
        marker.failReads = true
        executor = newExecutor()
        assertEquals(ExternalSendExecution.InterruptedByRestart, executor.state.value)
    }

    private val usd100 = ExchangeRate("USD", "100")

    @Test
    fun switchingCurrency_neverChangesTheAmount_untilTheUserEditsIt() {
        val viewModel = buildViewModel(rate = usd100)
        viewModel.goToAmount(coreAddress)
        viewModel.type("0.12345678")
        val exact = Dash(12_345_678)
        assertEquals(exact, viewModel.uiState.value.amount)

        viewModel.onCurrencySelected(dashMode = false)
        assertEquals("12.35", viewModel.uiState.value.amountText)
        assertEquals(exact, viewModel.uiState.value.amount)
        viewModel.onCurrencySelected(dashMode = true)
        assertEquals(exact, viewModel.uiState.value.amount)
        assertEquals("0.12345678", viewModel.uiState.value.amountText)

        // An edit re-parses what is on show.
        viewModel.onCurrencySelected(dashMode = false)
        viewModel.onKeyInput("back")
        assertNull(viewModel.uiState.value.pinnedAmount)
        assertEquals(Dash(12_300_000), viewModel.uiState.value.amount)
    }

    @Test
    fun aUriAmount_staysExactAcrossACurrencySwitch() {
        val viewModel = buildViewModel(
            SavedStateHandle(mapOf(ExternalSendViewModel.ARG_PREFILL to "dash:$coreAddress?amount=0.12345678")),
            rate = usd100
        )
        viewModel.onCurrencySelected(dashMode = false)
        assertEquals(Dash(12_345_678), viewModel.uiState.value.amount)
    }

    @Test
    fun max_followsTheSource_acrossACurrencySwitch() {
        val shieldedMax = Dash(123_456_789)
        service.balances.getValue(SendBalance.SHIELDED).value = twoDash
        service.balances.getValue(SendBalance.PLATFORM).value = twoDash
        service.max[SendBalance.SHIELDED] = shieldedMax
        service.max[SendBalance.PLATFORM] = oneDash
        val viewModel = buildViewModel(rate = usd100)
        viewModel.goToAmount(platformP2shAddress)
        viewModel.onMaxClick()
        viewModel.onCurrencySelected(dashMode = false)
        assertEquals(shieldedMax, viewModel.uiState.value.amount)

        viewModel.onSourceSelected(SendBalance.PLATFORM)
        with(viewModel.uiState.value) {
            assertTrue(isMax)
            assertFalse(dashMode)
            assertEquals(oneDash, amount)
        }
    }

    // ── Max follows a source change ──────────────────────────────────────────

    private val coreMax = Dash(3_064_752)
    private val shieldedMax = Dash(1_500_000)

    /** Core and Shielded funded, each with its own Max; [platformAddress] opens on Core. */
    private fun maxOnCore(saved: SavedStateHandle = SavedStateHandle()): ExternalSendViewModel {
        service.balances.getValue(SendBalance.CORE).value = twoDash
        service.balances.getValue(SendBalance.SHIELDED).value = twoDash
        service.max[SendBalance.CORE] = coreMax
        service.max[SendBalance.SHIELDED] = shieldedMax
        val viewModel = buildViewModel(saved)
        viewModel.goToAmount(platformAddress)
        assertEquals(SendBalance.CORE, viewModel.uiState.value.source)
        viewModel.onMaxClick()
        assertEquals(coreMax, viewModel.uiState.value.amount)
        return viewModel
    }

    @Test
    fun maxThenSwitchingSource_becomesTheNewSourcesMax() {
        val gate = CompletableDeferred<Unit>()
        service.maxGates[SendBalance.SHIELDED] = gate
        val viewModel = maxOnCore()

        viewModel.onSourceSelected(SendBalance.SHIELDED)
        // While the new source's Max is read, nothing is sendable: never the old source's Max.
        with(viewModel.uiState.value) {
            assertTrue(maxPending)
            assertFalse(isMax)
            assertEquals(Dash.ZERO, amount)
            assertEquals("0", amountText)
            assertFalse(canContinueAmount)
        }

        gate.complete(Unit)
        with(viewModel.uiState.value) {
            assertFalse(maxPending)
            assertTrue(isMax)
            assertEquals(shieldedMax, amount)
            assertEquals(shieldedMax.toKeypadString(), amountText)
            assertTrue(canContinueAmount)
        }
        viewModel.onAmountContinue()
        val request = viewModel.uiState.value.confirm?.request
        assertEquals(SendBalance.SHIELDED, request?.source)
        assertEquals(shieldedMax, request?.amount)
        assertEquals(true, request?.isMax)
    }

    @Test
    fun maxThenSwitchingToASourceWithNoMax_clearsTheAmount() {
        val viewModel = maxOnCore()
        service.max[SendBalance.PLATFORM] = null

        // Balance not known yet: Max is still pending.
        viewModel.onSourceSelected(SendBalance.PLATFORM)
        assertTrue(viewModel.uiState.value.maxPending)
        assertEquals(Dash.ZERO, viewModel.uiState.value.amount)

        // Known balance, no Max (fee unknown): the pending Max is dropped, the amount stays zero.
        service.balances.getValue(SendBalance.PLATFORM).value = twoDash
        with(viewModel.uiState.value) {
            assertFalse(maxPending)
            assertFalse(isMax)
            assertEquals(Dash.ZERO, amount)
            assertEquals("0", amountText)
            assertNull(pinnedAmount)
            assertFalse(canContinueAmount)
        }
    }

    @Test
    fun typingWhileMaxIsPending_cancelsIt() {
        val gate = CompletableDeferred<Unit>()
        service.maxGates[SendBalance.SHIELDED] = gate
        val viewModel = maxOnCore()
        viewModel.onSourceSelected(SendBalance.SHIELDED)
        assertTrue(viewModel.uiState.value.maxPending)

        viewModel.type("1")
        assertFalse(viewModel.uiState.value.maxPending)
        assertEquals(oneDash, viewModel.uiState.value.amount)

        gate.complete(Unit)
        with(viewModel.uiState.value) {
            assertFalse(isMax)
            assertEquals(shieldedMax, maxSendable)
            assertEquals(oneDash, amount)
        }
    }

    @Test
    fun aKeyTheKeypadRejects_stillCancelsAPendingMax() {
        service.maxGates[SendBalance.SHIELDED] = CompletableDeferred()
        val viewModel = maxOnCore()
        viewModel.onSourceSelected(SendBalance.SHIELDED)
        viewModel.onKeyInput("back")
        assertFalse(viewModel.uiState.value.maxPending)
        assertEquals(Dash.ZERO, viewModel.uiState.value.amount)
    }

    @Test
    fun aStaleSourcesMax_isIgnored() {
        val shieldedGate = CompletableDeferred<Unit>()
        val platformGate = CompletableDeferred<Unit>()
        service.maxGates[SendBalance.SHIELDED] = shieldedGate
        service.maxGates[SendBalance.PLATFORM] = platformGate
        service.balances.getValue(SendBalance.PLATFORM).value = twoDash
        service.max[SendBalance.PLATFORM] = oneDash
        val viewModel = maxOnCore()

        viewModel.onSourceSelected(SendBalance.SHIELDED)
        viewModel.onSourceSelected(SendBalance.PLATFORM)
        shieldedGate.complete(Unit)
        with(viewModel.uiState.value) {
            assertEquals(SendBalance.PLATFORM, source)
            assertTrue(maxPending)
            assertNull(maxSendable)
            assertEquals(Dash.ZERO, amount)
        }

        platformGate.complete(Unit)
        with(viewModel.uiState.value) {
            assertFalse(maxPending)
            assertTrue(isMax)
            assertEquals(oneDash, amount)
        }
    }

    @Test
    fun switchingSourceWithoutMax_keepsTheTypedAmount() {
        service.balances.getValue(SendBalance.CORE).value = twoDash
        service.balances.getValue(SendBalance.SHIELDED).value = twoDash
        service.max[SendBalance.SHIELDED] = shieldedMax
        val viewModel = buildViewModel()
        viewModel.goToAmount(platformAddress)
        viewModel.type("0.5")

        viewModel.onSourceSelected(SendBalance.SHIELDED)
        with(viewModel.uiState.value) {
            assertFalse(maxPending)
            assertFalse(isMax)
            assertEquals("0.5", amountText)
            assertEquals(Dash(50_000_000), amount)
        }
    }

    @Test
    fun aPendingMax_survivesProcessDeath_andResolvesOnRestore() {
        val saved = SavedStateHandle()
        service.maxGates[SendBalance.SHIELDED] = CompletableDeferred()
        val first = maxOnCore(saved)
        first.onSourceSelected(SendBalance.SHIELDED)
        assertTrue(first.uiState.value.maxPending)
        assertEquals(true, saved.get<Boolean>(ExternalSendViewModel.KEY_MAX_PENDING))
        assertNull(saved.get<Long>(ExternalSendViewModel.KEY_MAX_DUFFS))

        // The new process reads the Shielded Max at once, before the pending Max is restored.
        service.maxGates.remove(SendBalance.SHIELDED)
        val restored = buildViewModel(saved)
        with(restored.uiState.value) {
            assertEquals(SendBalance.SHIELDED, source)
            assertFalse(maxPending)
            assertTrue(isMax)
            assertEquals(shieldedMax, amount)
        }
        assertEquals(false, saved.get<Boolean>(ExternalSendViewModel.KEY_MAX_PENDING))
        assertEquals(shieldedMax.duffs, saved.get<Long>(ExternalSendViewModel.KEY_MAX_DUFFS))
    }

    @Test
    fun maxFollowsTheSource_whenAnAutomaticPickReplacesTheUsersSource() {
        service.hasIdentity.value = true
        service.balances.getValue(SendBalance.CORE).value = twoDash
        service.balances.getValue(SendBalance.IDENTITY).value = twoDash
        service.max[SendBalance.CORE] = coreMax
        service.max[SendBalance.IDENTITY] = oneDash
        val viewModel = buildViewModel()
        viewModel.goToAmount(platformAddress)
        viewModel.onSourceSelected(SendBalance.IDENTITY)
        assertEquals(SendBalance.IDENTITY, viewModel.uiState.value.source)
        viewModel.onMaxClick()
        assertEquals(oneDash, viewModel.uiState.value.amount)

        // The identity goes away: the source falls back to Core, and so does Max.
        service.hasIdentity.value = false
        with(viewModel.uiState.value) {
            assertEquals(SendBalance.CORE, source)
            assertTrue(isMax)
            assertEquals(coreMax, amount)
        }
    }

    @Test
    fun aNonCoreAmountRequest_opensTheAmountStep_withItsAmount() {
        val viewModel = buildViewModel(
            SavedStateHandle(mapOf(ExternalSendViewModel.ARG_PREFILL to "dash:$platformAddress?amount=0.5"))
        )
        with(viewModel.uiState.value) {
            assertEquals(ExternalSendStep.AMOUNT, step)
            assertEquals(SendDestination.Platform(platformAddress), destination)
            assertEquals(Dash(50_000_000), amount)
        }
    }

    @Test
    fun scanAction_keepsEveryInvoiceFormatOnTheBasicPath() {
        val connect = { _: String -> false }
        for (invoice in listOf(
            "pay:?r=https://example.com/invoice",
            "DASH:-ABCDEF",
            "dash:?r=https://example.com/invoice",
            "dash:$shieldedAddress?r=https://example.com/invoice"
        )) {
            assertEquals(invoice, AdvancedScanAction.PaymentRequest(invoice), AdvancedSendViewModel.scanActionFor(invoice, network, true, connect))
        }
        val request = "dash:$shieldedAddress?amount=1"
        assertEquals(AdvancedScanAction.ExternalSend(request), AdvancedSendViewModel.scanActionFor(request, network, true, connect))
        assertEquals(
            AdvancedScanAction.Error(wrongNetwork = true),
            AdvancedSendViewModel.scanActionFor("dash:$mainnetCoreAddress", network, true, connect)
        )
        assertEquals(
            AdvancedScanAction.Error(wrongNetwork = false),
            AdvancedSendViewModel.scanActionFor("bitcoin:abc", network, true, connect)
        )
        assertEquals(
            AdvancedScanAction.Error(wrongNetwork = false),
            AdvancedSendViewModel.scanActionFor("3QJmnh", network, true, connect)
        )
    }

    @Test
    fun anInterruptedCoreToPlatformSend_isResumed_whenTheExecutorStarts_andEachTimeTheScreenOpens() {
        // setUp's executor ran one sweep.
        assertEquals(1, service.platformResumes)
        buildViewModel()
        assertEquals(2, service.platformResumes)
        buildViewModel()
        assertEquals(3, service.platformResumes)
    }
}
