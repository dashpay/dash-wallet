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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.service.platform.sdk

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.dashfoundation.dashsdk.wallet.PlatformWalletManager
import org.junit.Test

class CoinJoinShieldFundingTest {
    @Test
    fun coinJoinPathsUseDedicatedDrainInsteadOfBip44Funding() = runBlocking {
        val manager = mockk<PlatformWalletManager>(relaxed = true)
        val service = mockk<DashSdkService>(relaxed = true)
        coEvery { service.walletManagerOrNull() } returns manager
        val source = DashSdkShieldedSource(service)
        val walletId = ByteArray(32)
        val recipient = ByteArray(43)

        for (path in listOf("m/9'/5'/4'/0'", "m/9'/1'/4'/0'")) {
            source.fundFromAssetLockFromAccount(walletId, recipient, 206741800L, path)
        }

        coVerify(exactly = 2) {
            manager.shieldedFundFromCoinJoinDrain(walletId, recipient, coinJoinAccountIndex = 0)
        }
        coVerify(exactly = 0) {
            manager.shieldedFundFromAssetLock(any(), any(), any(), any(), any())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedFundingPathIsRejectedBeforeSdkStartup() = runBlocking {
        val service = mockk<DashSdkService>()
        DashSdkShieldedSource(service).fundFromAssetLockFromAccount(
            ByteArray(32), ByteArray(43), 1L, "m/44'/1'/0'"
        )
    }
}
