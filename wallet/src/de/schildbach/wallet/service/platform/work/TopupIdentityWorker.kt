/*
 * Copyright 2024 Dash Core Group
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
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.database.dao.TopUpsDao
import de.schildbach.wallet.database.entity.TopUp
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.platform.PlatformBroadcastService
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.service.work.BaseWorker
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory

@HiltWorker
class TopupIdentityWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val analytics: AnalyticsService,
    private val platformBroadcastService: PlatformBroadcastService,
    private val topUpRepository: TopUpRepository,
    private val walletDataProvider: WalletDataProvider,
    private val platformRepo: PlatformRepo,
    private val coinJoinConfig: CoinJoinConfig,
    private val topUpsDao: TopUpsDao
) : BaseWorker(context, parameters) {
    companion object {
        private val log = LoggerFactory.getLogger(TopupIdentityWorker::class.java)
        const val KEY_PASSWORD = "TopupIdentityWorker.PASSWORD"
        const val KEY_IDENTITY = "TopupIdentityWorker.IDENTITY"
        const val KEY_TOPUP_TX = "TopupIdentityWorker.TOPUP_TX"
        const val KEY_VALUE = "TopupIdentityWorker.VALUE"
        const val KEY_BALANCE = "TopupIdentityWorker.BALANCE"
    }

    override suspend fun doWorkWithBaseProgress(): Result {
        val password = inputData.getString(KEY_PASSWORD)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_PASSWORD parameter"))
        val identity = inputData.getString(KEY_IDENTITY)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_IDENTITY parameter"))

        val topupTxId = inputData.getString(KEY_TOPUP_TX)?.let { Sha256Hash.wrap(it) }
        val authGroupExtension = walletDataProvider.wallet!!.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
        val topupTx = authGroupExtension.topupFundingTransactions.find { it.txId == topupTxId } ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_TOPUP_TX parameter"))

        val encryptionKey: KeyParameter
        try {
            encryptionKey = walletDataProvider.wallet!!.keyCrypter!!.deriveKey(password)
        } catch (ex: KeyCrypterException) {
            analytics.logError(ex, "Topup Identity: failed to derive encryption key")
            val msg = formatExceptionMessage("derive encryption key", ex)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to msg))
        }

        return try {
            org.bitcoinj.core.Context.propagate(walletDataProvider.wallet!!.context)
            val existingTopup = topUpsDao.getByTxId(topupTx.txId)
            if (existingTopup != null && existingTopup.used()) {
                Result.success(
                    workDataOf(
                        KEY_IDENTITY to identity,
                        KEY_TOPUP_TX to existingTopup.txId.toString(),
                        KEY_BALANCE to platformRepo.getIdentityBalance()?.balance
                    )
                )
            } else {
                val topupEntry = TopUp(toUserId = identity, workId = id.toString(), txId = topupTx.txId)
                topUpsDao.insert(topupEntry)
                topUpRepository.topUpIdentity(
                    topupTx,
                    encryptionKey
                )
                Result.success(
                    workDataOf(
                        KEY_IDENTITY to identity,
                        KEY_TOPUP_TX to topupTx.txId.toString(),
                        KEY_BALANCE to platformRepo.blockchainIdentity.creditBalance.value * 1000
                    )
                )
            }
        } catch (ex: Exception) {
            analytics.logError(ex, "Topup Identity: failed to topup identity")
            val args = when (ex) {
                is InsufficientMoneyException -> arrayOf(ex.missing.toString())
                else -> arrayOf()
            }
            Result.failure(
                workDataOf(
                    KEY_IDENTITY to identity,
                    KEY_TOPUP_TX to topupTx.txId.toString(),
                    KEY_EXCEPTION to ex.javaClass.simpleName,
                    KEY_ERROR_MESSAGE to formatExceptionMessage("topup exception:", ex),
                    KEY_EXCEPTION_ARGS to args
                )
            )
        }
    }
}