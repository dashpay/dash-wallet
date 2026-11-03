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

package de.schildbach.wallet.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Exercises the real preference store, including a failure before preferences can be decoded. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class WalletUIConfigTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun createConfig(): WalletUIConfig {
        val application = ApplicationProvider.getApplicationContext<Application>()
        // Each test gets a distinct backing file, including the corruption test before its first read.
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = temporaryFolder.root
        }
        return WalletUIConfig(
            context,
            mockk<WalletDataProvider>(relaxed = true),
            mockk<ExchangeRatesProvider>(relaxed = true)
        )
    }

    @Test
    fun `stored true hides balance`() {
        val config = createConfig()
        runBlocking { config.set(WalletUIConfig.AUTO_HIDE_BALANCE, true) }

        assertTrue(config.getAutoHideBalanceBlocking())
    }

    @Test
    fun `stored false shows balance`() {
        val config = createConfig()
        runBlocking { config.set(WalletUIConfig.AUTO_HIDE_BALANCE, false) }

        assertFalse(config.getAutoHideBalanceBlocking())
    }

    @Test
    fun `absent preference shows balance`() {
        val config = createConfig()

        assertNull(runBlocking { config.get(WalletUIConfig.AUTO_HIDE_BALANCE) })
        assertFalse(config.getAutoHideBalanceBlocking())
    }

    @Test
    fun `unreadable preferences hide balance instead of treating the store as empty`() {
        val preferencesFile = File(temporaryFolder.root, "datastore/wallet_ui.preferences_pb")
        check(preferencesFile.parentFile!!.mkdirs())
        // Truncated protobuf causes DataStore's serializer to throw CorruptionException (an IOException).
        preferencesFile.writeBytes(byteArrayOf(0x0A, 0x05, 0x01))
        val config = createConfig()

        assertTrue(config.getAutoHideBalanceBlocking())
        // Guard the distinction: the ordinary reader swallows the same storage failure as an absent key.
        assertNull(runBlocking { config.get(WalletUIConfig.AUTO_HIDE_BALANCE) })
    }
}
