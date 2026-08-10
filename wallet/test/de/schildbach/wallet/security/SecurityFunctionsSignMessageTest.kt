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

package de.schildbach.wallet.security

import de.schildbach.wallet.service.platform.sdk.PWFFI_ERROR_INVALID_PARAMETER
import de.schildbach.wallet.service.platform.sdk.SdkMessageSigner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.services.MessageSigningException
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Host-JVM tests for [SecurityFunctions.signMessage] after the dashj →
 * Kotlin SDK swap. No native calls: the SDK signing surface is faked via
 * [SdkMessageSigner], the same seam convention
 * `SdkL1SendServiceTest`/`SdkL1SendSource` use.
 *
 * The invariant these lock down: signing NEVER answers a failure with an
 * empty string. The dashj implementation returned `""` when the wallet did
 * not own the address, which made CrowdNode POST an unsigned request and
 * fail server-side with an opaque message. Every failure is now a typed
 * [MessageSigningException].
 */
class SecurityFunctionsSignMessageTest {

    private val address = "yTsGq4wV8WF5GKLaYV2C43zrkr2sfTtysT"
    private val email = "someone@example.com"

    /** Fake SDK signing seam — records the call, replays a scripted outcome. */
    private class FakeSigner(
        var onSign: (String, String) -> String = { _, _ -> "IIfakebase64signature==" }
    ) : SdkMessageSigner {
        var calls = 0
        var lastAddress: String? = null
        var lastMessage: String? = null

        override suspend fun signMessage(address: String, message: String): String {
            calls++
            lastAddress = address
            lastMessage = message
            return onSign(address, message)
        }
    }

    /**
     * The production signing logic under test. [SecurityFunctions] itself
     * cannot be constructed here — `PinRetryController`'s static
     * initializer builds an Android-dependent singleton that fails to
     * initialize on the host JVM — so `SecurityFunctions.signMessage` is a
     * one-line delegation to [signMessageViaSdk] and this exercises that
     * function directly. Same convention as `classifyCoreSendFailure` in
     * `SdkL1SendService`.
     */
    private suspend fun sign(signer: SdkMessageSigner, address: String, message: String): String =
        signMessageViaSdk(signer, address, message)

    // ── Happy path ────────────────────────────────────────────────────

    @Test
    fun `returns the SDK signature verbatim`() = runBlocking {
        val signer = FakeSigner(onSign = { _, _ -> "H9base64signature==" })

        val result = sign(signer, address, email)

        assertEquals("H9base64signature==", result)
        assertEquals(1, signer.calls)
    }

    @Test
    fun `passes address and message through unmodified`() = runBlocking {
        val signer = FakeSigner()
        // CrowdNode's two real messages: an email (RegisterEmail) and a
        // decimal duffs string (Withdrawal).
        sign(signer, address, "100000000")

        assertEquals(address, signer.lastAddress)
        assertEquals("100000000", signer.lastMessage)
    }

    // ── The regression guard: no empty string on failure ──────────────

    @Test
    fun `throws instead of returning empty string when the key is unavailable`() = runBlocking {
        val signer = FakeSigner(onSign = { _, _ ->
            throw DashSdkError.PlatformWallet.SigningKeyUnavailable("no signing key for address")
        })

        try {
            val result = sign(signer, address, email)
            fail(
                "expected MessageSigningException; the dashj implementation's silent " +
                    "empty-signature behavior must NOT be preserved (got: '$result')"
            )
        } catch (ex: MessageSigningException) {
            assertEquals(MessageSigningException.Reason.SIGNING_KEY_UNAVAILABLE, ex.reason)
        }
    }

    @Test
    fun `no failure mode answers with an empty or blank signature`() = runBlocking {
        val failures = listOf(
            DashSdkError.PlatformWallet.SigningKeyUnavailable("no signing key"),
            DashSdkError.PlatformWallet.Generic(PWFFI_ERROR_INVALID_PARAMETER, "bad address"),
            DashSdkError.PlatformWallet.Generic(99, "unknown"),
            IllegalStateException("no single bound SDK wallet to sign with"),
            IllegalArgumentException("message contains an unpaired surrogate")
        )

        failures.forEach { failure ->
            val signer = FakeSigner(onSign = { _, _ -> throw failure })
            try {
                val result = sign(signer, address, email)
                fail("expected a throw for $failure, got '$result'")
            } catch (ex: MessageSigningException) {
                assertNotEquals("", ex.message)
                assertSame("the original SDK error must survive as the cause", failure, ex.cause)
            }
        }
    }

    // ── Typed error mapping ───────────────────────────────────────────

    @Test
    fun `SigningKeyUnavailable maps to SIGNING_KEY_UNAVAILABLE and keeps the cause`() = runBlocking {
        val sdkError = DashSdkError.PlatformWallet.SigningKeyUnavailable("wallet owns no key for yTsG…")
        val signer = FakeSigner(onSign = { _, _ -> throw sdkError })

        try {
            sign(signer, address, email)
            fail("expected MessageSigningException")
        } catch (ex: MessageSigningException) {
            assertEquals(MessageSigningException.Reason.SIGNING_KEY_UNAVAILABLE, ex.reason)
            assertSame(sdkError, ex.cause)
        }
    }

    @Test
    fun `platform-wallet code 2 maps to INVALID_ADDRESS`() = runBlocking {
        // ErrorInvalidParameter has no dedicated Kotlin type: DashSdkError's
        // code mapping falls through to Generic(2, …).
        val sdkError = DashSdkError.PlatformWallet.Generic(
            PWFFI_ERROR_INVALID_PARAMETER,
            "invalid address"
        )
        val signer = FakeSigner(onSign = { _, _ -> throw sdkError })

        try {
            sign(signer, "not-an-address", email)
            fail("expected MessageSigningException")
        } catch (ex: MessageSigningException) {
            assertEquals(MessageSigningException.Reason.INVALID_ADDRESS, ex.reason)
            assertSame(sdkError, ex.cause)
        }
    }

    @Test
    fun `other generic native codes map to UNAVAILABLE`() = runBlocking {
        val sdkError = DashSdkError.PlatformWallet.Generic(99, "unknown failure")
        val signer = FakeSigner(onSign = { _, _ -> throw sdkError })

        try {
            sign(signer, address, email)
            fail("expected MessageSigningException")
        } catch (ex: MessageSigningException) {
            assertEquals(MessageSigningException.Reason.UNAVAILABLE, ex.reason)
        }
    }

    @Test
    fun `an unbootable SDK maps to UNAVAILABLE`() = runBlocking {
        // What DashSdkMessageSigner raises when ensureStarted() fails or no
        // single wallet is bound.
        val boom = IllegalStateException("SDK wallet manager missing after ensureStarted()")
        val signer = FakeSigner(onSign = { _, _ -> throw boom })

        try {
            sign(signer, address, email)
            fail("expected MessageSigningException")
        } catch (ex: MessageSigningException) {
            assertEquals(MessageSigningException.Reason.UNAVAILABLE, ex.reason)
            assertSame(boom, ex.cause)
        }
    }

    @Test
    fun `the surrogate guard surfaces as UNAVAILABLE rather than crashing`() = runBlocking {
        // The SDK require()s a well-formed message. CrowdNode's messages
        // (emails, decimal amounts) can never trip this, but the mapping
        // must still be a typed failure and not an escaping IllegalArgument.
        val signer = FakeSigner(onSign = { _, _ ->
            throw IllegalArgumentException("message contains an unpaired surrogate")
        })

        try {
            sign(signer, address, "\uD800")
            fail("expected MessageSigningException")
        } catch (ex: MessageSigningException) {
            assertEquals(MessageSigningException.Reason.UNAVAILABLE, ex.reason)
            assertTrue(ex.cause is IllegalArgumentException)
        }
    }

    // ── Structured concurrency ────────────────────────────────────────

    @Test
    fun `cancellation propagates unwrapped`() {
        val signer = FakeSigner(onSign = { _, _ -> throw CancellationException("scope cancelled") })

        try {
            runBlocking { sign(signer, address, email) }
            fail("expected CancellationException")
        } catch (ex: CancellationException) {
            // Wrapping cancellation into MessageSigningException would break
            // structured concurrency (a cancelled scope must stay cancelled).
            assertEquals("scope cancelled", ex.message)
        }
    }
}
