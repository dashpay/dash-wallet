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
import org.dash.wallet.features.exploredash.data.MerchantDao
import org.dash.wallet.features.exploredash.repository.DASH_DIRECT_TABLE
import org.dash.wallet.features.exploredash.repository.MERCHANT_TABLE
import org.dash.wallet.features.exploredash.repository.MerchantRepository
import org.slf4j.LoggerFactory
import kotlin.math.ceil

private const val PAGE_SIZE = 10000
private val log = LoggerFactory.getLogger(ExploreSyncWorker::class.java)

class ExploreSyncWorker constructor(val appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ExploreSyncWorkerEntryPoint {
        fun merchantDao(): MerchantDao
        fun merchantRepository(): MerchantRepository
    }

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(appContext, ExploreSyncWorkerEntryPoint::class.java)
    }

    override suspend fun doWork(): Result {
        log.info("Sync Explore Dash started")
        syncTable(MERCHANT_TABLE)
        syncTable(DASH_DIRECT_TABLE)
//        syncTable(ATM_TABLE)
        log.info("Sync Explore Dash finished")
        return Result.success()
    }

    private suspend fun syncTable(tableName: String) {
        val merchantRepository = entryPoint.merchantRepository()
        val merchantDao = entryPoint.merchantDao()

        val dataSize = merchantRepository.getDataSize(tableName)
        val totalPages = ceil(dataSize.toDouble() / PAGE_SIZE).toInt()
        log.info("$tableName $dataSize records in $totalPages chunks")
        val tableSyncWatch = Stopwatch.createStarted()
        for (page in 0 until totalPages) {
            val startAt = page * PAGE_SIZE
            val endAt = startAt + PAGE_SIZE
            log.info("$tableName chunk ${page + 1} of $totalPages ($startAt to $endAt)")
            val data = merchantRepository.get(tableName, startAt, endAt)
            merchantDao.save(data)
        }
        tableSyncWatch.stop()
        log.info("$tableName sync took $tableSyncWatch")
    }
}
