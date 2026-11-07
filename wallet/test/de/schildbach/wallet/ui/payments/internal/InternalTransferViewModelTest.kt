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

package de.schildbach.wallet.ui.payments.internal

import androidx.lifecycle.SavedStateHandle
import de.schildbach.wallet.service.platform.sdk.ExternalSendResult
import de.schildbach.wallet.service.platform.sdk.FeeEstimate
import de.schildbach.wallet.service.platform.sdk.InternalTransferService
import de.schildbach.wallet.service.platform.sdk.InternalTransferSupport
import de.schildbach.wallet.service.platform.sdk.NotSentReason
import de.schildbach.wallet.service.platform.sdk.SendBalance
import de.schildbach.wallet.service.platform.sdk.SendBalance.CORE
import de.schildbach.wallet.service.platform.sdk.SendBalance.IDENTITY
import de.schildbach.wallet.service.platform.sdk.SendBalance.PLATFORM
import de.schildbach.wallet.service.platform.sdk.SendBalance.SHIELDED
import de.schildbach.wallet.ui.payments.send.ExternalSendInFlightMarker
import de.schildbach.wallet.ui.payments.send.ExternalSendStatus
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
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InternalTransferViewModelTest {

    private companion object {
        const val WALLET_A = "wallet-a"
    }

    private val oneDash = Dash.COIN
    private val twoDash = Dash.COIN * 2L

    /** A fake service: every answer is a field the test sets; every transfer is recorded. */
    private class FakeService : InternalTransferService {
        val balances = SendBalance.entries.associateWith { MutableStateFlow<Dash?>(null) }
        val hasIdentity = MutableStateFlow<Boolean?>(true)
        val shielded = MutableStateFlow<Boolean?>(true)
        var fee: FeeEstimate? = null
        val max = mutableMapOf<Pair<SendBalance, SendBalance>, Dash?>()
        var gate: CompletableDeferred<ExternalSendResult>? = null
        var result: ExternalSendResult = ExternalSendResult.Sent(null)
        val transfers = mutableListOf<InternalTransferRequest>()
        var walletId: String? = WALLET_A

        override fun observeBalance(balance: SendBalance): Flow<Dash?> = balances.getValue(balance)
        override fun observeHasIdentity(): Flow<Boolean?> = hasIdentity
        override fun observeShieldedEnabled(): Flow<Boolean?> = shielded
        override fun currentWalletIdOrNull(): String? = walletId
        override suspend fun estimateFee(from: SendBalance, to: SendBalance, amount: Dash) = fee
        override suspend fun maxSendable(from: SendBalance, to: SendBalance): Dash? = max[from to to]
        override suspend fun transfer(
            from: SendBalance,
            to: SendBalance,
            amount: Dash,
            isMax: Boolean,
            expectedWalletIdHex: String?
        ): ExternalSendResult {
            transfers += InternalTransferRequest(from, to, amount, isMax, expectedWalletIdHex)
            return gate?.await() ?: result
        }
    }

    private class FakeMarker : ExternalSendInFlightMarker {
        var value = false
        override suspend fun isSet(): Boolean = value
        override suspend fun set() {
            value = true
        }
        override suspend fun clear() {
            value = false
        }
    }

    private val service = FakeService()
    private val rate = MutableStateFlow<ExchangeRate?>(null)
    private val marker = FakeMarker()
    private lateinit var executor: InternalTransferExecutor

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        executor = InternalTransferExecutor(service, marker, CoroutineScope(UnconfinedTestDispatcher()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(savedState: SavedStateHandle = SavedStateHandle()): InternalTransferViewModel {
        val walletUIConfig = mockk<WalletUIConfig> {
            every { observe(WalletUIConfig.SELECTED_CURRENCY) } returns flowOf("USD")
        }
        val rates = mockk<ExchangeRatesProvider> {
            every { observeExchangeRate(any()) } returns rate
        }
        return InternalTransferViewModel(service, executor, walletUIConfig, rates, savedState)
    }

    private fun InternalTransferViewModel.type(amount: String) {
        amount.forEach { onKeyInput(it.toString()) }
    }

    private val InternalTransferViewModel.state get() = uiState.value

    // ── pair ─────────────────────────────────────────────────────────────────

    @Test
    fun `default pair follows the flag and identity until the user picks`() {
        val viewModel = buildViewModel()
        assertEquals(CORE to SHIELDED, viewModel.state.from to viewModel.state.to)
        service.shielded.value = false
        assertEquals(CORE to IDENTITY, viewModel.state.from to viewModel.state.to)
        service.hasIdentity.value = false
        assertEquals(CORE to PLATFORM, viewModel.state.from to viewModel.state.to)

        service.shielded.value = true
        viewModel.onSwap()
        assertEquals(SHIELDED to CORE, viewModel.state.from to viewModel.state.to)
        service.hasIdentity.value = true
        // The user's pair stays.
        assertEquals(SHIELDED to CORE, viewModel.state.from to viewModel.state.to)
    }

    @Test
    fun `swap exchanges From and To (AC3)`() {
        val viewModel = buildViewModel()
        viewModel.onSwap()
        assertEquals(SHIELDED to CORE, viewModel.state.from to viewModel.state.to)
        viewModel.onSwap()
        assertEquals(CORE to SHIELDED, viewModel.state.from to viewModel.state.to)
    }

    @Test
    fun `picking the other side's balance swaps (AC4)`() {
        val viewModel = buildViewModel()
        viewModel.onShowPicker(InternalTransferPicker.FROM)
        viewModel.onBalancePicked(SHIELDED)
        assertEquals(SHIELDED to CORE, viewModel.state.from to viewModel.state.to)
        assertNull(viewModel.state.picker)

        viewModel.onShowPicker(InternalTransferPicker.TO)
        viewModel.onBalancePicked(SHIELDED)
        assertEquals(CORE to SHIELDED, viewModel.state.from to viewModel.state.to)

        viewModel.onShowPicker(InternalTransferPicker.TO)
        viewModel.onBalancePicked(IDENTITY)
        assertEquals(CORE to IDENTITY, viewModel.state.from to viewModel.state.to)
    }

    @Test
    fun `a pick that leads to an unsupported pair is refused`() {
        val viewModel = buildViewModel()
        viewModel.onShowPicker(InternalTransferPicker.TO)
        assertFalse(viewModel.state.pickable(InternalTransferPicker.TO, PLATFORM))
        viewModel.onBalancePicked(PLATFORM)
        assertEquals(CORE to SHIELDED, viewModel.state.from to viewModel.state.to)
    }

    @Test
    fun `shielded flag off disables Shielded in the pickers`() {
        service.shielded.value = false
        val viewModel = buildViewModel()
        assertTrue(SHIELDED in viewModel.state.balances)
        assertFalse(viewModel.state.pickable(InternalTransferPicker.TO, SHIELDED))
        assertFalse(viewModel.state.pickable(InternalTransferPicker.FROM, SHIELDED))
        assertTrue(viewModel.state.pickable(InternalTransferPicker.FROM, PLATFORM))
    }

    @Test
    fun `identity is hidden without one, and while unknown`() {
        service.hasIdentity.value = null
        val viewModel = buildViewModel()
        assertFalse(IDENTITY in viewModel.state.balances)
        service.hasIdentity.value = false
        assertFalse(IDENTITY in viewModel.state.balances)
        service.hasIdentity.value = true
        assertTrue(IDENTITY in viewModel.state.balances)
    }

    @Test
    fun `a chosen Identity pair falls back to the default when the identity goes`() {
        val viewModel = buildViewModel()
        viewModel.onShowPicker(InternalTransferPicker.TO)
        viewModel.onBalancePicked(IDENTITY)
        service.hasIdentity.value = false
        assertEquals(CORE to SHIELDED, viewModel.state.from to viewModel.state.to)
        assertFalse(viewModel.state.pairChosenByUser)
    }

    @Test
    fun `a restored Identity pair survives an identity that is not known yet`() {
        val savedState = SavedStateHandle(
            mapOf(
                InternalTransferViewModel.KEY_FROM to IDENTITY.name,
                InternalTransferViewModel.KEY_TO to CORE.name,
                InternalTransferViewModel.KEY_PAIR_CHOSEN to true
            )
        )
        service.hasIdentity.value = null
        service.shielded.value = true // the flag arrives first
        val viewModel = buildViewModel(savedState)
        assertEquals(IDENTITY to CORE, viewModel.state.from to viewModel.state.to)
        assertTrue(viewModel.state.pairChosenByUser)
        assertEquals(true, savedState.get<Boolean>(InternalTransferViewModel.KEY_PAIR_CHOSEN))
        assertEquals(IDENTITY.name, savedState.get<String>(InternalTransferViewModel.KEY_FROM))
        // Unknown blocks Continue but keeps the pair.
        service.balances.getValue(IDENTITY).value = twoDash
        viewModel.type("1")
        assertFalse(viewModel.state.canContinue)

        service.hasIdentity.value = true
        assertEquals(IDENTITY to CORE, viewModel.state.from to viewModel.state.to)
        assertTrue(viewModel.state.canContinue)
    }

    @Test
    fun `a restored Identity pair falls back to the default once there is confirmedly no identity`() {
        val savedState = SavedStateHandle(
            mapOf(
                InternalTransferViewModel.KEY_FROM to IDENTITY.name,
                InternalTransferViewModel.KEY_TO to CORE.name,
                InternalTransferViewModel.KEY_PAIR_CHOSEN to true
            )
        )
        service.hasIdentity.value = null
        val viewModel = buildViewModel(savedState)
        service.hasIdentity.value = false
        assertEquals(CORE to SHIELDED, viewModel.state.from to viewModel.state.to)
        assertFalse(viewModel.state.pairChosenByUser)
        assertEquals(false, savedState.get<Boolean>(InternalTransferViewModel.KEY_PAIR_CHOSEN))
    }

    @Test
    fun `a chosen Shielded pair survives an unreadable flag, and falls back when the flag is off`() {
        val viewModel = buildViewModel()
        viewModel.onSwap() // Shielded → Core, chosen
        service.shielded.value = null
        assertEquals(SHIELDED to CORE, viewModel.state.from to viewModel.state.to)
        assertEquals(InternalTransferSupport.SHIELDED_OFF, viewModel.state.support)
        service.shielded.value = false
        assertEquals(CORE to IDENTITY, viewModel.state.from to viewModel.state.to)
        assertFalse(viewModel.state.pairChosenByUser)
    }

    // ── amount ───────────────────────────────────────────────────────────────

    @Test
    fun `Max is the service's max for the pair`() {
        service.balances.getValue(CORE).value = twoDash
        service.max[CORE to SHIELDED] = oneDash
        val viewModel = buildViewModel()
        viewModel.onMaxClick()
        assertEquals(oneDash, viewModel.state.amount)
        assertTrue(viewModel.state.isMax)
        assertTrue(viewModel.state.canContinue)
    }

    @Test
    fun `an amount above Max is insufficient and disables Continue (AC7)`() {
        service.balances.getValue(CORE).value = twoDash
        service.max[CORE to SHIELDED] = oneDash
        val viewModel = buildViewModel()
        viewModel.type("1.5")
        assertTrue(viewModel.state.insufficientBalance)
        assertFalse(viewModel.state.canContinue)
        viewModel.onKeyInput("back")
        assertFalse(viewModel.state.insufficientBalance)
        assertTrue(viewModel.state.canContinue)
    }

    @Test
    fun `an unknown balance blocks Continue`() {
        val viewModel = buildViewModel()
        viewModel.type("1")
        assertNull(viewModel.state.balance)
        assertFalse(viewModel.state.canContinue)
    }

    @Test
    fun `an unsupported pair (reached by a swap) blocks Continue`() {
        service.balances.getValue(IDENTITY).value = twoDash
        val viewModel = buildViewModel()
        viewModel.onShowPicker(InternalTransferPicker.FROM)
        viewModel.onBalancePicked(SHIELDED) // Shielded → Core
        viewModel.onShowPicker(InternalTransferPicker.TO)
        viewModel.onBalancePicked(IDENTITY) // Shielded → Identity
        viewModel.onSwap()
        assertEquals(IDENTITY to SHIELDED, viewModel.state.from to viewModel.state.to)
        assertEquals(InternalTransferSupport.NOT_SUPPORTED_YET, viewModel.state.support)
        viewModel.type("1")
        assertFalse(viewModel.state.canContinue)
        viewModel.onContinue()
        assertNull(viewModel.state.confirm)
    }

    @Test
    fun `changing the pair drops Max but keeps its exact amount`() {
        service.balances.getValue(CORE).value = twoDash
        service.max[CORE to SHIELDED] = oneDash
        val viewModel = buildViewModel()
        viewModel.onMaxClick()
        viewModel.onShowPicker(InternalTransferPicker.TO)
        viewModel.onBalancePicked(IDENTITY)
        assertFalse(viewModel.state.isMax)
        assertEquals(oneDash, viewModel.state.amount)
    }

    // ── confirm and transfer ─────────────────────────────────────────────────

    @Test
    fun `Continue builds the confirm for the own To balance with the captured wallet`() {
        service.balances.getValue(CORE).value = twoDash
        service.fee = FeeEstimate(Dash(1000), exact = false)
        val viewModel = buildViewModel()
        viewModel.type("1")
        viewModel.onContinue()
        val confirm = requireNotNull(viewModel.state.confirm)
        assertEquals(InternalTransferRequest(CORE, SHIELDED, oneDash, false, WALLET_A), confirm.request)
        assertFalse(confirm.feeLoading)
        assertEquals(oneDash + Dash(1000), confirm.total)
        assertEquals(confirm.request, viewModel.confirmedRequest())
    }

    @Test
    fun `submit runs exactly the confirmed request and mirrors the executor`() {
        service.balances.getValue(CORE).value = twoDash
        service.gate = CompletableDeferred()
        val viewModel = buildViewModel()
        viewModel.type("1")
        viewModel.onContinue()
        val request = requireNotNull(viewModel.confirmedRequest())
        assertTrue(viewModel.submit(request))
        assertEquals(ExternalSendStatus.Sending(shieldedLeg = true), viewModel.state.status)
        assertFalse(viewModel.state.formEditable)
        assertNull(viewModel.state.confirm)

        viewModel.onHideSending()
        assertTrue(viewModel.state.sendingHidden)

        service.gate?.complete(ExternalSendResult.Sent(null))
        assertEquals(ExternalSendStatus.Done(ExternalSendResult.Sent(null)), viewModel.state.status)
        assertFalse(viewModel.state.sendingHidden)
        assertEquals(listOf(request), service.transfers)

        viewModel.onResultAcknowledged()
        assertEquals(ExternalSendStatus.Idle, viewModel.state.status)
        assertEquals("0", viewModel.state.amountText)
        assertFalse(marker.value)
    }

    @Test
    fun `a dismissed confirm is never submitted`() {
        service.balances.getValue(CORE).value = twoDash
        val viewModel = buildViewModel()
        viewModel.type("1")
        viewModel.onContinue()
        val request = requireNotNull(viewModel.confirmedRequest())
        viewModel.onDismissConfirm()
        assertFalse(viewModel.submit(request))
        assertTrue(service.transfers.isEmpty())
    }

    @Test
    fun `Not sent keeps the amount for a retry (AC14)`() {
        service.balances.getValue(CORE).value = twoDash
        service.result = ExternalSendResult.NotSent(NotSentReason.INSUFFICIENT_FUNDS)
        val viewModel = buildViewModel()
        viewModel.type("1")
        viewModel.onContinue()
        viewModel.submit(requireNotNull(viewModel.confirmedRequest()))
        viewModel.onResultAcknowledged()
        assertEquals(ExternalSendStatus.Idle, viewModel.state.status)
        assertEquals(oneDash, viewModel.state.amount)
        assertTrue(viewModel.state.canContinue)
    }

    @Test
    fun `pair and amount survive process death`() {
        service.balances.getValue(SHIELDED).value = twoDash
        val savedState = SavedStateHandle()
        val first = buildViewModel(savedState)
        first.onSwap()
        first.type("1.5")
        val restored = buildViewModel(savedState)
        assertEquals(SHIELDED to CORE, restored.state.from to restored.state.to)
        assertEquals("1.5", restored.state.amountText)
        assertTrue(restored.state.pairChosenByUser)
    }

    @Test
    fun `confirm notes reuse the External Send notes`() {
        assertEquals(
            listOf(R.string.external_send_shielded_not_instant, R.string.external_send_shielded_public_to_core),
            internalConfirmNotes(SHIELDED, CORE)
        )
        assertEquals(listOf(R.string.internal_transfer_withdrawal_not_instant), internalConfirmNotes(IDENTITY, CORE))
        assertEquals(emptyList<Int>(), internalConfirmNotes(CORE, SHIELDED))
    }

    // ── rate changes ─────────────────────────────────────────────────────────

    /** 2 DASH on Core, 100 fiat typed at 100 USD/DASH (1 DASH), then Continue. */
    private fun confirmOneDashTypedInFiat(): InternalTransferViewModel {
        rate.value = ExchangeRate("USD", "100")
        service.balances.getValue(CORE).value = twoDash
        val viewModel = buildViewModel()
        viewModel.onCurrencySelected(dashMode = false)
        viewModel.type("100")
        assertEquals(oneDash, viewModel.state.amount)
        viewModel.onContinue()
        assertEquals(oneDash, requireNotNull(viewModel.state.confirm).request.amount)
        return viewModel
    }

    @Test
    fun `a rate change while the confirm is open still submits the captured request`() {
        val viewModel = confirmOneDashTypedInFiat()
        // The typed fiat is now 2.5 DASH: the live form no longer fits, the captured 1 DASH does.
        rate.value = ExchangeRate("USD", "40")
        assertFalse(viewModel.state.canContinue)

        val request = requireNotNull(viewModel.confirmedRequest())
        assertEquals(oneDash, request.amount)
        assertTrue(viewModel.submit(request))
        assertEquals(listOf(request), service.transfers)
        assertFalse(viewModel.state.confirmRefused)
    }

    @Test
    fun `Confirm is refused explicitly when the balance no longer covers the captured request`() {
        val viewModel = confirmOneDashTypedInFiat()
        rate.value = ExchangeRate("USD", "40")
        service.balances.getValue(CORE).value = Dash(50_000_000)

        assertNull(viewModel.confirmedRequest())
        assertNull(viewModel.state.confirm)
        assertTrue(viewModel.state.confirmRefused)
        assertTrue(service.transfers.isEmpty())

        // Editing the amount clears the refusal.
        viewModel.onKeyInput("back")
        assertFalse(viewModel.state.confirmRefused)
    }

    @Test
    fun `a balance drop during authentication refuses the submit explicitly`() {
        val viewModel = confirmOneDashTypedInFiat()
        val request = requireNotNull(viewModel.confirmedRequest())
        service.balances.getValue(CORE).value = Dash(50_000_000)

        assertFalse(viewModel.submit(request))
        assertNull(viewModel.state.confirm)
        assertTrue(viewModel.state.confirmRefused)
        assertTrue(service.transfers.isEmpty())
    }

    @Test
    fun `Max in fiat mode is re-rendered at a new rate`() {
        rate.value = ExchangeRate("USD", "100")
        service.balances.getValue(CORE).value = twoDash
        service.max[CORE to SHIELDED] = oneDash
        val viewModel = buildViewModel()
        viewModel.onCurrencySelected(dashMode = false)
        viewModel.onMaxClick()
        assertEquals("100.00", viewModel.state.amountText)

        rate.value = ExchangeRate("USD", "50")
        assertEquals("50.00", viewModel.state.amountText)
        assertEquals(oneDash, viewModel.state.amount)
        assertTrue(viewModel.state.isMax)
    }

    @Test
    fun `a typed fiat amount keeps its text and follows the rate in Dash`() {
        rate.value = ExchangeRate("USD", "100")
        service.balances.getValue(CORE).value = twoDash
        val viewModel = buildViewModel()
        viewModel.onCurrencySelected(dashMode = false)
        viewModel.type("100")

        rate.value = ExchangeRate("USD", "50")
        assertEquals("100", viewModel.state.amountText)
        assertEquals(twoDash, viewModel.state.amount)
    }
}
