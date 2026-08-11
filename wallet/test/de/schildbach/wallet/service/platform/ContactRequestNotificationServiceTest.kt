/*
 * Copyright 2025 Dash Core Group.
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

import android.content.Context
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.dash.wallet.common.services.NotificationService
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards on [ContactRequestNotificationService]: the badge count must be recomputed
 * with no observer anywhere in sight, and a system notification must be posted for a
 * genuinely new received contact request — but not on the session's first contact
 * sync (restore storm), not twice for the same request, and not for something the
 * user has already seen on the notifications screen.
 */
class ContactRequestNotificationServiceTest {
    private companion object {
        /** Generous upper bound on the service's own coalesce delay. */
        const val WAIT_MS = 10_000L
    }

    private val lastSeen = 1_000_000L

    private val contextMock = mockk<Context> {
        every { getString(any()) } returns "You have a new contact request"
        every { getString(any(), *anyVararg()) } returns "someone has sent you a contact request"
    }
    private val notificationServiceMock = mockk<NotificationService>(relaxed = true)

    /**
     * Stand-ins for the Room table-invalidation feeds, driven by hand. No replay
     * and no buffer, so nothing fires at construction and every `emit` below
     * suspends until the service has actually taken it.
     */
    private val contactRequestRows = MutableSharedFlow<Int>()
    private val profileRows = MutableSharedFlow<Int>()
    private val lastSeenMarker = MutableSharedFlow<Long?>()

    private val profileDaoMock = mockk<DashPayProfileDao> {
        coEvery { loadByUserId(any()) } returns null
        every { observeCount() } returns profileRows
    }
    private val contactRequestDaoMock = mockk<DashPayContactRequestDao> {
        every { observeCount() } returns contactRequestRows
    }
    private val identityRepositoryMock = mockk<IdentityRepository> {
        coEvery { getNotificationCount(any()) } returns 3
    }
    private val configMock = mockk<DashPayConfig> {
        coEvery { areNotificationsDisabled() } returns false
        coEvery { get(DashPayConfig.LAST_SEEN_NOTIFICATION_TIME) } returns lastSeen
        every { observe(DashPayConfig.LAST_SEEN_NOTIFICATION_TIME) } returns lastSeenMarker
    }

    private fun service() = ContactRequestNotificationService(
        contextMock,
        identityRepositoryMock,
        configMock,
        profileDaoMock,
        contactRequestDaoMock,
        notificationServiceMock
    )

    /** Waits for the service's own data-driven recompute to publish [expected]. */
    private suspend fun awaitCount(
        service: ContactRequestNotificationService,
        expected: Int
    ) = withTimeout(WAIT_MS) { service.unseenNotificationCount.first { it == expected } }

    /**
     * The service subscribes to the table feeds on its own dispatcher, and a
     * buffer-less [MutableSharedFlow] silently DROPS an emission made before a
     * subscriber exists. Every emission below must be observed, so wait for the
     * subscriptions first.
     */
    private suspend fun awaitCollector() = withTimeout(WAIT_MS) {
        contactRequestRows.subscriptionCount.first { it > 0 }
        profileRows.subscriptionCount.first { it > 0 }
        lastSeenMarker.subscriptionCount.first { it > 0 }
    }

    private fun request(userId: String, timestamp: Long = lastSeen + 1) = DashPayContactRequest(
        userId = userId,
        toUserId = "me",
        accountReference = 0,
        encryptedPublicKey = ByteArray(0),
        senderKeyIndex = 0,
        recipientKeyIndex = 0,
        timestamp = timestamp,
        encryptedAccountLabel = null,
        autoAcceptProof = null
    )

    @Test
    fun countRefreshesWithoutAnyObserver() = runBlocking {
        val service = service()
        assertEquals(0, service.unseenNotificationCount.value)

        service.onContactRequestsSynced(listOf(request("alice")), initialSync = false)

        assertEquals(3, service.unseenNotificationCount.value)
    }

    @Test
    fun notificationsDisabledZeroesTheCount() = runBlocking {
        coEvery { configMock.areNotificationsDisabled() } returns true
        val service = service()

        service.onContactRequestsSynced(emptyList(), initialSync = false)

        assertEquals(0, service.unseenNotificationCount.value)
        verify(exactly = 0) { notificationServiceMock.showNotification(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun firstSyncOfTheSessionDoesNotNotify() = runBlocking {
        val service = service()

        service.onContactRequestsSynced(listOf(request("alice")), initialSync = false)

        verify(exactly = 0) { notificationServiceMock.showNotification(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun initialSyncDoesNotNotify() = runBlocking {
        val service = service()
        // complete one pass so the "first sync of this session" latch is set
        service.onContactRequestsSynced(emptyList(), initialSync = false)

        service.onContactRequestsSynced(listOf(request("alice")), initialSync = true)

        verify(exactly = 0) { notificationServiceMock.showNotification(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun newRequestAfterTheFirstSyncNotifiesExactlyOnce() = runBlocking {
        val service = service()
        service.onContactRequestsSynced(emptyList(), initialSync = false)

        service.onContactRequestsSynced(listOf(request("alice")), initialSync = false)
        // a later pass re-reporting the same request must stay silent
        service.onContactRequestsSynced(listOf(request("alice")), initialSync = false)

        // The channel id is asserted as a literal on purpose: it must be the IMPORTANCE_HIGH
        // contacts channel (heads-up + sound), never the IMPORTANCE_LOW DashPay one, and the
        // id string itself is frozen — the system keeps a channel keyed by it forever.
        verify(exactly = 1) {
            notificationServiceMock.showNotification(
                ContactRequestNotificationService.NOTIFICATION_TAG_PREFIX + "alice",
                any(), any(), any(), any(), "dash.notifications.contacts"
            )
        }
    }

    @Test
    fun requestOlderThanLastSeenIsNotAnnounced() = runBlocking {
        val service = service()
        service.onContactRequestsSynced(emptyList(), initialSync = false)

        service.onContactRequestsSynced(listOf(request("alice", timestamp = lastSeen - 1)), initialSync = false)

        verify(exactly = 0) { notificationServiceMock.showNotification(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun requestsSeenDuringTheFirstSyncAreNeverAnnouncedLater() = runBlocking {
        val service = service()
        // the wallet already knew about alice when the session's first pass ran
        service.onContactRequestsSynced(listOf(request("alice")), initialSync = false)

        service.onContactRequestsSynced(listOf(request("alice")), initialSync = false)

        verify(exactly = 0) { notificationServiceMock.showNotification(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun aNewContactRequestRowRecountsWithoutASyncPassEnding() = runBlocking {
        val service = service()
        awaitCollector()
        assertEquals(0, service.unseenNotificationCount.value)

        // A contact-request row is written. Nothing calls onContactRequestsSynced,
        // nothing navigates, nothing observes.
        contactRequestRows.emit(1)

        assertEquals(3, awaitCount(service, 3))
    }

    @Test
    fun aProfileArrivingLaterRecountsOnItsOwn() = runBlocking {
        // The live defect: getNotificationCount() skips a contact whose profile
        // row has not been downloaded yet, so the pass that inserted the request
        // legitimately published 0. The profile landed minutes later, in no pass
        // of its own — and the badge has to pick that up.
        coEvery { identityRepositoryMock.getNotificationCount(any()) } returns 0
        val service = service()
        awaitCollector()

        contactRequestRows.emit(1)
        // that recompute has genuinely run and legitimately answered 0
        coVerify(timeout = WAIT_MS, atLeast = 1) { identityRepositoryMock.getNotificationCount(any()) }
        assertEquals(0, service.unseenNotificationCount.value)

        coEvery { identityRepositoryMock.getNotificationCount(any()) } returns 1
        profileRows.emit(1)

        assertEquals(1, awaitCount(service, 1))
    }

    @Test
    fun theLastSeenMarkerMovingRecounts() = runBlocking {
        val service = service()
        awaitCollector()

        lastSeenMarker.emit(lastSeen + 1)

        assertEquals(3, awaitCount(service, 3))
    }

    @Test
    fun aBurstOfRowWritesStillSettlesOnTheFinalCount() = runBlocking {
        coEvery { identityRepositoryMock.getNotificationCount(any()) } returns 1
        val service = service()
        awaitCollector()

        // A sync pass writes many rows in a row; the coalescing must not drop the
        // last one, whose result is the one that matters.
        repeat(5) { contactRequestRows.emit(it) }
        coEvery { identityRepositoryMock.getNotificationCount(any()) } returns 4
        profileRows.emit(1)

        assertEquals(4, awaitCount(service, 4))
    }
}
