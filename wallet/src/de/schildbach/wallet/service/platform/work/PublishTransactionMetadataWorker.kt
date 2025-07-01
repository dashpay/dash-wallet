/*
 * Copyright 2025 Dash Core Group
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

package de.schildbach.wallet.service.platform.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.schildbach.wallet.service.platform.PlatformSynchronizationService
import de.schildbach.wallet.service.work.BaseWorker
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.Companion.TRANSACTION_METADATA_LAST_PAST_SAVE
import org.bitcoinj.core.InsufficientMoneyException
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory

@HiltWorker
class PublishTransactionMetadataWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val analytics: AnalyticsService,
    private val platformSynchronizationService: PlatformSynchronizationService,
    private val walletDataProvider: WalletDataProvider,
    private val dashPayConfig: DashPayConfig
) : BaseWorker(context, parameters) {
    companion object {
        private val log = LoggerFactory.getLogger(PublishTransactionMetadataWorker::class.java)
        const val KEY_PASSWORD = "PublishTransactionMetadataWorker.PASSWORD"
    }

    override suspend fun doWorkWithBaseProgress(): Result {

        return try {
            org.bitcoinj.core.Context.propagate(walletDataProvider.wallet!!.context)
            val now = System.currentTimeMillis()
            setProgress(0)
            val saveInfo = platformSynchronizationService.publishPastTxMetadata() { progress ->
                setProgress(progress)
            }
            if (saveInfo.itemsSaved == saveInfo.itemsToSave) {
                dashPayConfig.set(TRANSACTION_METADATA_LAST_PAST_SAVE, now)
                log.info("publish txmetadata successful: $saveInfo")
                Result.success(
                    workDataOf(
                        //KEY_IDENTITY to identity,
                    )
                )
            } else {
                log.info("publish txmetadata failure: $saveInfo")
                Result.failure(
                    workDataOf(
                        KEY_EXCEPTION to "only saved ${saveInfo.itemsSaved} of ${saveInfo.itemsToSave}",
                    )
                )
            }
        } catch (ex: Exception) {
            analytics.logError(ex, ": failed to txmetadata identity")
            val args = when (ex) {
                is InsufficientMoneyException -> arrayOf(ex.missing.toString())
                else -> arrayOf()
            }
            Result.failure(
                workDataOf(
                    KEY_EXCEPTION to ex.javaClass.simpleName,
                    KEY_ERROR_MESSAGE to formatExceptionMessage("publish txmetadata exception:", ex),
                    KEY_EXCEPTION_ARGS to args
                )
            )
        }
    }
}