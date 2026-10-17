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

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.AppDatabase
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.service.DashSystemService
import de.schildbach.wallet.service.platform.sdk.SdkProfileQueries
import de.schildbach.wallet.service.platform.sdk.SdkUsernameQueries
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.dashj.platform.dashpay.IdentityStatus
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.sdk.platform.Names
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A name-only retry after a lost or locked vote (match master): the requested replacement
 * becomes the primary/display name and the registered instant secondary stays the secondary.
 * Driven through the repository's real record update from the recovered dashj names, which is
 * what RestoreIdentityWorker runs right before and right after registering the replacement.
 */
class IdentityReplacementNameRecordTest {
    private val identityId = "6qKq6dvbcJ8fH8ekXCbrbQpNfKPRgcHLw1TEQSyx3KVy"

    private fun repository(): IdentityRepositoryImpl {
        val storage = mockk<BlockchainIdentityConfig>(relaxed = true)
        coEvery { storage.get(BlockchainIdentityConfig.CREATION_STATE) } returns IdentityCreationState.USERNAME_REGISTERING.name
        return IdentityRepositoryImpl(
            walletApplication = mockk<WalletApplication>(relaxed = true),
            appDatabase = mockk<AppDatabase>(relaxed = true),
            blockchainIdentityDataStorage = storage,
            walletDataProvider = mockk<WalletData>(relaxed = true),
            platformRepo = mockk<PlatformRepo>(relaxed = true),
            dashPayConfig = mockk<DashPayConfig>(relaxed = true),
            dashSystemService = mockk<DashSystemService>(relaxed = true),
            sdkUsernameQueries = mockk<SdkUsernameQueries>(relaxed = true),
            sdkProfileQueries = mockk<SdkProfileQueries>(relaxed = true)
        )
    }

    /** What dashj's recoverUsernames() leaves behind: NORMALIZED labels. */
    private fun recovered(primary: String, secondary: String?): BlockchainIdentity =
        mockk(relaxed = true) {
            every { registrationStatus } returns IdentityStatus.REGISTERED
            every { primaryUsername } returns Names.normalizeString(primary)
            every { currentUsername } returns Names.normalizeString(primary)
            every { secondaryUsername } returns secondary?.let { Names.normalizeString(it) }
            every { uniqueIdString } returns identityId
        }

    private fun record(primary: String, secondary: String?) = BlockchainIdentityData(
        creationState = IdentityCreationState.USERNAME_REGISTERING,
        creationStateErrorMessage = null,
        username = primary,
        usernameSecondary = secondary,
        userId = identityId,
        restoring = true
    )

    @Test
    fun `a pending non-contestable replacement is not overwritten by the registered secondary`() = runBlocking {
        // before the name step: only the instant secondary is on chain
        val data = record(primary = "replacement22", secondary = "brimoztest3")
        repository().updateBlockchainIdentityData(data, recovered(primary = "brimoztest3", secondary = null))

        assertEquals("the requested label survives for the name step to read", "replacement22", data.username)
        assertEquals("brimoztest3", data.usernameSecondary)
    }

    @Test
    fun `a pending contestable replacement is not overwritten by the registered secondary`() = runBlocking {
        val data = record(primary = "replacement", secondary = "brimoztest3")
        repository().updateBlockchainIdentityData(data, recovered(primary = "brimoztest3", secondary = null))

        assertEquals("replacement", data.username)
        assertEquals("brimoztest3", data.usernameSecondary)
    }

    @Test
    fun `after it registers the replacement is the display name and the secondary stays`() = runBlocking {
        // both names on chain; dashj may adopt either one as "primary"
        val pickedSecondary = record(primary = "replacement22", secondary = "brimoztest3")
        repository().updateBlockchainIdentityData(
            pickedSecondary,
            recovered(primary = "brimoztest3", secondary = "replacement22")
        )
        assertEquals("replacement22", pickedSecondary.username)
        assertEquals("brimoztest3", pickedSecondary.usernameSecondary)

        val pickedReplacement = record(primary = "replacement22", secondary = "brimoztest3")
        repository().updateBlockchainIdentityData(
            pickedReplacement,
            recovered(primary = "replacement22", secondary = "brimoztest3")
        )
        assertEquals("replacement22", pickedReplacement.username)
        assertEquals("brimoztest3", pickedReplacement.usernameSecondary)
    }

    @Test
    fun `with no secondary the replacement is simply the recovered name`() = runBlocking {
        val data = record(primary = "replacement22", secondary = null)
        repository().updateBlockchainIdentityData(data, recovered(primary = "replacement22", secondary = null))

        assertEquals("replacement22", data.username)
    }
}
