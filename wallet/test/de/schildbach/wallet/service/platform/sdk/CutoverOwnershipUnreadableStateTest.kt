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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.service.platform.sdk

import android.content.Context
import android.content.SharedPreferences
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.WalletDataProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * SR-03 through the REAL configuration stack.
 *
 * `CutoverUiDataServiceTest.anUnreadableCutoverStateIsNeverRememberedAsDashjOwnership`
 * throws from a mocked `DashPayConfig`, so it only shows what the service does
 * with an error that reaches it. It could never show that one does:
 * `BaseConfig.data` catches IOException and emits `emptyPreferences()`, so an
 * unreadable preferences file arrived at the ownership read as a MISSING key —
 * which `CutoverState.fromStored(null)` maps to DUAL_RUNNING, i.e. "dashj owns
 * the key chain" — and the fail-closed handler was never reached. A cold start
 * with CUT_OVER persisted, an unreadable file, no binding and nothing remembered
 * then served the held dashj chain's frozen, possibly already-funded address.
 *
 * So this drives a genuine DataStore IOException through the real
 * [DashPayConfig]/`BaseConfig`/`preferencesDataStore` stack over a real file,
 * with only the Android [Context] (a file system root and nothing else) mocked.
 */
class CutoverOwnershipUnreadableStateTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun anUnreadablePreferencesFileNeverAuthorisesTheHeldDashjChain() {
        val appFiles = files.newFolder("files")
        // Only the file system root is real: the DataStore under test resolves
        // its file as `applicationContext.filesDir/datastore/<name>`.
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns appFiles
        every { context.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true)

        // A preferences file that is PRESENT but not parseable. DataStore's
        // serializer raises CorruptionException (an IOException) and the default
        // NoOpCorruptionHandler rethrows it rather than resetting the store, so
        // reads and writes both keep failing — a damaged file as a device has it,
        // not a missing one.
        val preferences = File(appFiles, "datastore/${DashPayConfig.PREFERENCES_NAME}.preferences_pb")
        preferences.parentFile?.mkdirs()
        preferences.writeBytes(ByteArray(128) { 0xFF.toByte() })

        val config = DashPayConfig(context, mockk<WalletDataProvider>(relaxed = true))
        runBlocking {
            // The mechanism, pinned: the rescued read cannot tell this from a key
            // that was never written — which is why it must not be the one the
            // ownership decision is taken from.
            assertNull(
                "precondition: the rescued read reports the unreadable file as an absent key",
                config.observe(DashPayConfig.CUTOVER_STATE).first()
            )
            try {
                config.observePreservingErrors(DashPayConfig.CUTOVER_STATE).first()
                fail("the preferences file must be genuinely unreadable for this test to mean anything")
            } catch (expected: IOException) {
                // The error-preserving read keeps ABSENT and FAILED apart.
            }
        }

        // The cold start: no pipeline has published, nothing has been observed,
        // and the persisted state cannot be read.
        val scope = CoroutineScope(SupervisorJob())
        try {
            val service = CutoverUiDataService(
                source = mockk(relaxed = true),
                dashPayConfig = config,
                scope = scope,
                txDisplayCacheDao = mockk(relaxed = true),
                txGroupCacheDao = mockk(relaxed = true),
                walletUIConfig = mockk(relaxed = true),
                resolveString = { "" },
                notifyCoinsReceived = {}
            )
            assertTrue(
                "an unreadable cutover state must fail closed, not authorise the held dashj chain",
                service.cutoverOwnershipCommittedBlocking(2_000L)
            )
        } finally {
            scope.cancel()
        }
    }
}
