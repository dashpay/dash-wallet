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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.data.AtmDao
import org.dash.wallet.features.exploredash.data.MerchantDao
import org.dash.wallet.features.exploredash.data.model.Atm
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.slf4j.LoggerFactory
import java.util.*

class ExploreSyncWorker constructor(val appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private val log = LoggerFactory.getLogger(ExploreSyncWorker::class.java)
        private const val PAGE_SIZE = 10000
        const val SHARED_PREFS_NAME = "explore"
        const val PREFS_LAST_SYNC_KEY = "last_sync"
        const val PREFS_SYNC_IN_PROGRESS_KEY = "sync_in_progress"
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
        fun atmDao(): AtmDao
        fun merchantDao(): MerchantDao
        fun exploreRepository(): ExploreRepository
        fun analytics(): AnalyticsService
    }

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(appContext, ExploreSyncWorkerEntryPoint::class.java)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        log.info("Sync Explore Dash started")

        var atmDataSizeDB = 0
        var merchantDataSizeDB = 0
        var lastSync = 0L
        var lastDataUpdate = 0L
        try {
            lastSync = preferences.getLong(PREFS_LAST_SYNC_KEY, 0)

            exploreRepository.initMetadata()
            lastDataUpdate = exploreRepository.getLastUpdate()

            if (lastSync >= lastDataUpdate) {
                log.info("Data timestamp $lastSync, nothing to sync (${Date(lastSync)})")
                return@withContext Result.success()
            }

            val atmDao = entryPoint.atmDao()
            val merchantDao = entryPoint.merchantDao()

            val syncInProgress = preferences.getBoolean(PREFS_SYNC_IN_PROGRESS_KEY, false)
            if (!syncInProgress) {
                preferences.edit().putBoolean(PREFS_SYNC_IN_PROGRESS_KEY, true).apply()
                atmDao.deleteAll()
                merchantDao.deleteAll()
            }

            exploreRepository.initData(true)

            val tableSyncWatch = Stopwatch.createStarted()

            atmDataSizeDB = atmDao.getCount()
            val atmDataSize = exploreRepository.getAtmDataSize()
            if (atmDataSizeDB < atmDataSize) {
                val atmCache = mutableListOf<Atm>()
                exploreRepository.getAtmData(atmDataSizeDB).collect {
                    atmCache.add(it)
                }
                atmDao.save(atmCache)
                tableSyncWatch.stop()
                log.info("ATM sync took $tableSyncWatch (${atmCache.size} records)")
                atmCache.clear()
            }

            // skip Atm records if needed
            exploreRepository.getAtmData(atmDataSizeDB).collect()

            tableSyncWatch.reset()
            tableSyncWatch.start()

            merchantDataSizeDB = merchantDao.getCount()
            val merchantDataSize = exploreRepository.getMerchantDataSize()

            if (merchantDataSizeDB < merchantDataSize) {

                if (merchantDataSizeDB > 0) {
                    log.info("MERCHANT data partially synced ($merchantDataSizeDB of $merchantDataSize), continuing")
                } else {
                    log.info("MERCHANT data empty, fresh sync")
                }
                val merchantCache = mutableListOf<Merchant>()
                var counter = 0
                exploreRepository.getMerchantData(merchantDataSizeDB).collect {
                    counter++
                    merchantCache.add(it)
                    if (merchantCache.size == PAGE_SIZE) {
                        merchantDao.save(merchantCache)
                        merchantCache.clear()
                        log.info("MERCHANT $counter of ${merchantDataSize - merchantDataSizeDB} sync took $tableSyncWatch")
                    }
                }
                if (merchantCache.size > 0) {
                    merchantDao.save(merchantCache)
                    merchantCache.clear()
                }
                tableSyncWatch.stop()
                log.info("MERCHANT sync took $tableSyncWatch ($counter records)")
            }

            preferences.edit().apply {
                putBoolean(PREFS_SYNC_IN_PROGRESS_KEY, false)
                putLong(PREFS_LAST_SYNC_KEY, lastDataUpdate)
            }.apply()

            log.info("Sync Explore Dash finished")

            return@withContext Result.success()

        } catch (ex: Exception) {
            analytics.logError(ex, "syncing from $atmDataSizeDB, $merchantDataSizeDB ($lastSync/$lastDataUpdate)")
            log.info("Sync Explore Dash not fully finished: ${ex.message}", ex)
            return@withContext Result.failure()
        }

    }
}
