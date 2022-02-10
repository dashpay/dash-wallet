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

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.common.base.Stopwatch
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.data.AtmDao
import org.dash.wallet.features.exploredash.data.MerchantDao
import org.dash.wallet.features.exploredash.data.model.Atm
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.dash.wallet.features.exploredash.repository.DataSyncStatus
import org.slf4j.LoggerFactory
import java.util.*

class ExploreSyncWorker constructor(val appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private val log = LoggerFactory.getLogger(ExploreSyncWorker::class.java)
        private const val PAGE_SIZE = 10000
        const val SHARED_PREFS_NAME = "explore"
        const val PREFS_LAST_SYNC_KEY = "last_sync"
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

    private val syncStatus by lazy {
        entryPoint.syncStatus()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ExploreSyncWorkerEntryPoint {
        fun atmDao(): AtmDao
        fun merchantDao(): MerchantDao
        fun exploreRepository(): ExploreRepository
        fun analytics(): AnalyticsService
        fun syncStatus(): DataSyncStatus
    }

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(appContext, ExploreSyncWorkerEntryPoint::class.java)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        log.info("Sync Explore Dash started")

        var merchantDataSizeDB = 0
        var lastSync = 0L
        var lastDataUpdate = 0L
        try {
            exploreRepository.init()
            syncStatus.setSyncProgress(0.0)

            lastSync = preferences.getLong(PREFS_LAST_SYNC_KEY, 0)
            lastDataUpdate = exploreRepository.getLastUpdate()

            if (lastSync >= lastDataUpdate) {
                log.info("Data timestamp $lastSync, nothing to sync (${Date(lastSync)})")
                syncStatus.setSyncProgress(100.0)
                return@withContext Result.success()
            }

            val merchantDao = entryPoint.merchantDao()
            merchantDataSizeDB = merchantDao.getCount()

            val tableSyncWatch = Stopwatch.createStarted()

            if (merchantDataSizeDB == 0) {
                val atmDao = entryPoint.atmDao()
                atmDao.deleteAll()
                val atmCache = mutableListOf<Atm>()
                exploreRepository.getAtmData().collect {
                    atmCache.add(it)
                }
                atmDao.save(atmCache)
                tableSyncWatch.stop()
                log.info("ATM sync took $tableSyncWatch (${atmCache.size} records)")
                atmCache.clear()
            }

            tableSyncWatch.reset()
            syncStatus.setSyncProgress(10.0)
            tableSyncWatch.start()

            val merchantDataSize = exploreRepository.getMerchantDataSize()
            if (merchantDataSizeDB == 0) {
                merchantDao.deleteAll()
            } else {
                log.info("MERCHANT data partially synced ($merchantDataSizeDB of $merchantDataSize), continuing")
            }
            val merchantCache = mutableListOf<Merchant>()
            var counter = 0
            var merchantsLoaded = 0
            exploreRepository.getMerchantData(merchantDataSizeDB).collect {
                counter++
                merchantCache.add(it)
                if (merchantCache.size == PAGE_SIZE) {
                    merchantDao.save(merchantCache)
                    merchantCache.clear()
                    log.info("MERCHANT $counter of ${merchantDataSize - merchantDataSizeDB} sync took $tableSyncWatch")

                    delay(10000)
                    merchantsLoaded += PAGE_SIZE
                    log.info("{} - {}", merchantsLoaded, merchantDataSize)
                    syncStatus.setSyncProgress(10.0 + 0.9 * (merchantsLoaded.toDouble() / merchantDataSize) * 100.0)
                }
                // this math may not be correct
            }
            if (merchantCache.size > 0) {
                merchantDao.save(merchantCache)
                merchantCache.clear()
            }
            tableSyncWatch.stop()
            log.info("MERCHANT sync took $tableSyncWatch ($counter records)")

            preferences.edit()
                .putLong(PREFS_LAST_SYNC_KEY, Calendar.getInstance().timeInMillis)
                .apply()

            log.info("Sync Explore Dash finished")

            syncStatus.setSyncProgress(100.0)
            return@withContext Result.success()

        } catch (ex: Exception) {
            analytics.logError(ex, "syncing from $merchantDataSizeDB ($lastSync/$lastDataUpdate")
            log.info("Sync Explore Dash not fully finished: ${ex.message}")
            syncStatus.setSyncError(ex)
            return@withContext Result.failure()
        }
    }
}
