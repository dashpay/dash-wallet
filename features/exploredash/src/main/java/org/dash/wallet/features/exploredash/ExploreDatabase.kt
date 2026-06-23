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
import android.database.sqlite.SQLiteException
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import net.lingala.zip4j.ZipFile
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderDao
import org.dash.wallet.features.exploredash.data.explore.AtmDao
import org.dash.wallet.features.exploredash.data.explore.MerchantDao
import org.dash.wallet.features.exploredash.data.explore.model.Atm
import org.dash.wallet.features.exploredash.data.explore.model.AtmFTS
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.data.explore.model.MerchantFTS
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import org.dash.wallet.features.exploredash.utils.RoomConverters
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Database(
    entities = [
        Merchant::class,
        MerchantFTS::class,
        Atm::class,
        AtmFTS::class,
        GiftCardProvider::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(RoomConverters::class)
abstract class ExploreDatabase : RoomDatabase() {

    abstract fun merchantDao(): MerchantDao
    abstract fun atmDao(): AtmDao
    abstract fun giftCardProviderDao(): GiftCardProviderDao

    companion object {
        private const val EXPLORE_DB_NAME = "explore-database"
        private const val EXPLORE_DB_STAGING_NAME = "explore-database.staging"
        private val log = LoggerFactory.getLogger(ExploreDatabase::class.java)
        private var instance: ExploreDatabase? = null

        suspend fun getAppDatabase(context: Context, exploreConfig: ExploreConfig): ExploreDatabase {
            if (instance == null) {
                instance = open(context, exploreConfig)
            }
            return instance!!
        }

        suspend fun updateDatabase(
            context: Context,
            repository: ExploreRepository,
            exploreConfig: ExploreConfig
        ) {
            log.info("force update explore db")
            // Null the field before closing / before calling update so that if either
            // throws, callers don't get a handle to a closed RoomDatabase. If update
            // succeeds, instance is reassigned to the fresh DB.
            val previous = instance
            instance = null
            previous?.close()
            instance = update(context, repository, exploreConfig)
        }

        @VisibleForTesting
        internal fun resetInstanceForTest() {
            val previous = instance
            instance = null
            previous?.close()
        }

        private suspend fun open(context: Context, exploreConfig: ExploreConfig): ExploreDatabase {
            fixObsoleteName(context, exploreConfig)

            return try {
                buildAndProbe(context)
            } catch (ex: RuntimeException) {
                // RoomOpenHelper.checkIdentity throws IllegalStateException when the on-disk
                // file is missing room_master_table or has a schema that doesn't match the
                // entity definitions. Older interrupted update flows could leave the file in
                // this state with no way to self-heal (no version delta = no destructive
                // fallback).
                log.error("explore DB failed to open, recovering", ex)
                cleanupPreviousDatabases(context)
                clearLocalTimestamp(exploreConfig)
                try {
                    // Best effort: rebuild from the bundled asset so the user gets data
                    // back immediately. If the asset itself is broken (bad schema, failing
                    // migrations, missing entirely in the test classpath, etc.), fall
                    // through to the empty rebuild — the sync worker will populate later.
                    rebuildFromAsset(context, exploreConfig)
                } catch (preloadEx: Exception) {
                    log.error("asset preload failed during recovery, returning empty DB", preloadEx)
                    cleanupPreviousDatabases(context)
                    buildAndProbe(context)
                }
            }
        }

        private suspend fun rebuildFromAsset(
            context: Context,
            exploreConfig: ExploreConfig
        ): ExploreDatabase {
            // zip4j needs a File for AES-protected zips (random access for the password
            // path), so spool the asset into the cache dir first.
            val assetName = pickAssetName(context, exploreConfig)
            val cacheZip = File(context.cacheDir, "explore-recovery.zip")
            cacheZip.delete()
            context.assets.open(assetName).use { input ->
                FileOutputStream(cacheZip).use { output -> input.copyTo(output) }
            }

            try {
                val password = ZipFile(cacheZip).use { zip ->
                    val comment = zip.comment
                    require(!comment.isNullOrBlank()) {
                        "asset zip is missing comment metadata"
                    }
                    val parts = comment.split("#")
                    require(parts.size >= 2 && parts[1].isNotEmpty()) {
                        "asset zip comment is malformed: $comment"
                    }
                    parts[1]
                }
                context.deleteDatabase(EXPLORE_DB_STAGING_NAME)

                val staging = Room.databaseBuilder(
                    context,
                    ExploreDatabase::class.java,
                    EXPLORE_DB_STAGING_NAME
                )
                    .createFromInputStream({ openInnerDb(cacheZip, password) })
                    .setJournalMode(JournalMode.TRUNCATE)
                    .addMigrations(
                        ExploreDatabaseMigrations.migration1To2,
                        ExploreDatabaseMigrations.migration2To3,
                        ExploreDatabaseMigrations.migration3To4
                    )
                    .build()
                try {
                    // Force open: surfaces schema mismatch, migration failure, etc. now
                    // (so the outer try/catch in open() can fall back to empty rebuild).
                    val supportDb = staging.openHelper.writableDatabase
                    if (!hasExpectedData(supportDb)) {
                        throw SQLiteException("asset DB has empty merchant or atm")
                    }
                } finally {
                    staging.close()
                }

                promoteStagingToProduction(context)
                log.info("recovered explore DB from bundled asset {}", assetName)
                return buildAndProbe(context)
            } finally {
                cacheZip.delete()
            }
        }

        private suspend fun pickAssetName(context: Context, exploreConfig: ExploreConfig): String {
            // Prefer whichever flavor was last preloaded; fall back to the other if the
            // bundled APK doesn't ship it. Matches GCExploreDatabase.preloadFromAssetsInto.
            val preferTest = exploreConfig.get(ExploreConfig.PRELOADED_TEST_DB) ?: false
            val first = if (preferTest) "explore/explore-testnet.db" else "explore/explore.db"
            val second = if (preferTest) "explore/explore.db" else "explore/explore-testnet.db"
            return try {
                context.assets.open(first).close()
                first
            } catch (e: IOException) {
                context.assets.open(second).close()
                second
            }
        }

        private fun openInnerDb(zipFile: File, password: String): InputStream {
            val zip = ZipFile(zipFile, password.toCharArray())
            return zip.getInputStream(zip.getFileHeader("explore.db"))
        }

        private fun buildAndProbe(context: Context): ExploreDatabase {
            log.info("Open database {}", EXPLORE_DB_NAME)
            val database = Room.databaseBuilder(
                context,
                ExploreDatabase::class.java,
                EXPLORE_DB_NAME
            )
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(
                    ExploreDatabaseMigrations.migration1To2,
                    ExploreDatabaseMigrations.migration2To3,
                    ExploreDatabaseMigrations.migration3To4
                )
                .fallbackToDestructiveMigration()
                .build()
            // Force the underlying SQLite open + checkIdentity now so any schema mismatch
            // surfaces here (and can be recovered) instead of crashing the first DAO caller.
            database.openHelper.writableDatabase
            return database
        }

        private suspend fun clearLocalTimestamp(exploreConfig: ExploreConfig) {
            val prefs = exploreConfig.exploreDatabasePrefs.first()
            exploreConfig.saveExploreDatabasePrefs(prefs.copy(localDbTimestamp = 0L))
        }

        private suspend fun update(
            context: Context,
            repository: ExploreRepository,
            exploreConfig: ExploreConfig
        ): ExploreDatabase {
            // Clear any leftover staging file from a previously interrupted update.
            context.deleteDatabase(EXPLORE_DB_STAGING_NAME)

            val dbUpdateFile = repository.getUpdateFile()
            val dbBuilder = Room.databaseBuilder(
                context,
                ExploreDatabase::class.java,
                EXPLORE_DB_STAGING_NAME
            )

            // Build and validate against the staging name. If preloadAndOpen throws,
            // the production DB on disk is left untouched.
            val staging = preloadAndOpen(dbBuilder, repository, dbUpdateFile)
            staging.close()

            promoteStagingToProduction(context)

            return open(context, exploreConfig)
        }

        private fun promoteStagingToProduction(context: Context) {
            val dbDir = context.getDatabasePath(EXPLORE_DB_NAME).parentFile
                ?: throw IOException("databases directory unavailable")
            val stagingFile = File(dbDir, EXPLORE_DB_STAGING_NAME)
            val prodFile = File(dbDir, EXPLORE_DB_NAME)

            if (!stagingFile.exists()) {
                throw IOException("staging DB missing at ${stagingFile.absolutePath}")
            }

            // Drop the production file (and its journal/wal/shm).
            context.deleteDatabase(EXPLORE_DB_NAME)

            if (!stagingFile.renameTo(prodFile)) {
                throw IOException("could not promote staging DB to production")
            }

            // Discard staging journal/wal/shm; Room will recreate them under the production name.
            File(dbDir, "$EXPLORE_DB_STAGING_NAME-journal").delete()
            File(dbDir, "$EXPLORE_DB_STAGING_NAME-shm").delete()
            File(dbDir, "$EXPLORE_DB_STAGING_NAME-wal").delete()
        }

        @VisibleForTesting
        suspend fun preloadAndOpen(
            dbBuilder: Builder<ExploreDatabase>,
            repository: ExploreRepository,
            dbUpdateFile: File
        ): ExploreDatabase {
            require(dbUpdateFile.exists()) { "dbUpdateFile doesn't exist" }

            return suspendCancellableCoroutine { coroutine ->
                var database: ExploreDatabase? = null

                log.info("create explore db from InputStream")
                dbBuilder.createFromInputStream(
                    { repository.getDatabaseInputStream(dbUpdateFile) },
                    object : PrepackagedDatabaseCallback() {
                        override fun onOpenPrepackagedDatabase(db: SupportSQLiteDatabase) {
                            log.info("onOpenPrepackagedDatabase")
                        }
                    }
                ).addMigrations(
                    ExploreDatabaseMigrations.migration1To2,
                    ExploreDatabaseMigrations.migration2To3,
                    ExploreDatabaseMigrations.migration3To4
                )

                val onOpenCallback = object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        log.info("opened database: ${db.path}")
                        if (!dbUpdateFile.delete()) {
                            log.error("unable to delete " + dbUpdateFile.absolutePath)
                        }

                        try {
                            if (hasExpectedData(db)) {
                                repository.finalizeUpdate()
                                log.info("successfully loaded new version of explore db")

                                if (coroutine.isActive) {
                                    coroutine.resume(database!!)
                                }
                            } else {
                                log.info("database update file was empty")

                                if (coroutine.isActive) {
                                    coroutine.resumeWithException(SQLiteException("Database update file is empty"))
                                }
                            }
                        } catch (ex: Exception) {
                            log.error("error reading merchant & atm count", ex)

                            if (coroutine.isActive) {
                                coroutine.resumeWithException(ex)
                            }
                        }
                    }
                }

                database = dbBuilder
                    .setJournalMode(JournalMode.TRUNCATE)
                    .addMigrations(
                        ExploreDatabaseMigrations.migration1To2,
                        ExploreDatabaseMigrations.migration2To3,
                        ExploreDatabaseMigrations.migration3To4
                    )
                    .addCallback(onOpenCallback)
                    .build()

                if (database.isOpen) {
                    log.warn("database is already open")
                }

                log.info("querying database to trigger the open callback")
                database.query("SELECT * FROM sqlite_master", null)
            }
        }

        private fun hasExpectedData(db: SupportSQLiteDatabase): Boolean {
            var cursor = db.query("SELECT id FROM merchant;")
            val merchantCount = cursor.count
            cursor.close()
            cursor = db.query("SELECT id FROM atm;")
            val atmCount = cursor.count
            cursor.close()

            return merchantCount > 0 && atmCount > 0
        }

        private suspend fun fixObsoleteName(context: Context, config: ExploreConfig) {
            config.get(ExploreConfig.EXPLORE_DATABASE_NAME)?.let { previousName ->
                val file = context.getDatabasePath(previousName)
                file.renameTo(context.getDatabasePath(EXPLORE_DB_NAME))
            }
        }

        private fun cleanupPreviousDatabases(context: Context) {
            log.info("cleanup, before: ${context.databaseList().joinToString("; ")}")
            // deleteDatabase removes the main file plus -journal/-wal/-shm side files.
            context.deleteDatabase(EXPLORE_DB_NAME)
            context.deleteDatabase(EXPLORE_DB_STAGING_NAME)
            log.info("cleanup, after: ${context.databaseList().joinToString("; ")}")
        }
    }
}
