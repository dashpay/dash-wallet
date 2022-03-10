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
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.common.base.Stopwatch
import com.google.firebase.FirebaseNetworkException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.dash.wallet.features.exploredash.repository.DataSyncStatusService
import org.slf4j.LoggerFactory
import java.util.*

@HiltWorker
class ExploreSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val analytics: AnalyticsService,
    private val exploreRepository: ExploreRepository,
    private val syncStatus: DataSyncStatusService
): CoroutineWorker(appContext, workerParams) {
    companion object {
        private val log = LoggerFactory.getLogger(ExploreSyncWorker::class.java)
    }

    @SuppressLint("CommitPrefEdits")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        log.info("sync explore db started")

        var localDataTimestamp = 0L
        var remoteDataTimestamp = 0L
        try {
            syncStatus.setSyncProgress(0.0)
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
                syncStatus.setSyncProgress(100.0)
                return@withContext Result.success()
            }
            syncStatus.setSyncProgress(10.0)

            exploreRepository.download()

            syncStatus.setSyncProgress(80.0)

            AppExploreDatabase.forceUpdate()

            log.info("sync explore db finished $tableSyncWatch")

            syncStatus.setSyncProgress(100.0)

        } catch (ex: FirebaseNetworkException) {
            log.warn("sync explore no network", ex)
            syncStatus.setSyncError(ex)
            return@withContext Result.failure()
        } catch (ex: Exception) {
            analytics.logError(ex, "syncing from $localDataTimestamp, $remoteDataTimestamp")
            log.error("sync explore db crashed ${ex.message}", ex)
            syncStatus.setSyncError(ex)
            return@withContext Result.failure()
        }

        return@withContext Result.success()
    }
}
