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
package de.schildbach.wallet.service

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.ALARM_REQUEST_CODE_PERIODIC
import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.ALARM_REQUEST_CODE_RESTART
import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.ReplayRestartAlarmAction
import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.decideOnReplayRestartAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The replay restart alarm (`ALARM_REQUEST_CODE_RESTART`) is a REPEATING
 * fifteen-minute alarm armed by a teardown that interrupts a replay. Plan
 * §32.9 gave it an identity of its own so the periodic scheduler could no
 * longer overwrite it — which also meant the periodic scheduler's cancel no
 * longer reached it. Review (2026-09-24): the wallet wipe cancelled only the
 * periodic alarm, so a restart alarm armed before the wipe went on starting
 * the service against the wiped wallet every fifteen minutes.
 *
 * Robolectric drives the real `AlarmManager` calls here; the retirement
 * policy itself is a pure function, pinned below it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class ReplayRestartAlarmTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun armedRequestCodes(): List<Int> {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return shadowOf(alarmManager).scheduledAlarms.map { shadowOf(it.operation).requestCode }.sorted()
    }

    // ── the wipe ───────────────────────────────────────────────────────

    /** THE FIELD SHAPE: interrupted replay → restart alarm armed → wallet wipe. */
    @Test
    fun walletWipe_retiresTheReplayRestartAlarm_notOnlyThePeriodicOne() {
        BlockchainServiceImpl.armReplayRestartAlarm(context)
        WalletApplication.scheduleStartBlockchainService(context)
        assertEquals(
            "two alarms with distinct identities stand before the wipe",
            listOf(ALARM_REQUEST_CODE_PERIODIC, ALARM_REQUEST_CODE_RESTART),
            armedRequestCodes()
        )

        // Wipe phase 4a (WalletApplication.destroyWalletFiles).
        WalletApplication.scheduleStartBlockchainService(context, true)

        assertEquals("nothing may start the service on a wiped wallet", emptyList<Int>(), armedRequestCodes())
    }

    @Test
    fun walletWipe_retiresARestartAlarmThatStandsAlone() {
        // A replay interrupted before any idle stop ever armed the periodic one.
        BlockchainServiceImpl.armReplayRestartAlarm(context)
        assertEquals(listOf(ALARM_REQUEST_CODE_RESTART), armedRequestCodes())

        WalletApplication.scheduleStartBlockchainService(context, true)

        assertEquals(emptyList<Int>(), armedRequestCodes())
    }

    // ── the two identities ─────────────────────────────────────────────

    @Test
    fun thePeriodicReschedule_doesNotTouchTheRestartAlarm() {
        // §32.9: the periodic scheduler cancels and re-arms ITS alarm on every
        // call; the restart alarm has to survive that, or an idle stop after a
        // refused restart would silently drop the recovery.
        BlockchainServiceImpl.armReplayRestartAlarm(context)
        WalletApplication.scheduleStartBlockchainService(context)
        WalletApplication.scheduleStartBlockchainService(context)

        assertEquals(listOf(ALARM_REQUEST_CODE_PERIODIC, ALARM_REQUEST_CODE_RESTART), armedRequestCodes())
    }

    @Test
    fun retiringTheRestartAlarm_leavesThePeriodicOneStanding() {
        BlockchainServiceImpl.armReplayRestartAlarm(context)
        WalletApplication.scheduleStartBlockchainService(context)

        BlockchainServiceImpl.cancelReplayRestartAlarm(context, "test")

        assertEquals(listOf(ALARM_REQUEST_CODE_PERIODIC), armedRequestCodes())
    }

    @Test
    fun retiringWithNothingArmed_isHarmless() {
        BlockchainServiceImpl.cancelReplayRestartAlarm(context, "test")
        WalletApplication.scheduleStartBlockchainService(context, true)
        assertEquals(emptyList<Int>(), armedRequestCodes())
    }

    @Test
    fun theRestartAlarm_repeatsEveryFifteenMinutes_startingInOneMinute() {
        val before = System.currentTimeMillis()
        val firstFire = BlockchainServiceImpl.armReplayRestartAlarm(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarm = shadowOf(alarmManager).scheduledAlarms.single()

        assertTrue(firstFire - before in 60_000L..61_000L)
        assertEquals(firstFire, alarm.triggerAtTime)
        assertEquals(AlarmManager.INTERVAL_FIFTEEN_MINUTES, alarm.interval)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertTrue("the start must be permitted to promote itself", shadowOf(alarm.operation).isForegroundService)
    }

    // ── the retirement rule ────────────────────────────────────────────

    @Test
    fun aTeardownThatInterruptsAReplay_arms() {
        assertEquals(
            ReplayRestartAlarmAction.ARM,
            decideOnReplayRestartAlarm(initialised = true, replaying = true, deliberateStop = false, bindBlocked = false)
        )
    }

    @Test
    fun aTeardownWithNothingToRecover_retires() {
        assertEquals(
            "replay complete, idle stop",
            ReplayRestartAlarmAction.RETIRE,
            decideOnReplayRestartAlarm(initialised = true, replaying = false, deliberateStop = false, bindBlocked = false)
        )
        assertEquals(
            "the user wiped or reset the wallet",
            ReplayRestartAlarmAction.RETIRE,
            decideOnReplayRestartAlarm(initialised = true, replaying = true, deliberateStop = true, bindBlocked = false)
        )
        assertEquals(
            "SDK setup pending: the unlock receiver owns the restart",
            ReplayRestartAlarmAction.RETIRE,
            decideOnReplayRestartAlarm(initialised = true, replaying = true, deliberateStop = false, bindBlocked = true)
        )
    }

    /** §37: the refused starts after Andrei's stuck cleanup must not retire the alarm that keeps trying. */
    @Test
    fun aTeardownOfAnInstanceThatNeverInitialised_leavesTheAlarmAlone() {
        assertEquals(
            ReplayRestartAlarmAction.LEAVE,
            decideOnReplayRestartAlarm(initialised = false, replaying = false, deliberateStop = false, bindBlocked = false)
        )
        assertEquals(
            "even a deliberate stop of an uninitialised instance decides nothing; the wipe retires on its own",
            ReplayRestartAlarmAction.LEAVE,
            decideOnReplayRestartAlarm(initialised = false, replaying = false, deliberateStop = true, bindBlocked = false)
        )
    }
}
