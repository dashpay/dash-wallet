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

import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.service.platform.ContactIntegrityRepair.Direction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The integrity pass is the ONLY retry for a contact whose key chain was
 * skipped on a lookup timeout (the incremental contact fetch never revisits a
 * stored row). Field logs (12.0.0-qa28/qa30): it was skipped on every one of
 * 831 passes because the dashpay contract was never loaded, so 7 restored
 * contacts never got their chains and 5 incoming payments went unseen.
 */
class ContactIntegrityRepairTest {

    private val me = "me"

    private fun sent(to: String) =
        DashPayContactRequest(me, to, 0, ByteArray(96), 0, 0, 1L, null, null)

    private fun received(from: String, accountReference: Int = 0) =
        DashPayContactRequest(from, me, accountReference, ByteArray(96), 0, 0, 1L, null, null)

    /** A fake wallet + network the repair drives through its lambdas. */
    private class World {
        val keyChains = mutableSetOf<String>()
        var contractAvailable = false
        var contractLoads = 0
        /** Contact keys whose identity lookup currently times out. */
        val timingOut = mutableSetOf<String>()
        val lookups = mutableListOf<String>()
        val profiles = mutableSetOf<String>()
        val profileFetches = mutableListOf<List<String>>()
        var fetchedProfilesBecomeAvailable = true

        fun key(row: DashPayContactRequest, direction: Direction) = when (direction) {
            Direction.SENT -> ContactIntegrityRepair.sentKey(row)
            Direction.RECEIVED -> ContactIntegrityRepair.receivedKey(row)
        }

        suspend fun run(
            repair: ContactIntegrityRepair,
            sentRows: List<DashPayContactRequest>,
            receivedRows: List<DashPayContactRequest>
        ) = repair.run(
            sentRequests = sentRows,
            receivedRequests = receivedRows,
            hasKeyChain = { row, d -> key(row, d) in keyChains },
            ensureContractLoaded = {
                contractLoads++
                contractAvailable
            },
            addKeyChain = { row, d ->
                val k = key(row, d)
                lookups += k
                if (k in timingOut) {
                    false // checkAndAdd* returns false on a timed-out lookup
                } else {
                    keyChains += k
                    true
                }
            },
            hasProfile = { it in profiles },
            fetchProfiles = { ids ->
                profileFetches += ids
                if (fetchedProfilesBecomeAvailable) profiles += ids
            }
        )
    }

    private var now = 1_000_000L
    private fun repair() = ContactIntegrityRepair(clock = { now }, initialBackoffMs = 1_000L, maxBackoffMs = 8_000L)

    @Test
    fun integrityPass_runsOnceTheContractBecomesAvailable() = runTest {
        val world = World().apply { profiles += listOf("alice", "bob") }
        val repair = repair()
        val rows = listOf(sent("alice"))
        val receivedRows = listOf(received("bob"))

        // Pass 1: chains missing, contract cannot be loaded -> no document is built.
        val first = world.run(repair, rows, receivedRows)
        assertTrue(first.contractUnavailable)
        assertEquals(2, first.keyChainsMissing)
        assertEquals(0, first.keyChainsAdded)
        assertTrue(world.lookups.isEmpty())

        // The contract load is backed off too: a pass inside the window does not retry it.
        world.contractAvailable = true
        world.run(repair, rows, receivedRows)
        assertEquals(1, world.contractLoads)
        assertTrue(world.lookups.isEmpty())

        // Once the window passes the contract loads and the chains are repaired.
        now += 1_000L
        val third = world.run(repair, rows, receivedRows)
        assertFalse(third.contractUnavailable)
        assertEquals(2, third.keyChainsAdded)
        assertEquals(setOf("sent:alice", "received:bob:0"), world.keyChains)
    }

    @Test
    fun contactSkippedOnTimeout_getsItsKeyChainOnALaterPass() = runTest {
        val world = World().apply {
            contractAvailable = true
            profiles += listOf("alice", "bob", "carol")
            keyChains += "sent:alice" // healthy contact
            timingOut += "received:carol:0"
        }
        val repair = repair()
        val sentRows = listOf(sent("alice"), sent("bob"))
        val receivedRows = listOf(received("carol"))

        val first = world.run(repair, sentRows, receivedRows)
        // Only contacts actually MISSING a chain are looked up; alice never is.
        assertEquals(listOf("sent:bob", "received:carol:0"), world.lookups)
        assertEquals(1, first.keyChainsAdded)
        assertFalse("received:carol:0" in world.keyChains)

        // Still timing out, and inside the backoff window: no 60 s lookup this pass.
        world.lookups.clear()
        val second = world.run(repair, sentRows, receivedRows)
        assertTrue(world.lookups.isEmpty())
        assertEquals(1, second.keyChainsDeferred)

        // The node recovers and the window has passed: the chain is added.
        world.timingOut.clear()
        now += 1_000L
        val third = world.run(repair, sentRows, receivedRows)
        assertEquals(listOf("received:carol:0"), world.lookups)
        assertEquals(1, third.keyChainsAdded)
        assertTrue("received:carol:0" in world.keyChains)

        // Nothing missing any more: later passes do no lookups and need no contract.
        world.lookups.clear()
        val loadsBefore = world.contractLoads
        val fourth = world.run(repair, sentRows, receivedRows)
        assertEquals(0, fourth.keyChainsMissing)
        assertTrue(world.lookups.isEmpty())
        assertEquals(loadsBefore, world.contractLoads)
    }

    @Test
    fun persistentTimeout_backsOffExponentiallyUpToTheCap() = runTest {
        val world = World().apply {
            contractAvailable = true
            profiles += "dave"
            timingOut += "sent:dave"
        }
        val repair = repair()
        val rows = listOf(sent("dave"))

        // Attempt times: t, t+1s, t+3s, t+7s, t+15s (1, 2, 4, 8 s waits), then the 8 s cap.
        val attemptsAt = mutableListOf<Long>()
        val start = now
        repeat(40) {
            val before = world.lookups.size
            world.run(repair, rows, emptyList())
            if (world.lookups.size > before) attemptsAt += now - start
            now += 500L
        }
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L), attemptsAt)
        assertEquals(5, repair.failureCount("sent:dave"))
    }

    @Test
    fun aThrowingRepair_doesNotStopTheOtherContacts() = runTest {
        val repair = repair()
        val added = mutableListOf<String>()
        val result = repair.run(
            sentRequests = listOf(sent("erin"), sent("frank")),
            receivedRequests = emptyList(),
            hasKeyChain = { row, _ -> row.toUserId in added },
            ensureContractLoaded = { true },
            addKeyChain = { row, _ ->
                if (row.toUserId == "erin") throw NullPointerException("Documents.create")
                added += row.toUserId
                true
            },
            hasProfile = { true },
            fetchProfiles = { }
        )
        assertEquals(1, result.keyChainsAdded)
        assertEquals(listOf("frank"), added)
        assertEquals(1, repair.failureCount("sent:erin"))
    }

    @Test
    fun missingProfiles_areRefetched_evenWhenTheContractIsUnavailable() = runTest {
        val world = World().apply {
            contractAvailable = false
            profiles += "alice"
            keyChains += listOf("sent:alice", "sent:bob")
        }
        val repair = repair()
        val sentRows = listOf(sent("alice"), sent("bob"))
        val receivedRows = listOf(received("carol"), received("bob", accountReference = 7))

        val result = world.run(repair, sentRows, receivedRows)
        // bob appears twice (sent + received) but is fetched once; alice has a profile.
        assertEquals(listOf(listOf("bob", "carol")), world.profileFetches)
        assertEquals(listOf("bob", "carol"), result.profilesRefetched)

        // A fetched profile is not fetched again.
        world.profileFetches.clear()
        world.run(repair, sentRows, receivedRows)
        assertTrue(world.profileFetches.isEmpty())
    }

    @Test
    fun aProfileThatStillCannotBeFound_isRetriedNextPass() = runTest {
        val world = World().apply {
            keyChains += "sent:gina"
            fetchedProfilesBecomeAvailable = false
        }
        val repair = repair()
        world.run(repair, listOf(sent("gina")), emptyList())
        world.run(repair, listOf(sent("gina")), emptyList())
        assertEquals(listOf(listOf("gina"), listOf("gina")), world.profileFetches)
    }
}
