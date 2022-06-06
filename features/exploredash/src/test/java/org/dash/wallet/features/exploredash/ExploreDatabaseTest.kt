/*
 * Copyright 2022 Dash Core Group.
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
import android.content.SharedPreferences
import android.content.res.Resources
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility.await
import org.dash.wallet.features.exploredash.repository.GCExploreDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.lang.RuntimeException
import kotlin.time.ExperimentalTime
import kotlin.time.Duration.Companion.seconds


@ExperimentalTime
@RunWith(RobolectricTestRunner::class)
class ExploreDatabaseTest {
    @get:Rule
    var instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun noPreloadedDb_doesNotUpdateLocalTimestamp() {
        runBlocking {
            var timestamp = -1L

            val editor = mock<SharedPreferences.Editor>()
            editor.stub {
                on { putLong(any(), anyLong()) } doAnswer { i ->
                    timestamp = i.arguments[1] as Long
                    editor
                }
            }

            val mockPreferences = mock<SharedPreferences> {
                on { getLong(any(), any()) } doReturn timestamp
                on { edit() } doReturn editor
            }
            val appContext = ApplicationProvider.getApplicationContext<Context>()
            val repo = GCExploreDatabase(appContext, mockPreferences, mock(), mock())

            val dbBuilder = Room.databaseBuilder(
                appContext,
                AppExploreDatabase::class.java,
                "explore.db"
            ).allowMainThreadQueries()

            val updateFile = File("/not/existing/file.db")
            val database = AppExploreDatabase.buildDatabase(dbBuilder, repo, updateFile)
            database.query("SELECT * FROM sqlite_master", null)
            delay(3.seconds)

            assertFalse("This case should not update the timestamp", timestamp > 0)
            database.close()
        }
    }

    @Test
    fun emptyPreloadedDb_doesNotUpdateLocalTimestamp() {
        runBlocking {
            var timestamp = -1L

            val editor = mock<SharedPreferences.Editor>()
            editor.stub {
                on { putLong(any(), anyLong()) } doAnswer { i ->
                    timestamp = i.arguments[1] as Long
                    editor
                }
            }

            val mockPreferences = mock<SharedPreferences> {
                on { getLong(any(), any()) } doReturn timestamp
                on { edit() } doReturn editor
            }
            val appContext = ApplicationProvider.getApplicationContext<Context>()
            val repo = GCExploreDatabase(appContext, mockPreferences, mock(), mock())

            val dbBuilder = Room.databaseBuilder(
                appContext,
                AppExploreDatabase::class.java,
                "explore.db"
            ).allowMainThreadQueries()

            val resource = javaClass.classLoader?.getResource("empty_explore.db")
            val updateFile = File(resource?.file ?: throw Resources.NotFoundException("explore.db not found"))
            val database = AppExploreDatabase.buildDatabase(dbBuilder, repo, updateFile)
            database.query("SELECT * FROM sqlite_master", null)
            delay(3.seconds)

            assertFalse("This case should not update the timestamp", timestamp > 0)
            database.close()
        }
    }

    @Test
    fun badPreloadedDb_throwsAndDoesNotUpdateLocalTimestamp() {
        runBlocking {
            var timestamp = -1L

            val editor = mock<SharedPreferences.Editor>()
            editor.stub {
                on { putLong(any(), anyLong()) } doAnswer { i ->
                    timestamp = i.arguments[1] as Long
                    editor
                }
            }

            val mockPreferences = mock<SharedPreferences> {
                on { getLong(any(), any()) } doReturn timestamp
                on { edit() } doReturn editor
            }
            val appContext = ApplicationProvider.getApplicationContext<Context>()
            val repo = GCExploreDatabase(appContext, mockPreferences, mock(), mock())

            val dbBuilder = Room.databaseBuilder(
                appContext,
                AppExploreDatabase::class.java,
                "explore.db"
            ).allowMainThreadQueries()

            val updateFile = File.createTempFile("temp", "file")
            val database = AppExploreDatabase.buildDatabase(dbBuilder, repo, updateFile)

            try {
                database.query("SELECT * FROM sqlite_master", null)
                delay(3.seconds)
                assertTrue(false)
            } catch (ex: RuntimeException) {
                println("Got expected RuntimeException: ${ex.message}")
                assertFalse("This case should not update the timestamp", timestamp > 0)
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun goodPreloadedDb_updatesLocalTimestamp() {
        runBlocking {
            var timestamp = -1L

            val editor = mock<SharedPreferences.Editor>()
            editor.stub {
                on { putLong(any(), anyLong()) } doAnswer { i ->
                    timestamp = i.arguments[1] as Long
                    editor
                }
            }

            val mockPreferences = mock<SharedPreferences> {
                on { getLong(any(), any()) } doReturn timestamp
                on { edit() } doReturn editor
            }
            val appContext = ApplicationProvider.getApplicationContext<Context>()
            val repo = GCExploreDatabase(appContext, mockPreferences, mock(), mock())

            val dbBuilder = Room.databaseBuilder(
                appContext,
                AppExploreDatabase::class.java,
                "explore.db"
            ).allowMainThreadQueries()

            val resource = javaClass.classLoader?.getResource("explore.db")
            val updateFile = File(resource?.file ?: throw Resources.NotFoundException("explore.db not found"))
            val database = AppExploreDatabase.buildDatabase(dbBuilder, repo, updateFile)
            database.query("SELECT * FROM sqlite_master", null)
            delay(3.seconds)

            assertTrue("This case should update the timestamp", timestamp > 0)
            database.close()
        }
    }
}