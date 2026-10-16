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

import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashfoundation.dashsdk.identity.IdentityKeyPreview
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the L1 (DIP-13) invite CLAIM's false-locked Keystore
 * handling (MO-974): a claim refused because the Keystore denied the
 * lock-bound identity key as device-locked while the device reported
 * UNLOCKED is retried a bounded number of times — re-storing the keys first,
 * so they land under the SDK's never-lock-bound alias — and never on a
 * genuinely locked device or a non-Keystore failure.
 */
class SdkL1InviteCreationTest {

    private val walletIdHex = "aa".repeat(32)
    private val uri = "dashpay://invite?assetlocktx=00&pk=secret&islock=00"
    private val identityId = ByteArray(32) { 7 }
    private val identityIdBase58 = Identifier.from(identityId).toString()

    private val falseLocked = DashSdkError.PlatformWallet.Generic(
        5000,
        "SDK error: Protocol error: Generic Error: Keystore denied 'decrypt' on lock-bound alias " +
            "'org.dashfoundation.wallet.keys.devicebound' as device-locked; KeyguardManager at throw " +
            "time: isDeviceLocked=false isKeyguardLocked=false (FALSE-LOCKED: device reports unlocked " +
            "but Keystore denied — Keystore2 lock-state misreporting; retryable immediately)"
    )

    private val genuinelyLocked = DashSdkError.PlatformWallet.Generic(
        5000,
        "SDK error: Protocol error: Generic Error: Keystore denied 'decrypt' on lock-bound alias " +
            "'org.dashfoundation.wallet.keys.devicebound' as device-locked; KeyguardManager at throw " +
            "time: isDeviceLocked=true isKeyguardLocked=true (genuinely locked — retry after unlock)"
    )

    /** A FRESH six-key list per call: the pipeline zeroes each scalar after storing it. */
    private fun keys() = List(6) { index ->
        IdentityKeyPreview(
            identityIndex = 0,
            derivationPath = "m/9'/1'/5'/0'/0'/0'/$index'",
            publicKey = ByteArray(33) { (index + 2).toByte() },
            privateKey = ByteArray(32) { (index + 1).toByte() }
        )
    }

    private val pubHexes = keys().map { it.publicKeyHex }

    private val pauses = mutableListOf<Long>()

    private fun source() = mockk<L1InviteSource> {
        coEvery { boundWalletIdOrNull() } returns walletIdHex
        coEvery { managedIdentityCount(walletIdHex) } returns 0
        coEvery { previewRegistrationKeySet(walletIdHex, 0) } answers { keys() }
        coEvery { storeIdentityPrivateKey(walletIdHex, any(), any()) } just Runs
    }

    private fun config() = mockk<DashPayConfig> {
        coEvery { get(DashPayConfig.USE_KOTLIN_SDK_L1_INVITE) } returns true
    }

    private fun creation(source: L1InviteSource, deviceLockedNow: () -> Boolean = { false }) =
        SdkL1InviteCreation(
            source = source,
            dashPayConfig = config(),
            cutoverCommitted = { true },
            feeDuffs = { 3_000_000L },
            nowUnixSeconds = { 1_000L },
            pause = { pauses += it },
            deviceLockedNow = deviceLockedNow
        )

    @Test
    fun `false-locked claim re-stores the keys and retries successfully`() = runTest {
        val source = source()
        coEvery { source.claimInvitation(walletIdHex, uri, 0, any(), 1_000L) } throws falseLocked andThen identityId

        val result = creation(source).claimL1Invite(uri, "alice")

        assertEquals(SdkWriteResult.Broadcast(identityIdBase58), result)
        coVerify(exactly = 2) { source.claimInvitation(walletIdHex, uri, 0, any(), 1_000L) }
        // Persisted once before the first claim and again before the retry —
        // the re-store is what moves the keys off the lock-bound alias.
        coVerify(exactly = 2) { source.previewRegistrationKeySet(walletIdHex, 0) }
        pubHexes.forEach { hex ->
            coVerify(exactly = 2) { source.storeIdentityPrivateKey(walletIdHex, hex, any()) }
        }
        assertEquals(listOf(500L), pauses)
    }

    @Test
    fun `a second false-locked refusal is retried once more and then succeeds`() = runTest {
        val source = source()
        coEvery {
            source.claimInvitation(walletIdHex, uri, 0, any(), 1_000L)
        } throws falseLocked andThenThrows falseLocked andThen identityId

        val result = creation(source).claimL1Invite(uri, "alice")

        assertEquals(SdkWriteResult.Broadcast(identityIdBase58), result)
        coVerify(exactly = 3) { source.claimInvitation(walletIdHex, uri, 0, any(), 1_000L) }
        assertEquals(listOf(500L, 1_500L), pauses)
    }

    @Test
    fun `exhausted retries keep the existing NotBroadcast outcome`() = runTest {
        val source = source()
        coEvery { source.claimInvitation(walletIdHex, uri, 0, any(), 1_000L) } throws falseLocked

        val result = creation(source).claimL1Invite(uri, "alice")

        // Bounded: one original attempt plus exactly two retries.
        coVerify(exactly = 3) { source.claimInvitation(walletIdHex, uri, 0, any(), 1_000L) }
        assertEquals(listOf(500L, 1_500L), pauses)
        assertTrue("must be NotBroadcast, got $result", result is SdkWriteResult.NotBroadcast)
        val notSent = result as SdkWriteResult.NotBroadcast
        assertTrue(notSent.reason, notSent.reason.contains("considers the device locked"))
        assertSame(falseLocked, notSent.cause)
    }

    @Test
    fun `a genuinely locked refusal is not retried`() = runTest {
        val source = source()
        coEvery { source.claimInvitation(walletIdHex, uri, 0, any(), 1_000L) } throws genuinelyLocked

        val result = creation(source).claimL1Invite(uri, "alice")

        coVerify(exactly = 1) { source.claimInvitation(any(), any(), any(), any(), any()) }
        assertTrue("no retry back-off", pauses.isEmpty())
        assertTrue("must be NotBroadcast, got $result", result is SdkWriteResult.NotBroadcast)
        assertSame(genuinelyLocked, (result as SdkWriteResult.NotBroadcast).cause)
    }

    @Test
    fun `a device that is locked by retry time stops the retries`() = runTest {
        val source = source()
        coEvery { source.claimInvitation(walletIdHex, uri, 0, any(), 1_000L) } throws falseLocked

        val result = creation(source, deviceLockedNow = { true }).claimL1Invite(uri, "alice")

        coVerify(exactly = 1) { source.claimInvitation(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { source.previewRegistrationKeySet(walletIdHex, 0) }
        assertTrue("must be NotBroadcast, got $result", result is SdkWriteResult.NotBroadcast)
        assertSame(falseLocked, (result as SdkWriteResult.NotBroadcast).cause)
    }

    @Test
    fun `a bare User-not-authenticated refusal is not retried`() = runTest {
        val source = source()
        coEvery {
            source.claimInvitation(walletIdHex, uri, 0, any(), 1_000L)
        } throws RuntimeException("Generic Error: User not authenticated")

        val result = creation(source).claimL1Invite(uri, "alice")

        coVerify(exactly = 1) { source.claimInvitation(any(), any(), any(), any(), any()) }
        assertTrue(pauses.isEmpty())
        assertTrue("must be NotBroadcast, got $result", result is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun `a non-Keystore failure is not retried`() = runTest {
        val source = source()
        val network = DashSdkError.NetworkError("connection reset")
        coEvery { source.claimInvitation(walletIdHex, uri, 0, any(), 1_000L) } throws network

        val result = creation(source).claimL1Invite(uri, "alice")

        coVerify(exactly = 1) { source.claimInvitation(any(), any(), any(), any(), any()) }
        assertTrue(pauses.isEmpty())
        assertEquals(SdkWriteResult.Ambiguous(network), result)
    }

    @Test
    fun `an ambiguous failure during a retry is Ambiguous and not retried further`() = runTest {
        val source = source()
        val network = DashSdkError.NetworkError("connection reset")
        coEvery {
            source.claimInvitation(walletIdHex, uri, 0, any(), 1_000L)
        } throws falseLocked andThenThrows network

        val result = creation(source).claimL1Invite(uri, "alice")

        coVerify(exactly = 2) { source.claimInvitation(any(), any(), any(), any(), any()) }
        assertEquals(listOf(500L), pauses)
        assertEquals(SdkWriteResult.Ambiguous(network), result)
    }
}
