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

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Base58
import org.bitcoinj.core.Utils
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [SdkIdentityQueries] reproduces the legacy key-hash lookup (unique index, then a single
 * non-unique match) over the SDK, passes failures through unchanged, bounds each round trip
 * (a timeout throws, never reads as absent) and turns a native [LinkageError] into an exception.
 */
class SdkIdentityQueriesTest {
    private val hash = ByteArray(20) { 3 }
    private val hashHex = Utils.HEX.encode(hash)
    private val firstId = Base58.encode(ByteArray(32) { 7 })
    private val secondId = Base58.encode(ByteArray(32) { 8 })

    private fun identityJson(idBase58: String) =
        """{"${'$'}formatVersion":"0","id":"$idBase58","publicKeys":[],"balance":5,"revision":0}"""

    private val source = mockk<SdkIdentityReadSource>()
    private val queries = SdkIdentityQueries(source)

    /**
     * [actual] is [expected] or the copy kotlinx.coroutines' stack-trace recovery makes of it when
     * it crosses the bounded query's `await` (the copy keeps the original as its cause).
     */
    private fun assertSameFailure(expected: Throwable, actual: Throwable) {
        if (actual === expected) return
        assertSame(expected, actual.cause)
        assertEquals(expected.javaClass, actual.javaClass)
    }

    @Test
    fun uniqueMatchIsReturnedWithoutTheNonUniqueQuery() = runBlocking {
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } returns identityJson(firstId)

        val found = queries.identityByPublicKeyHash(hash)

        assertEquals(Identifier.from(firstId), found?.id)
        coVerify(exactly = 0) { source.identitiesJsonByNonUniquePublicKeyHash(any(), any()) }
    }

    @Test
    fun noIdentityInEitherIndexIsNull() = runBlocking {
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } returns null
        coEvery { source.identitiesJsonByNonUniquePublicKeyHash(hashHex, null) } returns "[]"

        assertNull(queries.identityByPublicKeyHash(hash))
    }

    @Test
    fun exactlyOneNonUniqueMatchIsReturned() = runBlocking {
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } returns null
        coEvery { source.identitiesJsonByNonUniquePublicKeyHash(hashHex, null) } returns "[${identityJson(firstId)}]"
        coEvery { source.identitiesJsonByNonUniquePublicKeyHash(hashHex, firstId) } returns "[]"

        assertEquals(Identifier.from(firstId), queries.identityByPublicKeyHash(hash)?.id)
    }

    @Test
    fun moreThanOneNonUniqueMatchIsAmbiguousNotAbsent() {
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } returns null
        coEvery { source.identitiesJsonByNonUniquePublicKeyHash(hashHex, null) } returns "[${identityJson(firstId)}]"
        coEvery { source.identitiesJsonByNonUniquePublicKeyHash(hashHex, firstId) } returns "[${identityJson(secondId)}]"

        assertThrows(SdkAmbiguousIdentityException::class.java) { runBlocking { queries.identityByPublicKeyHash(hash) } }
    }

    @Test
    fun failuresPassThroughUnchanged() {
        val network = DashSdkError.NetworkError("unreachable")
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } throws network
        assertSameFailure(network, assertThrows(DashSdkError.NetworkError::class.java) { runBlocking { queries.identityByPublicKeyHash(hash) } })

        val internal = DashSdkError.InternalError("proof")
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } returns null
        coEvery { source.identitiesJsonByNonUniquePublicKeyHash(hashHex, null) } throws internal
        assertSameFailure(internal, assertThrows(DashSdkError.InternalError::class.java) { runBlocking { queries.identityByPublicKeyHash(hash) } })
    }

    @Test
    fun aHashThatIsNot20BytesIsRejectedBeforeAnyQuery() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { queries.identityByPublicKeyHash(ByteArray(32)) } }
        coVerify(exactly = 0) { source.identityJsonByPublicKeyHash(any()) }
    }

    @Test
    fun balanceIsReadByBase58IdentityId() = runBlocking {
        coEvery { source.creditBalance(firstId) } returns 42_000L
        assertEquals(42_000L, queries.creditBalance(Identifier.from(firstId)))
    }

    /**
     * Blocks the abandoned native-style call (no suspension point) until the test releases it in
     * `@After`, so a read cannot finish inside the budget and no thread is left parked.
     */
    private val release = CountDownLatch(1)

    private fun hang() {
        release.await(30, TimeUnit.SECONDS)
    }

    @After
    fun releaseHungReads() {
        release.countDown()
    }

    /** A source whose every read blocks past the test budget. */
    private fun hangingSource(): SdkIdentityReadSource = mockk<SdkIdentityReadSource>().also {
        coEvery { it.identityJsonByPublicKeyHash(any()) } answers { hang(); null }
        coEvery { it.identitiesJsonByNonUniquePublicKeyHash(any(), any()) } answers { hang(); "[]" }
        coEvery { it.creditBalance(any()) } answers { hang(); null }
    }

    @Test
    fun aDiscoveryRoundTripPastTheBudgetIsAFailureNotAbsent() {
        val bounded = SdkIdentityQueries(hangingSource(), timeoutMs = 50)
        assertThrows(SdkIdentityQueryTimeoutException::class.java) {
            runBlocking { bounded.identityByPublicKeyHash(hash) }
        }
    }

    @Test
    fun eachNonUniquePageIsBoundedToo() {
        val slowPages = mockk<SdkIdentityReadSource>()
        coEvery { slowPages.identityJsonByPublicKeyHash(hashHex) } returns null
        coEvery { slowPages.identitiesJsonByNonUniquePublicKeyHash(hashHex, null) } answers { hang(); "[]" }
        assertThrows(SdkIdentityQueryTimeoutException::class.java) {
            runBlocking { SdkIdentityQueries(slowPages, timeoutMs = 50).identityByPublicKeyHash(hash) }
        }

        val slowSecondPage = mockk<SdkIdentityReadSource>()
        coEvery { slowSecondPage.identityJsonByPublicKeyHash(hashHex) } returns null
        coEvery { slowSecondPage.identitiesJsonByNonUniquePublicKeyHash(hashHex, null) } returns "[${identityJson(firstId)}]"
        coEvery { slowSecondPage.identitiesJsonByNonUniquePublicKeyHash(hashHex, firstId) } answers {
            hang(); "[]"
        }
        assertThrows(SdkIdentityQueryTimeoutException::class.java) {
            runBlocking { SdkIdentityQueries(slowSecondPage, timeoutMs = 50).identityByPublicKeyHash(hash) }
        }
    }

    @Test
    fun aBalanceReadPastTheBudgetIsAFailureNotNull() {
        val bounded = SdkIdentityQueries(hangingSource(), timeoutMs = 50)
        assertThrows(SdkIdentityQueryTimeoutException::class.java) {
            runBlocking { bounded.creditBalance(Identifier.from(firstId)) }
        }
    }

    @Test
    fun theSdksOwnNullWithinTheBudgetStaysNull() = runBlocking {
        coEvery { source.creditBalance(firstId) } returns null
        assertNull(queries.creditBalance(Identifier.from(firstId)))
    }

    @Test
    fun aNativeLinkageErrorBecomesAnOrdinaryExceptionInTheProductionSource() {
        val service = mockk<DashSdkService>()
        val link = UnsatisfiedLinkError("dlopen failed: libdash_sdk.so")
        coEvery { service.ensureStarted() } throws link
        val production = SdkIdentityQueries(DashSdkIdentityReadSource(service))

        val discovery = assertThrows(SdkNativeLibraryUnavailableException::class.java) {
            runBlocking { production.identityByPublicKeyHash(hash) }
        }
        assertSame(link, discovery.cause)
        val balance = assertThrows(SdkNativeLibraryUnavailableException::class.java) {
            runBlocking { production.creditBalance(Identifier.from(firstId)) }
        }
        assertSame(link, balance.cause)
        assertTrue(Exception::class.java.isInstance(balance))
    }

    @Test
    fun productionSourceStartsTheSdkFirstAndFailsWhenItCannot() {
        val service = mockk<DashSdkService>()
        val boot = IllegalStateException("SDK bootstrap failed")
        coEvery { service.ensureStarted() } throws boot
        val production = SdkIdentityQueries(DashSdkIdentityReadSource(service))

        assertSameFailure(boot, assertThrows(IllegalStateException::class.java) { runBlocking { production.identityByPublicKeyHash(hash) } })
        assertSameFailure(boot, assertThrows(IllegalStateException::class.java) { runBlocking { production.creditBalance(Identifier.from(firstId)) } })
    }
}
