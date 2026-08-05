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
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.entity.Invitation
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.dash.wallet.common.money.Dash
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashfoundation.dashsdk.wallet.OneTimeOrchardKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-JVM tests for the SHIELDED (L2) inviter path
 * ([SdkShieldedInviteCreation]): the contested → denomination selection, the
 * flag/balance preflights, the [SdkWriteResult] classification of the funding
 * transfer, and that the produced link carries the generated one-time key +
 * funding height. Robolectric supplies the real `android.net.Uri` the link
 * builds on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class SdkShieldedInviteCreationTest {

    private val walletIdHex = "aa".repeat(32)

    /** 0.03 / 0.25 DASH in credits — without loading Constants. */
    private val feeCredits = 3_000_000_000L
    private val contestedFeeCredits = 25_000_000_000L
    // Both are members of the allowed exit-denomination set (v13); the pre-v13
    // 0.1 / 0.3 pair is not — 0.3 is not exitable at all.
    private val denominationCredits = 3_000_000_000L // 0.03 DASH
    private val contestedDenominationCredits = 25_000_000_000L // 0.25 DASH

    private val spendingKey = ByteArray(32) { (it + 1).toByte() }
    private val orchardAddress = ByteArray(43) { 9 }
    private val fundingHeight = 987_654
    private val key = OneTimeOrchardKey(spendingKey = spendingKey, address = orchardAddress)

    private val statusFlow = MutableStateFlow(ShieldedSyncStatus.READY)
    private val balanceFlow = MutableStateFlow(creditsToDash(denominationCredits))

    private fun balanceService(ready: Boolean = true) = mockk<ShieldedBalanceService> {
        coEvery { ensureShieldedReady() } returns ready
        every { shieldedSyncStatus } returns statusFlow
        every { observeShieldedBalance() } returns balanceFlow
    }

    private fun happySource() = mockk<ShieldedInviteSource> {
        coEvery { boundWalletIdOrNull() } returns walletIdHex
        coEvery { generateOneTimeOrchardKey() } returns key
        coEvery { fundNotesToRaw43(walletIdHex, orchardAddress, any()) } just Runs
        coEvery { currentChainTipHeight() } returns fundingHeight
    }

    private fun config(flag: Boolean? = true) = mockk<DashPayConfig> {
        coEvery { get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } returns flag
    }

    private fun dao() = mockk<InvitationsDao> { coEvery { insert(any()) } just Runs }

    private fun service(
        source: ShieldedInviteSource = happySource(),
        flag: Boolean? = true,
        balance: ShieldedBalanceService = balanceService(),
        invitationsDao: InvitationsDao = dao(),
        generateOneLink: suspend (InvitationLinkData) -> String? = { null }
    ) = SdkShieldedInviteCreation(
        source = source,
        dashPayConfig = config(flag),
        shieldedBalanceService = balance,
        invitationsDao = invitationsDao,
        feeCredits = { contested -> if (contested) contestedFeeCredits else feeCredits },
        generateOneLink = generateOneLink
    )

    // ── Pure helpers ──────────────────────────────────────────────────────

    @Test
    fun denominationSelection_nonContestedIsPointZeroThree_contestedIsPointTwentyFive() {
        assertEquals(denominationCredits, shieldedInviteDenominationCredits(feeCredits))
        assertEquals(contestedDenominationCredits, shieldedInviteDenominationCredits(contestedFeeCredits))
        assertNull(shieldedInviteDenominationCredits(0L))
    }

    /**
     * The two-note layout is a CLAIM-side correctness property, not a wire
     * detail: two sub-target notes force the claimer's greedy largest-first
     * selection to spend BOTH, which keeps Orchard's padding action — and its
     * RANDOM dummy nullifier — out of the bundle, so the identity id derived
     * from the published nullifiers is reproducible across retries. Regressing
     * to a single full-target note would silently break claim recovery.
     */
    @Test
    fun inviteFundingSplit_isTwoEvenSubTargetNotes_summingToTheDenomination() {
        listOf(denominationCredits, contestedDenominationCredits).forEach { total ->
            val split = inviteFundingSplit(total)
            assertEquals(2, split.size)
            assertEquals(total, split.sum())
            // Both strictly below the target, or selection could stop on one.
            split.forEach { assertTrue(it < total) }
        }
        // Both shipped denominations split exactly evenly.
        assertEquals(listOf(1_500_000_000L, 1_500_000_000L), inviteFundingSplit(3_000_000_000L))
        assertEquals(listOf(12_500_000_000L, 12_500_000_000L), inviteFundingSplit(25_000_000_000L))
        // An odd total puts the extra credit in the second note.
        assertEquals(listOf(2L, 3L), inviteFundingSplit(5L))
    }

    @Test
    fun bytes32ToHex_isLowercase64Chars() {
        val hex = bytes32ToHex(spendingKey)
        assertEquals(64, hex.length)
        assertEquals(hex.lowercase(), hex)
        assertEquals("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20", hex)
    }

    // ── Preflights ────────────────────────────────────────────────────────

    @Test
    fun flagOff_isInert() = runTest {
        val source = mockk<ShieldedInviteSource>()
        val result = service(source = source, flag = false, balance = mockk())
            .createShieldedInvite("alice", "Alice", "", contested = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun runtimeNotReady_notBroadcast() = runTest {
        val result = service(source = mockk(), balance = balanceService(ready = false))
            .createShieldedInvite("alice", "Alice", "", contested = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun balanceBelowDenomination_notBroadcast_neverFunds() = runTest {
        balanceFlow.value = Dash(1_000_000L) // 0.01 DASH < the 0.03 denomination
        try {
            val source = happySource()
            val result = service(source = source)
                .createShieldedInvite("alice", "Alice", "", contested = false)
            assertTrue(result is SdkWriteResult.NotBroadcast)
            coVerify(exactly = 0) { source.fundNotesToRaw43(any(), any(), any()) }
        } finally {
            balanceFlow.value = creditsToDash(denominationCredits)
        }
    }

    // ── The funding transfer + link ───────────────────────────────────────

    @Test
    fun happyPath_fundsTheDenomination_andBuildsAShieldedLink() = runTest {
        val source = happySource()
        val dao = dao()
        val inserted = slot<Invitation>()
        coEvery { dao.insert(capture(inserted)) } just Runs

        val result = service(source = source, invitationsDao = dao)
            .createShieldedInvite("alice", "Alice", "https://x/a.png", contested = false)

        val invite = (result as SdkWriteResult.Broadcast).value
        val link = invite.linkData
        assertTrue(link.isShielded)
        assertEquals("alice", link.user)
        assertEquals("Alice", link.displayName)
        assertEquals("https://x/a.png", link.avatarUrl)
        assertEquals(bytes32ToHex(spendingKey), link.oneTimeKey)
        assertEquals(fundingHeight, link.fundingHeight)

        // The denomination is funded to the generated one-time address as TWO
        // even sub-target notes (see inviteFundingSplit).
        coVerify {
            source.fundNotesToRaw43(
                walletIdHex,
                orchardAddress,
                inviteFundingSplit(denominationCredits)
            )
        }
        // …and the link states what was funded, so the CLAIMER can tell which
        // tier this invite paid for. A shielded note has no on-chain asset
        // lock to read the amount off and the claim FFI never reports a note's
        // value, so the link is the only channel for it — without it the claim
        // screen falls back to "non-contested only" for every invite.
        assertEquals(denominationCredits, link.shieldedFundingCredits)
        // A shielded tracking row is persisted (keyed with the shielded prefix).
        assertTrue(
            inserted.captured.fundingAddress
                .startsWith(SdkShieldedInviteCreation.SHIELDED_FUNDING_ADDRESS_PREFIX)
        )
        // With no OneLink generator (default), share/persist fall back to the raw deep link.
        assertEquals(link.link.toString(), invite.shareLink)
        assertEquals(link.link.toString(), inserted.captured.dynamicLink)
    }

    @Test
    fun broadcast_wrapsShareLinkAndPersistenceInTheOneLink() = runTest {
        val source = happySource()
        val dao = dao()
        val inserted = slot<Invitation>()
        coEvery { dao.insert(capture(inserted)) } just Runs
        val oneLink = "https://dashpay.onelink.appsflyersdk.com/xyz?af_dp=dashpay"

        val result = service(source = source, invitationsDao = dao, generateOneLink = { oneLink })
            .createShieldedInvite("alice", "Alice", "", contested = false)

        val invite = (result as SdkWriteResult.Broadcast).value
        // Shared/copied link AND the persisted row are the OneLink, not the raw deep link (H1).
        assertEquals(oneLink, invite.shareLink)
        assertEquals(oneLink, inserted.captured.dynamicLink)
        assertEquals(oneLink, inserted.captured.shortDynamicLink)
        // The raw deep link is retained as the preview source.
        assertTrue(invite.linkData.isShielded)
    }

    @Test
    fun broadcast_oneLinkFailureFallsBackToRawDeepLink() = runTest {
        val source = happySource()
        val dao = dao()
        val inserted = slot<Invitation>()
        coEvery { dao.insert(capture(inserted)) } just Runs

        val result = service(
            source = source,
            invitationsDao = dao,
            generateOneLink = { null } // generation unavailable
        ).createShieldedInvite("alice", "Alice", "", contested = false)

        val invite = (result as SdkWriteResult.Broadcast).value
        assertEquals(invite.linkData.link.toString(), invite.shareLink)
        assertEquals(invite.linkData.link.toString(), inserted.captured.dynamicLink)
    }

    @Test
    fun contested_fundsThePointTwentyFiveDenomination() = runTest {
        balanceFlow.value = creditsToDash(contestedDenominationCredits)
        try {
            val source = happySource()
            val result = service(source = source)
                .createShieldedInvite("alice", "Alice", "", contested = true)
            assertTrue(result is SdkWriteResult.Broadcast)
            coVerify {
                source.fundNotesToRaw43(
                    walletIdHex,
                    orchardAddress,
                    inviteFundingSplit(contestedDenominationCredits)
                )
            }
            // The two tiers must differ in the LINK as well as in the spend.
            // Funding 0.25 while emitting a link indistinguishable from a 0.03
            // invite is what made the claimer's screen insist the contested
            // fee bought nothing.
            assertEquals(
                contestedDenominationCredits,
                (result as SdkWriteResult.Broadcast).value.linkData.shieldedFundingCredits
            )
        } finally {
            balanceFlow.value = creditsToDash(denominationCredits)
        }
    }

    @Test
    fun fundingRejectedPreBroadcast_notBroadcast() = runTest {
        val source = happySource()
        coEvery {
            source.fundNotesToRaw43(any(), any(), any())
        } throws DashSdkError.InvalidParameter("bad recipient")
        val result = service(source = source)
            .createShieldedInvite("alice", "Alice", "", contested = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun fundingUnprovableFailure_isAmbiguous() = runTest {
        val source = happySource()
        coEvery {
            source.fundNotesToRaw43(any(), any(), any())
        } throws DashSdkError.Timeout("dapi timeout")
        val result = service(source = source)
            .createShieldedInvite("alice", "Alice", "", contested = false)
        assertTrue(result is SdkWriteResult.Ambiguous)
    }

    @Test
    fun missingChainTipHeight_stillBuildsAValidLink_withNullHeight() = runTest {
        val source = happySource()
        coEvery { source.currentChainTipHeight() } returns null
        val result = service(source = source)
            .createShieldedInvite("alice", "Alice", "", contested = false)
        val link = (result as SdkWriteResult.Broadcast).value.linkData
        assertTrue(link.isShielded)
        // 0 height serializes to bh=0, which parses back as 0 (present, valid).
        assertEquals(0, link.fundingHeight)
    }

    @Test
    fun walletNotBound_notBroadcast_neverGeneratesKey() = runTest {
        val source = happySource()
        coEvery { source.boundWalletIdOrNull() } returns null
        val result = service(source = source)
            .createShieldedInvite("alice", "Alice", "", contested = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        coVerify(exactly = 0) { source.generateOneTimeOrchardKey() }
    }
}
