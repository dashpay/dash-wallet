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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SdkIdentityQueries] reproduces the legacy key-hash lookup (unique index, then a single
 * non-unique match) over the SDK and passes failures through unchanged.
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
    fun moreThanOneNonUniqueMatchIsNoneAsWithTheLegacyLookup() = runBlocking {
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } returns null
        coEvery { source.identitiesJsonByNonUniquePublicKeyHash(hashHex, null) } returns "[${identityJson(firstId)}]"
        coEvery { source.identitiesJsonByNonUniquePublicKeyHash(hashHex, firstId) } returns "[${identityJson(secondId)}]"

        assertNull(queries.identityByPublicKeyHash(hash))
    }

    @Test
    fun failuresPassThroughUnchanged() {
        val network = DashSdkError.NetworkError("unreachable")
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } throws network
        assertSame(network, assertThrows(DashSdkError.NetworkError::class.java) { runBlocking { queries.identityByPublicKeyHash(hash) } })

        val internal = DashSdkError.InternalError("proof")
        coEvery { source.identityJsonByPublicKeyHash(hashHex) } returns null
        coEvery { source.identitiesJsonByNonUniquePublicKeyHash(hashHex, null) } throws internal
        assertSame(internal, assertThrows(DashSdkError.InternalError::class.java) { runBlocking { queries.identityByPublicKeyHash(hash) } })
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

    @Test
    fun onlyNetworkAndTimeoutCountAsNetworkUnavailable() {
        assertTrue(isSdkNetworkUnavailable(DashSdkError.NetworkError("x")))
        assertTrue(isSdkNetworkUnavailable(DashSdkError.Timeout("x")))
        assertFalse(isSdkNetworkUnavailable(DashSdkError.InternalError("x")))
        assertFalse(isSdkNetworkUnavailable(DashSdkError.NotFound("x")))
        assertFalse(isSdkNetworkUnavailable(IllegalStateException("x")))
    }

    @Test
    fun productionSourceStartsTheSdkFirstAndFailsWhenItCannot() {
        val service = mockk<DashSdkService>()
        val boot = IllegalStateException("SDK bootstrap failed")
        coEvery { service.ensureStarted() } throws boot
        val production = SdkIdentityQueries(DashSdkIdentityReadSource(service))

        assertSame(boot, assertThrows(IllegalStateException::class.java) { runBlocking { production.identityByPublicKeyHash(hash) } })
        assertSame(boot, assertThrows(IllegalStateException::class.java) { runBlocking { production.creditBalance(Identifier.from(firstId)) } })
    }
}
