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

package de.schildbach.wallet.service.platform.sdk

import android.app.Application
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-JVM tests for [SdkL1InviteCreation.createL1Invite]'s confirmed-amount
 * guard (thepastaclaw's dash-wallet#1584 review, "Keep transparent funding
 * within the confirmed amount"): the confirm dialog authenticates the user
 * against the amount it showed, but `feeDuffs` re-resolves the contested fee
 * LIVE at funding time — a 13-to-14 protocol activation, or a failed
 * protocol-version read falling back to LEGACY, can land on a HIGHER fee than
 * what was confirmed. [SdkL1InviteCreation.createL1Invite] must refuse to fund
 * more than `approvedAmountDuffs` rather than spending the difference
 * silently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class SdkL1InviteCreationTest {

    private val walletIdHex = "aa".repeat(32)

    // The protocol-14 CURRENT contested fee (0.15 DASH) vs. the protocol-13
    // LEGACY contested fee (0.25 DASH) a null/failed funding-time protocol
    // read falls back to — see ContestedUsernameFees. Core DUFFS scale (1
    // DASH = 100,000,000 duffs), matching feeDuffs/approvedAmountDuffs/
    // L1InviteSource.createInvitation() and the transparent counterpart's
    // fixtures (dash-wallet#1584 review).
    private val currentContestedDuffs = 15_000_000L
    private val legacyContestedDuffs = 25_000_000L

    private fun happySource() = mockk<L1InviteSource> {
        coEvery { boundWalletIdOrNull() } returns walletIdHex
        coEvery {
            createInvitation(any(), any(), any(), any(), any(), any())
        } returns CreatedL1Invitation(
            outPoint = ByteArray(36) { 1 },
            uri = "dashpay://invite?assetlocktx=${"11".repeat(32)}"
        )
    }

    private fun config() = mockk<DashPayConfig> {
        coEvery { get(DashPayConfig.USE_KOTLIN_SDK_L1_INVITE) } returns true
    }

    private fun service(
        source: L1InviteSource,
        feeDuffs: suspend (contested: Boolean) -> Long
    ) = SdkL1InviteCreation(
        source = source,
        dashPayConfig = config(),
        cutoverCommitted = { true },
        feeDuffs = feeDuffs
    )

    @Test
    fun fundingTimeFeeAboveApprovedAmount_refusesWithoutFunding() = runTest {
        val source = happySource()
        // Quoted/confirmed at protocol 14 (0.15 DASH); the funding-time read
        // then fails and ContestedUsernameFees falls back to LEGACY (0.25
        // DASH) — the exact scenario the review comment described.
        val result = service(source, feeDuffs = { legacyContestedDuffs })
            .createL1Invite(
                username = "alice",
                displayName = "Alice",
                avatarUrl = "",
                inviterIdentityIdBase58 = null,
                contested = true,
                approvedAmountDuffs = currentContestedDuffs
            )

        assertTrue(result is SdkWriteResult.NotBroadcast)
        coVerify(exactly = 0) { source.createInvitation(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun fundingTimeFeeAtOrBelowApprovedAmount_funds() = runTest {
        val source = happySource()
        val result = service(source, feeDuffs = { currentContestedDuffs })
            .createL1Invite(
                username = "alice",
                displayName = "Alice",
                avatarUrl = "",
                inviterIdentityIdBase58 = null,
                contested = true,
                approvedAmountDuffs = currentContestedDuffs
            )

        assertTrue(result is SdkWriteResult.Broadcast)
        coVerify {
            source.createInvitation(eq(walletIdHex), eq(currentContestedDuffs), any(), isNull(), eq("alice"), any())
        }
    }
}
