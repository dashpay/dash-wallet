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
import androidx.work.Data
import androidx.work.WorkerParameters
import junit.framework.TestCase.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.dash.wallet.features.exploredash.repository.ExploreDataSyncStatus
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.dash.wallet.features.exploredash.repository.GCExploreDatabase
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import org.dash.wallet.features.exploredash.utils.ExploreDatabasePrefs
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.lang.IllegalArgumentException
import java.lang.RuntimeException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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
            onBlocking { updateExploreDatabasePrefs(any()) } doAnswer { invocation ->
                prefsState = invocation.getArgument<(ExploreDatabasePrefs) -> ExploreDatabasePrefs>(0)(prefsState)
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
         * Builds a Room DB of the current version at a temp location, optionally inserts a dummy merchant +
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

                zipExploreDb(context.getDatabasePath(innerName), outputFile)
            } finally {
                context.deleteDatabase(innerName)
            }
        }

        private fun zipExploreDb(innerFile: File, outputFile: File) {
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
        }

        private fun createPopulatedVersion4(file: File, tables: Map<String, String> = mapOf()) {
            createExploreDatabaseVersion4(file, tables) { v4 ->
                v4.execSQL("INSERT INTO merchant (name) VALUES ('dummy merchant')")
                v4.execSQL("INSERT INTO atm (name) VALUES ('dummy atm')")
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
            assertEquals("A failed update must not record the schema version", 0, prefsState.localDbSchemaVersion)
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
            onBlocking { updateExploreDatabasePrefs(any()) } doAnswer { invocation ->
                prefs = invocation.getArgument<(ExploreDatabasePrefs) -> ExploreDatabasePrefs>(0)(prefs)
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
        // An empty database is never up to date, so the first open has reset the timestamp.
        // Put it back to simulate a device that had synced before the file was damaged.
        assertEquals(0L, prefs.localDbTimestamp)
        prefs = ExploreDatabasePrefs(localDbTimestamp = 1_234_567_890L)

        val updateStaging = appContext.getDatabasePath("explore-database.staging")
        updateStaging.writeText("an update being staged")

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

        assertEquals(
            "a recovery must leave the staging file of a running update alone",
            "an update being staged",
            updateStaging.readText()
        )

        // 5. Rebuilt DB must be functional with the v4 schema in place.
        recovered.openHelper.readableDatabase.query("SELECT count(*) FROM merchant", emptyArray<Any?>()).use { c ->
            assertTrue("merchant should be queryable after recovery", c.moveToFirst())
            assertEquals("rebuilt DB should have empty merchant", 0, c.getInt(0))
        }
        recovered.close()
    }

    private fun updateConfig() = mock<ExploreConfig> {
        on { exploreDatabasePrefs } doAnswer { flowOf(prefsState) }
        onBlocking { saveExploreDatabasePrefs(any()) } doAnswer { invocation ->
            prefsState = invocation.arguments[0] as ExploreDatabasePrefs
        }
        onBlocking { updateExploreDatabasePrefs(any()) } doAnswer { invocation ->
            prefsState = invocation.getArgument<(ExploreDatabasePrefs) -> ExploreDatabasePrefs>(0)(prefsState)
        }
        onBlocking { get<String>(any()) } doReturn null
    }

    /** Stages a version 5 update file whose gift card merchants are in the given countries. */
    private fun stageUpdateWithCountries(repository: GCExploreDatabase, vararg countries: String) {
        val innerName = "countries-inner-${System.nanoTime()}"
        val room = Room.databaseBuilder(appContext, ExploreDatabase::class.java, innerName)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase
            for (country in countries) {
                db.execSQL("INSERT INTO merchant (name, merchantId, country) VALUES ('Shop', '$country', '$country')")
            }
            db.execSQL("INSERT INTO atm (name) VALUES ('dummy atm')")
        } finally {
            room.close()
        }
        zipExploreDb(appContext.getDatabasePath(innerName), repository.getUpdateFile())
        appContext.deleteDatabase(innerName)
    }

    /** A sync run as on an upgrade: the bundled asset is swapped in first, then the download. */
    private class TwoSwapRepository(
        private val files: GCExploreDatabase,
        private val asset: File?,
        private val remote: File,
        private val remoteTimestamp: Long? = null,
        private val isTimestampWriteFailing: Boolean = false,
        private val beforeDownload: suspend () -> Unit = {}
    ) : ExploreRepository by files {
        override suspend fun preloadFromAssetsInto(dbUpdateFile: File, checkTestDB: Boolean): Boolean {
            asset?.copyTo(dbUpdateFile, overwrite = true)
            return asset != null
        }

        override suspend fun getRemoteTimestamp(): Long = remoteTimestamp ?: (files.getTimestamp(asset!!) + 1_000)

        override suspend fun download() {
            beforeDownload()
            remote.copyTo(files.getUpdateFile(), overwrite = true)
        }

        override suspend fun finalizeUpdate() {
            if (isTimestampWriteFailing) {
                throw IOException("the timestamp could not be written")
            }
            files.finalizeUpdate()
        }
    }

    private fun updateZipWithCountries(files: GCExploreDatabase, name: String, vararg countries: String): File {
        val zip = File(appContext.cacheDir, name)
        stageUpdateWithCountries(files, *countries)
        files.getUpdateFile().copyTo(zip, overwrite = true)
        files.getUpdateFile().delete()
        return zip
    }

    private fun syncWorker(repository: ExploreRepository, syncStatus: ExploreDataSyncStatus, config: ExploreConfig) =
        ExploreSyncWorker(
            appContext,
            mock<WorkerParameters>(defaultAnswer = Mockito.RETURNS_DEEP_STUBS) {
                on { inputData } doReturn Data.EMPTY
            },
            mock(),
            repository,
            syncStatus,
            config
        )

    @Test
    fun syncWhoseTimestampWriteFails_stillAnnouncesTheReplacedCatalog() = runBlocking {
        val config = updateConfig()
        val files = GCExploreDatabase(appContext, config, mock(), mock())
        val remote = updateZipWithCountries(files, "remote.zip", "US", "GB")
        val repository = TwoSwapRepository(files, null, remote, remoteTimestamp = 5_000, isTimestampWriteFailing = true)
        val syncStatus = ExploreDataSyncStatus()

        syncWorker(repository, syncStatus, config).doWork()

        val dao = ExploreDatabase.getAppDatabase(appContext, config).merchantDao()
        assertEquals(listOf("GB", "US"), withContext(Dispatchers.IO) { dao.getCountries() }.sorted())
        assertEquals(1, syncStatus.getCatalogGeneration().first())
        assertEquals("a failed update is fetched again", 0L, prefsState.localDbTimestamp)
    }

    @Test
    fun version4FileBesideACurrentTimestamp_isReplacedByTheSync() = runBlocking {
        val config = updateConfig()
        val files = GCExploreDatabase(appContext, config, mock(), mock())
        val remote = updateZipWithCountries(files, "remote.zip", "US", "GB")
        // After a downgrade and an upgrade: the older build left its file, this build's marks survived
        createPopulatedVersion4(appContext.getDatabasePath("explore-database"))
        prefsState = ExploreDatabasePrefs(localDbTimestamp = 5_000, localDbSchemaVersion = ExploreDatabase.VERSION)
        val repository = TwoSwapRepository(files, null, remote, remoteTimestamp = 5_000)

        syncWorker(repository, ExploreDataSyncStatus(), config).doWork()

        val dao = ExploreDatabase.getAppDatabase(appContext, config).merchantDao()
        assertEquals(listOf("GB", "US"), withContext(Dispatchers.IO) { dao.getCountries() }.sorted())
    }

    @Test
    fun daoFromBeforeATwoSwapSync_readsTheSecondFileWhenToldOfTheNewCatalog() = runBlocking {
        val config = updateConfig()
        val files = GCExploreDatabase(appContext, config, mock(), mock())
        val asset = updateZipWithCountries(files, "asset.zip", "US")
        val remote = updateZipWithCountries(files, "remote.zip", "US", "GB")

        // Hilt hands out the DAOs of the first instance for the whole session
        val dao = ExploreDatabase.getAppDatabase(appContext, config).merchantDao()
        val syncStatus = ExploreDataSyncStatus()
        val handledGeneration = AtomicInteger(-1)
        val countriesRead = CopyOnWriteArrayList<List<String>>()
        val reader = launch(Dispatchers.IO) {
            syncStatus.getCatalogGeneration().collect { generation ->
                countriesRead.add(dao.getCountries().sorted())
                handledGeneration.set(generation)
            }
        }
        suspend fun awaitReader() = withTimeout(10_000) {
            while (handledGeneration.get() != syncStatus.getCatalogGeneration().first()) {
                delay(10)
            }
        }
        awaitReader()
        val repository = TwoSwapRepository(files, asset, remote) { awaitReader() }

        syncWorker(repository, syncStatus, config).doWork()
        awaitReader()
        reader.cancel()

        assertEquals(listOf(listOf(), listOf("GB", "US")), countriesRead.toList())
    }

    @Test
    fun updateWithSeveralCountries_listsThemAll() = runTest {
        val config = updateConfig()
        val repository = GCExploreDatabase(appContext, config, mock(), mock())
        stageUpdateWithCountries(repository, "US", "GB")

        ExploreDatabase.updateDatabase(appContext, repository, config)

        val dao = ExploreDatabase.getAppDatabase(appContext, config).merchantDao()
        assertEquals(listOf("GB", "US"), withContext(Dispatchers.IO) { dao.getCountries() }.sorted())
    }

    private fun stageUpdateFile(repository: GCExploreDatabase): Long {
        GOOD_EXPLORE_DB.copyTo(repository.getUpdateFile(), overwrite = true)
        return ZipFile(GOOD_EXPLORE_DB).use { it.comment.split("#")[0].toLong() }
    }

    @Test
    fun goodUpdate_recordsTimestampAndSchemaVersion() = runTest {
        val config = updateConfig()
        val repository = GCExploreDatabase(appContext, config, mock(), mock())
        val expectedTimestamp = stageUpdateFile(repository)
        val daoFromBeforeTheUpdate = ExploreDatabase.getAppDatabase(appContext, config).merchantDao()

        ExploreDatabase.updateDatabase(appContext, repository, config)

        assertEquals("Wrong timestamp set;", expectedTimestamp, prefsState.localDbTimestamp)
        assertEquals(ExploreDatabase.VERSION, prefsState.localDbSchemaVersion)
        assertEquals(expectedTimestamp, prefsState.localDbTimestampFor(ExploreDatabase.VERSION))
        ExploreDatabase.getAppDatabase(appContext, config).openHelper.readableDatabase
            .query("SELECT count(*) FROM merchant").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        // Injected DAOs outlive the swap; the country list is read through one right after a sync
        assertEquals(listOf<String>(), withContext(Dispatchers.IO) { daoFromBeforeTheUpdate.getCountries() })
        assertEquals(1, withContext(Dispatchers.IO) { daoFromBeforeTheUpdate.getCount() })
    }

    @Test
    fun updateThatCannotReplaceTheDatabase_recordsNothing() = runTest {
        val config = updateConfig()
        val repository = GCExploreDatabase(appContext, config, mock(), mock())
        stageUpdateFile(repository)
        // A non-empty directory in the database's place makes the final rename fail
        val blocker = appContext.getDatabasePath("explore-database")
        File(blocker, "child").apply { parentFile!!.mkdirs() }.writeText("x")

        try {
            ExploreDatabase.updateDatabase(appContext, repository, config)
            fail("the update should not have replaced the database")
        } catch (ex: IOException) {
            assertEquals(0L, prefsState.localDbTimestamp)
            assertEquals(0, prefsState.localDbSchemaVersion)
        } finally {
            blocker.deleteRecursively()
        }
    }

    @Test
    fun localTimestamp_fromAnotherSchemaVersion_countsAsNoData() {
        val upgraded = ExploreDatabasePrefs(localDbTimestamp = 1_234_567_890L)
        assertEquals(0L, upgraded.localDbTimestampFor(ExploreDatabase.VERSION))

        val older = upgraded.copy(localDbSchemaVersion = ExploreDatabase.VERSION - 1)
        assertEquals(0L, older.localDbTimestampFor(ExploreDatabase.VERSION))

        val current = upgraded.copy(localDbSchemaVersion = ExploreDatabase.VERSION)
        assertEquals(1_234_567_890L, current.localDbTimestampFor(ExploreDatabase.VERSION))
    }

    @Test
    fun version4UpdateFile_isMigratedWhenLoaded() = version4UpdateFileIsMigrated(mapOf())

    @Test
    fun publishedVersion4UpdateFile_isMigratedWhenLoaded() = version4UpdateFileIsMigrated(PUBLISHED_VERSION_4_TABLES)

    private fun version4UpdateFileIsMigrated(tables: Map<String, String>) = runTest {
        val inner = appContext.getDatabasePath("explore-v4-inner")
        createPopulatedVersion4(inner, tables)
        val v4File = File(appContext.cacheDir, "explore-v4.zip")
        zipExploreDb(inner, v4File)
        appContext.deleteDatabase(inner.name)
        val dbBuilder =
            Room.databaseBuilder(appContext, ExploreDatabase::class.java, "explore.db").allowMainThreadQueries()

        val database = ExploreDatabase.preloadAndOpen(dbBuilder, repo, v4File)

        assertEquals(5, database.openHelper.readableDatabase.version)
        assertEquals(1, database.merchantDao().getCount())
        assertEquals(listOf<String>(), database.merchantDao().getCountries())
        database.close()
    }

    @Test
    fun version4LocalDb_isMigratedInPlaceAndKeepsItsRows() = version4LocalDbIsMigrated(mapOf())

    @Test
    fun publishedVersion4LocalDb_isMigratedInPlaceAndKeepsItsRows() =
        version4LocalDbIsMigrated(PUBLISHED_VERSION_4_TABLES)

    private fun version4LocalDbIsMigrated(tables: Map<String, String>) = runTest {
        createPopulatedVersion4(appContext.getDatabasePath("explore-database"), tables)
        // A build that reads version 4 wrote this timestamp; the version marker is from an earlier
        // run of this build. Neither says the migrated rows are the version 5 data.
        prefsState = ExploreDatabasePrefs(
            localDbTimestamp = 1_234_567_890L,
            localDbSchemaVersion = ExploreDatabase.VERSION
        )

        val database = ExploreDatabase.getAppDatabase(appContext, updateConfig())

        val db = database.openHelper.readableDatabase
        assertEquals(5, db.version)
        for (table in listOf("merchant", "atm")) {
            db.query("SELECT count(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assertEquals("$table rows must survive the migration", 1, cursor.getInt(0))
            }
        }
        assertEquals(0L, prefsState.localDbTimestampFor(ExploreDatabase.VERSION))
    }

    @Test
    fun emptyLocalDb_isNeverUpToDate() = runTest {
        val config = updateConfig()
        // What a build that reads version 4 leaves behind after wiping a version 5 file
        ExploreDatabase.getAppDatabase(appContext, config).close()
        ExploreDatabase.resetInstanceForTest()
        prefsState = ExploreDatabasePrefs(
            localDbTimestamp = 1_234_567_890L,
            localDbSchemaVersion = ExploreDatabase.VERSION
        )

        ExploreDatabase.getAppDatabase(appContext, config)

        assertEquals(0L, prefsState.localDbTimestamp)
    }

    @Test
    fun populatedCurrentLocalDb_keepsItsTimestamp() = runTest {
        val config = updateConfig()
        val repository = GCExploreDatabase(appContext, config, mock(), mock())
        val timestamp = stageUpdateFile(repository)
        ExploreDatabase.updateDatabase(appContext, repository, config)
        ExploreDatabase.resetInstanceForTest()

        ExploreDatabase.getAppDatabase(appContext, config)

        assertEquals(timestamp, prefsState.localDbTimestampFor(ExploreDatabase.VERSION))
    }
    @Test
    fun localDbWithoutMerchants_isNeverUpToDate() = runTest {
        val config = updateConfig()
        ExploreDatabase.getAppDatabase(appContext, config).openHelper.writableDatabase
            .execSQL("INSERT INTO atm (name) VALUES ('dummy atm')")
        ExploreDatabase.resetInstanceForTest()
        prefsState = ExploreDatabasePrefs(
            localDbTimestamp = 1_234_567_890L,
            localDbSchemaVersion = ExploreDatabase.VERSION
        )

        ExploreDatabase.getAppDatabase(appContext, config)

        assertEquals(0L, prefsState.localDbTimestamp)
    }
}
