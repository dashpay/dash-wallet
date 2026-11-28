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

package org.dash.wallet.features.exploredash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import org.dash.wallet.features.exploredash.utils.ExploreDatabasePrefs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExploreConfigTimestampTest {
    private val config = ExploreConfig(ApplicationProvider.getApplicationContext<Context>(), mock<WalletDataProvider>())

    @Test
    fun timestamps_areKeptApartFromTheOnesAVersion4BuildReads() = runBlocking {
        // Left by a build that reads the version 4 file
        config.set(ExploreConfig.LOCAL_DB_TIMESTAMP, 1_111L)
        assertEquals(0L, config.exploreDatabasePrefs.first().localDbTimestamp)

        config.saveExploreDatabasePrefs(
            ExploreDatabasePrefs(localDbTimestamp = 2_222L, localDbSchemaVersion = ExploreDatabase.VERSION)
        )

        assertEquals(2_222L, config.exploreDatabasePrefs.first().localDbTimestampFor(ExploreDatabase.VERSION))
        assertEquals(2_222L, config.get(ExploreConfig.LOCAL_DB_TIMESTAMP_V5))
        // After a downgrade the older build must find no data of its own and load its file again
        assertEquals(0L, config.get(ExploreConfig.LOCAL_DB_TIMESTAMP))

        config.updateExploreDatabasePrefs { it.copy(failedSyncAttempts = it.failedSyncAttempts + 1) }

        val prefs = config.exploreDatabasePrefs.first()
        assertEquals(2_222L, prefs.localDbTimestamp)
        assertEquals(1, prefs.failedSyncAttempts)
    }
}
