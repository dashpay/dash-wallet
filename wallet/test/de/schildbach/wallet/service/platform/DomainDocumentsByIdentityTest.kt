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

package de.schildbach.wallet.service.platform

import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [fetchAllByIdentity] against a fake Drive that behaves like the real
 * `records.identity in [...]` query: ordered by identity, capped at 100
 * documents, and limited to 100 ids per `in` clause.
 */
class DomainDocumentsByIdentityTest {

    private data class Name(val id: String, val identity: Identifier)

    private class FakeDrive(val names: List<Name>, val cap: Int = 100) {
        val queries = mutableListOf<List<Identifier>>()

        fun query(ids: List<Identifier>): List<Name> {
            require(ids.size <= cap) { "an in clause holds at most $cap values, got ${ids.size}" }
            queries += ids
            val wanted = ids.toSet()
            return names.filter { it.identity in wanted }
                .sortedWith { a, b ->
                    compareIdentifiers(a.identity, b.identity).takeIf { it != 0 } ?: a.id.compareTo(b.id)
                }
                .take(cap)
        }
    }

    private val random = Random(42)

    private fun identity(): Identifier = Identifier.from(random.nextBytes(32))

    private fun fetch(drive: FakeDrive, ids: List<Identifier>): List<Name> =
        fetchAllByIdentity(
            identityIds = ids,
            pageLimit = 100,
            identityOf = { it.identity },
            documentIdOf = { it.id },
            query = drive::query
        )

    /** Names per identity, so each identity's whole set can be checked. */
    private fun namesFor(ids: List<Identifier>, perIdentity: (Int) -> Int): List<Name> =
        ids.flatMapIndexed { index, id -> List(perIdentity(index)) { n -> Name("$id/$n", id) } }

    @Test
    fun theFieldShape_everyIdentityInTheFullBatchGetsItsNames() {
        // 132 contacts, batched 100 + 32; the 100-id batch holds 125 names,
        // so a single capped query leaves the highest-sorting identities with
        // none — the 26 "domain document ... could not be found" contacts.
        val ids = List(132) { identity() }
        val names = namesFor(ids) { index -> if (index < 100 && index % 4 == 0) 2 else 1 }
        val drive = FakeDrive(names)

        val fetched = fetch(drive, ids)

        assertEquals(names.toSet(), fetched.toSet())
        assertEquals("no document may be returned twice", fetched.size, fetched.map { it.id }.toSet().size)
        assertTrue("the capped batch is re-queried", drive.queries.size > 2)
    }

    @Test
    fun identitiesWithManyNamesAreCompleteAcrossSeveralPages() {
        val ids = List(100) { identity() }
        val names = namesFor(ids) { index -> 1 + index % 7 } // ~400 names for 100 ids
        val drive = FakeDrive(names)

        assertEquals(names.toSet(), fetch(drive, ids).toSet())
    }

    @Test
    fun anUncappedBatchCostsOneQuery() {
        val ids = List(40) { identity() }
        val names = namesFor(ids) { 2 }
        val drive = FakeDrive(names)

        assertEquals(names.toSet(), fetch(drive, ids).toSet())
        assertEquals(1, drive.queries.size)
    }

    @Test
    fun idsWithoutNamesDoNotStopTheOthers() {
        val ids = List(100) { identity() }
        // Only every third identity has names, three of them each — 102 names.
        val names = namesFor(ids) { index -> if (index % 3 == 0) 3 else 0 }
        val drive = FakeDrive(names)

        assertEquals(names.toSet(), fetch(drive, ids).toSet())
    }

    @Test
    fun oneIdentityFillingAWholePage_stopsInsteadOfLooping() {
        val heavy = identity()
        val names = List(150) { n -> Name("$heavy/$n", heavy) }
        val drive = FakeDrive(names)

        val fetched = fetch(drive, listOf(heavy))

        assertEquals(100, fetched.size)
        assertEquals(1, drive.queries.size)
    }

    /** An identifier whose every byte is [b], so the test controls the sort order. */
    private fun identityOf(b: Int): Identifier = Identifier.from(ByteArray(32) { b.toByte() })

    @Test
    fun aSaturatedIdentityDoesNotStarveTheIdentitiesAboveIt() {
        // A sorts below B; A alone has exactly a page of names, so the first
        // page holds only A and cannot shrink the batch. B must still be asked.
        val a = identityOf(0x10)
        val b = identityOf(0x20)
        val names = List(100) { n -> Name("$a/$n", a) } + Name("$b/0", b)
        val drive = FakeDrive(names)

        val fetched = fetch(drive, listOf(b, a))

        assertEquals(names.toSet(), fetched.toSet())
        assertEquals(listOf(b), drive.queries.last())
    }

    @Test
    fun aReducedBatchStartingWithASaturatedIdentity_stillReachesTheRest() {
        // X < A < B. Page 1 is X's name plus 99 of A's: X is complete and
        // leaves; the reduced batch [A, B] then starts with A, which fills
        // page 2 by itself (150 names). B is above A and must still be fetched.
        val x = identityOf(0x05)
        val a = identityOf(0x10)
        val b = identityOf(0x20)
        val aNames = List(150) { n -> Name("$a/${n.toString().padStart(3, '0')}", a) }
        val names = listOf(Name("$x/0", x)) + aNames + listOf(Name("$b/0", b), Name("$b/1", b))
        val drive = FakeDrive(names)

        val fetched = fetch(drive, listOf(a, b, x))

        assertTrue("X's name is kept", Name("$x/0", x) in fetched)
        assertTrue("B's names are fetched", fetched.containsAll(listOf(Name("$b/0", b), Name("$b/1", b))))
        assertEquals("A keeps the one page it got", 100, fetched.count { it.identity == a })
        assertEquals("no document may be returned twice", fetched.size, fetched.map { it.id }.toSet().size)
        assertEquals(3, drive.queries.size)
        assertEquals(listOf(b), drive.queries.last())
    }

    @Test
    fun severalSaturatedIdentitiesInARow_eachLeaveAndTheLastNamesAreFetched() {
        val a = identityOf(0x10)
        val b = identityOf(0x20)
        val c = identityOf(0x30)
        val names = List(100) { n -> Name("$a/$n", a) } +
            List(120) { n -> Name("$b/$n", b) } +
            Name("$c/0", c)
        val drive = FakeDrive(names)

        val fetched = fetch(drive, listOf(c, b, a))

        assertTrue(Name("$c/0", c) in fetched)
        assertEquals(100, fetched.count { it.identity == a })
        assertEquals(100, fetched.count { it.identity == b })
        assertEquals(3, drive.queries.size)
    }

    @Test
    fun aFullPageNamingAnIdentityNotAskedFor_stopsInsteadOfLooping() {
        // A misbehaving server: every page is 100 documents of an identity
        // below all requested ones. Dropping "identities above it" cannot
        // shrink the batch, so the helper must stop, not spin.
        val stranger = identityOf(0x01)
        val asked = identityOf(0x20)
        var calls = 0
        val fetched = fetchAllByIdentity(
            identityIds = listOf(asked),
            pageLimit = 100,
            identityOf = { it.identity },
            documentIdOf = { it.id },
            query = { calls++; List(100) { n -> Name("$stranger/$n", stranger) } }
        )

        assertEquals(1, calls)
        assertEquals(100, fetched.size)
    }

    @Test
    fun compareIdentifiers_isUnsignedBytewise() {
        val low = Identifier.from(ByteArray(32) { 0x7f })
        val high = Identifier.from(ByteArray(32) { 0x80.toByte() })

        assertTrue(compareIdentifiers(low, high) < 0)
        assertTrue(compareIdentifiers(high, low) > 0)
        assertEquals(0, compareIdentifiers(low, Identifier.from(ByteArray(32) { 0x7f })))
    }
}
