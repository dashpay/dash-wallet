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
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet.WalletApplication
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The one-minute recovery restart against Robolectric's AlarmManager.
 *
 * What matters is whether a cancel removes the alarm that was actually set. AlarmManager only
 * cancels an intent equal to the one it holds, and one built with a different request code
 * cancels nothing and says nothing, which is how a wipe used to leave this alarm behind. Left
 * behind, it starts the service with no wallet: onCreate returns before completing
 * onCreateCompleted, and every later start, including the replacement wallet's, waits on it.
 */
@RunWith(RobolectricTestRunner::class)
// a plain Application: this needs only a Context, and booting the real WalletApplication would
// drag in Hilt and Firebase
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class BlockchainServiceRecoveryAlarmTest {
    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    private fun scheduledAlarms() = shadowOf(alarmManager).scheduledAlarms.size

    @Test
    fun `a wipe's cancel removes the recovery restart it scheduled`() {
        BlockchainServiceImpl.scheduleRecoveryRestart(context) { true }
        assertEquals("the recovery restart was not scheduled", 1, scheduledAlarms())

        BlockchainServiceImpl.cancelRecoveryRestart(context)

        assertEquals("the recovery restart survived the cancel meant for it", 0, scheduledAlarms())
    }

    @Test
    fun `ordinary cleanup still leaves the recovery restart alone`() {
        // The reason it has a request code of its own. Cancelling the usage backoff alarm, as
        // every onDestroy does, must not throw away the fast restart the pending-payment watch is
        // waiting on.
        BlockchainServiceImpl.scheduleRecoveryRestart(context) { true }

        WalletApplication.scheduleStartBlockchainService(context, true)

        assertEquals("ordinary cleanup cancelled the recovery restart", 1, scheduledAlarms())
    }

    @Test
    fun `no recovery restart is scheduled when there is no wallet`() {
        BlockchainServiceImpl.scheduleRecoveryRestart(context) { false }

        assertEquals(0, scheduledAlarms())
    }

    @Test
    fun `a wallet removed while the restart is being scheduled leaves no restart behind`() {
        // Given: a wipe clears the wallet and cancels between the check and the alarm being set,
        // so its cancel lands before there is anything to cancel
        // that is, the wallet is already gone by the time the alarm has been set
        var askedAfterSetting = false
        val hasWallet = {
            askedAfterSetting = scheduledAlarms() == 1
            false
        }

        BlockchainServiceImpl.scheduleRecoveryRestart(context, hasWallet)

        assertEquals("the wallet was not asked about once the alarm was set", true, askedAfterSetting)
        assertEquals("a restart outlived the wallet it was for", 0, scheduledAlarms())
    }
}
