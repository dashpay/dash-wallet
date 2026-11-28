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

package de.schildbach.wallet.ui.dashpay

import de.schildbach.wallet.livedata.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the accept/send contact request failure-surfacing
 * decision ([newSendContactRequestErrors]) behind
 * `DashPayViewModel.consumeNewSendContactRequestErrors`: a failed accept
 * must never be a silent no-op, but WorkManager retains FAILED work across
 * sessions and re-emits the state map on every change, so a failure
 * surfaces only when the user started the operation on this screen, and
 * only once per run.
 */
class NewSendContactRequestErrorsTest {

    private val userA = "userA"
    private val userB = "userB"

    private fun error(msg: String = "send contact request failed"): Resource<Unit> =
        Resource.error(msg, null)

    @Test
    fun failureOfOperationStartedByUser_surfacesOnce() {
        val surfaced = hashSetOf<String>()
        val map = mapOf(userA to error())

        assertEquals(listOf(userA), newSendContactRequestErrors(map, setOf(userA), surfaced))
        // Re-emission of the same failed state map (any work change re-emits
        // the whole map) must not surface it again.
        assertTrue(newSendContactRequestErrors(map, setOf(userA), surfaced).isEmpty())
    }

    @Test
    fun staleFailure_notStartedFromThisScreen_isIgnored() {
        // WorkManager keeps FAILED work around, including from previous app
        // sessions — those must not pop a dialog on screen entry.
        val surfaced = hashSetOf<String>()
        val map = mapOf(userA to error())

        assertTrue(newSendContactRequestErrors(map, emptySet(), surfaced).isEmpty())
        assertTrue(surfaced.isEmpty())
    }

    @Test
    fun nonErrorStates_neverSurface() {
        val surfaced = hashSetOf<String>()
        val map = mapOf<String, Resource<Unit>>(
            userA to Resource.loading(null),
            userB to Resource.success(Unit)
        )

        assertTrue(newSendContactRequestErrors(map, setOf(userA, userB), surfaced).isEmpty())
    }

    @Test
    fun retryFailure_surfacesAgain() {
        val surfaced = hashSetOf<String>()

        // First failure surfaces.
        assertEquals(
            listOf(userA),
            newSendContactRequestErrors(mapOf(userA to error()), setOf(userA), surfaced)
        )
        // The retry runs (LOADING re-arms the latch)…
        assertTrue(
            newSendContactRequestErrors(
                mapOf(userA to Resource.loading(null)), setOf(userA), surfaced
            ).isEmpty()
        )
        // …and its failure surfaces anew.
        assertEquals(
            listOf(userA),
            newSendContactRequestErrors(mapOf(userA to error()), setOf(userA), surfaced)
        )
    }

    @Test
    fun mixedMap_onlyNewUserStartedFailuresSurface() {
        val surfaced = hashSetOf<String>("alreadyShown")
        val map = mapOf(
            userA to error(), // started by user, new → surfaces
            userB to error(), // stale (not started from this screen) → ignored
            "alreadyShown" to error(), // started by user but already surfaced
            "pending" to Resource.loading<Unit>(null)
        )

        val result = newSendContactRequestErrors(
            map,
            setOf(userA, "alreadyShown", "pending"),
            surfaced
        )
        assertEquals(listOf(userA), result)
    }
}
