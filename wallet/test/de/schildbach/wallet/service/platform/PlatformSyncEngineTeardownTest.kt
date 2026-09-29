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
package de.schildbach.wallet.service.platform

import de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Host-JVM tests for the Kotlin-SDK engine teardown contract on
 * [PlatformSynchronizationService]:
 *
 * - The routine blockchain-service teardown ([PlatformSyncService.shutdown])
 *   stops the L1 shadow SPV and the shielded sync loop on EVERY build type.
 *   Debug builds used to keep both running as a battery trade-off (a warm SPV
 *   skipped the islock-verification delay after an idle restart); that left
 *   the process alive after the app closed and masked cold-start behaviour
 *   under test, so the exemption was removed (2026-09-25).
 * - [PlatformSyncService.stopSdkEngines] stops both engines unconditionally
 *   and failure-contained — it is the explicit stop the wallet-wipe path
 *   (BlockchainServiceImpl cleanup, before `finalizeWipe()`) relies on, so
 *   it must never depend on the debug skip and one failing stop must never
 *   prevent the other.
 */
class PlatformSyncEngineTeardownTest {

    private val l1ShadowSyncService = mockk<L1ShadowSyncService>(relaxed = true)
    private val shieldedBalanceService = mockk<ShieldedBalanceService>(relaxed = true)
    private val identityRepository = mockk<IdentityRepository>(relaxed = true)

    private fun service() = PlatformSynchronizationService(
        platform = mockk(relaxed = true),
        platformRepo = mockk(relaxed = true),
        analytics = mockk(relaxed = true),
        config = mockk(relaxed = true),
        walletApplication = mockk(relaxed = true),
        transactionMetadataProvider = mockk(relaxed = true),
        transactionMetadataChangeCacheDao = mockk(relaxed = true),
        transactionMetadataDocumentDao = mockk(relaxed = true),
        blockchainIdentityDataDao = mockk(relaxed = true),
        dashPayProfileDao = mockk(relaxed = true),
        dashPayContactRequestDao = mockk(relaxed = true),
        dashPayConfig = mockk(relaxed = true),
        giftCardDao = mockk(relaxed = true),
        invitationsDao = mockk(relaxed = true),
        usernameRequestDao = mockk(relaxed = true),
        usernameVoteDao = mockk(relaxed = true),
        identityConfig = mockk(relaxed = true),
        topUpRepository = mockk(relaxed = true),
        identityRepository = identityRepository,
        walletDataProvider = mockk(relaxed = true),
        walletSeam = mockk(relaxed = true),
        sdkProfileQueries = mockk(relaxed = true),
        sdkUsernameQueries = mockk(relaxed = true),
        sdkIdentityVerifyQueries = mockk(relaxed = true),
        sdkWalletBinder = mockk(relaxed = true),
        nonInteractiveWalletUnlock = mockk(relaxed = true),
        l1ShadowSyncService = l1ShadowSyncService,
        shieldedBalanceService = shieldedBalanceService,
        cutoverUiDataService = mockk(relaxed = true),
        sdkBlockchainStateService = mockk(relaxed = true),
        cutoverTxSeamService = mockk(relaxed = true),
        shieldedTransferExecutor = mockk(relaxed = true),
        contactRequestNotificationService = mockk(relaxed = true),
        dashPaySyncStatus = de.schildbach.wallet.service.DashPaySyncStatus()
    )

    @Test
    fun shutdown_stopsBothSdkEngines_onDebugBuildsToo() = runBlocking {
        // The unit-test variant (_testNet3Debug) is a debug build, which is
        // exactly the build type that used to be exempt.
        service().shutdown()

        coVerify(exactly = 1) { l1ShadowSyncService.stop() }
        coVerify(exactly = 1) { shieldedBalanceService.stop() }
    }

    @Test
    fun stopSdkEngines_stopsBothEnginesRegardlessOfBuildType() = runBlocking {
        service().stopSdkEngines()

        coVerify(exactly = 1) { l1ShadowSyncService.stop() }
        coVerify(exactly = 1) { shieldedBalanceService.stop() }
    }

    @Test
    fun stopSdkEngines_aFailingShadowStopStillStopsTheShieldedRuntime() = runBlocking {
        coEvery { l1ShadowSyncService.stop() } throws RuntimeException("rust client stop failed")

        // Must not throw — the wipe path depends on this call completing.
        service().stopSdkEngines()

        coVerify(exactly = 1) { shieldedBalanceService.stop() }
    }
}
