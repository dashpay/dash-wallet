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

package de.schildbach.wallet.ui.payments

import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.payments.ReceiveBalanceTab.CORE
import de.schildbach.wallet.ui.payments.ReceiveBalanceTab.IDENTITY
import de.schildbach.wallet.ui.payments.ReceiveBalanceTab.PLATFORM
import de.schildbach.wallet.ui.payments.ReceiveBalanceTab.SHIELD
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.bitcoinj.core.Context
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.money.Coin
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.PaymentURI
import org.dash.wallet.common.services.ReceiveAddressUnavailableException
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveViewModelTest {
    private companion object {
        const val CORE_ADDRESS = "ydW78zVxRgNhANX2qtG4saSCC5ejNQjw2U"
        const val SHIELDED_ADDRESS = "tdash1qshieldedaddress"
        const val PLATFORM_ADDRESS = "tevo1qplatformaddress"
        const val IDENTITY_ID = "GWRSAVFMjXx8HpQFaNJMqBV7MBgMK4br5UESsB4S31Ec"
    }

    private val shieldedFlag = MutableStateFlow<Boolean?>(false)
    private val identity = MutableStateFlow(identityData(hasUsername = false))

    /** What the view model's identity read collects; [identity] unless a test fails it. */
    private var identitySource: Flow<BlockchainIdentityData> = identity
    private val mainDispatcher = UnconfinedTestDispatcher()

    private var coreAddress: String? = CORE_ADDRESS
    private val reads = mutableListOf<ReceiveBalanceTab>()

    private val sources = ReceiveAddressSources(
        core = ReceiveAddressSource { reads += CORE; coreAddress },
        shielded = ReceiveAddressSource { reads += SHIELD; SHIELDED_ADDRESS },
        platform = ReceiveAddressSource { reads += PLATFORM; PLATFORM_ADDRESS }
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun identityData(hasUsername: Boolean) = BlockchainIdentityData(
        creationState = if (hasUsername) IdentityCreationState.DONE else IdentityCreationState.NONE,
        creationStateErrorMessage = null,
        username = if (hasUsername) "johndoe" else null,
        usernameSecondary = null,
        userId = if (hasUsername) IDENTITY_ID else null,
        restoring = false
    )

    private fun buildViewModel(
        supportsPlatform: Boolean = true,
        receiveSources: ReceiveAddressSources = sources
    ): ReceiveViewModel {
        val platformRepo = mockk<PlatformRepo> {
            every { observeProfileByUserId(any()) } returns flowOf(null)
        }
        val identityConfig = mockk<BlockchainIdentityConfig> {
            every { observePreservingErrors() } returns identitySource
        }
        val dashPayConfig = mockk<DashPayConfig> {
            every { observePreservingErrors(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } returns shieldedFlag
        }
        return ReceiveViewModel(
            receiveSources,
            platformRepo,
            identityConfig,
            dashPayConfig,
            mockk<AnalyticsService>(relaxed = true),
            supportsPlatform
        )
    }

    // ── tab list ─────────────────────────────────────────────────────────────

    @Test
    fun `tabsFor shows Shield and Platform only with platform support and the flag, Identity only with a username`() {
        assertEquals(listOf(CORE), ReceiveViewModel.tabsFor(false, false, false))
        assertEquals(listOf(CORE), ReceiveViewModel.tabsFor(true, false, false))
        assertEquals(listOf(CORE), ReceiveViewModel.tabsFor(false, true, false))
        assertEquals(listOf(CORE, SHIELD, PLATFORM), ReceiveViewModel.tabsFor(true, true, false))
        assertEquals(listOf(CORE, IDENTITY), ReceiveViewModel.tabsFor(false, false, true))
        assertEquals(listOf(CORE, SHIELD, PLATFORM, IDENTITY), ReceiveViewModel.tabsFor(true, true, true))
    }

    @Test
    fun `the view model follows the flag and the username`() {
        val viewModel = buildViewModel()
        assertEquals(listOf(CORE), viewModel.uiState.value.tabs)

        shieldedFlag.value = true
        assertEquals(listOf(CORE, SHIELD, PLATFORM), viewModel.uiState.value.tabs)

        identity.value = identityData(hasUsername = true)
        assertEquals(listOf(CORE, SHIELD, PLATFORM, IDENTITY), viewModel.uiState.value.tabs)
        assertEquals("johndoe", viewModel.uiState.value.username)
    }

    @Test
    fun `without platform support the flag does not add Shield or Platform`() {
        shieldedFlag.value = true
        val viewModel = buildViewModel(supportsPlatform = false)
        assertEquals(listOf(CORE), viewModel.uiState.value.tabs)
    }

    @Test
    fun `a failing flag read hides Shield and Platform and keeps retrying`() = runBlocking {
        var attempts = 0
        val failingThenTrue = flow {
            attempts++
            if (attempts == 1) throw IOException("datastore")
            emit(true)
        }
        val values = shieldedEnabledFlow(failingThenTrue, retryDelayMs = { 0L })
            .take(2)
            .toList()
        assertEquals(listOf(false, true), values)
    }

    @Test
    fun `losing the selected tab falls back to Core and reloads it`() {
        shieldedFlag.value = true
        val viewModel = buildViewModel()
        viewModel.selectTab(SHIELD)
        assertEquals(SHIELD, viewModel.uiState.value.selectedTab)

        shieldedFlag.value = false
        val state = viewModel.uiState.value
        assertEquals(CORE, state.selectedTab)
        assertEquals(CORE_ADDRESS, state.address)
    }

    @Test
    fun `a failed identity read withholds the Identity tab until a retry reads it`() {
        identity.value = identityData(hasUsername = true)
        var attempts = 0
        identitySource = flow {
            attempts++
            if (attempts <= 2) throw IOException("datastore")
            emitAll(identity)
        }
        val viewModel = buildViewModel(supportsPlatform = false)
        viewModel.refresh()

        // failing: no Identity tab, no username, and Core is not reloaded by the fallback
        assertEquals(1, attempts)
        assertEquals(listOf(CORE), viewModel.uiState.value.tabs)
        assertNull(viewModel.uiState.value.username)
        val coreReads = reads.count { it == CORE }

        mainDispatcher.scheduler.advanceTimeBy(5_001)
        assertEquals(2, attempts)
        assertEquals(listOf(CORE), viewModel.uiState.value.tabs)
        assertNull(viewModel.uiState.value.username)
        assertEquals(coreReads, reads.count { it == CORE })

        // the read recovers on the same view model
        mainDispatcher.scheduler.advanceTimeBy(10_001)
        assertEquals(3, attempts)
        assertEquals(listOf(CORE, IDENTITY), viewModel.uiState.value.tabs)
        assertEquals("johndoe", viewModel.uiState.value.username)
        assertEquals(CORE, viewModel.uiState.value.selectedTab)
        assertEquals(coreReads, reads.count { it == CORE })
    }

    @Test
    fun `a failed identity read while Identity is selected falls back to Core, which stays after recovery`() {
        identity.value = identityData(hasUsername = true)
        val fail = CompletableDeferred<Unit>()
        var attempts = 0
        identitySource = flow {
            attempts++
            if (attempts == 1) {
                emit(identity.value)
                fail.await()
                throw IOException("datastore")
            }
            emitAll(identity)
        }
        val viewModel = buildViewModel(supportsPlatform = false)
        viewModel.selectTab(IDENTITY)
        assertEquals(IDENTITY, viewModel.uiState.value.selectedTab)
        assertEquals(IDENTITY_ID, viewModel.uiState.value.address)

        fail.complete(Unit)
        var state = viewModel.uiState.value
        assertEquals(listOf(CORE), state.tabs)
        assertEquals(CORE, state.selectedTab)
        assertEquals(CORE_ADDRESS, state.address)
        assertNull(state.username)
        val coreReads = reads.count { it == CORE }

        mainDispatcher.scheduler.advanceTimeBy(5_001)
        state = viewModel.uiState.value
        assertEquals(2, attempts)
        assertEquals(listOf(CORE, IDENTITY), state.tabs)
        assertEquals(CORE, state.selectedTab)
        assertEquals(CORE_ADDRESS, state.address)
        assertEquals("johndoe", state.username)
        assertEquals(coreReads, reads.count { it == CORE })
    }

    // ── switching tabs ───────────────────────────────────────────────────────

    @Test
    fun `switching tabs loads that tab's address with a bare QR payload`() {
        shieldedFlag.value = true
        identity.value = identityData(hasUsername = true)
        val viewModel = buildViewModel()
        viewModel.refresh()
        assertEquals(CORE_ADDRESS, viewModel.uiState.value.address)
        assertTrue(viewModel.uiState.value.qrPayload?.startsWith("dash:$CORE_ADDRESS") == true)

        viewModel.selectTab(SHIELD)
        assertEquals(SHIELDED_ADDRESS, viewModel.uiState.value.address)
        assertEquals(SHIELDED_ADDRESS, viewModel.uiState.value.qrPayload)

        viewModel.selectTab(PLATFORM)
        assertEquals(PLATFORM_ADDRESS, viewModel.uiState.value.address)
        assertEquals(PLATFORM_ADDRESS, viewModel.uiState.value.qrPayload)

        viewModel.selectTab(IDENTITY)
        assertEquals(IDENTITY_ID, viewModel.uiState.value.address)
        assertEquals(IDENTITY_ID, viewModel.uiState.value.qrPayload)

        assertEquals(listOf(CORE, SHIELD, PLATFORM), reads)
    }

    @Test
    fun `selecting a tab that is not shown does nothing`() {
        val viewModel = buildViewModel()
        viewModel.selectTab(SHIELD)
        assertEquals(CORE, viewModel.uiState.value.selectedTab)
        assertFalse(SHIELD in reads)
    }

    @Test
    fun `a read for a tab the user has left never publishes`() {
        shieldedFlag.value = true
        val shieldedGate = CompletableDeferred<String?>()
        val gatedSources = ReceiveAddressSources(
            core = ReceiveAddressSource { CORE_ADDRESS },
            shielded = ReceiveAddressSource { shieldedGate.await() },
            platform = ReceiveAddressSource { PLATFORM_ADDRESS }
        )
        val viewModel = buildViewModel(receiveSources = gatedSources)
        viewModel.selectTab(SHIELD)
        viewModel.selectTab(CORE)
        assertEquals(CORE_ADDRESS, viewModel.uiState.value.address)

        shieldedGate.complete(SHIELDED_ADDRESS)
        assertEquals(CORE, viewModel.uiState.value.selectedTab)
        assertEquals(CORE_ADDRESS, viewModel.uiState.value.address)
    }

    @Test
    fun `a resume during a read in flight does not start a second one`() {
        val gate = CompletableDeferred<String?>()
        var coreReads = 0
        val gatedSources = ReceiveAddressSources(
            core = ReceiveAddressSource { coreReads++; gate.await() },
            shielded = ReceiveAddressSource { null },
            platform = ReceiveAddressSource { null }
        )
        val viewModel = buildViewModel(receiveSources = gatedSources)
        viewModel.refresh()
        viewModel.refresh()
        assertEquals(1, coreReads)
        assertTrue(viewModel.uiState.value.loading)

        gate.complete(CORE_ADDRESS)
        assertEquals(CORE_ADDRESS, viewModel.uiState.value.address)
        assertFalse(viewModel.uiState.value.loading)
    }

    // ── fail closed ──────────────────────────────────────────────────────────

    @Test
    fun `an unavailable Core address gives no QR`() {
        coreAddress = null
        val viewModel = buildViewModel()
        viewModel.refresh()
        val state = viewModel.uiState.value
        assertNull(state.address)
        assertNull(state.qrPayload)
        assertTrue(state.unavailable)
    }

    @Test
    fun `a failed revalidation takes down the address that was on show`() {
        val viewModel = buildViewModel()
        viewModel.refresh()
        assertEquals(CORE_ADDRESS, viewModel.uiState.value.address)

        coreAddress = null
        viewModel.refresh()
        assertNull(viewModel.uiState.value.address)
        assertNull(viewModel.uiState.value.qrPayload)
    }

    @Test
    fun `a fresh page shows progress, not unavailable`() {
        val state = ReceiveUIState()
        assertTrue(state.loading)
        assertFalse(state.unavailable)
        assertNull(state.qrPayload)
    }

    @Test
    fun `a re-read after a failed read keeps unavailable on show until it answers`() {
        val gate = CompletableDeferred<String?>()
        var coreReads = 0
        val gatedSources = ReceiveAddressSources(
            core = ReceiveAddressSource { if (coreReads++ == 0) null else gate.await() },
            shielded = ReceiveAddressSource { null },
            platform = ReceiveAddressSource { null }
        )
        val viewModel = buildViewModel(receiveSources = gatedSources)
        viewModel.refresh()
        assertTrue(viewModel.uiState.value.unavailable)

        viewModel.refresh()
        assertTrue(viewModel.uiState.value.unavailable)

        gate.complete(CORE_ADDRESS)
        assertFalse(viewModel.uiState.value.unavailable)
        assertEquals(CORE_ADDRESS, viewModel.uiState.value.address)
    }

    @Test
    fun `a source that throws gives no QR`() {
        val throwingSources = ReceiveAddressSources(
            core = ReceiveAddressSource { throw IllegalStateException("Wallet not yet initialised") },
            shielded = ReceiveAddressSource { null },
            platform = ReceiveAddressSource { null }
        )
        val viewModel = buildViewModel(receiveSources = throwingSources)
        viewModel.refresh()
        assertNull(viewModel.uiState.value.qrPayload)
        assertTrue(viewModel.uiState.value.unavailable)
    }

    @Test
    fun `the Core source returns null when the engine cannot answer, never a dashj address`() = runBlocking {
        val dashjWallet = mockk<Wallet> {
            every { context } returns Context(TestNet3Params.get())
        }
        val walletData = mockk<WalletData> {
            every { wallet } returns dashjWallet
            every { currentReceiveAddressLive() } throws ReceiveAddressUnavailableException()
        }
        assertNull(CoreReceiveAddressSource(walletData).address())
    }

    @Test
    fun `the Core source returns null before the wallet is loaded`() = runBlocking {
        val walletData = mockk<WalletData> {
            every { wallet } returns null
        }
        assertNull(CoreReceiveAddressSource(walletData).address())
    }

    // ── specify amount, username, QR payload ─────────────────────────────────

    @Test
    fun `Specify amount is enabled on every tab with an address, and always on Core`() {
        // Core reads its own fresh address after the amount is entered, as it always has.
        assertTrue(ReceiveUIState(selectedTab = CORE).specifyAmountEnabled)
        assertTrue(ReceiveUIState(selectedTab = CORE, address = CORE_ADDRESS).specifyAmountEnabled)
        assertTrue(ReceiveUIState(selectedTab = SHIELD, address = SHIELDED_ADDRESS).specifyAmountEnabled)
        assertTrue(ReceiveUIState(selectedTab = PLATFORM, address = PLATFORM_ADDRESS).specifyAmountEnabled)
        assertTrue(ReceiveUIState(selectedTab = IDENTITY, address = IDENTITY_ID).specifyAmountEnabled)
        // No address on show: nothing to put in the request.
        assertFalse(ReceiveUIState(selectedTab = SHIELD).specifyAmountEnabled)
        assertFalse(ReceiveUIState(selectedTab = PLATFORM, loading = false).specifyAmountEnabled)
        assertFalse(ReceiveUIState(selectedTab = IDENTITY, loading = true).specifyAmountEnabled)
    }

    @Test
    fun `the view model enables Specify amount on each tab once its address is read`() {
        shieldedFlag.value = true
        identity.value = identityData(hasUsername = true)
        val viewModel = buildViewModel()
        viewModel.refresh()
        assertTrue(viewModel.uiState.value.specifyAmountEnabled)
        for (tab in listOf(SHIELD, PLATFORM, IDENTITY)) {
            viewModel.selectTab(tab)
            assertEquals(tab, viewModel.uiState.value.selectedTab)
            assertTrue("Specify amount on $tab", viewModel.uiState.value.specifyAmountEnabled)
        }
    }

    @Test
    fun `a tab whose address read fails disables Specify amount`() {
        shieldedFlag.value = true
        val failing = ReceiveAddressSources(
            core = sources.core,
            shielded = ReceiveAddressSource { null },
            platform = sources.platform
        )
        val viewModel = buildViewModel(receiveSources = failing)
        viewModel.selectTab(SHIELD)
        assertTrue(viewModel.uiState.value.unavailable)
        assertFalse(viewModel.uiState.value.specifyAmountEnabled)
    }

    @Test
    fun `the amount request is exactly a dash URI with the amount in DASH`() {
        val shielded = "tdash1zq2ksj5u7v5f0pn6lyl4m5k6c3l8wl3x9g0e7v"
        assertEquals(
            "dash:$shielded?amount=0.5",
            ReceiveViewModel.amountRequestPayload(shielded, Coin.valueOf(50_000_000L))
        )
        assertEquals(
            "dash:$PLATFORM_ADDRESS?amount=1",
            ReceiveViewModel.amountRequestPayload(PLATFORM_ADDRESS, Coin.COIN)
        )
        assertEquals(
            "dash:$IDENTITY_ID?amount=12.3456789",
            ReceiveViewModel.amountRequestPayload(IDENTITY_ID, Coin.valueOf(1_234_567_890L))
        )
        // Small amounts: the smallest unit, no exponent, no trailing zeros.
        assertEquals(
            "dash:$SHIELDED_ADDRESS?amount=0.00000001",
            ReceiveViewModel.amountRequestPayload(SHIELDED_ADDRESS, Coin.SATOSHI)
        )
        assertEquals(
            "dash:$SHIELDED_ADDRESS?amount=0.0001",
            ReceiveViewModel.amountRequestPayload(SHIELDED_ADDRESS, Coin.valueOf(10_000L))
        )
    }

    @Test
    fun `the amount request formats the amount as Core's dash URI does`() {
        for (duffs in listOf(1L, 10_000L, 50_000_000L, 100_000_000L, 1_234_567_890L, 2_100_000_000_000L)) {
            val amount = Coin.valueOf(duffs)
            val core = PaymentURI.convertToPaymentURI(
                AddressNetwork.DASH_TESTNET,
                CORE_ADDRESS,
                amount,
                null,
                null,
                null
            )
            val request = ReceiveViewModel.amountRequestPayload(CORE_ADDRESS, amount)
            assertEquals(core, request)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the amount request rejects a negative amount`() {
        ReceiveViewModel.amountRequestPayload(SHIELDED_ADDRESS, Coin.valueOf(-1L))
    }

    @Test
    fun `the username row shows on Core and Identity when there is a username`() {
        assertFalse(ReceiveUIState(selectedTab = CORE).showUsername)
        assertTrue(ReceiveUIState(selectedTab = CORE, username = "u").showUsername)
        assertTrue(ReceiveUIState(selectedTab = IDENTITY, username = "u").showUsername)
        assertFalse(ReceiveUIState(selectedTab = SHIELD, username = "u").showUsername)
        assertFalse(ReceiveUIState(selectedTab = PLATFORM, username = "u").showUsername)
    }

    @Test
    fun `the Core QR is a dash URI carrying the username, the others the bare value`() {
        assertEquals("dash:$CORE_ADDRESS", ReceiveViewModel.qrPayloadFor(CORE, CORE_ADDRESS, null))
        assertTrue(ReceiveViewModel.qrPayloadFor(CORE, CORE_ADDRESS, "johndoe").contains("johndoe"))
        assertEquals(SHIELDED_ADDRESS, ReceiveViewModel.qrPayloadFor(SHIELD, SHIELDED_ADDRESS, "johndoe"))
        assertEquals(PLATFORM_ADDRESS, ReceiveViewModel.qrPayloadFor(PLATFORM, PLATFORM_ADDRESS, null))
        assertEquals(IDENTITY_ID, ReceiveViewModel.qrPayloadFor(IDENTITY, IDENTITY_ID, null))
    }
}
