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

import de.schildbach.wallet.service.platform.InMemoryVerifyLinkObligationStore
import de.schildbach.wallet.service.platform.VerifyLinkObligation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VerifyLinkReconciler]: the one owner of a contested name's verification-link publication.
 * A document is created only after a lookup proves it absent, never twice on an uncertain
 * outcome, and the obligation is kept until the document is found.
 */
class VerifyLinkReconcilerTest {
    private val identityId = "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr"
    private val link = "https://example.com/proof"
    private val t0 = 1_000_000_000L

    private inner class World {
        val store = InMemoryVerifyLinkObligationStore()
        var current = VerifyLinkReconciler.CurrentRequest(identityId, "alice", link)
        var owned = true
        var present: Boolean? = false
        var inFlight = false
        var now = t0
        val enqueued = mutableListOf<Pair<String, String>>()

        val reconciler = VerifyLinkReconciler(
            store = store,
            currentRequest = { current },
            ownsOrContends = { _, _ -> owned },
            documentPresent = { _, _ -> present },
            publicationInFlight = { inFlight },
            enqueuePublication = { label, url -> enqueued += label to url },
            clock = { now }
        )

        fun record() = runBlocking { store.recordVerifyObligation(identityId, "alice", link) }

        fun pass() = runBlocking { reconciler.reconcile() }

        /** Moves the clock past both the settle time and the pass interval. */
        fun later() {
            now += VerifyLinkReconciler.SETTLE_MS + VerifyLinkReconciler.MIN_PASS_INTERVAL_MS
        }
    }

    @Test
    fun `a registered contested name with no document yet is published once`() {
        val w = World()
        w.record()

        assertTrue(w.pass())

        assertEquals(listOf("alice" to link), w.enqueued)
        assertEquals("the attempt is recorded", t0, w.store.obligation?.attemptAtMs)
    }

    @Test
    fun `an ambiguous submission whose document landed is never created again, and the obligation clears`() {
        val w = World()
        w.record()
        w.pass()
        runBlocking { w.store.markVerifyUncertain(identityId, "alice", w.now) } // the worker: Ambiguous
        w.present = true
        w.later()

        assertFalse(w.pass())

        assertEquals("no second create", 1, w.enqueued.size)
        assertNull("done once the document is found", w.store.obligation)
    }

    @Test
    fun `an ambiguous submission is not re-published before it settles, then only once its absence is proven`() {
        val w = World()
        w.record()
        w.pass()
        runBlocking { w.store.markVerifyUncertain(identityId, "alice", w.now) }
        w.now += VerifyLinkReconciler.MIN_PASS_INTERVAL_MS // not settled yet

        assertFalse(w.pass())
        assertEquals(1, w.enqueued.size)

        w.later()
        assertTrue("absent once settled: one more publication", w.pass())
        assertEquals(2, w.enqueued.size)
        assertFalse("its attempt is no longer uncertain", w.store.obligation?.uncertain ?: true)
    }

    @Test
    fun `an unreadable lookup creates nothing`() {
        val w = World()
        w.record()
        runBlocking { w.store.markVerifyUncertain(identityId, "alice", w.now) }
        w.later()
        w.present = null

        assertFalse(w.pass())

        assertTrue(w.enqueued.isEmpty())
        assertNotNull(w.store.obligation)
    }

    @Test
    fun `a transient worker failure is published again by a later pass`() {
        val w = World()
        w.record()
        w.pass() // the worker then fails before submitting (the identity not loaded yet)
        w.later()

        assertTrue(w.pass())

        assertEquals(2, w.enqueued.size)
    }

    @Test
    fun `nothing is published while a publication is in flight or the name is not the identity's yet`() {
        val w = World()
        w.record()
        w.inFlight = true
        assertFalse(w.pass())
        w.inFlight = false
        w.owned = false
        w.later()
        assertFalse(w.pass())

        assertTrue(w.enqueued.isEmpty())
        assertNotNull("kept until the name registers", w.store.obligation)
    }

    @Test
    fun `a recovery's registration is published by the reconcile`() {
        // RestoreIdentityWorker registers the name through the SDK owner, which records the
        // obligation; nothing in the worker publishes it
        val w = World()
        w.record()
        w.owned = false
        w.pass() // not registered yet
        w.owned = true // the recovery's registration landed
        w.now += VerifyLinkReconciler.MIN_PASS_INTERVAL_MS

        assertTrue(w.pass())
        assertEquals(1, w.enqueued.size)
    }

    @Test
    fun `a request that no longer applies is dropped`() {
        for (change in listOf<(World) -> Unit>(
            { it.current = it.current.copy(identityId = "8fLsPxiqXkZuS2Dhe3y5JVbbLpz8aVLPEkfZwcrGqZjk") },
            { it.current = it.current.copy(username = "bobby") },
            { it.current = it.current.copy(link = "") }
        )) {
            val w = World()
            w.record()
            change(w)

            assertFalse(w.pass())

            assertTrue(w.enqueued.isEmpty())
            assertNull(w.store.obligation)
        }
    }

    @Test
    fun `Platform is read at most once per pass interval`() {
        val w = World()
        w.record()
        w.owned = false
        var reads = 0
        val counting = VerifyLinkReconciler(
            store = w.store,
            currentRequest = { w.current },
            ownsOrContends = { _, _ -> reads++; false },
            documentPresent = { _, _ -> false },
            publicationInFlight = { false },
            enqueuePublication = { _, _ -> },
            clock = { w.now }
        )
        runBlocking {
            counting.reconcile()
            counting.reconcile()
            w.now += VerifyLinkReconciler.MIN_PASS_INTERVAL_MS
            counting.reconcile()
        }

        assertEquals(2, reads)
    }

    @Test
    fun `re-recording the same request keeps an uncertain attempt`() = runBlocking {
        val store = InMemoryVerifyLinkObligationStore()
        store.recordVerifyObligation(identityId, "alice", link)
        store.markVerifyUncertain(identityId, "Alice", t0)
        store.recordVerifyObligation(identityId, "alice", link)

        assertEquals(VerifyLinkObligation(identityId, "alice", link, t0, uncertain = true), store.obligation)
    }

    @Test
    fun `only a read that completed proves the document absent`() = runBlocking {
        // the SDK search's own answer is definite; without it (flag off, failed) the dashj query,
        // which returns null only for an empty result and throws on any failed read
        assertEquals(true, verifyDocumentPresence({ java.util.Optional.of(link) }, { error("not reached") }))
        assertEquals(false, verifyDocumentPresence({ java.util.Optional.empty() }, { error("not reached") }))
        assertEquals(true, verifyDocumentPresence({ null }, { Any() }))
        assertEquals(false, verifyDocumentPresence({ null }, { null }))
        assertNull(
            "a failed read is unknown, never absent",
            verifyDocumentPresence({ null }, { throw org.dashj.platform.dapiclient.MaxRetriesReachedException(emptyList<Exception>()) })
        )
        assertNull(verifyDocumentPresence({ throw java.io.IOException("search failed") }, { null }))
    }

    @Test
    fun `a lookup that cannot be read never enqueues`() {
        val w = World()
        w.record()
        val failing = VerifyLinkReconciler(
            store = w.store,
            currentRequest = { w.current },
            ownsOrContends = { _, _ -> true },
            documentPresent = { _, _ -> verifyDocumentPresence({ null }, { throw java.io.IOException("DAPI down") }) },
            publicationInFlight = { false },
            enqueuePublication = { label, url -> w.enqueued += label to url },
            clock = { w.now }
        )

        assertFalse(runBlocking { failing.reconcile() })

        assertTrue(w.enqueued.isEmpty())
        assertNotNull("kept for a later pass", w.store.obligation)
    }

    @Test
    fun `updating the verification of a request in its vote goes through the obligation`() = runBlocking {
        val store = InMemoryVerifyLinkObligationStore()
        val savedLinks = mutableListOf<String>()
        var kicked = 0

        assertTrue(
            requestVerificationLinkPublication(identityId, "alice", link, { savedLinks += it }, store, { kicked++ })
        )

        assertEquals(VerifyLinkObligation(identityId, "alice", link), store.obligation)
        assertEquals("the link is on record, so the reconcile keeps the request", listOf(link), savedLinks)
        assertEquals(1, kicked)
    }

    @Test
    fun `a verification update without an identity, a name or a valid link records nothing`() = runBlocking {
        val store = InMemoryVerifyLinkObligationStore()
        assertFalse(requestVerificationLinkPublication(null, "alice", link, {}, store, {}))
        assertFalse(requestVerificationLinkPublication(identityId, null, link, {}, store, {}))
        assertFalse(requestVerificationLinkPublication(identityId, "alice", "not a link", {}, store, {}))
        assertNull(store.obligation)
    }
}
