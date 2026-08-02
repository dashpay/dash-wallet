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
import android.content.Intent
import androidx.core.os.bundleOf
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.ui.dashpay.NotificationsFragment
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet_test.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dash.wallet.common.services.NotificationService
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped owner of the "unseen DashPay notifications" count and of the
 * system notification posted when a contact request is received.
 *
 * Why this exists: the badge used to be computed exclusively inside
 * [de.schildbach.wallet.ui.dashpay.ContactsBasedLiveData], which registers its
 * `OnContactsUpdated` listener only in `onActive()` — i.e. only while the home
 * screen is actually observing. When a contact request landed while the user was
 * on any other screen, `PlatformSynchronizationService.fireContactsUpdatedListeners()`
 * had no listener to reach and the count was never recomputed. Owning the count in
 * a @Singleton makes the recompute lifecycle-independent: whatever the UI is doing,
 * [unseenNotificationCount] is current, and a view that starts observing later
 * immediately receives the value that was computed while it was away.
 *
 * Everything here is fail-soft. Contact sync must never break because a badge
 * refresh or a notification failed, so every public entry point swallows and logs.
 */
@Singleton
class ContactRequestNotificationService @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val identityRepository: IdentityRepository,
    private val dashPayConfig: DashPayConfig,
    private val dashPayProfileDao: DashPayProfileDao,
    private val notificationService: NotificationService
) {
    companion object {
        private val log = LoggerFactory.getLogger(ContactRequestNotificationService::class.java)

        /** Notification tag prefix; the per-request suffix keeps distinct senders distinct. */
        const val NOTIFICATION_TAG_PREFIX = "dashpay_contact_request_"

        /**
         * Upper bound on the remembered "already notified" keys. A contact set is
         * small, but this is a process-lifetime set so it gets a hard cap; the
         * oldest keys are evicted first (insertion-ordered [LinkedHashSet]).
         */
        private const val MAX_REMEMBERED_REQUESTS = 200

        /**
         * At most this many individual notifications per sync pass. A burst larger
         * than this is a catch-up, not a live event — the badge still counts them
         * all, we just don't carpet-bomb the shade.
         */
        private const val MAX_NOTIFICATIONS_PER_PASS = 5
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val refreshMutex = Mutex()

    private val _unseenNotificationCount = MutableStateFlow(0)

    /**
     * Number of DashPay notifications newer than
     * [DashPayConfig.LAST_SEEN_NOTIFICATION_TIME]; drives the home-screen bell.
     * Recomputed by [refreshCount]/[refreshCountInBackground] and by every
     * [onContactRequestsSynced] pass, with or without an active observer.
     */
    val unseenNotificationCount: StateFlow<Int> = _unseenNotificationCount.asStateFlow()

    /**
     * Has a contact-request sync pass already completed in this process? Notifications
     * are suppressed until it has, so restoring/reinstalling a wallet with an existing
     * contact list does not post one notification per pre-existing request.
     */
    private val firstContactSyncCompleted = AtomicBoolean(false)

    /** Keys of contact requests already notified about in this process. Guarded by itself. */
    private val notifiedRequests = LinkedHashSet<String>()

    /** Recompute [unseenNotificationCount] off the caller's thread; never throws. */
    fun refreshCountInBackground() {
        scope.launch {
            try {
                refreshCount()
            } catch (e: Exception) {
                log.error("failed to refresh the notification count", e)
            }
        }
    }

    /**
     * Recompute the unseen count from the database. Serialized so two concurrent
     * sync passes cannot interleave and publish a stale value last.
     */
    suspend fun refreshCount() {
        refreshMutex.withLock {
            val count = if (dashPayConfig.areNotificationsDisabled()) {
                0
            } else {
                val lastSeen = dashPayConfig.get(DashPayConfig.LAST_SEEN_NOTIFICATION_TIME) ?: 0
                identityRepository.getNotificationCount(lastSeen)
            }

            if (count >= 0 && _unseenNotificationCount.value != count) {
                log.info("unseen notification count: {} -> {}", _unseenNotificationCount.value, count)
                _unseenNotificationCount.value = count
            }
        }
    }

    /**
     * Called by [PlatformSynchronizationService] at the end of a contact-request sync
     * pass with the received requests that were newly inserted during it.
     *
     * Refreshes the badge count unconditionally (that is the lifecycle-independent
     * half of the fix), then posts a system notification for each genuinely new
     * received request, subject to these guards:
     *  - the pass must not be the wallet's [initialSync], and one sync pass must have
     *    already completed this process — no notification storm on restore;
     *  - DashPay notifications must not be disabled
     *    ([DashPayConfig.LAST_SEEN_NOTIFICATION_TIME] == DISABLE_NOTIFICATIONS);
     *  - the request must be newer than LAST_SEEN_NOTIFICATION_TIME — anything the
     *    user has already seen on the notifications screen stays silent;
     *  - the request must not have been notified about already in this process.
     *
     * Never throws: a failure here must not abort contact sync.
     */
    suspend fun onContactRequestsSynced(
        newReceivedRequests: List<DashPayContactRequest>,
        initialSync: Boolean
    ) {
        try {
            refreshCount()
        } catch (e: Exception) {
            log.error("failed to refresh the notification count after contact sync", e)
        }

        try {
            notifyNewContactRequests(newReceivedRequests, initialSync)
        } catch (e: Exception) {
            log.error("failed to post contact request notifications", e)
        } finally {
            // Latch after the pass so that the very first pass of this process is the
            // one that primes the "already known" state rather than notifying about it.
            firstContactSyncCompleted.set(true)
        }
    }

    private suspend fun notifyNewContactRequests(
        newReceivedRequests: List<DashPayContactRequest>,
        initialSync: Boolean
    ) {
        if (newReceivedRequests.isEmpty()) {
            return
        }

        if (initialSync || !firstContactSyncCompleted.get()) {
            // Prime the dedupe set so the requests seen during the first pass are never
            // announced later by a subsequent pass that re-reports them.
            newReceivedRequests.forEach { rememberNotified(keyOf(it)) }
            log.info(
                "skipping contact request notifications for {} request(s): first sync of this session",
                newReceivedRequests.size
            )
            return
        }

        val lastSeen = dashPayConfig.get(DashPayConfig.LAST_SEEN_NOTIFICATION_TIME) ?: 0

        if (lastSeen == DashPayConfig.DISABLE_NOTIFICATIONS) {
            newReceivedRequests.forEach { rememberNotified(keyOf(it)) }
            return
        }

        val pending = newReceivedRequests
            .filter { it.timestamp >= lastSeen }
            .filter { rememberNotified(keyOf(it)) }

        if (pending.isEmpty()) {
            return
        }

        pending.take(MAX_NOTIFICATIONS_PER_PASS).forEach { request ->
            val displayName = try {
                dashPayProfileDao.loadByUserId(request.userId)?.nameLabel
            } catch (e: Exception) {
                log.warn("could not load the profile for {}", request.userId, e)
                null
            }

            val message = if (displayName.isNullOrEmpty()) {
                appContext.getString(R.string.notification_new_contact_request)
            } else {
                appContext.getString(R.string.notifications_you_received, displayName)
            }

            // showNotification() already no-ops + logs when notifications are turned off
            // or POST_NOTIFICATIONS (API 33+) was never granted, so no extra guard here.
            notificationService.showNotification(
                tag = NOTIFICATION_TAG_PREFIX + request.userId,
                message = message,
                title = null,
                imageUrl = null,
                intent = notificationsScreenIntent(),
                // IMPORTANCE_HIGH channel: heads-up banner + sound. Not the DashPay channel,
                // which is IMPORTANCE_LOW (silent) for identity-creation progress.
                channelId = Constants.NOTIFICATION_CHANNEL_ID_CONTACTS
            )
            log.info("posted a contact request notification for {}", request.userId)
        }

        if (pending.size > MAX_NOTIFICATIONS_PER_PASS) {
            log.info(
                "suppressed {} additional contact request notifications in this pass",
                pending.size - MAX_NOTIFICATIONS_PER_PASS
            )
        }
    }

    /**
     * Tap target: the notifications screen, the same destination the home-screen bell
     * opens. A failure to build it must not cost the user the notification itself, so
     * it degrades to a null intent (notification without a tap action).
     */
    private fun notificationsScreenIntent(): Intent? = try {
        MainActivity.createIntent(
            appContext,
            R.id.showNotificationsFragment,
            bundleOf("mode" to NotificationsFragment.MODE_NOTIFICATIONS)
        )
    } catch (e: Exception) {
        log.warn("could not build the notifications-screen intent", e)
        null
    }

    private fun keyOf(request: DashPayContactRequest): String =
        "${request.userId}:${request.toUserId}:${request.accountReference}"

    /** @return true when [key] had not been notified about before (and is now remembered). */
    private fun rememberNotified(key: String): Boolean = synchronized(notifiedRequests) {
        val added = notifiedRequests.add(key)

        while (notifiedRequests.size > MAX_REMEMBERED_REQUESTS) {
            val oldest = notifiedRequests.firstOrNull() ?: break
            notifiedRequests.remove(oldest)
        }

        added
    }
}
