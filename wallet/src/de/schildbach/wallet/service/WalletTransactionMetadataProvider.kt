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

import de.schildbach.wallet.data.AddressMetadataDao
import de.schildbach.wallet.data.TransactionMetadataChangeCacheDao
import de.schildbach.wallet.data.TransactionMetadataCacheItem
import de.schildbach.wallet.data.TransactionMetadataDao
import de.schildbach.wallet.data.TransactionMetadataDocumentDao
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.script.ScriptPattern
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.*
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.transactions.TransactionCategory
import org.dash.wallet.common.data.TransactionMetadata
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.Executors
import javax.inject.Inject

class WalletTransactionMetadataProvider @Inject constructor(
    private val transactionMetadataDao: TransactionMetadataDao,
    private val addressMetadataDao: AddressMetadataDao,
    private val walletData: WalletDataProvider,
    private val transactionMetadataChangeCacheDao: TransactionMetadataChangeCacheDao,
    private val transactionMetadataDocumentDao: TransactionMetadataDocumentDao
) : TransactionMetadataProvider {

    companion object {
        val log = LoggerFactory.getLogger(WalletTransactionMetadataProvider::class.java)
    }

    private val syncScope = CoroutineScope(
        Executors.newFixedThreadPool(5).asCoroutineDispatcher()
    )

    private suspend fun insertTransactionMetadata(txId: Sha256Hash, isSyncingPlatform: Boolean): TransactionMetadata? {
        val walletTx = walletData.wallet!!.getTransaction(txId)
        Context.propagate(walletData.wallet!!.context)
        walletTx?.run {
            val txValue = getValue(walletData.wallet!!) ?: Coin.ZERO
            val sentTime = confidence.sentAt?.time
            var updateTime = updateTime.time

            val isInternal = isEntirelySelf(this, walletData.wallet!!)

            // Search for items from platform
            var hasChanges = false
            val platformSentTimestamp = transactionMetadataDocumentDao.getSentTimestamp(txId)
            val platformMemo = transactionMetadataDocumentDao.getTransactionMemo(txId)
            val platformService = transactionMetadataDocumentDao.getTransactionService(txId)
            val platformTaxCategory = transactionMetadataDocumentDao.getTransactionTaxCategory(txId)
            val platformExchangeRate = transactionMetadataDocumentDao.getTransactionExchangeRate(txId)

            var rate: String? = null
            var code: String? = null

            if (platformSentTimestamp != null) {
                updateTime = platformSentTimestamp;
            } else if (sentTime != null && sentTime < updateTime) {
                updateTime = sentTime
                hasChanges = true
            }

            if (platformExchangeRate != null) {
                rate = platformExchangeRate.rate.toString()
                code = platformExchangeRate.currencyCode
                // if we are pulling from platform, then update the tx object
                exchangeRate = org.bitcoinj.utils.ExchangeRate(Fiat.parseFiat(code, rate))
            } else if (exchangeRate != null) {
                rate = exchangeRate?.fiat?.let { MonetaryFormat.FIAT.noCode().format(it).toString() }
                code = exchangeRate?.fiat?.currencyCode
                hasChanges = true
            }

            val myMemo = when {
                platformMemo != null -> { memo = platformMemo; platformMemo }
                memo != null -> { hasChanges = true; memo!! }
                else -> ""
            }

            val metadata = TransactionMetadata(
                txId,
                updateTime,
                txValue,
                TransactionCategory.fromTransaction(type, txValue, isInternal),
                taxCategory = platformTaxCategory?.let { TaxCategory.fromValue(it) },
                currencyCode = code,
                rate = rate,
                memo = myMemo,
                service = platformService
            )
            transactionMetadataDao.insert(metadata)
            // only add to the change cache if some metadata exists
            if (metadata.isNotEmpty() && !isSyncingPlatform && hasChanges) {
                transactionMetadataChangeCacheDao.insert(TransactionMetadataCacheItem(metadata))
            }
            log.info("txmetadata: inserting $metadata")

            return metadata
        }

        return null
    }

    private suspend fun updateAndInsertIfNotExist(
        txId: Sha256Hash,
        isSyncingPlatform: Boolean,
        update: suspend (TransactionMetadata) -> Unit
    ) {
        val existing = transactionMetadataDao.load(txId)

        if (existing != null) {
            log.info("txmetadata for $txId exists, only do update")
            update(existing)
        } else {
            log.info("txmetadata for $txId does not exist, perform insert, then update")
            insertTransactionMetadata(txId, isSyncingPlatform)?.let { update(it) }
        }
    }

    override suspend fun importTransactionMetadata(txId: Sha256Hash) {
        updateAndInsertIfNotExist(txId, false) { }
    }

    override suspend fun setTransactionMetadata(transactionMetadata: TransactionMetadata) {
        transactionMetadataDao.insert(transactionMetadata)
    }

    override suspend fun setTransactionTaxCategory(txId: Sha256Hash, taxCategory: TaxCategory, isSyncingPlatform: Boolean) {
        updateAndInsertIfNotExist(txId, isSyncingPlatform) {
            transactionMetadataDao.updateTaxCategory(txId, taxCategory)
            if (!isSyncingPlatform) {
                transactionMetadataChangeCacheDao.insertTaxCategory(txId, taxCategory)
            }
        }
    }

    override suspend fun setTransactionSentTime(
        txId: Sha256Hash,
        timestamp: Long,
        isSyncingPlatform: Boolean
    ) {
        updateAndInsertIfNotExist(txId, isSyncingPlatform) {
            transactionMetadataDao.updateSentTime(txId, timestamp)
            if (!isSyncingPlatform) {
                transactionMetadataChangeCacheDao.insertSentTime(txId, timestamp)
            }
        }
    }

    override suspend fun syncPlatformMetadata(txId: Sha256Hash, metadata: TransactionMetadata) {
        updateAndInsertIfNotExist(txId, true) { existing ->
            val updated = existing.copy(
                // txId and value are kept the same
                txId = existing.txId,
                value = existing.value,
                // update the rest from platform if not empty, otherwise keep existing
                type = metadata.type.takeIf { it != TransactionCategory.Invalid } ?: existing.type,
                taxCategory = metadata.taxCategory ?: existing.taxCategory,
                currencyCode = metadata.currencyCode ?: existing.currencyCode,
                rate = metadata.rate ?: existing.rate,
                memo = metadata.memo.ifEmpty { existing.memo },
                service = metadata.service ?: existing.service,
                timestamp = metadata.timestamp.takeIf { it != 0L } ?: existing.timestamp
            )

            transactionMetadataDao.update(updated)
        }
    }

    override suspend fun setTransactionType(txId: Sha256Hash, type: Int, isSyncingPlatform: Boolean) {
        TODO("Not yet implemented")
    }

    override suspend fun setTransactionExchangeRate(txId: Sha256Hash, exchangeRate: ExchangeRate, isSyncingPlatform: Boolean) {
        if (exchangeRate.rate != null) {
            updateAndInsertIfNotExist(txId, isSyncingPlatform) {
                transactionMetadataDao.updateExchangeRate(
                    txId,
                    exchangeRate.currencyCode,
                    exchangeRate.rate!!
                )
                if (!isSyncingPlatform) {
                    transactionMetadataChangeCacheDao.insertExchangeRate(
                        txId,
                        exchangeRate.currencyCode,
                        exchangeRate.rate!!
                    )
                }
            }
        }
    }

    override suspend fun setTransactionMemo(txId: Sha256Hash, memo: String, isSyncingPlatform: Boolean) {
        updateAndInsertIfNotExist(txId, isSyncingPlatform) {
            transactionMetadataDao.updateMemo(txId, memo)
            if (!isSyncingPlatform) {
                transactionMetadataChangeCacheDao.insertMemo(txId, memo)
            }
        }
    }

    override suspend fun setTransactionService(txId: Sha256Hash, service: String, isSyncingPlatform: Boolean) {
        updateAndInsertIfNotExist(txId, isSyncingPlatform) {
            transactionMetadataDao.updateService(txId, service)
            if (!isSyncingPlatform) {
                transactionMetadataChangeCacheDao.insertService(txId, service)
            }
        }
    }

    override fun syncTransactionBlocking(tx: Transaction) {
        runBlocking {
            syncTransaction(tx)
        }
    }

    override suspend fun syncTransaction(tx: Transaction) {
        log.info("sync transaction metadata: ${tx.txId}")
        val metadata = transactionMetadataDao.load(tx.txId)
        if (metadata != null) {
            // it does exist.  Check what is missing in the table vs the transaction
            log.info("sync transaction metadata exists: ${tx.txId}")
            val exchangeRate = tx.exchangeRate
            // sync exchange rates
            if (metadata.rate != null && tx.exchangeRate == null) {
                tx.exchangeRate = org.bitcoinj.utils.ExchangeRate(
                    Fiat.parseFiat(
                        metadata.currencyCode,
                        metadata.rate
                    )
                )
            } else if (metadata.rate == null && exchangeRate != null) {
                transactionMetadataDao.updateExchangeRate(
                    tx.txId,
                    exchangeRate.fiat.currencyCode,
                    exchangeRate.fiat.value.toString()
                )
                transactionMetadataChangeCacheDao.insertExchangeRate(
                    tx.txId,
                    exchangeRate.fiat.currencyCode,
                    exchangeRate.fiat.value.toString()
                )
            }

            // sync transaction memo
            if (metadata.memo.isNotBlank() && tx.memo == null) {
                tx.memo = metadata.memo
            } else if (metadata.memo.isBlank() && tx.memo != null) {
                setTransactionMemo(tx.txId, tx.memo!!)
            }

            // sync service name
            if (metadata.service == null) {
                val addressMetadata = getAddressMetadata(tx.txId)
                if (addressMetadata?.service != null) {
                    setTransactionService(tx.txId, addressMetadata.service)
                }
            }
        } else {
            // it does not exist, so import everything from the transaction
            log.info("sync transaction metadata not exists: ${tx.txId}")
            insertTransactionMetadata(tx.txId, false)
        }
    }

    override suspend fun getTransactionMetadata(txId: Sha256Hash): TransactionMetadata? {
        var transactionMetadata = transactionMetadataDao.load(txId)
        if (transactionMetadata == null) {
            insertTransactionMetadata(txId, false)
        }

        transactionMetadata = transactionMetadataDao.load(txId)
        if (transactionMetadata != null && transactionMetadata.taxCategory == null) {
            transactionMetadata.taxCategory = getDefaultTaxCategory(txId)
        }
        return transactionMetadata
    }

    /**
     * obtains the default tax category for transfers
     */
    private suspend fun getDefaultTaxCategory(txId: Sha256Hash): TaxCategory? {
        val addressMetadata = getAddressMetadata(txId)
        return addressMetadata?.taxCategory
    }

    private suspend fun getAddressMetadata(txId: Sha256Hash): AddressMetadata? {
        val tx = walletData.wallet!!.getTransaction(txId)
        tx?.run {
            // outgoing transaction, check inputs
            for (input in inputs) {
                if (input.connectedOutput != null) {
                    val output = input.connectedOutput!!
                    val address = when {
                        ScriptPattern.isP2PKH(output.scriptPubKey) ->
                            Address.fromPubKeyHash(
                                walletData.networkParameters,
                                ScriptPattern.extractHashFromP2PKH(output.scriptPubKey)
                            )
                        else -> null // there shouldn't be other output types on our tx's
                    }
                    if (address != null) {
                        val metadata =
                            addressMetadataDao.loadSender(address.toBase58())
                        if (metadata != null) {
                            return metadata
                        }
                    }
                }
            }
            // incoming transaction, checkout outputs
            for (output in outputs) {
                log.info("metadata: $output")
                val address = when {
                    ScriptPattern.isP2PKH(output.scriptPubKey) ->
                        Address.fromPubKeyHash(
                            walletData.networkParameters,
                            ScriptPattern.extractHashFromP2PKH(output.scriptPubKey)
                        )
                    ScriptPattern.isP2SH(output.scriptPubKey) ->
                        Address.fromScriptHash(
                            walletData.networkParameters,
                            ScriptPattern.extractHashFromP2SH(output.scriptPubKey)
                        )
                    else -> null // for now ignore OP_RETURN (DashPay Expense?)
                }
                if (address != null) {
                    val metadata =
                        addressMetadataDao.loadRecipient(address.toBase58())
                    if (metadata != null) {
                        return metadata
                    }
                }
            }
        }
        return null
    }

    override suspend fun getAllTransactionMetadata(): List<TransactionMetadata> {
        val metadataList = transactionMetadataDao.load()

        // look up the transaction metadata and merge with address metadata
        for (metadata in metadataList) {
            if (metadata.taxCategory == null) {
                // if there is no user specified tax category, then look at address_metadata
                val taxCategory = getDefaultTaxCategory(metadata.txId)

                if (taxCategory != null) {
                    metadata.taxCategory = taxCategory
                }
            }
        }
        return metadataList
    }

    override fun observeAllMemos(): Flow<Map<Sha256Hash, String>> {
        return transactionMetadataDao.observeMemos().map { list ->
            list.associateBy( {it.txId}, {it.memo} )
        }
    }

    override suspend fun markAddressWithTaxCategory(
        address: String,
        isInput: Boolean,
        taxCategory: TaxCategory,
        service: String
    ) {
        // check to see if this address has been used before
        // if it has been used before, that means the same address was used more than once
        // possibly for two different services or actions.
        // This may not matter as the default tax category is probably the same for each.
        if (addressMetadataDao.exists(address, isInput)) {
            log.info("address $address/$isInput was already added")
        }

        addressMetadataDao.markAddress(address, isInput, taxCategory, service)
    }

    override suspend fun maybeMarkAddressWithTaxCategory(
        address: String,
        isInput: Boolean,
        taxCategory: TaxCategory,
        service: String
    ): Boolean {
        return if (!addressMetadataDao.exists(address, isInput)) {
            addressMetadataDao.markAddress(address, isInput, taxCategory, service)
            true
        } else {
            false
        }
    }

    override fun markAddressAsync(
        address: String,
        isInput: Boolean,
        taxCategory: TaxCategory,
        service: String
    ) {
        syncScope.launch(Dispatchers.IO) {
            markAddressWithTaxCategory(address, isInput, taxCategory, service)
        }
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeTransactionMetadata(txId: Sha256Hash): Flow<TransactionMetadata?> {
        return transactionMetadataDao.observe(txId).map { transactionMetadata ->
            // if there is no user specified tax category, then look at address_metadata
            if (transactionMetadata != null && transactionMetadata.taxCategory == null) {
                transactionMetadata.taxCategory = getDefaultTaxCategory(txId)
            }
            transactionMetadata
        }
    }

    override suspend fun clear() {
        transactionMetadataDao.clear()
        addressMetadataDao.clear()
    }
}
