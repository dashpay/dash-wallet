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
package de.schildbach.wallet

import android.app.Activity
import android.content.Intent
import de.schildbach.wallet.service.RestartService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.dash.wallet.common.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last-stopped hook must fire when the user LEAVES, and must not fire when
 * Android merely recreates the visible activity.
 *
 * `onStoppedLast()` was dead code for years — this class overrides
 * `onActivityStarted`/`onActivityStopped` without calling `super`, so
 * `ActivitiesTracker.numStarted` never advanced and the base never reached it.
 * Re-enabling it (MO-995) brought the hook back to life AND brought a hazard
 * with it: a configuration change stops the outgoing activity before starting
 * its replacement, so `visibleActivityCount` legitimately touches zero while
 * the user is still looking at the app.
 *
 * With auto-logout enabled at a zero-minute timeout that is not cosmetic —
 * `setAppWentBackground(true)` alone makes `AutoLogout.shouldLogout()` true,
 * and `FORCE_FINISH_ACTION` finishes the stack. Nine activities in
 * `AndroidManifest.xml` neither pin portrait nor handle orientation
 * themselves, so rotating one would land the user on the lock screen without
 * ever having left the wallet.
 */
class WalletActivityTrackerLifecycleTest {

    private fun tracker(
        autoLogoutEnabled: Boolean = true,
        autoLogoutMinutes: Int = 0
    ): Triple<WalletActivityTracker, AutoLogout, WalletApplication> {
        val app = mockk<WalletApplication>(relaxed = true)
        val config = mockk<Configuration>()
        every { config.autoLogoutEnabled } returns autoLogoutEnabled
        every { config.autoLogoutMinutes } returns autoLogoutMinutes
        val autoLogout = mockk<AutoLogout>()
        every { autoLogout.setAppWentBackground(any()) } just Runs
        val restartService = mockk<RestartService>(relaxed = true)
        return Triple(
            WalletActivityTracker(app, config, autoLogout, restartService),
            autoLogout,
            app
        )
    }

    private fun activity(changingConfigurations: Boolean): Activity =
        mockk<Activity>(relaxed = true).also {
            every { it.isChangingConfigurations } returns changingConfigurations
        }

    /** The user actually left: the hook must fire and the app must lock. */
    @Test
    fun genuineBackgrounding_firesTheLastStoppedHook() {
        val (tracker, autoLogout, app) = tracker()
        val a = activity(changingConfigurations = false)

        tracker.onActivityStarted(a)
        assertTrue("app must read as foreground while an activity is started", AppForegroundMonitor.isForeground.value)

        tracker.onActivityStopped(a)

        verify(exactly = 1) { autoLogout.setAppWentBackground(true) }
        verify(exactly = 1) { app.sendBroadcast(any<Intent>()) }
        assertFalse("leaving the app must clear the foreground signal", AppForegroundMonitor.isForeground.value)
    }

    /**
     * THE REGRESSION: a rotation must not look like leaving. Android stops the
     * outgoing instance first, so the count hits zero mid-recreation.
     */
    @Test
    fun configurationChange_doesNotFireTheLastStoppedHook() {
        val (tracker, autoLogout, app) = tracker()
        val outgoing = activity(changingConfigurations = true)

        tracker.onActivityStarted(outgoing)
        tracker.onActivityStopped(outgoing)

        verify(exactly = 0) { autoLogout.setAppWentBackground(any()) }
        verify(exactly = 0) { app.sendBroadcast(any<Intent>()) }
        assertTrue(
            "a rotation must leave the app reading as foreground — the user never left",
            AppForegroundMonitor.isForeground.value
        )

        // ...and the replacement arriving must keep the count balanced, so a
        // LATER genuine background still fires exactly once.
        val incoming = activity(changingConfigurations = false)
        tracker.onActivityStarted(incoming)
        tracker.onActivityStopped(incoming)

        verify(exactly = 1) { autoLogout.setAppWentBackground(true) }
        assertFalse(AppForegroundMonitor.isForeground.value)
    }

    /** Auto-logout off: the hook still runs, but nothing is force-finished. */
    @Test
    fun genuineBackgrounding_withAutoLogoutOff_doesNotForceFinish() {
        val (tracker, autoLogout, app) = tracker(autoLogoutEnabled = false)
        val a = activity(changingConfigurations = false)

        tracker.onActivityStarted(a)
        tracker.onActivityStopped(a)

        verify(exactly = 1) { autoLogout.setAppWentBackground(true) }
        verify(exactly = 0) { app.sendBroadcast(any<Intent>()) }
    }
}
