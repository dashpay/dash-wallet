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
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the Phase 3e DashPay write facade: the
 * flag/preflight orchestration and — most importantly — the
 * no-double-broadcast decision table. No native calls; the SDK write
 * surface is faked via [SdkDashPayWriteSource].
 *
 * Invariant under test: the dashj fallback (a [SdkWriteResult.NotBroadcast]
 * return) is produced ONLY on outcomes where the SDK provably submitted
 * nothing; every unprovable failure is [SdkWriteResult.Ambiguous].
 */
class SdkDashPayWritesTest {

    private val ownUserId = "5DbLwAxGBzUzo81VewMUwn4b5P4bpv9FNFybi25XB5Bk"
    private val toUserId = Identifier.from(ByteArray(32) { 2 }).toString()
    private val walletId = "ab".repeat(32)

    private class FakeSource(
        var boundWalletId: () -> String? = { null },
        var identityManaged: (String, ByteArray) -> Boolean = { _, _ -> false },
        var onSendContactRequest: (String, ByteArray, ByteArray) -> Unit = { _, _, _ -> },
        var onCreateOrUpdateProfile: (String, ByteArray, String?, String?, String?, Boolean) -> Unit =
            { _, _, _, _, _, _ -> }
    ) : SdkDashPayWriteSource {
        var boundCalls = 0
        var managedCalls = 0
        var broadcastCalls = 0
        var lastSender: ByteArray? = null
        var lastRecipient: ByteArray? = null
        var lastWalletId: String? = null
        var lastProfileArgs: List<Any?>? = null

        override suspend fun boundWalletIdOrNull(): String? {
            boundCalls++
            return boundWalletId()
        }

        override suspend fun isIdentityManaged(walletIdHex: String, identityId: ByteArray): Boolean {
            managedCalls++
            return identityManaged(walletIdHex, identityId)
        }

        override suspend fun sendContactRequest(
            walletIdHex: String,
            senderIdentityId: ByteArray,
            recipientIdentityId: ByteArray
        ) {
            broadcastCalls++
            lastWalletId = walletIdHex
            lastSender = senderIdentityId
            lastRecipient = recipientIdentityId
            onSendContactRequest(walletIdHex, senderIdentityId, recipientIdentityId)
        }

        override suspend fun createOrUpdateProfile(
            walletIdHex: String,
            identityId: ByteArray,
            displayName: String?,
            publicMessage: String?,
            avatarUrl: String?,
            doCreate: Boolean
        ) {
            broadcastCalls++
            lastWalletId = walletIdHex
            lastProfileArgs = listOf(identityId, displayName, publicMessage, avatarUrl, doCreate)
            onCreateOrUpdateProfile(walletIdHex, identityId, displayName, publicMessage, avatarUrl, doCreate)
        }
    }

    private fun config(enabled: Boolean?): DashPayConfig = mockk {
        if (enabled == null) {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES) } throws
                IllegalStateException("datastore unavailable")
        } else {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES) } returns enabled
        }
    }

    /** A source in the fully-ready state: wallet bound, identity managed. */
    private fun readySource() = FakeSource(
        boundWalletId = { walletId },
        identityManaged = { _, _ -> true }
    )

    private fun writes(source: FakeSource, enabled: Boolean? = true) =
        SdkDashPayWrites(source, config(enabled))

    // ── classifyBroadcastFailure: the decision table itself ──────────────

    @Test
    fun classify_preBroadcastValidationErrors_areNotBroadcast() {
        val definitelyNotBroadcast = listOf(
            DashSdkError.InvalidParameter("bad id"),
            DashSdkError.InvalidState("wallet closed"),
            DashSdkError.NotFound("identity not found"),
            DashSdkError.NotImplemented("nope"),
            DashSdkError.PlatformWallet.InvalidHandle("stale handle"),
            // Documented by the SDK as definitive non-execution with
            // reservations released.
            DashSdkError.PlatformWallet.ShieldedBroadcastFailed("relay rejected"),
            DashSdkError.PlatformWallet.ShieldedNoRecordedAnchor("tree mid-block")
        )
        for (error in definitelyNotBroadcast) {
            val result = classifyBroadcastFailure(error)
            assertTrue(
                "${error.javaClass.simpleName} must be NotBroadcast",
                result is SdkWriteResult.NotBroadcast
            )
            assertSame(error, (result as SdkWriteResult.NotBroadcast).cause)
        }
    }

    @Test
    fun classify_everythingElse_isAmbiguous() {
        val possiblyBroadcast = listOf<Throwable>(
            DashSdkError.NetworkError("connection reset"),
            DashSdkError.Timeout("dapi timeout"),
            DashSdkError.SerializationError("bad response payload"),
            DashSdkError.CryptoError("proof verification failed"),
            DashSdkError.ProtocolError("consensus error"),
            DashSdkError.DriveInternalError("drive error"),
            DashSdkError.InternalError("unknown"),
            DashSdkError.PlatformWallet.WalletOperation("wallet op failed"),
            DashSdkError.PlatformWallet.Generic(999, "generic"),
            // Documented by the SDK itself as "may already be on chain".
            DashSdkError.PlatformWallet.TransactionBroadcastUnconfirmed("ambiguous"),
            DashSdkError.PlatformWallet.ShieldedSpendUnconfirmed("ambiguous"),
            RuntimeException("JNI blew up")
        )
        for (error in possiblyBroadcast) {
            val result = classifyBroadcastFailure(error)
            assertTrue(
                "${error.javaClass.simpleName} must be Ambiguous",
                result is SdkWriteResult.Ambiguous
            )
            assertSame(error, (result as SdkWriteResult.Ambiguous).cause)
        }
    }

    // ── Preflight: everything before the broadcast is NotBroadcast ───────

    @Test
    fun flagOff_isNotBroadcast_andNeverTouchesSdk() = runBlocking {
        val source = readySource()
        val writes = writes(source, enabled = false)

        assertTrue(writes.sendContactRequest(ownUserId, toUserId) is SdkWriteResult.NotBroadcast)
        assertTrue(
            writes.createOrUpdateProfile(ownUserId, "d", null, null, false, true)
                is SdkWriteResult.NotBroadcast
        )
        assertEquals(0, source.boundCalls + source.managedCalls + source.broadcastCalls)
    }

    @Test
    fun flagReadFailure_isNotBroadcast_andNeverTouchesSdk() = runBlocking {
        val source = readySource()
        val writes = writes(source, enabled = null)

        assertTrue(writes.sendContactRequest(ownUserId, toUserId) is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.boundCalls + source.managedCalls + source.broadcastCalls)
    }

    @Test
    fun malformedIdentityIds_areNotBroadcast_withoutSdkCalls() = runBlocking {
        val source = readySource()
        val writes = writes(source)

        assertTrue(writes.sendContactRequest("not-base58!!", toUserId) is SdkWriteResult.NotBroadcast)
        assertTrue(writes.sendContactRequest(ownUserId, "not-base58!!") is SdkWriteResult.NotBroadcast)
        assertTrue(
            writes.createOrUpdateProfile("not-base58!!", null, null, null, false, true)
                is SdkWriteResult.NotBroadcast
        )
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun walletNotBound_isNotBroadcast_withoutBroadcastAttempt() = runBlocking {
        val source = FakeSource(boundWalletId = { null })
        val result = writes(source).sendContactRequest(ownUserId, toUserId)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun boundLookupFailure_isNotBroadcast_withoutBroadcastAttempt() = runBlocking {
        val source = FakeSource(boundWalletId = { throw IllegalStateException("bootstrap failed") })
        val result = writes(source).sendContactRequest(ownUserId, toUserId)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun identityNotManaged_isNotBroadcast_withoutBroadcastAttempt() = runBlocking {
        val source = FakeSource(
            boundWalletId = { walletId },
            identityManaged = { _, _ -> false }
        )
        val result = writes(source).sendContactRequest(ownUserId, toUserId)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(1, source.managedCalls)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun identityManagedCheckFailure_isNotBroadcast_withoutBroadcastAttempt() = runBlocking {
        val source = FakeSource(
            boundWalletId = { walletId },
            identityManaged = { _, _ -> throw DashSdkError.InternalError("snapshot failed") }
        )
        val result = writes(source).sendContactRequest(ownUserId, toUserId)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun avatarDigestProfile_isNotBroadcast_withoutAnySdkCall() = runBlocking {
        val source = readySource()
        val result = writes(source).createOrUpdateProfile(
            ownUserId, "name", "msg", "https://a/b.png", hasAvatarDigest = true, doCreate = false
        )

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.boundCalls + source.managedCalls + source.broadcastCalls)
    }

    // ── Broadcast attempt outcomes ────────────────────────────────────────

    @Test
    fun sendContactRequest_success_isBroadcast_withMappedIds() = runBlocking {
        val source = readySource()
        val result = writes(source).sendContactRequest(ownUserId, toUserId)

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(1, source.broadcastCalls)
        assertEquals(walletId, source.lastWalletId)
        assertArrayEquals(Identifier.from(ownUserId).toBuffer(), source.lastSender)
        assertArrayEquals(Identifier.from(toUserId).toBuffer(), source.lastRecipient)
    }

    @Test
    fun createOrUpdateProfile_success_isBroadcast_withMappedArgs() = runBlocking {
        val source = readySource()
        val result = writes(source).createOrUpdateProfile(
            ownUserId, "Alice", null, "https://a/b.png", hasAvatarDigest = false, doCreate = true
        )

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(1, source.broadcastCalls)
        val (identityId, displayName, publicMessage, avatarUrl, doCreate) = source.lastProfileArgs!!
        assertArrayEquals(Identifier.from(ownUserId).toBuffer(), identityId as ByteArray)
        assertEquals("Alice", displayName)
        assertNull(publicMessage)
        assertEquals("https://a/b.png", avatarUrl)
        assertEquals(true, doCreate)
    }

    @Test
    fun broadcastValidationFailure_isNotBroadcast_dashjFallbackSafe() = runBlocking {
        val source = readySource().apply {
            onSendContactRequest = { _, _, _ -> throw DashSdkError.InvalidParameter("bad recipient") }
        }
        val result = writes(source).sendContactRequest(ownUserId, toUserId)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(1, source.broadcastCalls)
    }

    @Test
    fun broadcastNetworkFailure_isAmbiguous_noDashjFallback() = runBlocking {
        val source = readySource().apply {
            onSendContactRequest = { _, _, _ -> throw DashSdkError.NetworkError("conn dropped mid-submit") }
        }
        val result = writes(source).sendContactRequest(ownUserId, toUserId)

        assertTrue(result is SdkWriteResult.Ambiguous)
        assertEquals(1, source.broadcastCalls)
    }

    @Test
    fun profileBroadcastTimeout_isAmbiguous_noDashjFallback() = runBlocking {
        val source = readySource().apply {
            onCreateOrUpdateProfile = { _, _, _, _, _, _ -> throw DashSdkError.Timeout("submit timeout") }
        }
        val result = writes(source).createOrUpdateProfile(
            ownUserId, "Alice", null, null, hasAvatarDigest = false, doCreate = false
        )

        assertTrue(result is SdkWriteResult.Ambiguous)
        assertEquals(1, source.broadcastCalls)
    }

    @Test
    fun everyOutcome_makesAtMostOneBroadcastAttempt() = runBlocking {
        // The facade must never retry internally — retries are the caller's
        // (and only for NotBroadcast, via the dashj path).
        val outcomes = listOf<(String, ByteArray, ByteArray) -> Unit>(
            { _, _, _ -> },
            { _, _, _ -> throw DashSdkError.InvalidParameter("x") },
            { _, _, _ -> throw DashSdkError.NetworkError("x") },
            { _, _, _ -> throw RuntimeException("x") }
        )
        for (outcome in outcomes) {
            val source = readySource().apply { onSendContactRequest = outcome }
            writes(source).sendContactRequest(ownUserId, toUserId)
            assertEquals(1, source.broadcastCalls)
        }
    }

    @Test
    fun resultTypes_partitionCorrectly() {
        // Compile-time-ish sanity: Broadcast is success, the other two are
        // failures with distinct fallback semantics.
        val broadcast: SdkWriteResult<Unit> = SdkWriteResult.Broadcast(Unit)
        val notBroadcast: SdkWriteResult<Unit> = SdkWriteResult.NotBroadcast("flag off")
        val ambiguous: SdkWriteResult<Unit> = SdkWriteResult.Ambiguous(RuntimeException())

        assertTrue(broadcast is SdkWriteResult.Broadcast)
        assertFalse(notBroadcast is SdkWriteResult.Ambiguous)
        assertFalse(ambiguous is SdkWriteResult.NotBroadcast)
    }
}
