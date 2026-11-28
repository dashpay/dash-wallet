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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.repository.ExploreDataSyncStatus
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import org.dash.wallet.features.exploredash.utils.PiggyCardsConstants.SUPPORT_PIGGY_CARDS_TEST_MERCHANT
import org.dash.wallet.features.exploredash.utils.PiggyCardsTestMerchants
import org.dash.wallet.features.exploredash.utils.PiggyCardsTestMerchants.PIGGY_CARDS_TEST_FIXED_MERCHANT_ID
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

@HiltWorker
class ExploreSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val analytics: AnalyticsService,
    private val exploreRepository: ExploreRepository,
    private val syncStatus: ExploreDataSyncStatus,
    private val exploreConfig: ExploreConfig
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val USE_TEST_DB_KEY = "use_test_database"
        private val log = LoggerFactory.getLogger(ExploreSyncWorker::class.java)

        fun run(@ApplicationContext context: Context, isMainNet: Boolean) {
            val inputData = Data.Builder().putBoolean(USE_TEST_DB_KEY, !isMainNet)
            val syncDataWorkRequest =
                OneTimeWorkRequest.Builder(ExploreSyncWorker::class.java)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS,
                        TimeUnit.MILLISECONDS
                    )
                    .setInputData(inputData.build())
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork("Sync Explore Data", ExistingWorkPolicy.KEEP, syncDataWorkRequest)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        log.info("sync explore db started")

        var remoteDataTimestamp = 0L
        var preloadedDbTimestamp = 0L
        var databasePrefs = exploreConfig.exploreDatabasePrefs.first()
        var isCatalogReplaced = false

        try {
            // Opening checks the file on disk and resets the timestamp of one that is empty or
            // from an older schema, so the timestamp read next describes the data that is there.
            ExploreDatabase.getAppDatabase(appContext, exploreConfig)
            databasePrefs = exploreConfig.exploreDatabasePrefs.first()
            syncStatus.setSyncProgress(0.0)

            val timeInMillis = measureTimeMillis {
                val updateFile = exploreRepository.getUpdateFile()
                val checkTestDB = inputData.getBoolean(USE_TEST_DB_KEY, false)
                val hasPreloaded = exploreRepository.preloadFromAssetsInto(updateFile, checkTestDB)

                var localDbTimestamp = databasePrefs.localDbTimestampFor(ExploreDatabase.VERSION)
                log.info("local data timestamp: $localDbTimestamp (${Date(localDbTimestamp)})")

                remoteDataTimestamp = exploreRepository.getRemoteTimestamp()
                log.info("remote data timestamp: $remoteDataTimestamp (${Date(remoteDataTimestamp)})")

                if (hasPreloaded) {
                    preloadedDbTimestamp = exploreRepository.getTimestamp(updateFile)
                    log.info("preloaded data timestamp: $preloadedDbTimestamp (${Date(preloadedDbTimestamp)})")

                    // Only take the asset path when the asset can actually improve on
                    // local state: fresh install (local == 0) or asset is newer. The
                    // "local doesn't match remote" case is what the server download
                    // below is for — loading a stale asset over a newer local DB just
                    // forces migrations on an old user_version and risks crashing.
                    if (localDbTimestamp == 0L || localDbTimestamp < preloadedDbTimestamp) {
                        ExploreDatabase.updateDatabase(appContext, exploreRepository, exploreConfig) {
                            isCatalogReplaced = true
                        }
                        // The snapshot from the start of doWork() is stale by now: finalizeUpdate
                        // has written the new timestamp and schema version.
                        exploreConfig.updateExploreDatabasePrefs {
                            it.copy(preloadedOnTimestamp = preloadedDbTimestamp)
                        }
                        databasePrefs = exploreConfig.exploreDatabasePrefs.first()
                        localDbTimestamp = databasePrefs.localDbTimestampFor(ExploreDatabase.VERSION)
                    }
                }

                if (!updateFile.delete()) {
                    log.error("unable to delete " + updateFile.absolutePath)
                }

                if (localDbTimestamp >= remoteDataTimestamp) {
                    log.info("explore db is up to date, nothing to sync")
                    syncStatus.setSyncProgress(100.0)
                    exploreConfig.updateExploreDatabasePrefs { prefs ->
                        // Some devices might have lastSyncTimestamp as 0 due to the bug. Need to
                        // update manually
                        // TODO: this can be removed after some time
                        prefs.copy(
                            failedSyncAttempts = 0,
                            lastSyncTimestamp = prefs.lastSyncTimestamp.takeIf { it > 0 } ?: remoteDataTimestamp
                        )
                    }

                    addPiggyCardsTestMerchantIfNeeded()
                    return@withContext Result.success()
                }
                syncStatus.setSyncProgress(10.0)

                exploreRepository.download()

                syncStatus.setSyncProgress(80.0)

                ExploreDatabase.updateDatabase(appContext, exploreRepository, exploreConfig) {
                    isCatalogReplaced = true
                }
            }

            log.info("sync explore db finished, took $timeInMillis ms")

            syncStatus.setSyncProgress(100.0)
            addPiggyCardsTestMerchantIfNeeded()
        } catch (ex: FirebaseNetworkException) {
            log.warn("sync explore no network", ex)
            syncStatus.setSyncError(ex)
            return@withContext Result.failure()
        } catch (ex: Exception) {
            analytics.logError(
                ex,
                "local: ${databasePrefs.localDbTimestamp}, " +
                    "preloaded: $preloadedDbTimestamp, remote: $remoteDataTimestamp"
            )
            log.error("sync explore db crashed ${ex.message}", ex)
            syncStatus.setSyncError(ex)
            exploreConfig.updateExploreDatabasePrefs { it.copy(failedSyncAttempts = it.failedSyncAttempts + 1) }
            return@withContext Result.failure()
        } finally {
            // Told once, after the last swap of the run: readers that reload between the asset
            // swap and the download swap would stay on the file the second swap deletes.
            if (isCatalogReplaced) {
                syncStatus.setCatalogReplaced()
            }
        }

        exploreConfig.updateExploreDatabasePrefs { it.copy(failedSyncAttempts = 0) }
        return@withContext Result.success()
    }

    private suspend fun addPiggyCardsTestMerchantIfNeeded() {
        val database = ExploreDatabase.getAppDatabase(appContext, exploreConfig)
        val merchantDao = database.merchantDao()
        val giftCardProviderDao = database.giftCardProviderDao()

        val testMerchantIds = PiggyCardsTestMerchants.ALL.map { it.merchantId }
        if (merchantDao.getMerchantById(PIGGY_CARDS_TEST_FIXED_MERCHANT_ID) != null) {
            val deletedProviders = giftCardProviderDao.deleteByMerchantIds(testMerchantIds)
            val deletedMerchants = merchantDao.deleteByMerchantIds(testMerchantIds)
            log.info(
                "removed existing PiggyCards test data: $deletedMerchants merchant(s), " +
                    "$deletedProviders provider(s)"
            )
        }

        if (!SUPPORT_PIGGY_CARDS_TEST_MERCHANT) {
            return
        }

        try {
            log.info("adding PiggyCards test merchants")

            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

            val merchants = PiggyCardsTestMerchants.ALL.map { data ->
                Merchant(
                    merchantId = data.merchantId,
                    paymentMethod = data.paymentMethod,
                    redeemType = data.redeemType,
                    savingsPercentage = data.providerSavingsPercentage,
                    denominationsType = data.providerDenominationsType,
                    addDate = now,
                    updateDate = now,
                    country = Locale.US.country,
                    currency = Constants.USD_CURRENCY
                ).apply {
                    name = data.name
                    active = true
                    source = data.provider
                    sourceId = data.sourceId
                    logoLocation = data.logoLocation
                    type = data.type
                    territory = data.territory
                    city = data.city
                    website = data.website
                }
            }
            merchantDao.save(merchants)

            PiggyCardsTestMerchants.ALL.forEach { data ->
                giftCardProviderDao.insert(
                    GiftCardProvider(
                        merchantId = data.merchantId,
                        provider = data.provider,
                        redeemType = data.redeemType,
                        savingsPercentage = data.providerSavingsPercentage,
                        active = true,
                        denominationsType = data.providerDenominationsType,
                        sourceId = data.sourceId,
                        currency = Constants.USD_CURRENCY
                    )
                )
            }

            log.info("PiggyCards test merchant added successfully")
        } catch (ex: Exception) {
            log.error("error adding PiggyCards test merchant", ex)
        }
    }
}
