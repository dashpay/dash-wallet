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
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.google.firebase.FirebaseNetworkException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.repository.ExploreDataSyncStatus
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

@HiltWorker
class ExploreSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val analytics: AnalyticsService,
    private val exploreRepository: ExploreRepository,
    private val syncStatus: ExploreDataSyncStatus
): CoroutineWorker(appContext, workerParams) {
    companion object {
        const val USE_TEST_DB_KEY = "use_test_database"
        private val log = LoggerFactory.getLogger(ExploreSyncWorker::class.java)

        fun run(@ApplicationContext context: Context, isMainNet: Boolean) {
            val inputData = Data.Builder().putBoolean(
                USE_TEST_DB_KEY,
                !isMainNet
            )
            val syncDataWorkRequest = OneTimeWorkRequest.Builder(ExploreSyncWorker::class.java)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setInputData(inputData.build())
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "Sync Explore Data",
                ExistingWorkPolicy.KEEP,
                syncDataWorkRequest
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        log.info("sync explore db started")

        var localDataTimestamp = 0L
        var remoteDataTimestamp = 0L
        var preloadedDbTimestamp = 0L

        try {
            syncStatus.setSyncProgress(0.0)

            val timeInMillis = measureTimeMillis {
                val updateFile = exploreRepository.getUpdateFile()
                val checkTestDB = inputData.getBoolean(USE_TEST_DB_KEY, false)
                val hasPreloaded = exploreRepository.preloadFromAssetsInto(updateFile, checkTestDB)

                if (hasPreloaded) {
                    preloadedDbTimestamp = exploreRepository.getTimestamp(updateFile)
                    log.info("preloaded data timestamp: $preloadedDbTimestamp (${Date(preloadedDbTimestamp)})")

                    if (exploreRepository.localDatabaseTimestamp == 0L ||
                        exploreRepository.localDatabaseTimestamp < preloadedDbTimestamp
                    ) {
                        // force data preloading for fresh installs
                        // and a newer preloaded DB
                        ExploreDatabase.updateDatabase(
                            appContext,
                            exploreRepository
                        )
                        exploreRepository.preloadedOnTimestamp = preloadedDbTimestamp
                    }
                }

                if (!updateFile.delete()) {
                    log.error("unable to delete " + updateFile.absolutePath)
                }

                localDataTimestamp = exploreRepository.localDatabaseTimestamp
                log.info("local data timestamp: $localDataTimestamp (${Date(localDataTimestamp)})")

                remoteDataTimestamp = exploreRepository.getRemoteTimestamp()
                log.info("remote data timestamp: $remoteDataTimestamp (${Date(remoteDataTimestamp)})")

                if (localDataTimestamp >= remoteDataTimestamp) {
                    log.info("explore db is up to date, nothing to sync")
                    syncStatus.setSyncProgress(100.0)
                    exploreRepository.failedSyncAttempts = 0

                    if (exploreRepository.lastSyncAttemptTimestamp <= 0) {
                        // Some devices might have this as 0 due to the bug. Need to update manually
                        // TODO: this can be removed after some time
                        exploreRepository.lastSyncAttemptTimestamp = remoteDataTimestamp
                    }

                    return@withContext Result.success()
                }
                syncStatus.setSyncProgress(10.0)

                exploreRepository.download()

                syncStatus.setSyncProgress(80.0)

                ExploreDatabase.updateDatabase(
                    appContext,
                    exploreRepository
                )
            }

            log.info("sync explore db finished, took $timeInMillis ms")

            syncStatus.setSyncProgress(100.0)

        } catch (ex: FirebaseNetworkException) {
            log.warn("sync explore no network", ex)
            syncStatus.setSyncError(ex)
            return@withContext Result.failure()
        } catch (ex: Exception) {
            analytics.logError(ex, "local: $localDataTimestamp, preloaded: ${preloadedDbTimestamp}, remote: $remoteDataTimestamp")
            log.error("sync explore db crashed ${ex.message}", ex)
            syncStatus.setSyncError(ex)
            exploreRepository.failedSyncAttempts += 1
            return@withContext Result.failure()
        }

        exploreRepository.failedSyncAttempts = 0
        return@withContext Result.success()
    }
}
