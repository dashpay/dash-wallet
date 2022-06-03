/*
 * Copyright (c) 2022. Dash Core Group.
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

package de.schildbach.wallet.service

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.TransactionMetadataDao
import de.schildbach.wallet.util.value
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.data.*
import org.dash.wallet.common.services.TransactionMetadataService
import org.dash.wallet.common.transactions.TaxCategory
import org.dash.wallet.common.transactions.TransactionCategory
import org.dash.wallet.common.transactions.TransactionMetadata
import org.slf4j.LoggerFactory
import javax.inject.Inject

class WalletTransactionMetadataService @Inject constructor(
    private val transactionMetadataDao: TransactionMetadataDao,
    private val walletApplication: WalletApplication
) : TransactionMetadataService {

    companion object {
        val log = LoggerFactory.getLogger(WalletTransactionMetadataService::class.java)
    }

    private suspend fun insertTransactionMetadata(txId: Sha256Hash) {
        val walletTx = walletApplication.wallet.getTransaction(txId)
        Context.propagate(walletApplication.wallet.context)
        walletTx?.run {
            val txValue = value ?: Coin.ZERO
            val sentTime = confidence.sentAt?.time
            var updateTime = updateTime.time
            if (sentTime != null && sentTime < updateTime) {
                updateTime = sentTime
            }

            val metadata = TransactionMetadata(
                txId,
                updateTime,
                txValue,
                TransactionCategory.fromTransaction(type, txValue),
                taxCategory = null,
                walletTx.exchangeRate?.fiat?.currencyCode,
                walletTx.exchangeRate?.fiat?.let {
                    MonetaryFormat.FIAT.noCode().format(it).toString()
                }
            )
            transactionMetadataDao.insert(metadata)
            log.info("txmetadata: inserting $metadata")
        }
    }

    private suspend fun updateAndInsertIfNotExist(txId: Sha256Hash, update: suspend () -> Unit) {
        if (transactionMetadataDao.exists(txId)) {
            update()
        } else {
            insertTransactionMetadata(txId)
            update()
        }
    }

    override suspend fun importTransactionMetadata(txId: Sha256Hash) {
        updateAndInsertIfNotExist(txId) { }
    }

    override suspend fun setTransactionMetadata(transactionMetadata: TransactionMetadata) {
        transactionMetadataDao.insert(transactionMetadata)
    }

    override suspend fun setTransactionTaxCategory(txid: Sha256Hash, taxCategory: TaxCategory) {
        updateAndInsertIfNotExist(txid) {
            transactionMetadataDao.updateTaxCategory(txid, taxCategory)
        }
    }

    override suspend fun setTransactionType(txid: Sha256Hash, type: Int) {
        TODO("Not yet implemented")
    }

    override suspend fun setTransactionExchangeRate(txid: Sha256Hash, exchangeRate: ExchangeRate) {
        TODO("Not yet implemented")
    }

    override suspend fun setTransactionMemo(txid: Sha256Hash, memo: String) {
        TODO("Not yet implemented")
    }

    override suspend fun getTransactionMetadata(txid: Sha256Hash): TransactionMetadata? {
        val transactionMetadata = transactionMetadataDao.load(txid)
        if (transactionMetadata == null) {
            insertTransactionMetadata(txid)
        }
        return transactionMetadataDao.load(txid)
    }

    override suspend fun getAllTransactionMetadata(): List<TransactionMetadata> {
        return transactionMetadataDao.load()
    }


    override fun observeTransactionMetadata(txid: Sha256Hash): Flow<TransactionMetadata?> {
        return transactionMetadataDao.observe(txid)
    }
}