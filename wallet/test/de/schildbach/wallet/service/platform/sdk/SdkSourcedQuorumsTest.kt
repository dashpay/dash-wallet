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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun hashHex(seed: Int) = "%02x".format(seed).repeat(32)
private fun keyHex(seed: Int) = "%02x".format(seed).repeat(48)

private fun quorumsJson(vararg pairs: Pair<String, String>): String {
    val sets = pairs.joinToString(",") { (hash, key) ->
        """{"quorum_hash":"$hash","core_height":1,"members":[],"threshold_public_key":"$key"}"""
    }
    return """{"quorum_hashes":[],"current_quorum_hash":"${hashHex(1)}",""" +
        """"validator_sets":[$sets],"last_block_proposer":"aa","last_platform_block_height":5}"""
}

class DecodeHexOrNullTest {

    @Test
    fun decodesExactLength() {
        val bytes = decodeHexOrNull("00ff10", 3)!!
        assertArrayEquals(byteArrayOf(0x00, 0xff.toByte(), 0x10), bytes)
    }

    @Test
    fun accepts0xPrefixAndUppercase() {
        assertArrayEquals(byteArrayOf(0xab.toByte()), decodeHexOrNull("0xAB", 1))
    }

    @Test
    fun rejectsWrongLengthAndBadDigits() {
        assertNull(decodeHexOrNull("00ff", 3)) // too short
        assertNull(decodeHexOrNull("00ff1022", 3)) // too long
        assertNull(decodeHexOrNull("00zz10", 3)) // bad digit
        assertNull(decodeHexOrNull("", 1))
    }
}

class ParseQuorumThresholdKeysTest {

    @Test
    fun parsesValidatorSets() {
        val json = quorumsJson(
            hashHex(0x11) to keyHex(0x21),
            hashHex(0x12) to keyHex(0x22)
        )
        val keys = parseQuorumThresholdKeys(json)
        assertEquals(2, keys.size)
        assertArrayEquals(decodeHexOrNull(keyHex(0x21), 48), keys[hashHex(0x11)])
        assertArrayEquals(decodeHexOrNull(keyHex(0x22), 48), keys[hashHex(0x12)])
    }

    @Test
    fun lowercasesUppercaseHashKeys() {
        val json = quorumsJson(hashHex(0xAB).uppercase() to keyHex(0x21))
        val keys = parseQuorumThresholdKeys(json)
        assertEquals(setOf(hashHex(0xAB)), keys.keys)
    }

    @Test
    fun skipsMalformedEntriesKeepsGoodOnes() {
        val json = quorumsJson(
            "nothex" to keyHex(0x21), // bad hash
            hashHex(0x12) to "beef", // wrong-length key
            hashHex(0x13) to keyHex(0x23) // good
        )
        val keys = parseQuorumThresholdKeys(json)
        assertEquals(setOf(hashHex(0x13)), keys.keys)
    }

    @Test
    fun toleratesMissingFields() {
        assertTrue(parseQuorumThresholdKeys("""{"validator_sets":[{}]}""").isEmpty())
        assertTrue(parseQuorumThresholdKeys("""{"validator_sets":{}}""").isEmpty())
        assertTrue(parseQuorumThresholdKeys("""{}""").isEmpty())
    }

    @Test
    fun toleratesGarbage() {
        assertTrue(parseQuorumThresholdKeys("not json at all {{{").isEmpty())
        assertTrue(parseQuorumThresholdKeys("").isEmpty())
        assertTrue(parseQuorumThresholdKeys("42").isEmpty())
    }
}

class SdkQuorumKeyCacheTest {

    private var now = 0L
    private val clock: () -> Long = { now }

    @Test
    fun fetchesOnFirstAccessAndCaches() {
        var fetches = 0
        val cache = SdkQuorumKeyCache(
            fetchQuorumsJson = { fetches++; quorumsJson(hashHex(0x11) to keyHex(0x21)) },
            clock = clock
        )
        assertEquals(setOf(hashHex(0x11)), cache.snapshot().keys)
        assertEquals(1, fetches)
        // Within the refresh interval nothing refetches.
        now += 60_000L
        assertEquals(setOf(hashHex(0x11)), cache.snapshot().keys)
        assertEquals(1, fetches)
    }

    @Test
    fun mergesAcrossRefreshesSoRotatedOutQuorumsStayResolvable() {
        var call = 0
        val cache = SdkQuorumKeyCacheUnderTest {
            call++
            if (call == 1) quorumsJson(hashHex(0x11) to keyHex(0x21))
            else quorumsJson(hashHex(0x12) to keyHex(0x22))
        }
        assertEquals(setOf(hashHex(0x11)), cache.cache.snapshot().keys)
        cache.now += 5 * 60_000L // past the refresh interval
        val merged = cache.cache.snapshot()
        assertEquals(setOf(hashHex(0x11), hashHex(0x12)), merged.keys)
    }

    @Test
    fun emptyResultRetriesAfterRetryIntervalOnly() {
        var fetches = 0
        val cache = SdkQuorumKeyCache(
            fetchQuorumsJson = { fetches++; null },
            clock = clock
        )
        assertTrue(cache.snapshot().isEmpty())
        assertEquals(1, fetches)
        // Immediately after a failed fetch: rate-limited, no refetch.
        now += 1_000L
        assertTrue(cache.snapshot().isEmpty())
        assertEquals(1, fetches)
        // After the retry interval the fetch runs again.
        now += 30_000L
        assertTrue(cache.snapshot().isEmpty())
        assertEquals(2, fetches)
    }

    @Test
    fun fetchFailureKeepsPreviousKeys() {
        var call = 0
        val cache = SdkQuorumKeyCacheUnderTest {
            call++
            if (call == 1) quorumsJson(hashHex(0x11) to keyHex(0x21))
            else throw IllegalStateException("boom")
        }
        assertEquals(setOf(hashHex(0x11)), cache.cache.snapshot().keys)
        cache.now += 5 * 60_000L
        // The throwing refresh is contained and the old keys survive.
        assertEquals(setOf(hashHex(0x11)), cache.cache.snapshot().keys)
        assertTrue(call >= 2)
    }

    @Test
    fun runawayMergeResetsToLatestFetch() {
        var call = 0
        val harness = SdkQuorumKeyCacheUnderTest(maxEntries = 2) {
            call++
            when (call) {
                1 -> quorumsJson(hashHex(0x11) to keyHex(0x21), hashHex(0x12) to keyHex(0x22))
                else -> quorumsJson(hashHex(0x13) to keyHex(0x23))
            }
        }
        assertEquals(2, harness.cache.snapshot().size)
        harness.now += 5 * 60_000L
        // 2 cached + 1 fetched exceeds maxEntries=2 → reset to the fetch.
        assertEquals(setOf(hashHex(0x13)), harness.cache.snapshot().keys)
    }

    /** Small harness bundling a mutable clock with the cache under test. */
    private class SdkQuorumKeyCacheUnderTest(
        maxEntries: Int = 512,
        fetch: suspend () -> String?
    ) {
        var now = 0L
        val cache = SdkQuorumKeyCache(
            fetchQuorumsJson = fetch,
            clock = { now },
            maxEntries = maxEntries
        )
    }
}
