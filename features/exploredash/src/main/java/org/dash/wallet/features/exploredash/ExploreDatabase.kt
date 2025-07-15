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
import kotlinx.coroutines.suspendCancellableCoroutine
import org.dash.wallet.features.exploredash.data.explore.AtmDao
import org.dash.wallet.features.exploredash.data.explore.GiftCardProviderDao
import org.dash.wallet.features.exploredash.data.explore.MerchantDao
import org.dash.wallet.features.exploredash.data.explore.model.Atm
import org.dash.wallet.features.exploredash.data.explore.model.AtmFTS
import org.dash.wallet.features.exploredash.data.explore.model.GiftCardProvider
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.data.explore.model.MerchantFTS
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import org.dash.wallet.features.exploredash.utils.RoomConverters
import org.slf4j.LoggerFactory
import java.io.File
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
        private val log = LoggerFactory.getLogger(ExploreDatabase::class.java)
        private var instance: ExploreDatabase? = null

        suspend fun getAppDatabase(context: Context, exploreConfig: ExploreConfig): ExploreDatabase {
            if (instance == null) {
                instance = open(context, exploreConfig)
            }
            return instance!!
        }

        suspend fun updateDatabase(context: Context, repository: ExploreRepository) {
            log.info("force update explore db")
            if (instance != null) {
                instance!!.close()
            }
            instance = update(context, repository)
        }

        private suspend fun open(context: Context, exploreConfig: ExploreConfig): ExploreDatabase {
            fixObsoleteName(context, exploreConfig)

            val dbBuilder = Room.databaseBuilder(
                context,
                ExploreDatabase::class.java,
                EXPLORE_DB_NAME
            )

            log.info("Open database {}", EXPLORE_DB_NAME)
            return dbBuilder
                .setJournalMode(JournalMode.TRUNCATE)
                .fallbackToDestructiveMigration()
                .build()
        }

        private suspend fun update(context: Context, repository: ExploreRepository): ExploreDatabase {
            cleanupPreviousDatabases(context)

            val dbUpdateFile = repository.getUpdateFile()
            val dbBuilder = Room.databaseBuilder(
                context,
                ExploreDatabase::class.java,
                EXPLORE_DB_NAME
            )

            return preloadAndOpen(dbBuilder, repository, dbUpdateFile)
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
            var list = context.databaseList()
            log.info("cleanup, before: ${list.joinToString("; ")}")

            for (database in list) {
                if (database.startsWith(EXPLORE_DB_NAME)) {
                    context.deleteDatabase(database)
                }
            }

            list = context.databaseList()
            log.info("cleanup, after: ${list.joinToString("; ")}")
        }
    }
}
