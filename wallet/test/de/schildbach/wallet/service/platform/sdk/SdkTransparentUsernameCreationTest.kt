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

import de.schildbach.wallet.Constants
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.dashfoundation.dashsdk.identity.IdentityKeyPreview
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for [transparentUsernameFeeDuffs] — the exact `feeDuffs`
 * lambda [SdkTransparentUsernameCreation]'s `@Inject` constructor wires —
 * pinning the MO-1069 protocol-version gate: a wallet funded with the
 * Platform v4.2 (0.15 DASH) contested fee while the network still runs
 * protocol 13 fails DPNS registration with "Insufficient identity balance"
 * (the network still needs ~0.2 DASH of prefunded voting balance, i.e. the
 * 0.25 DASH total LEGACY fee) — reproduced live before this fix routed the
 * funding amount through [ContestedUsernameFees] instead of the raw,
 * un-gated `Constants.DASH_PAY_FEE_CONTESTED` constant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SdkTransparentUsernameCreationTest {

    private fun sdkService(protocolVersion: Int?) = mockk<DashSdkService> {
        coEvery { currentProtocolVersion() } returns protocolVersion
    }

    @Test
    fun `contested registration funds 0_25 DASH while the network still runs protocol 13`() = runTest {
        val amountDuffs = transparentUsernameFeeDuffs(contested = true, sdkService = sdkService(13))
        assertEquals(Constants.DASH_PAY_FEE_CONTESTED_LEGACY.value, amountDuffs)
    }

    @Test
    fun `contested registration funds 0_15 DASH once the network reports protocol 14`() = runTest {
        val amountDuffs = transparentUsernameFeeDuffs(contested = true, sdkService = sdkService(14))
        assertEquals(Constants.DASH_PAY_FEE_CONTESTED.value, amountDuffs)
    }

    @Test
    fun `an unresolved protocol version falls back to the 0_25 legacy fee, never the newer one`() = runTest {
        val amountDuffs = transparentUsernameFeeDuffs(contested = true, sdkService = sdkService(null))
        assertEquals(Constants.DASH_PAY_FEE_CONTESTED_LEGACY.value, amountDuffs)
    }

    @Test
    fun `non-contested registration always funds the fixed 0_03 DASH fee, regardless of protocol version`() = runTest {
        assertEquals(
            Constants.DASH_PAY_FEE.value,
            transparentUsernameFeeDuffs(contested = false, sdkService = sdkService(13))
        )
        assertEquals(
            Constants.DASH_PAY_FEE.value,
            transparentUsernameFeeDuffs(contested = false, sdkService = sdkService(14))
        )
    }

    // ── confirmed-amount funding guard (thepastaclaw's dash-wallet#1584 review, ──
    // ── "Transparent username funding cap" — mirrors SdkL1InviteCreation's   ──
    // ── identical guard, added in cca267fcd)                                 ──

    private val walletIdHex = "aa".repeat(32)
    private val identityId = ByteArray(32) { 7 }
    private val identityIdBase58 = Identifier.from(identityId).toString()

    // "brian" is contestable per the real Names.isUsernameContestable (see
    // RequestUserNameViewModelTest's "brian must be contested" assertions) —
    // createUsernameTransparent derives contested-ness from the label itself,
    // unlike SdkL1InviteCreation.createL1Invite, which takes it as a parameter.
    private val contestedUsername = "brian"

    // The protocol-14 CURRENT contested fee (0.15 DASH) vs. the protocol-13
    // LEGACY contested fee (0.25 DASH) a null/failed funding-time protocol
    // read falls back to — see ContestedUsernameFees.
    private val currentContestedDuffs = 15_000_000L
    private val legacyContestedDuffs = 25_000_000L

    private val registrationKeys = List(4) { index ->
        IdentityKeyPreview(
            identityIndex = 0,
            derivationPath = "m/9'/1'/5'/0'/0'/0'/$index'",
            publicKey = ByteArray(33) { index.toByte() },
            privateKey = ByteArray(32) { (index + 1).toByte() }
        )
    }

    private fun happySource() = mockk<TransparentUsernameSource> {
        coEvery { boundWalletIdOrNull() } returns walletIdHex
        coEvery { managedIdentityCount(walletIdHex) } returns 0
        coEvery { unresolvedRegistrationAssetLock(walletIdHex, 0) } returns null
        coEvery { previewRegistrationKeySet(walletIdHex, 0, true) } returns registrationKeys
        coEvery { storeIdentityPrivateKey(walletIdHex, any(), any()) } just Runs
        coEvery {
            registerWithWalletFunding(walletIdHex, any(), any(), 0, registrationKeys)
        } returns identityId
        coEvery { registrationAssetLockTxidDisplayHex(walletIdHex, 0) } returns null
        coEvery { registerDpnsName(walletIdHex, identityId, contestedUsername) } returns "$contestedUsername.dash"
    }

    private fun service(source: TransparentUsernameSource, feeDuffs: suspend (contested: Boolean) -> Long) =
        SdkTransparentUsernameCreation(
            source = source,
            cutoverCommitted = { true },
            feeDuffs = feeDuffs,
            handOffToLegacy = {}
        )

    @Test
    fun fundingTimeFeeAboveApprovedAmount_refusesWithoutFunding() = runTest {
        val source = happySource()
        // Quoted/confirmed at protocol 14 (0.15 DASH); the funding-time read
        // then fails and ContestedUsernameFees falls back to LEGACY (0.25
        // DASH) — the exact scenario the review comment described.
        val result = service(source, feeDuffs = { legacyContestedDuffs })
            .createUsernameTransparent(
                username = contestedUsername,
                approvedAmountDuffs = currentContestedDuffs
            )

        assertTrue(result is SdkWriteResult.NotBroadcast)
        coVerify(exactly = 0) { source.registerWithWalletFunding(any(), any(), any(), any(), any()) }
    }

    @Test
    fun fundingTimeFeeAtOrBelowApprovedAmount_funds() = runTest {
        val source = happySource()
        val result = service(source, feeDuffs = { currentContestedDuffs })
            .createUsernameTransparent(
                username = contestedUsername,
                approvedAmountDuffs = currentContestedDuffs
            )

        assertTrue(result is SdkWriteResult.Broadcast)
        coVerify {
            source.registerWithWalletFunding(
                walletIdHex,
                currentContestedDuffs,
                any(),
                0,
                registrationKeys
            )
        }
    }
}
