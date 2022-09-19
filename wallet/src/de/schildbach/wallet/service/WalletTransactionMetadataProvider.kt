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
import de.schildbach.wallet.data.TransactionMetadataDao
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
import java.util.concurrent.Executors
import javax.inject.Inject

class WalletTransactionMetadataProvider @Inject constructor(
    private val transactionMetadataDao: TransactionMetadataDao,
    private val addressMetadataDao: AddressMetadataDao,
    private val walletData: WalletDataProvider
) : TransactionMetadataProvider {

    companion object {
        val log = LoggerFactory.getLogger(WalletTransactionMetadataProvider::class.java)
    }

    private val syncScope = CoroutineScope(
        Executors.newFixedThreadPool(5).asCoroutineDispatcher()
    )

    private suspend fun insertTransactionMetadata(txId: Sha256Hash) {
        val walletTx = walletData.wallet!!.getTransaction(txId)
        Context.propagate(walletData.wallet!!.context)
        walletTx?.run {
            val txValue = getValue(walletData.wallet!!) ?: Coin.ZERO
            val sentTime = confidence.sentAt?.time
            var updateTime = updateTime.time
            if (sentTime != null && sentTime < updateTime) {
                updateTime = sentTime
            }
            val isInternal = isEntirelySelf(this, walletData.wallet!!)

            val metadata = TransactionMetadata(
                txId,
                updateTime,
                txValue,
                TransactionCategory.fromTransaction(type, txValue, isInternal),
                taxCategory = null,
                exchangeRate?.fiat?.currencyCode,
                exchangeRate?.fiat?.let {
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

    override suspend fun setTransactionTaxCategory(txId: Sha256Hash, taxCategory: TaxCategory) {
        updateAndInsertIfNotExist(txId) {
            transactionMetadataDao.updateTaxCategory(txId, taxCategory)
        }
    }

    override suspend fun setTransactionType(txId: Sha256Hash, type: Int) {
        TODO("Not yet implemented")
    }

    override suspend fun setTransactionExchangeRate(txId: Sha256Hash, exchangeRate: ExchangeRate) {
        if (exchangeRate.rate != null) {
            updateAndInsertIfNotExist(txId) {
                transactionMetadataDao.updateExchangeRate(
                    txId,
                    exchangeRate.currencyCode,
                    exchangeRate.rate!!
                )
            }
        }
    }

    override suspend fun setTransactionMemo(txId: Sha256Hash, memo: String) {
        updateAndInsertIfNotExist(txId) {
            transactionMetadataDao.updateMemo(txId, memo)
        }
    }

    override suspend fun setTransactionService(txId: Sha256Hash, service: String) {
        updateAndInsertIfNotExist(txId) {
            transactionMetadataDao.updateService(txId, service)
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
            insertTransactionMetadata(tx.txId)
        }
    }

    override suspend fun getTransactionMetadata(txId: Sha256Hash): TransactionMetadata? {
        var transactionMetadata = transactionMetadataDao.load(txId)
        if (transactionMetadata == null) {
            insertTransactionMetadata(txId)
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

    override fun clear() {
        syncScope.launch {
            transactionMetadataDao.clear()
            addressMetadataDao.clear()
        }
    }
}