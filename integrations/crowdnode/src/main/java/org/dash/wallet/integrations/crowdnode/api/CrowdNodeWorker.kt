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

package org.dash.wallet.integrations.crowdnode.api

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.bitcoinj.core.Address
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.crowdnode.R
import org.slf4j.LoggerFactory

@HiltWorker
class CrowdNodeWorker @AssistedInject constructor(
    @Assisted val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val crowdNodeApi: CrowdNodeApi,
    private val walletDataProvider: WalletDataProvider,
    private val notificationService: NotificationService,
    private val analytics: AnalyticsService
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        private val log = LoggerFactory.getLogger(CrowdNodeWorker::class.java)

        const val WORK_NAME = "CrowdNode.WORK"
        const val API_REQUEST = "CrowdNodeWorker.API_REQUEST"
        const val SIGNUP_CALL = "CrowdNodeWorker.SIGNUP"
        const val ACCOUNT_ADDRESS = "CrowdNodeWorker.ACCOUNT_ADDRESS"
    }

    override suspend fun doWork(): Result {
        val operation = inputData.getString(API_REQUEST)
        val accountAddress = inputData.getString(ACCOUNT_ADDRESS)
        log.info("CrowdNode work started, operation: $operation")

        try {
            if (!accountAddress.isNullOrEmpty()) {
                val address = Address.fromBase58(walletDataProvider.networkParameters, accountAddress)

                when (operation) {
                    SIGNUP_CALL -> {
                        val notification = notificationService.buildNotification(
                            appContext.getString(R.string.crowdnode_creating),
                            intent = crowdNodeApi.notificationIntent
                        )
                        log.info("calling setForeground")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            setForeground(
                                ForegroundInfo(
                                    operation.hashCode(),
                                    notification,
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                                )
                            )
                        } else {
                            setForeground(
                                ForegroundInfo(
                                    operation.hashCode(),
                                    notification
                                )
                            )
                        }
                        crowdNodeApi.signUp(address)
                    }
                }
            }

            return Result.success()
        } catch (ex: Exception) {
            analytics.logError(ex, "operation: $operation")
            log.error("CrowdNode work failed: ${ex.message}", ex)
            return Result.failure()
        }
    }
}
