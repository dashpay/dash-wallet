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
import com.google.firebase.FirebaseException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.data.AtmDao
import org.dash.wallet.features.exploredash.data.BaseDao
import org.dash.wallet.features.exploredash.data.MerchantDao
import org.dash.wallet.features.exploredash.data.model.Atm
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.SearchResult
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.dash.wallet.features.exploredash.repository.FirebaseExploreDatabase.Tables.ATM_TABLE
import org.dash.wallet.features.exploredash.repository.FirebaseExploreDatabase.Tables.DASH_DIRECT_TABLE
import org.dash.wallet.features.exploredash.repository.FirebaseExploreDatabase.Tables.DCG_MERCHANT_TABLE
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.math.ceil

class ExploreSyncWorker constructor(val appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private val log = LoggerFactory.getLogger(ExploreSyncWorker::class.java)
        private const val PAGE_SIZE = 2000
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

    override suspend fun doWork(): Result {
        log.info("Sync Explore Dash started")
        val lastSync = preferences.getLong(PREFS_LAST_SYNC_KEY, 0)

        try {
            val lastDataUpdate = exploreRepository.getLastUpdate()

            if (lastSync >= lastDataUpdate) {
                log.info("Data timestamp $lastSync, nothing to sync (${Date(lastSync)})")
                return Result.success()
            }

            val allSynced = maybeSyncTable(DCG_MERCHANT_TABLE, "DCG", Merchant::class.java, entryPoint.merchantDao())
                         && maybeSyncTable(ATM_TABLE, "CoinFlip", Atm::class.java, entryPoint.atmDao())
                         && maybeSyncTable(DASH_DIRECT_TABLE, "DashDirect", Merchant::class.java, entryPoint.merchantDao())

            return if (allSynced) {
                preferences.edit().putLong(PREFS_LAST_SYNC_KEY, Calendar.getInstance().timeInMillis).apply()
                log.info("Sync Explore Dash finished")
                Result.success()
            } else {
                log.info("Sync Explore Dash not fully finished")
                Result.retry()
            }
        } catch (ex: Exception) {
            log.info("Sync Explore Dash error: ${ex.message}")
            analytics.logError(ex)
            return Result.retry()
        }
    }

    private suspend fun <T: SearchResult> maybeSyncTable(
        tableName: String,
        source: String,
        valueType: Class<T>,
        dao: BaseDao<T>
    ): Boolean {
        return try {
            val prefsLastSyncKey = "${PREFS_LAST_SYNC_KEY}_$tableName"
            val lastSync = preferences.getLong(prefsLastSyncKey, 0)
            val lastDataUpdate = exploreRepository.getLastUpdate(tableName)

            if (lastSync < lastDataUpdate) {
                log.info("Local $tableName data timestamp\t$lastSync (${Date(lastSync)})")
                log.info("Remote $tableName data timestamp\t$lastDataUpdate (${Date(lastDataUpdate)})")
                dao.deleteAll(source)
                syncTable(tableName, valueType, dao)
                preferences.edit().putLong(prefsLastSyncKey, lastDataUpdate).apply()
                log.info("Sync $tableName finished")
            } else {
                log.info("Data $tableName timestamp $lastSync, nothing to sync (${Date(lastSync)})")
            }
            true
        } catch (ex: Exception) {
            log.error("Error while syncing ${tableName}: ${ex.message}")
            analytics.logError(ex, tableName)
            false
        }
    }

    private suspend fun <T: SearchResult> syncTable(
        tableName: String,
        valueType: Class<T>,
        dao: BaseDao<T>
    ) {
        val dataSize = exploreRepository.getDataSize(tableName)
        val totalPages = ceil(dataSize.toDouble() / PAGE_SIZE).toInt()
        log.info("$tableName $dataSize records in $totalPages chunks")
        val tableSyncWatch = Stopwatch.createStarted()
        var synced = 0

        for (page in 0 until totalPages) {
            val startAt = page * PAGE_SIZE
            val endAt = startAt + PAGE_SIZE
            log.info("$tableName chunk ${page + 1} of $totalPages ($startAt to $endAt)")
            val data = exploreRepository.get(tableName, startAt, endAt, valueType)
            synced += data.size
            dao.save(data)
        }

        if (synced != dataSize) {
            throw FirebaseException("sync is broken, expected $dataSize records, got $synced")
        }

        tableSyncWatch.stop()
        log.info("$tableName sync took $tableSyncWatch")
    }
}
