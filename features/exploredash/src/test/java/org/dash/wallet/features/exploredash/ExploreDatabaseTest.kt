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
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import junit.framework.TestCase.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.dash.wallet.features.exploredash.repository.GCExploreDatabase
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import org.dash.wallet.features.exploredash.utils.ExploreDatabasePrefs
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.lang.IllegalArgumentException
import java.lang.RuntimeException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ExploreDatabaseTest {
    @get:Rule var instantTaskExecutorRule = InstantTaskExecutorRule()

    private var timestamp = -1L
    private var prefsState = ExploreDatabasePrefs()
    private val mockPreferences = mock<ExploreConfig>()
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val repo = GCExploreDatabase(appContext, mockPreferences, mock(), mock())

    @Before
    fun setup() {
        timestamp = -1L
        prefsState = ExploreDatabasePrefs()
        mockPreferences.stub {
            onBlocking { get<Long>(any()) }.doReturn(timestamp)
            onBlocking { set(any(), anyLong()) } doAnswer { i -> timestamp = i.arguments[1] as Long }
            // finalizeUpdate now writes via saveExploreDatabasePrefs, not set(). Capture
            // both so timestamp tracking works regardless of which path is used.
            on { exploreDatabasePrefs } doAnswer { flowOf(prefsState) }
            onBlocking { saveExploreDatabasePrefs(any()) } doAnswer { invocation ->
                prefsState = invocation.arguments[0] as ExploreDatabasePrefs
                timestamp = prefsState.localDbTimestamp
            }
        }
        ensureFixturesExist()
    }

    private fun ensureFixturesExist() {
        if (!GOOD_EXPLORE_DB.exists()) generateExploreZip(appContext, GOOD_EXPLORE_DB, populated = true)
        if (!EMPTY_EXPLORE_DB.exists()) generateExploreZip(appContext, EMPTY_EXPLORE_DB, populated = false)
    }

    companion object {
        // Files live in features/exploredash/test/resources/. Generated on first run if
        // missing; safe to commit so subsequent runs (and CI) skip the generation step.
        private val TEST_RESOURCES_DIR = File(System.getProperty("user.dir"), "test/resources")
        private val GOOD_EXPLORE_DB = File(TEST_RESOURCES_DIR, "explore.db")
        private val EMPTY_EXPLORE_DB = File(TEST_RESOURCES_DIR, "empty_explore.db")
        private const val FIXTURE_PASSWORD = "test"

        /**
         * Builds a v4 Room DB at a temp location, optionally inserts a dummy merchant +
         * atm row, then wraps the resulting SQLite file in a password-protected zip
         * with a "<timestamp>#<password>" comment — matching the format produced by
         * the Firebase Storage publisher and consumed by [GCExploreDatabase].
         */
        private fun generateExploreZip(
            context: Context,
            outputFile: File,
            populated: Boolean
        ) {
            outputFile.parentFile?.mkdirs()
            val innerName = "fixture-inner-${System.nanoTime()}"
            try {
                val room = Room.databaseBuilder(context, ExploreDatabase::class.java, innerName)
                    .allowMainThreadQueries()
                    .build()
                try {
                    val db = room.openHelper.writableDatabase
                    if (populated) {
                        // hasExpectedData() requires both tables to be non-empty.
                        db.execSQL("INSERT INTO merchant (name) VALUES ('dummy merchant')")
                        db.execSQL("INSERT INTO atm (name) VALUES ('dummy atm')")
                    }
                } finally {
                    room.close()
                }

                val innerFile = context.getDatabasePath(innerName)
                outputFile.delete()
                ZipFile(outputFile, FIXTURE_PASSWORD.toCharArray()).use { zip ->
                    val params = ZipParameters().apply {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        fileNameInZip = "explore.db"
                    }
                    zip.addFile(innerFile, params)
                    zip.comment = "${System.currentTimeMillis()}#$FIXTURE_PASSWORD"
                }
            } finally {
                context.deleteDatabase(innerName)
            }
        }
    }

    @Test
    fun noPreloadedDb_throwAndDoesNotUpdateLocalTimestamp() = runTest {
        val dbBuilder =
            Room.databaseBuilder(appContext, ExploreDatabase::class.java, "explore.db").allowMainThreadQueries()

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
    fun emptyPreloadedDb_throwDoesNotUpdateLocalTimestamp() = runTest {
        val dbBuilder =
            Room.databaseBuilder(appContext, ExploreDatabase::class.java, "explore.db").allowMainThreadQueries()

        var database: ExploreDatabase? = null

        try {
            database = ExploreDatabase.preloadAndOpen(dbBuilder, repo, EMPTY_EXPLORE_DB)
            assertTrue(false)
        } catch (ex: SQLiteException) {
            println("Got expected SQLiteException: ${ex.message}")
            assertFalse("This case should not update the timestamp", timestamp > 0)
        } finally {
            database?.close()
        }
    }

    @Test
    fun badPreloadedDb_throwsAndDoesNotUpdateLocalTimestamp() = runTest {
        val dbBuilder =
            Room.databaseBuilder(appContext, ExploreDatabase::class.java, "explore.db").allowMainThreadQueries()

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

    @After
    fun teardownExploreDb() {
        ExploreDatabase.resetInstanceForTest()
        appContext.deleteDatabase("explore-database")
        appContext.deleteDatabase("explore-database.staging")
    }

    @Test
    fun corruptedDb_recoveryRebuildsAndClearsLocalTimestamp() = runTest {
        // Simulate a previously-synced device whose local timestamp matches remote.
        var prefs = ExploreDatabasePrefs(localDbTimestamp = 1_234_567_890L)
        val mockConfig = mock<ExploreConfig> {
            on { exploreDatabasePrefs } doAnswer { flowOf(prefs) }
            onBlocking { saveExploreDatabasePrefs(any()) } doAnswer { invocation ->
                prefs = invocation.arguments[0] as ExploreDatabasePrefs
            }
            // No obsolete db name to migrate from.
            onBlocking { get<String>(any()) } doReturn null
        }

        // 1. First open creates a fresh, empty v4 DB on disk.
        val good = ExploreDatabase.getAppDatabase(appContext, mockConfig)
        good.openHelper.readableDatabase.query("SELECT count(*) FROM merchant", emptyArray<Any?>()).use { c ->
            assertTrue("merchant should be queryable on a fresh DB", c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        good.close()
        ExploreDatabase.resetInstanceForTest()

        val dbFile = appContext.getDatabasePath("explore-database")
        assertTrue("DB file should exist after first open", dbFile.exists())
        // Sanity: timestamp untouched so far.
        assertEquals(1_234_567_890L, prefs.localDbTimestamp)

        // 2. Corrupt: drop merchant + room_master_table to mimic the field crash —
        //    Found columns empty + no room_master_table => "Pre-packaged database has
        //    an invalid schema" thrown by RoomOpenHelper.checkIdentity.
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            db.execSQL("DROP TABLE merchant")
            db.execSQL("DROP TABLE room_master_table")
        }

        // 3. Reopening should hit the catch-and-recover path in open().
        val recovered = ExploreDatabase.getAppDatabase(appContext, mockConfig)

        // 4. localDbTimestamp must be zeroed so the next sync force-reloads from
        //    server / preloaded assets (the normal "up to date" branch otherwise
        //    leaves the corrupt file untouched).
        assertEquals(
            "recovery should reset localDbTimestamp to 0",
            0L,
            prefs.localDbTimestamp
        )

        // 5. Rebuilt DB must be functional with the v4 schema in place.
        recovered.openHelper.readableDatabase.query("SELECT count(*) FROM merchant", emptyArray<Any?>()).use { c ->
            assertTrue("merchant should be queryable after recovery", c.moveToFirst())
            assertEquals("rebuilt DB should have empty merchant", 0, c.getInt(0))
        }
        recovered.close()
    }

    @Test
    fun goodPreloadedDb_updatesLocalTimestamp() = runTest {
        val dbBuilder =
            Room.databaseBuilder(appContext, ExploreDatabase::class.java, "explore.db").allowMainThreadQueries()

        val zipFile = ZipFile(GOOD_EXPLORE_DB)
        val expectedTimestamp = zipFile.comment.split("#")[0].toLong()
        zipFile.close()

        val database = ExploreDatabase.preloadAndOpen(dbBuilder, repo, GOOD_EXPLORE_DB)

        // finalizeUpdate() launches its update on repo.configScope (Dispatchers.IO).
        // Wait for the launch to drain so the assertion isn't racy.
        repo.configScope.coroutineContext.job.children.forEach { child: Job -> child.join() }

        println(timestamp)
        assertTrue("This case should update the timestamp", timestamp > 0)
        assertEquals("Wrong timestamp set;", expectedTimestamp, timestamp)
        database.close()
    }
}
