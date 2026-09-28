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
package de.schildbach.wallet.service

import android.content.Context
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.payments.PendingDirectPaymentVerifier
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.util.viewModels.MainCoroutineRule
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Mixing waits at the same barrier a payment does, and the caller publishes MIXING before asking
 * it. A failure that left the status there would be read as "already mixing" by every later
 * request, so nothing would ever start it again while the UI went on saying it was running.
 */
@ExperimentalCoroutinesApi
class CoinJoinMixingStartTest {
    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private lateinit var pendingPaymentVerifier: PendingDirectPaymentVerifier
    private lateinit var walletDataProvider: WalletDataProvider
    private lateinit var service: CoinJoinMixingService

    @Before
    fun setUp() {
        pendingPaymentVerifier = mockk(relaxed = true)
        walletDataProvider = mockk(relaxed = true)

        val blockchainStateProvider = mockk<BlockchainStateProvider>(relaxed = true)
        every { blockchainStateProvider.observeNetworkStatus() } returns emptyFlow()
        every { blockchainStateProvider.observeBlockChain() } returns emptyFlow()
        every { blockchainStateProvider.observeState() } returns emptyFlow()

        val config = mockk<CoinJoinConfig>(relaxed = true)
        every { config.observeMode() } returns emptyFlow()

        every { walletDataProvider.observeTotalBalance() } returns emptyFlow()

        service = CoinJoinMixingService(
            mockk<Context>(relaxed = true),
            mockk<WalletApplication>(relaxed = true),
            mockk<DashSystemService>(relaxed = true),
            walletDataProvider,
            blockchainStateProvider,
            config,
            mockk<PlatformRepo>(relaxed = true),
            mockk<AnalyticsService>(relaxed = true),
            pendingPaymentVerifier
        )
        // the constructor's own collectors have touched these; the assertions below are about what
        // beginMixing() does
        clearMocks(walletDataProvider, answers = false)
    }

    @Test
    fun `mixing is left retryable when pending payments cannot be restored`() = runBlocking {
        coEvery { pendingPaymentVerifier.awaitRestored() } throws
            IllegalStateException("pending payments could not be restored")

        service.beginMixing()

        // PAUSED, not MIXING: the next request to mix sees a previous status that is not MIXING
        // and so goes through the barrier again, which updateBalance() reaches on every block
        assertEquals(MixingStatus.PAUSED, service.getMixingState())
        coVerify(exactly = 1) { pendingPaymentVerifier.awaitRestored() }
    }

    @Test
    fun `nothing is prepared when the barrier refuses`() = runBlocking {
        coEvery { pendingPaymentVerifier.awaitRestored() } throws
            IllegalStateException("pending payments could not be restored")

        service.beginMixing()

        // preparation registers listeners and starts the client manager, and only stopMixing()
        // undoes it; running it on an attempt that then fails would leave that standing and the
        // retry would stack a second copy of every listener on top
        verify(exactly = 0) { walletDataProvider.wallet }
    }
}
