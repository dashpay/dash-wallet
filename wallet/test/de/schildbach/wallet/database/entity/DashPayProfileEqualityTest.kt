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
package de.schildbach.wallet.database.entity

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A profile whose only change is its username (a registered replacement name) must reach its
 * observers: they de-duplicate with distinctUntilChanged(), i.e. with [DashPayProfile.equals].
 */
class DashPayProfileEqualityTest {
    private val userId = "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr"

    @Test
    fun `profiles that differ only in their username are different`() {
        assertNotEquals(DashPayProfile(userId, "brimoztest3"), DashPayProfile(userId, "replacement22"))
        assertEquals(DashPayProfile(userId, "replacement22"), DashPayProfile(userId, "replacement22"))
    }

    @Test
    fun `a username-only update is not suppressed by distinctUntilChanged`() = runBlocking {
        val emitted = flowOf(
            DashPayProfile(userId, "brimoztest3"),
            DashPayProfile(userId, "replacement22")
        ).distinctUntilChanged().toList()

        assertEquals(listOf("brimoztest3", "replacement22"), emitted.map { it.username })
    }
}
