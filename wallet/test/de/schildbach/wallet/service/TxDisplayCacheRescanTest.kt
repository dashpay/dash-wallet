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

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.dao.TxDisplayCacheDao
import de.schildbach.wallet.database.dao.TxGroupCacheDao
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.CutoverState
import de.schildbach.wallet.service.platform.sdk.CutoverUiDataService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.junit.After
import org.junit.Test

/**
 * MO-1054 end to end through [TxDisplayCacheService]: a user rescan clears the
 * caches twice — the service teardown ([TxDisplayCacheService.clearDatabaseForRescan])
 * and, on the next service start, the dashj `wallet.reset()` the missing-blockstore
 * branch performs on the held, empty dashj wallet (dashj fires the reset event
 * even then). Post-cutover both must keep the SDK-fed rows; pre-cutover both
 * still clear; a wallet wipe always clears.
 */
class TxDisplayCacheRescanTest {

    private val walletReset = MutableSharedFlow<Unit>(replay = 1)
    private val displayDao = mockk<TxDisplayCacheDao>(relaxed = true)
    private val groupDao = mockk<TxGroupCacheDao>(relaxed = true)
    private val cutoverUi = mockk<CutoverUiDataService>(relaxed = true)
    private val dashPayConfig = mockk<DashPayConfig>(relaxed = true)
    private var service: TxDisplayCacheService? = null

    /**
     * A real [TxDisplayCacheService] at [cutover] with every feed but the
     * dashj reset event ([walletReset]) empty, so only the rescan paths act.
     */
    private fun service(cutover: CutoverState): TxDisplayCacheService {
        coEvery { dashPayConfig.get(DashPayConfig.CUTOVER_STATE) } returns cutover.name
        coEvery { displayDao.getAll() } returns emptyList()
        coEvery { displayDao.getCount() } returns 3_272
        val walletData = mockk<WalletData>(relaxed = true) {
            every { observeWallet() } returns flowOf(null)
            every { observeWalletReset() } returns walletReset
            every { wallet } returns null
        }
        val metadata = mockk<TransactionMetadataProvider>(relaxed = true) {
            every { observePresentableMetadata() } returns emptyFlow()
        }
        val identityRepo = mockk<IdentityRepository>(relaxed = true) {
            every { observeContacts(any(), any(), any()) } returns emptyFlow()
        }
        val blockchainState = mockk<BlockchainStateProvider>(relaxed = true) {
            every { observeState() } returns emptyFlow()
        }
        return TxDisplayCacheService(
            walletData = walletData,
            walletApplication = mockk<WalletApplication>(relaxed = true),
            txDisplayCacheDao = displayDao,
            txGroupCacheDao = groupDao,
            metadataProvider = metadata,
            platformRepo = mockk<PlatformRepo>(relaxed = true),
            identityRepo = identityRepo,
            blockchainStateProvider = blockchainState,
            displayCacheRefreshBus = DisplayCacheRefreshBus(),
            dashPayConfig = dashPayConfig,
            cutoverUiDataService = { cutoverUi }
        ).also { service = it }
    }

    @After
    fun tearDown() {
        service?.serviceScope?.cancel()
    }

    @Test
    fun postCutoverRescanKeepsTheRowsThroughTeardownAndTheRestartReset() = runBlocking {
        val cache = service(CutoverState.CUT_OVER)

        cache.clearDatabaseForRescan()   // service teardown
        walletReset.emit(Unit)           // next start: missing blockstore → wallet.reset()

        // Both clears asked for a reconcile walk in place of the wipe; the
        // second one runs on the service's own worker, so wait for it.
        verify(timeout = 5_000, exactly = 2) { cutoverUi.requestFullReconcile() }
        coVerify(exactly = 0) { displayDao.deleteAll() }
        coVerify(exactly = 0) { groupDao.deleteAll() }
    }

    @Test
    fun preCutoverRescanStillClearsOnBothPaths() = runBlocking {
        val cache = service(CutoverState.DUAL_RUNNING)

        cache.clearDatabaseForRescan()
        coVerify(exactly = 1) { displayDao.deleteAll() }

        walletReset.emit(Unit)
        coVerify(timeout = 5_000, exactly = 2) { displayDao.deleteAll() }
        coVerify(exactly = 2) { groupDao.deleteAll() }
        verify(exactly = 0) { cutoverUi.requestFullReconcile() }
    }

    @Test
    fun postCutoverWalletWipeStillClears() = runBlocking {
        val cache = service(CutoverState.CUT_OVER)

        cache.clearDatabase()

        coVerify(exactly = 1) { displayDao.deleteAll() }
        coVerify(exactly = 1) { groupDao.deleteAll() }
    }
}
