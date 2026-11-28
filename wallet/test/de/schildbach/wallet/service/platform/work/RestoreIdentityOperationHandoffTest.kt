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
package de.schildbach.wallet.service.platform.work

import android.app.Application
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import de.schildbach.wallet.security.SecurityGuard
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The SDK registrations' recovery handoff: an older recovery job for the same identity that is
 * still running must not absorb it (KEEP discarded the new job, and the older one could write the
 * old name back). The handoff's job is chained after any in-flight one (APPEND_OR_REPLACE), so a
 * recovery for the replacement always runs, reading the record and journal when it starts.
 */
class RestoreIdentityOperationHandoffTest {
    private val identityId = "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr"
    private val workManager = mockk<WorkManager>(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
        mockkStatic(SecurityGuard::class)
        every { SecurityGuard.getInstance() } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `a handoff is chained after an in-flight recovery, never discarded`() {
        RestoreIdentityOperation(mockk<Application>(relaxed = true)).create(identityId, fromCreation = true, afterInFlight = true)

        verify {
            workManager.beginUniqueWork(
                RestoreIdentityOperation.uniqueWorkName(identityId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun `other triggers still join a running recovery`() {
        RestoreIdentityOperation(mockk<Application>(relaxed = true)).create(identityId, true)

        verify {
            workManager.beginUniqueWork(
                RestoreIdentityOperation.uniqueWorkName(identityId),
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>()
            )
        }
    }
}
