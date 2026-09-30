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
package de.schildbach.wallet.ui.dashpay.work

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for [labelsFor] — the KEY_LABELS value the worker's failure paths
 * put into `androidx.work.Data`.
 *
 * Pins the emulator-5554 finding (testnet, build 12000013): both failure paths built
 * that value as `arrayOfnames.map { labelMap[it] }`, a `List`, and
 * `androidx.work.Data` accepts only `String[]` —
 *
 *     java.lang.IllegalArgumentException: Key BroadcastUsernameVotesWorker.LABELS
 *       has invalid type class java.util.ArrayList
 *         at androidx.work.Data$Builder.put(Data.java:923)
 *
 * so reporting a vote failure threw before it could return, and the user saw the
 * `ArrayList` message instead of the real vote error. `.toTypedArray()` alone is not
 * enough either: `labelMap[it]` is a nullable lookup, so it would yield an
 * `Array<String?>`, which `Data` rejects for the same reason.
 *
 * These assertions therefore check BOTH properties the crash turned on: the static type
 * is a non-null `Array<String>`, and no element is null even for a name the map has
 * never heard of.
 */
class VoteOutputLabelsTest {

    @Test
    fun `known names map to their display labels`() {
        val labels: Array<String> = labelsFor(
            arrayOf("test-11o1", "a1ice"),
            mapOf("test-11o1" to "test-1101", "a1ice" to "alice")
        )
        assertArrayEquals(arrayOf("test-1101", "alice"), labels)
    }

    @Test
    fun `an unknown name falls back to the normalized name, never to null`() {
        // The all-failed path produced exactly this: no vote came back, so the name set
        // held no entry the labelMap knew, and the lookup returned null.
        val labels: Array<String> = labelsFor(arrayOf("unmapped"), emptyMap())
        // The `Array<String>` declared type above is the other half of the assertion:
        // an `Array<String?>` would not compile here, and `Data` would reject it too.
        assertArrayEquals(arrayOf("unmapped"), labels)
    }

    @Test
    fun `the array stays the same length as the names it labels`() {
        // UsernameRequestsFragment reads KEY_LABELS and KEY_NORMALIZED_LABELS as
        // parallel arrays; filtering an unmapped name out would desync them.
        val names = arrayOf("known", "unmapped", "other")
        assertEquals(names.size, labelsFor(names, mapOf("known" to "Known")).size)
    }

    @Test
    fun `an empty name list yields an empty array`() {
        assertEquals(0, labelsFor(emptyArray(), mapOf("known" to "Known")).size)
    }
}
