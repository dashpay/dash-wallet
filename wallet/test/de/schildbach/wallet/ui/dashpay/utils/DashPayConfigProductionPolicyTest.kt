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

package de.schildbach.wallet.ui.dashpay.utils

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet_test.BuildConfig
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.WalletDataProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class DashPayConfigProductionPolicyTest {
    @Test
    fun productionPolicySurvivesPreferenceEditsAndWipes_whileQaRemainsConfigurable() = runBlocking {
        val config = DashPayConfig(
            ApplicationProvider.getApplicationContext(),
            mockk<WalletDataProvider>(relaxed = true)
        )
        val locked = !BuildConfig.DEBUG && BuildConfig.FLAVOR == "prod"
        val flags = mapOf(
            Pair(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS, true),
            Pair(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES, true),
            Pair(DashPayConfig.USE_KOTLIN_SDK_SHIELDED, true),
            Pair(DashPayConfig.USE_KOTLIN_SDK_L1_INVITE, true),
            Pair(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW, true),
            Pair(DashPayConfig.USE_KOTLIN_SDK_L1_SEND, false)
        )
        // The constructor seeds unset flags on Dispatchers.IO; in unlocked
        // variants it could otherwise write a default over the edit below.
        config.initialSeeding.join()
        // Bypass set(), as an imported or externally modified preferences file would.
        config.editPreferences { prefs -> flags.forEach { (key, value) -> prefs[key] = !value } }
        flags.forEach { (key, value) ->
            val expected = if (locked) value else !value
            assertEquals(key.name, expected, config.get(key))
            assertEquals(key.name, expected, config.observe(key).first())
            config.set(key, !value)
            assertEquals(key.name, expected, config.get(key))
            assertEquals(key.name, expected, config.observe(key).first())
        }
        // Tools diagnostics remain functional in every variant, including prodRelease.
        for (enabled in listOf(true, false, true)) {
            config.setDashjSyncDiagnostic(enabled)
            assertEquals(enabled, config.getDashjSyncDiagnostic())
            assertEquals(enabled, config.observeDashjSyncDiagnostic().first())
        }
        val unrelated = booleanPreferencesKey("unrelated_policy_test")
        config.set(unrelated, true)
        assertEquals(true, config.get(unrelated))
        assertEquals(true, config.observe(unrelated).first())

        config.clearAll()
        if (locked) {
            flags.forEach { (key, value) ->
                assertEquals(key.name, value, config.get(key))
                assertEquals(key.name, value, config.observe(key).first())
            }
        }
    }
}
