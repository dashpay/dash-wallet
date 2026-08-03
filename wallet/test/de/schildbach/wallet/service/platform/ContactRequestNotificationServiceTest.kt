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
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
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
    private val lastSeen = 1_000_000L

    private val contextMock = mockk<Context> {
        every { getString(any()) } returns "You have a new contact request"
        every { getString(any(), *anyVararg()) } returns "someone has sent you a contact request"
    }
    private val notificationServiceMock = mockk<NotificationService>(relaxed = true)
    private val profileDaoMock = mockk<DashPayProfileDao> {
        coEvery { loadByUserId(any()) } returns null
    }
    private val identityRepositoryMock = mockk<IdentityRepository> {
        coEvery { getNotificationCount(any()) } returns 3
    }
    private val configMock = mockk<DashPayConfig> {
        coEvery { areNotificationsDisabled() } returns false
        coEvery { get(DashPayConfig.LAST_SEEN_NOTIFICATION_TIME) } returns lastSeen
    }

    private fun service() = ContactRequestNotificationService(
        contextMock,
        identityRepositoryMock,
        configMock,
        profileDaoMock,
        notificationServiceMock
    )

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
}
