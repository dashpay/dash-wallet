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

import de.schildbach.wallet.WalletApplicationExt.resumeMetadataSavesIfWipeComplete
import de.schildbach.wallet.service.platform.work.TransactionMetadataSaveQueue
import de.schildbach.wallet.util.WalletWipeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** After Reset Wallet, transaction metadata saves resume only if the wipe finished. */
class WalletWipeMetadataSavesTest {

    @get:Rule
    val filesDir = TemporaryFolder()

    @get:Rule
    val noBackupFilesDir = TemporaryFolder()

    private val applicationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private val queue = TransactionMetadataSaveQueue(applicationScope).apply { pause() }

    @After
    fun tearDown() {
        applicationScope.cancel()
    }

    @Test
    fun aFinishedWipeResumesSaves() {
        resumeMetadataSavesIfWipeComplete(queue, wipeFinished = true) { WalletWipeState.pendingOrNull(filesDir.root, noBackupFilesDir.root) }

        assertFalse(queue.isPaused)
    }

    @Test
    fun aWipeThatNeverRanKeepsSavesPaused() {
        // begin() could not write the marker, so finish() saw nothing pending
        // and skipped the destroy: the marker is absent but the old wallet is still there
        resumeMetadataSavesIfWipeComplete(queue, wipeFinished = false) { WalletWipeState.pendingOrNull(filesDir.root, noBackupFilesDir.root) }

        assertTrue(queue.isPaused)
    }

    @Test
    fun aFailedWipeKeepsSavesPaused() {
        // a failed destroy leaves the marker so the next launch re-runs the wipe
        WalletWipeState.begin(filesDir.root, noBackupFilesDir.root)

        resumeMetadataSavesIfWipeComplete(queue, wipeFinished = false) { WalletWipeState.pendingOrNull(filesDir.root, noBackupFilesDir.root) }

        assertTrue(queue.isPaused)
    }

    @Test
    fun aMarkerLeftBehindAfterAFinishedWipeKeepsSavesPaused() {
        // complete() could not delete the marker; the next launch re-runs the wipe
        WalletWipeState.begin(filesDir.root, noBackupFilesDir.root)

        resumeMetadataSavesIfWipeComplete(queue, wipeFinished = true) { WalletWipeState.pendingOrNull(filesDir.root, noBackupFilesDir.root) }

        assertTrue(queue.isPaused)
    }

    @Test
    fun anUnreadableMarkerKeepsSavesPaused() {
        resumeMetadataSavesIfWipeComplete(queue, wipeFinished = true) { null }

        assertTrue(queue.isPaused)
    }

    @Test
    fun aMarkerCheckThatThrowsKeepsSavesPaused() {
        resumeMetadataSavesIfWipeComplete(queue, wipeFinished = true) { throw IOException("unreadable") }

        assertTrue(queue.isPaused)
    }
}
