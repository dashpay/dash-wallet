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
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.repository.ExploreDataSyncStatus
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import org.dash.wallet.features.exploredash.utils.PiggyCardsConstants
import org.dash.wallet.features.exploredash.utils.PiggyCardsConstants.PIGGY_CARDS_TEST_FIXED_MERCHANT_ID
import org.dash.wallet.features.exploredash.utils.PiggyCardsConstants.PIGGY_CARDS_TEST_FLEXIBLE_MERCHANT_ID
import org.dash.wallet.features.exploredash.utils.PiggyCardsConstants.SUPPORT_PIGGY_CARDS_TEST_MERCHANT
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

        try {
            syncStatus.setSyncProgress(0.0)

            val timeInMillis = measureTimeMillis {
                val updateFile = exploreRepository.getUpdateFile()
                val checkTestDB = inputData.getBoolean(USE_TEST_DB_KEY, false)
                val hasPreloaded = exploreRepository.preloadFromAssetsInto(updateFile, checkTestDB)

                log.info(
                    "local data timestamp: ${databasePrefs.localDbTimestamp} (${Date(databasePrefs.localDbTimestamp)})"
                )

                remoteDataTimestamp = exploreRepository.getRemoteTimestamp()
                log.info("remote data timestamp: $remoteDataTimestamp (${Date(remoteDataTimestamp)})")

                if (hasPreloaded) {
                    preloadedDbTimestamp = exploreRepository.getTimestamp(updateFile)
                    log.info("preloaded data timestamp: $preloadedDbTimestamp (${Date(preloadedDbTimestamp)})")

                    val forceLoad = (
                        databasePrefs.localDbTimestamp != remoteDataTimestamp &&
                            databasePrefs.localDbTimestamp != preloadedDbTimestamp
                        )
                    if (databasePrefs.localDbTimestamp == 0L ||
                        databasePrefs.localDbTimestamp < preloadedDbTimestamp ||
                        forceLoad
                    ) {
                        // force data preloading for fresh installs
                        // and a newer preloaded DB
                        ExploreDatabase.updateDatabase(appContext, exploreRepository)
                        databasePrefs = databasePrefs.copy(preloadedOnTimestamp = preloadedDbTimestamp)
                        exploreConfig.saveExploreDatabasePrefs(databasePrefs)
                    }
                }

                if (!updateFile.delete()) {
                    log.error("unable to delete " + updateFile.absolutePath)
                }

                if (databasePrefs.localDbTimestamp >= remoteDataTimestamp) {
                    log.info("explore db is up to date, nothing to sync")
                    syncStatus.setSyncProgress(100.0)
                    databasePrefs = databasePrefs.copy(failedSyncAttempts = 0)

                    if (databasePrefs.lastSyncTimestamp <= 0) {
                        // Some devices might have this as 0 due to the bug. Need to update
                        // manually
                        // TODO: this can be removed after some time
                        databasePrefs = databasePrefs.copy(lastSyncTimestamp = remoteDataTimestamp)
                    }

                    exploreConfig.saveExploreDatabasePrefs(databasePrefs)

                    addPiggyCardsTestMerchantIfNeeded()
                    return@withContext Result.success()
                }
                syncStatus.setSyncProgress(10.0)

                exploreRepository.download()

                syncStatus.setSyncProgress(80.0)

                ExploreDatabase.updateDatabase(appContext, exploreRepository)
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
            exploreConfig.saveExploreDatabasePrefs(
                databasePrefs.copy(failedSyncAttempts = databasePrefs.failedSyncAttempts + 1)
            )
            return@withContext Result.failure()
        }

        databasePrefs = exploreConfig.exploreDatabasePrefs.first()
        exploreConfig.saveExploreDatabasePrefs(databasePrefs.copy(failedSyncAttempts = 0))
        return@withContext Result.success()
    }

    private suspend fun addPiggyCardsTestMerchantIfNeeded() {
        val database = ExploreDatabase.getAppDatabase(appContext, exploreConfig)
        val merchantDao = database.merchantDao()
        val giftCardProviderDao = database.giftCardProviderDao()

        //if (!SUPPORT_PIGGY_CARDS_TEST_MERCHANT) {
            val testMerchantIds = PiggyCardsConstants.TEST_CARDS.keys.map { it }
            val deletedProviders = giftCardProviderDao.deleteByMerchantIds(testMerchantIds)
            val deletedMerchants = merchantDao.deleteByMerchantIds(testMerchantIds)
            log.info(
                "removed existing PiggyCards test data: $deletedMerchants merchant(s), " +
                        "$deletedProviders provider(s)"
            )
        //    return
        //}

        try {
            if (merchantDao.getMerchantById(PIGGY_CARDS_TEST_FIXED_MERCHANT_ID) != null) {
                log.info("PiggyCards test merchant(s) already exist, skipping")
                return
            }

            log.info("adding PiggyCards test merchant")

            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val testFixedMerchant = Merchant(
                merchantId = PIGGY_CARDS_TEST_FIXED_MERCHANT_ID,
                paymentMethod = "gift card",
                redeemType = "online",
                savingsPercentage = -2500,
                denominationsType = "Fixed",
                addDate = now,
                updateDate = now
            ).apply {
                name = "Piggy Cards Test Merchant"
                active = true
                source = "PiggyCards"
                sourceId = "177"
                logoLocation = "https://piggy.cards/image/catalog/piggycards/logo2023_mobile.png"
                type = "online"
                territory = "MA"
                city = "Boston"
                website = "https://piggy.cards"
            }

            val testFlexibleMerchant = Merchant(
                merchantId = PIGGY_CARDS_TEST_FLEXIBLE_MERCHANT_ID,
                paymentMethod = "gift card",
                redeemType = "online",
                savingsPercentage = -2500,
                denominationsType = "min-max",
                addDate = now,
                updateDate = now
            ).apply {
                name = "Piggy Cards Flexible Test Merchant"
                active = true
                source = "PiggyCards"
                sourceId = "177"
                logoLocation = "https://piggy.cards/image/catalog/piggycards/logo2023_mobile.png"
                type = "online"
                territory = "MA"
                city = "Boston"
                website = "https://piggy.cards"
            }

            val homeDepotTestMerchant = Merchant(
                merchantId = PiggyCardsConstants.HOME_DEPOT_TEST_FLEXIBLE_MERCHANT_ID,
                paymentMethod = "gift card",
                redeemType = "online",
                savingsPercentage = 100,
                denominationsType = "min-max",
                addDate = now,
                updateDate = now
            ).apply {
                name = "Home Depot [Flexible]"
                active = true
                source = "PiggyCards"
                sourceId = "74"
                logoLocation = "https://piggy.cards/image/catalog/piggycards/Home_Depot_Copy.jpg"
                type = "online"
                website = "https://www.homedepot.com"
            }

            val appleTestMerchant = Merchant(
                merchantId = PiggyCardsConstants.APPLE_TEST_FLEXIBLE_MERCHANT_ID,
                paymentMethod = "gift card",
                redeemType = "online",
                savingsPercentage = 100,
                denominationsType = "min-max",
                addDate = now,
                updateDate = now
            ).apply {
                name = "Apple [Flexible]"
                active = true
                source = "PiggyCards"
                sourceId = "13"
                logoLocation = "https://piggy.cards/image/catalog/incenti/8aaa3d5d-logo.png"
                type = "online"
                website = "https://www.apple.com"
            }

            val dominosTestMerchant = Merchant(
                merchantId = PiggyCardsConstants.APPLE_TEST_FLEXIBLE_MERCHANT_ID,
                paymentMethod = "gift card",
                redeemType = "online",
                savingsPercentage = 100,
                denominationsType = "min-max",
                addDate = now,
                updateDate = now
            ).apply {
                name = "Dominos [Flexible]"
                active = true
                source = "PiggyCards"
                sourceId = "45"
                logoLocation = "https://piggy.cards/image/catalog/incenti/68ea431c-logo.png"
                type = "online"
                website = "https://www.apple.com"
            }

            merchantDao.save(
                listOf(
                    testFlexibleMerchant,
                    testFixedMerchant,
                    homeDepotTestMerchant,
                    appleTestMerchant,
                    dominosTestMerchant
                )
            )

            giftCardProviderDao.insert(
                GiftCardProvider(
                    merchantId = PIGGY_CARDS_TEST_FIXED_MERCHANT_ID,
                    provider = "PiggyCards",
                    redeemType = "online",
                    savingsPercentage = 100,
                    active = true,
                    denominationsType = "fixed",
                    sourceId = "177"
                )
            )

            giftCardProviderDao.insert(
                GiftCardProvider(
                    merchantId = PIGGY_CARDS_TEST_FLEXIBLE_MERCHANT_ID,
                    provider = "PiggyCards",
                    redeemType = "online",
                    savingsPercentage = -250,
                    active = true,
                    denominationsType = "min-max",
                    sourceId = "177"
                )
            )

            giftCardProviderDao.insert(
                GiftCardProvider(
                    merchantId = PiggyCardsConstants.HOME_DEPOT_TEST_FLEXIBLE_MERCHANT_ID,
                    provider = "PiggyCards",
                    redeemType = "online",
                    savingsPercentage = -250,
                    active = true,
                    denominationsType = "min-max",
                    sourceId = "74"
                )
            )

            giftCardProviderDao.insert(
                GiftCardProvider(
                    merchantId = PiggyCardsConstants.APPLE_TEST_FLEXIBLE_MERCHANT_ID,
                    provider = "PiggyCards",
                    redeemType = "online",
                    savingsPercentage = 100,
                    active = true,
                    denominationsType = "min-max",
                    sourceId = "13"
                )
            )

            giftCardProviderDao.insert(
                GiftCardProvider(
                    merchantId = PiggyCardsConstants.DOMINOS_TEST_FLEXIBLE_MERCHANT_ID,
                    provider = "PiggyCards",
                    redeemType = "online",
                    savingsPercentage = 150,
                    active = true,
                    denominationsType = "min-max",
                    sourceId = "45"
                )
            )

            log.info("PiggyCards test merchant added successfully")
        } catch (ex: Exception) {
            log.error("error adding PiggyCards test merchant", ex)
        }
    }
}
