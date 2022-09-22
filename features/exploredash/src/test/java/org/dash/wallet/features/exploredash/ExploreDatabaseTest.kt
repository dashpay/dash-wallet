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
import android.database.sqlite.SQLiteException
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import junit.framework.TestCase.*
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import org.dash.wallet.features.exploredash.repository.GCExploreDatabase
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.lang.IllegalArgumentException
import java.lang.RuntimeException
import kotlin.time.ExperimentalTime

@ExperimentalTime
@RunWith(RobolectricTestRunner::class)
class ExploreDatabaseTest {
    @get:Rule
    var instantTaskExecutorRule = InstantTaskExecutorRule()

    private var timestamp = -1L
    private val editor = mock<SharedPreferences.Editor>()
    private val mockPreferences = mock<SharedPreferences> {
        on { getLong(any(), any()) } doReturn timestamp
        on { edit() } doReturn editor
    }
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val repo = GCExploreDatabase(appContext, mockPreferences, mock(), mock())

    @Before
    fun setup() {
        editor.stub {
            on { putLong(any(), anyLong()) } doAnswer { i ->
                timestamp = i.arguments[1] as Long
                editor
            }
        }
    }

    @Test
    fun noPreloadedDb_throwAndDoesNotUpdateLocalTimestamp() = runBlocking {
        timestamp = -1L

        val dbBuilder = Room.databaseBuilder(
            appContext,
            ExploreDatabase::class.java,
            "explore.db"
        ).allowMainThreadQueries()

        val updateFile = File("/not/existing/file.db")
        var database: ExploreDatabase? = null

        try {
            database = ExploreDatabase.preloadAndOpen(dbBuilder, repo, updateFile)
            assertTrue(false)
        } catch (ex: IllegalArgumentException) {
            println("Got expected IllegalArgumentException: ${ex.message}")
            assertFalse("This case should not update the timestamp", timestamp > 0)
        } finally {
            database?.close()
        }
    }

    @Test
    fun emptyPreloadedDb_throwDoesNotUpdateLocalTimestamp() = runBlocking {
        timestamp = -1L

        val dbBuilder = Room.databaseBuilder(
            appContext,
            ExploreDatabase::class.java,
            "explore.db"
        ).allowMainThreadQueries()

        val resource = javaClass.classLoader?.getResource("empty_explore.db")
        val updateFile =
            File(resource?.file ?: throw Resources.NotFoundException("explore.db not found"))

        var database: ExploreDatabase? = null

        try {
            database = ExploreDatabase.preloadAndOpen(dbBuilder, repo, updateFile)
            assertTrue(false)
        } catch (ex: SQLiteException) {
            println("Got expected SQLiteException: ${ex.message}")
            assertFalse("This case should not update the timestamp", timestamp > 0)
        } finally {
            database?.close()
        }
    }

    @Test
    fun badPreloadedDb_throwsAndDoesNotUpdateLocalTimestamp() = runBlocking {
        timestamp = -1L

        val dbBuilder = Room.databaseBuilder(
            appContext,
            ExploreDatabase::class.java,
            "explore.db"
        ).allowMainThreadQueries()

        val updateFile = File.createTempFile("temp", "file")
        var database: ExploreDatabase? = null

        try {
            database = ExploreDatabase.preloadAndOpen(dbBuilder, repo, updateFile)
            assertTrue(false)
        } catch (ex: RuntimeException) {
            println("Got expected RuntimeException: ${ex.message}")
            assertFalse("This case should not update the timestamp", timestamp > 0)
        } finally {
            database?.close()
        }
    }

    @Test
    fun goodPreloadedDb_updatesLocalTimestamp() = runBlocking {
        timestamp = -1L

        val dbBuilder = Room.databaseBuilder(
            appContext,
            ExploreDatabase::class.java,
            "explore.db"
        ).allowMainThreadQueries()

        val resource = javaClass.classLoader?.getResource("explore.db")
        val updateFile =
            File(resource?.file ?: throw Resources.NotFoundException("explore.db not found"))
        val zipFile = ZipFile(updateFile)
        val comment = zipFile.comment.split("#".toRegex()).toTypedArray()
        val databaseTimestamp = comment[0].toLong()
        zipFile.close()

        val database = ExploreDatabase.preloadAndOpen(dbBuilder, repo, updateFile)

        assertTrue("This case should update the timestamp", timestamp > 0)
        assertEquals("Wrong timestamp set;", databaseTimestamp, timestamp)
        database.close()
    }
}