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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Host-JVM regression tests for the Kotlin-SDK engine RESTART contract on
 * [PlatformSynchronizationService] — the mirror image of
 * [PlatformSyncEngineTeardownTest].
 *
 * MO-995 / MO-998 (v12.0.0 QA, both networks): the SDK's L1 (SPV) engine
 * went down on a routine blockchain-service teardown and never came back
 * for the rest of the process. Symptoms: incoming transactions never
 * arrived (two Coinbase deposits lost for 2h10m on mainnet), the header
 * sat at "syncing", and Buy Credits failed with "pre-broadcast: SPV client
 * not started".
 *
 * The call-site half of that bug was in `BlockchainServiceImpl`:
 * [PlatformSyncService.resume] lived only inside `checkService()`'s
 * peergroup-start block, BELOW the `!dashjEngineMayStart` early return, so
 * post-cutover it was unreachable and the engines only ever started from
 * [PlatformSyncService.init] (once per process, from `WalletApplication`).
 * That is fixed by calling [PlatformSyncService.resume] from the
 * post-cutover branch of `BlockchainServiceImpl.onCreate` — a line in an
 * Android service, not reachable from a host-JVM test.
 *
 * What IS testable, and what the fix now depends on, is the contract
 * underneath it: **resume() must be able to bring the engines back up
 * after a teardown has taken them down, any number of times.** That is not
 * a given — [PlatformSyncService.shutdown] cancels the sync scope's
 * children, and `stopSync()` does the same on a wallet reset. Cancelling
 * the scope's `SupervisorJob` itself instead (an easy, invisible change:
 * `syncJob.cancel()` / `syncScope.cancel()` in place of
 * `cancelChildren()`) would leave every later `resume()` a silent no-op
 * and reinstate exactly the MO-995 latch one layer down, with the
 * BlockchainServiceImpl fix still in place and looking correct.
 */
class PlatformSyncEngineRestartTest {

    private val l1ShadowSyncService = mockk<L1ShadowSyncService>(relaxed = true)
    private val shieldedBalanceService = mockk<ShieldedBalanceService>(relaxed = true)
    private val identityRepository = mockk<IdentityRepository>(relaxed = true)

    /** The engine kick is asynchronous (`syncScope.launch { bindJob.join(); … }`). */
    private val kickTimeoutMs = 10_000L

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
        cutoverAutoCommitObserver = mockk(relaxed = true),
        shieldedTransferExecutor = mockk(relaxed = true),
        contactRequestNotificationService = mockk(relaxed = true),
        dashPaySyncStatus = de.schildbach.wallet.service.DashPaySyncStatus()
    )

    @Test
    fun resume_startsTheL1Engine() = runBlocking {
        service().resume()

        coVerify(timeout = kickTimeoutMs, exactly = 1) { l1ShadowSyncService.startIfEnabled() }
        Unit
    }

    /**
     * The MO-995 / MO-998 sequence, at this layer: the engines are stopped
     * by a service teardown, then a later service start calls resume().
     * `stopSdkEngines()` is used rather than `shutdown()` so the assertion
     * holds on debug variants too (shutdown() deliberately skips the stops
     * there — see [PlatformSyncEngineTeardownTest]).
     */
    @Test
    fun resume_afterAnEngineStop_bringsTheL1EngineBackUp() = runBlocking {
        val service = service()

        service.stopSdkEngines()
        coVerify(exactly = 1) { l1ShadowSyncService.stop() }

        service.resume()

        coVerify(timeout = kickTimeoutMs, exactly = 1) { l1ShadowSyncService.startIfEnabled() }
        Unit
    }

    /**
     * A teardown that ran the full [PlatformSyncService.shutdown] path — the
     * one that cancels the sync scope's children — must not disarm resume().
     * This is the assertion that fails if the scope's job is ever cancelled
     * outright instead of its children.
     *
     * The setup matters: shutdown()'s cancel block is gated on
     * `platformSyncJob != null && identityRepository.hasBlockchainIdentity`,
     * so an identity and an armed ticker are both required or shutdown()
     * skips the cancellation entirely and the test proves nothing. Verified
     * by mutation: replacing shutdown()'s
     * `syncScope.coroutineContext.cancelChildren(…)` with
     * `syncJob.cancel(…)` fails this test and only this test.
     */
    @Test
    fun resume_afterFullShutdown_stillStartsTheL1Engine() = runBlocking {
        every { identityRepository.hasBlockchainIdentity } returns true
        val service = service()

        // Arms platformSyncJob in syncScope, so shutdown() below takes the
        // branch that cancels the scope's children.
        service.initSync(runFirstUpdateBlocking = false)
        service.shutdown()
        service.resume()

        coVerify(timeout = kickTimeoutMs, exactly = 1) { l1ShadowSyncService.startIfEnabled() }
        Unit
    }

    /**
     * Every service restart in a process gets its own kick. A single-shot
     * guard added to the kick path (or a scope that survives only the first
     * teardown) reinstates the latch after the second teardown, which is
     * precisely how MO-995 presented: the first service restart of the
     * process looked fine, later ones silently had no SPV.
     */
    @Test
    fun resume_isRearmableAcrossRepeatedStopRestartCycles() = runBlocking {
        val service = service()

        repeat(3) {
            service.stopSdkEngines()
            service.resume()
        }

        coVerify(timeout = kickTimeoutMs, exactly = 3) { l1ShadowSyncService.startIfEnabled() }
        coVerify(exactly = 3) { l1ShadowSyncService.stop() }
        Unit
    }
}
