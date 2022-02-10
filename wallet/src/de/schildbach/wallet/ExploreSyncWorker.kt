/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.common.base.Stopwatch
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.slf4j.LoggerFactory
import java.util.*

class ExploreSyncWorker constructor(val appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private val log = LoggerFactory.getLogger(ExploreSyncWorker::class.java)
        const val SHARED_PREFS_NAME = "explore"
        const val PREFS_LOCAL_DB_TIMESTAMP_KEY = "last_sync"
    }

    private val exploreRepository by lazy {
        entryPoint.exploreRepository()
    }

    private val preferences by lazy {
        appContext.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val analytics by lazy {
        entryPoint.analytics()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ExploreSyncWorkerEntryPoint {
        fun exploreRepository(): ExploreRepository
        fun analytics(): AnalyticsService
    }

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(appContext, ExploreSyncWorkerEntryPoint::class.java)
    }

    @SuppressLint("CommitPrefEdits")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        log.info("sync explore db started")

        var lastSync = 0L
        var remoteDataTimestamp = 0L
        try {
            val tableSyncWatch = Stopwatch.createStarted()

            lastSync = preferences.getLong(PREFS_LOCAL_DB_TIMESTAMP_KEY, 0)
            remoteDataTimestamp = exploreRepository.getLastUpdate()
            log.info("remote data timestamp: $remoteDataTimestamp (${Date(remoteDataTimestamp)})")

            if (lastSync >= remoteDataTimestamp) {
                log.info("data timestamp $lastSync, nothing to sync (${Date(lastSync)})")
                return@withContext Result.success()
            }

            exploreRepository.download()

            preferences.edit().putLong(PREFS_LOCAL_DB_TIMESTAMP_KEY, remoteDataTimestamp).apply()

            AppExploreDatabase.forceUpdate()

            log.info("sync explore db finished $tableSyncWatch")

        } catch (ex: Exception) {
            analytics.logError(ex, "syncing from $lastSync, $remoteDataTimestamp")
            log.info("sync explore db crashed ${ex.message}", ex)
            return@withContext Result.failure()
        }

        return@withContext Result.success()
    }
}
