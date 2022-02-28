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
import com.google.firebase.FirebaseNetworkException
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
    }

    private val exploreRepository by lazy {
        entryPoint.exploreRepository()
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

        var localDataTimestamp = 0L
        var remoteDataTimestamp = 0L
        try {
            val tableSyncWatch = Stopwatch.createStarted()

            localDataTimestamp = exploreRepository.localTimestamp.run {
                if (this == 0L) {
                    // force data preloading for fresh installs
                    AppExploreDatabase.getAppDatabase()
                }
                exploreRepository.localTimestamp
            }
            log.info("local data timestamp: $localDataTimestamp (${Date(localDataTimestamp)})")

            remoteDataTimestamp = exploreRepository.getRemoteTimestamp()
            log.info("remote data timestamp: $remoteDataTimestamp (${Date(remoteDataTimestamp)})")

            if (localDataTimestamp >= remoteDataTimestamp) {
                log.info("explore db is up to date, nothing to sync")
                return@withContext Result.success()
            }

            exploreRepository.download()
            AppExploreDatabase.forceUpdate()

            log.info("sync explore db finished $tableSyncWatch")

        } catch (ex: FirebaseNetworkException) {
            log.warn("sync explore no network", ex)
            return@withContext Result.failure()
        } catch (ex: Exception) {
            analytics.logError(ex, "syncing from $localDataTimestamp, $remoteDataTimestamp")
            log.error("sync explore db crashed ${ex.message}", ex)
            return@withContext Result.failure()
        }

        return@withContext Result.success()
    }
}
