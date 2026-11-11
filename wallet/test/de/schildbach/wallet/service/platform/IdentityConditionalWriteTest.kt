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

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.database.entity.IdentityRecordKey
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.dashj.platform.dpp.voting.BlockInfo
import org.dashj.platform.dpp.voting.Contenders
import org.dashj.platform.dpp.voting.ContestedDocumentVotePollWinnerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Optional

/**
 * A vote lookup for the old name writes its result back while a name-only retry replaces the
 * name and fails. The lookup's write is paused at the store boundary (after the service decided
 * to write), the replacement is accepted and fails with its pending mark cleared, then the write
 * resumes: over the real DataStore-backed [BlockchainIdentityConfig], the record must keep the
 * replacement name and its error. Only a compare inside the write's own store edit holds this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class IdentityConditionalWriteTest {

    private val myUserId = "G2HnoKSdqpTzcfd1HcU1RYk3R7Zmrc7yPYExrS3bXiDf"
    private val platformRepo = mockk<PlatformRepo>(relaxed = true)
    private val identityRepository = mockk<IdentityRepository>(relaxed = true)

    @Test
    fun aVoteWritePausedAtTheStore_neverUndoesAReplacementThatFailedMeanwhile() = runBlocking {
        val config = BlockchainIdentityConfig(
            ApplicationProvider.getApplicationContext(),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        config.clear()
        config.insert(BlockchainIdentityData(IdentityCreationState.VOTING, null, "alice", null, myUserId, false))

        // the write boundary: the service's write reaches the repository, then waits
        val atWrite = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        coEvery { identityRepository.updateBlockchainIdentityData(any<BlockchainIdentityData>()) } coAnswers {
            atWrite.complete(Unit)
            resume.await()
            config.insert(firstArg())
            written.complete(Unit)
        }
        coEvery { identityRepository.updateBlockchainIdentityDataIfUnchanged(any(), any()) } coAnswers {
            atWrite.complete(Unit)
            resume.await()
            config.insertIfUnchanged(firstArg(), secondArg<IdentityRecordKey>()).also { written.complete(Unit) }
        }
        val locked = mockk<ContestedDocumentVotePollWinnerInfo> {
            every { isLocked } returns true
            every { noWinner } returns false
            every { isWinner(any()) } returns false
        }
        every { platformRepo.getUsername(any()) } returns Resource.success(null)
        every { platformRepo.getVoteContenders("alice") } returns
            Contenders(Optional.of(Pair(locked, mockk<BlockInfo>())), mapOf(), 0, 0)

        // the check waits at its write (it writes in line, as one conditional write)
        val check = launch(Dispatchers.IO) { service(config).checkUsernameVotingStatus() }
        withTimeout(5_000) { atWrite.await() }

        val error = "name-only retry: DuplicateUniqueIndexError"
        config.beginNameOnlyRetry(RequestedNameContext(myUserId, "alice3", null))
        config.failNameOnlyRetry(error)
        resume.complete(Unit)
        withTimeout(5_000) { written.await() }
        withTimeout(5_000) { check.join() }

        val stored = requireNotNull(config.load())
        assertEquals("alice3", stored.username)
        assertEquals(error, stored.creationStateErrorMessage)
        assertEquals(IdentityCreationState.USERNAME_REGISTERING, stored.creationState)
        assertNull("the old name's LOCKED status is not written back", stored.usernameRequested)
    }

    private fun service(config: BlockchainIdentityConfig) = PlatformSynchronizationService(
        platform = mockk(relaxed = true),
        platformRepo = platformRepo,
        analytics = mockk(relaxed = true),
        config = mockk(relaxed = true),
        walletApplication = mockk(relaxed = true),
        transactionMetadataProvider = mockk(relaxed = true),
        transactionMetadataChangeCacheDao = mockk(relaxed = true),
        transactionMetadataDocumentDao = mockk(relaxed = true),
        blockchainIdentityDataDao = config,
        dashPayProfileDao = mockk(relaxed = true),
        dashPayContactRequestDao = mockk(relaxed = true),
        dashPayConfig = mockk(relaxed = true),
        giftCardDao = mockk(relaxed = true),
        invitationsDao = mockk(relaxed = true),
        usernameRequestDao = mockk(relaxed = true),
        usernameVoteDao = mockk(relaxed = true),
        identityConfig = config,
        topUpRepository = mockk(relaxed = true),
        identityRepository = identityRepository,
        walletDataProvider = mockk(relaxed = true),
        walletSeam = mockk(relaxed = true),
        sdkProfileQueries = mockk(relaxed = true),
        sdkUsernameQueries = mockk(relaxed = true),
        sdkIdentityVerifyQueries = mockk(relaxed = true),
        sdkWalletBinder = mockk(relaxed = true),
        nonInteractiveWalletUnlock = mockk(relaxed = true),
        l1ShadowSyncService = mockk(relaxed = true),
        shieldedBalanceService = mockk(relaxed = true),
        platformAddressSyncService = mockk(relaxed = true),
        cutoverUiDataService = mockk(relaxed = true),
        sdkBlockchainStateService = mockk(relaxed = true),
        cutoverTxSeamService = mockk(relaxed = true),
        shieldedTransferExecutor = mockk(relaxed = true),
        externalSendExecutor = { mockk(relaxed = true) },
        contactRequestNotificationService = mockk(relaxed = true),
        dashPaySyncStatus = de.schildbach.wallet.service.DashPaySyncStatus(),
        shieldedHistoryRowSync = dagger.Lazy { mockk<de.schildbach.wallet.service.platform.sdk.ShieldedHistoryRowSync>(relaxed = true) },
        transparentUsernameCreation = javax.inject.Provider { mockk(relaxed = true) }
    )
}
