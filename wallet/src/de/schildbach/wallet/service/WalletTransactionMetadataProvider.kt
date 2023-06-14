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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.*
import de.schildbach.wallet.database.dao.AddressMetadataDao
import de.schildbach.wallet.database.dao.IconBitmapDao
import de.schildbach.wallet.database.dao.TransactionMetadataDao
import de.schildbach.wallet.database.dao.TransactionMetadataChangeCacheDao
import de.schildbach.wallet.database.dao.TransactionMetadataDocumentDao
import de.schildbach.wallet.database.entity.TransactionMetadataCacheItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.bitcoinj.core.*
import org.bitcoinj.core.Address
import org.bitcoinj.script.ScriptPattern
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.*
import org.dash.wallet.common.data.entity.AddressMetadata
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.data.entity.IconBitmap
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.transactions.TransactionCategory
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.decodeBitmap
import org.dash.wallet.features.exploredash.data.dashdirect.GiftCardDao
import org.slf4j.LoggerFactory
import java.util.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
class WalletTransactionMetadataProvider @Inject constructor(
    private val transactionMetadataDao: TransactionMetadataDao,
    private val addressMetadataDao: AddressMetadataDao,
    private val iconBitmapDao: IconBitmapDao,
    private val walletData: WalletDataProvider,
    private val giftCardDao: GiftCardDao,
    private val transactionMetadataChangeCacheDao: TransactionMetadataChangeCacheDao,
    private val transactionMetadataDocumentDao: TransactionMetadataDocumentDao
) : TransactionMetadataProvider {

    companion object {
        private val log = LoggerFactory.getLogger(WalletTransactionMetadataProvider::class.java)
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
            if (sentTime != null && sentTime < updateTime) {
                updateTime = sentTime
            }
            val isInternal = isEntirelySelf(walletData.wallet!!)

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

            val platformIconUrl = transactionMetadataDocumentDao.getTransactionIconUrl(txId)

            if (!platformIconUrl.isNullOrEmpty()) {
                try {
                    updateIcon(txId, platformIconUrl)
                } catch (ex: Exception) {
                    log.error("Failed to make an http call for icon: $platformIconUrl")
                }
            }

            val giftCardNumber = transactionMetadataDocumentDao.getGiftCardNumber(txId)
            val giftCardPin = transactionMetadataDocumentDao.getGiftCardPin(txId)
            val merchantName = transactionMetadataDocumentDao.getMerchantName(txId)
            val giftCardPrice = transactionMetadataDocumentDao.getOriginalPrice(txId)
            val barcodeValue = transactionMetadataDocumentDao.getBarcodeValue(txId)
            val barcodeFormat = transactionMetadataDocumentDao.getBarcodeFormat(txId)
            val merchantUrl = transactionMetadataDocumentDao.getMerchantUrl(txId)

            val giftCard = GiftCard(
                txId,
                merchantName ?: "",
                giftCardPrice ?: 0.0,
                giftCardNumber,
                giftCardPin,
                barcodeValue,
                barcodeFormat?.let { BarcodeFormat.valueOf(it) },
                merchantUrl
            )
            insertOrUpdateGiftCard(giftCard)

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

    override suspend fun syncPlatformMetadata(
        txId: Sha256Hash,
        metadata: TransactionMetadata,
        giftCard: GiftCard?,
        iconUrl: String?
    ) {
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

        if (giftCard != null) {
            insertOrUpdateGiftCard(giftCard)
        }

        if (!iconUrl.isNullOrEmpty()) {
            try {
                updateIcon(txId, iconUrl)
            } catch (ex: Exception) {
                log.error("Failed to make an http call for icon: $iconUrl")
            }
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

    override suspend fun markGiftCardTransaction(txId: Sha256Hash, iconUrl: String?) {
        var transactionMetadata: TransactionMetadata
        updateAndInsertIfNotExist(txId, false) {
            transactionMetadata = it.copy(
                service = ServiceName.DashDirect,
                taxCategory = TaxCategory.Expense
            )
            transactionMetadataDao.update(transactionMetadata)
            transactionMetadataChangeCacheDao.markGiftCardTx(
                txId,
                ServiceName.DashDirect,
                TaxCategory.Expense,
                iconUrl
            )
        }

        if (!iconUrl.isNullOrEmpty()) {
            try {
                updateIcon(txId, iconUrl)
            } catch (ex: Exception) {
                log.error("Failed to make an http call for icon: $iconUrl")
            }
        }
    }

    override suspend fun updateGiftCardMetadata(giftCard: GiftCard) {
        giftCardDao.updateGiftCard(giftCard)
        transactionMetadataChangeCacheDao.insertGiftCardData(
            giftCard.txId,
            giftCard.number,
            giftCard.pin,
            giftCard.merchantName,
            giftCard.price,
            giftCard.merchantUrl
        )
    }

    override suspend fun updateGiftCardBarcode(txId: Sha256Hash, barcodeValue: String, barcodeFormat: BarcodeFormat) {
        giftCardDao.updateBarcode(txId, barcodeValue, barcodeFormat)
        transactionMetadataChangeCacheDao.insertBarcode(txId, barcodeValue, barcodeFormat.toString())
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
    
    override fun observePresentableMetadata(): Flow<Map<Sha256Hash, PresentableTxMetadata>> {
        return iconBitmapDao.observeBitmaps()
            .distinctUntilChanged()
            .map { rows ->
                rows.mapValues {
                    // Only keep a single bitmap instance per unique data row
                    BitmapFactory.decodeByteArray(it.value.imageData, 0, it.value.imageData.size)
                }
            }
            .flatMapLatest { bitmaps ->
                giftCardDao.observeGiftCards().distinctUntilChanged().flatMapLatest { giftCards ->
                    transactionMetadataDao.observePresentableMetadata()
                        .distinctUntilChanged()
                        .map { metadataList ->
                            metadataList.values.forEach { metadata ->
                                metadata.customIconId?.let { iconId ->
                                    metadata.icon = bitmaps[iconId]
                                }

                                if (metadata.service == ServiceName.DashDirect) {
                                    metadata.title = giftCards[metadata.txId]?.merchantName
                                }
                            }
                            metadataList
                        }
                }
            }
    }

    override suspend fun getIcon(iconId: Sha256Hash): Bitmap? {
        iconBitmapDao.getBitmap(iconId)?.let {
            return BitmapFactory.decodeByteArray(it.imageData, 0, it.imageData.size)
        }

        return null
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

    override fun observeTransactionMetadata(txId: Sha256Hash): Flow<TransactionMetadata?> {
        return transactionMetadataDao.observe(txId)
            .distinctUntilChanged()
            .map { transactionMetadata ->
                // if there is no user specified tax category, then look at address_metadata
                if (transactionMetadata != null && transactionMetadata.taxCategory == null) {
                    transactionMetadata.taxCategory = getDefaultTaxCategory(txId)
                }
                transactionMetadata
            }
    }

    private fun updateIcon(txId: Sha256Hash, iconUrl: String) {
        val request = Request.Builder().url(iconUrl).get().build()
        Constants.HTTP_CLIENT.newCall(request).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                log.error("Failed to fetch the icon for url: $iconUrl", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.let {
                    syncScope.launch {
                        try {
                            val bitmap = it.decodeBitmap()
                            val icon = resizeIcon(bitmap)
                            val imageData = getBitmapData(icon)
                            val imageHash = Sha256Hash.of(imageData)

                            iconBitmapDao.addBitmap(IconBitmap(imageHash, imageData, iconUrl, icon.height, icon.width))
                            transactionMetadataDao.updateIconId(txId, imageHash)
                        } catch (ex: Exception) {
                            log.error("Failed to resize and save the icon for url: $iconUrl", ex)
                        }
                    }
                }
            }
        })
    }

    private fun resizeIcon(bitmap: Bitmap): Bitmap {
        var width = bitmap.width
        var height = bitmap.height
        val destSize = 150.0

        if (width > destSize || height > destSize) {
            if (width < height) {
                val scale = destSize / height
                height = destSize.toInt()
                width = (width * scale).toInt()
            } else if (width > height) {
                val scale = destSize / width
                width = destSize.toInt()
                height = (height * scale).toInt()
            }
        }

        return Bitmap.createScaledBitmap(bitmap, width, height, false)
    }

    private fun getBitmapData(bitmap: Bitmap): ByteArray {
        val outputStream = ByteArrayOutputStream()
        outputStream.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        return outputStream.toByteArray()
    }

    private suspend fun insertOrUpdateGiftCard(giftCard: GiftCard) {
        if (giftCard.merchantName.isEmpty() && giftCard.number == null && giftCard.pin == null &&
            (giftCard.barcodeValue == null || giftCard.barcodeFormat == null)
        ) {
            // Empty gift card. Nothing to insert/update
            return
        }

        val existingGiftCard = giftCardDao.getGiftCard(giftCard.txId)

        if (existingGiftCard == null) {
            giftCardDao.insertGiftCard(giftCard)
        } else {
            val updatedGiftCard = existingGiftCard.copy(
                merchantName = giftCard.merchantName.ifEmpty { existingGiftCard.merchantName },
                price = giftCard.price.takeIf { it != 0.0 } ?: existingGiftCard.price,
                number = giftCard.number ?: existingGiftCard.number,
                pin = giftCard.pin ?: existingGiftCard.pin,
                barcodeValue = giftCard.barcodeValue ?: existingGiftCard.barcodeValue,
                barcodeFormat = giftCard.barcodeFormat ?: existingGiftCard.barcodeFormat,
                merchantUrl = giftCard.merchantUrl ?: existingGiftCard.merchantUrl
            )
            giftCardDao.updateGiftCard(updatedGiftCard)
        }
    }

    override suspend fun clear() {
        transactionMetadataDao.clear()
        addressMetadataDao.clear()
        iconBitmapDao.clear()
    }
}
