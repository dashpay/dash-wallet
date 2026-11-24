/*
 * Copyright 2026 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.dashpay.work

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.Operation
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.common.util.concurrent.Futures
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.service.platform.IdentityRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.Configuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class InviterContactRequestWorkerTest {
    private val identityRepository = mockk<IdentityRepository>(relaxed = true)
    private val configuration = mockk<Configuration>(relaxed = true)
    private val inviterId = "inviterUserId"
    private val identityId = "inviteeUserId"
    private var savedInviter: String? = null

    @Before
    fun setUp() {
        mockkConstructor(SendContactRequestOperation::class)
        val operation = mockk<Operation> { every { result } returns Futures.immediateFuture(Operation.SUCCESS) }
        every { anyConstructed<SendContactRequestOperation>().create(any()) } returns mockk {
            every { enqueue() } returns operation
        }
        every { configuration.inviter } answers { savedInviter }
        every { configuration.inviter = any() } answers { savedInviter = firstArg() }
        every { identityRepository.blockchainIdentity } returns mockk(relaxed = true) {
            every { uniqueIdString } returns identityId
        }
        coEvery { identityRepository.getUser("inviter") } returns listOf(
            UsernameSearchResult("inviter", DashPayProfile(inviterId, "inviter"), null, null)
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun reachableInviter_isSentTheContactRequest() {
        assertTrue(run() is Result.Success)

        verify(exactly = 1) { anyConstructed<SendContactRequestOperation>().create(inviterId) }
        assertEquals(inviterId, savedInviter)
        verify { configuration.inviterContactRequestSentInfoShown = false }
    }

    @Test
    fun inviterLookupFailure_isRetried() {
        coEvery { identityRepository.getUser(any()) } throws IOException("offline")

        assertTrue(run() is Result.Retry)

        verify(exactly = 0) { anyConstructed<SendContactRequestOperation>().create(any()) }
        assertNull(savedInviter)
    }

    @Test
    fun inviterLookupFailure_stopsRetryingAfterTheLastAttempt() {
        coEvery { identityRepository.getUser(any()) } throws IOException("offline")

        assertTrue(run(attempt = InviterContactRequestWorker.MAX_ATTEMPTS - 1) is Result.Failure)
    }

    @Test
    fun anotherIdentityLoaded_isNotSentFrom() {
        every { identityRepository.blockchainIdentity } returns mockk(relaxed = true) {
            every { uniqueIdString } returns "someoneElse"
        }

        assertTrue(run() is Result.Failure)

        verify(exactly = 0) { anyConstructed<SendContactRequestOperation>().create(any()) }
    }

    @Test
    fun identityNotLoadedYet_isRetried() {
        every { identityRepository.blockchainIdentity } returns null

        assertTrue(run() is Result.Retry)

        verify(exactly = 0) { anyConstructed<SendContactRequestOperation>().create(any()) }
    }

    @Test
    fun inviterAlreadySentARequest_isNotSentAnother() {
        savedInviter = inviterId

        assertTrue(run() is Result.Success)

        verify(exactly = 0) { anyConstructed<SendContactRequestOperation>().create(any()) }
    }

    @Test
    fun unknownInviter_fails() {
        coEvery { identityRepository.getUser(any()) } returns emptyList()

        assertTrue(run() is Result.Failure)

        verify(exactly = 0) { anyConstructed<SendContactRequestOperation>().create(any()) }
    }

    private fun run(attempt: Int = 0): Result {
        val parameters = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns workDataOf(
                InviterContactRequestWorker.KEY_INVITER_USERNAME to "inviter",
                InviterContactRequestWorker.KEY_IDENTITY_ID to identityId
            )
            every { runAttemptCount } returns attempt
        }
        val worker = InviterContactRequestWorker(
            ApplicationProvider.getApplicationContext(),
            parameters,
            identityRepository,
            configuration
        )
        return runBlocking { worker.doWorkWithBaseProgress() }
    }
}
