/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.database.dao

import androidx.room.*
import de.schildbach.wallet.database.entity.TransactionMetadataCacheItem
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.TaxCategory

/**
 * @author Eric Britten
 */
@Dao
interface TransactionMetadataChangeCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transactionMetadata: TransactionMetadataCacheItem)

    @Query("delete from transaction_metadata_cache where id in (:idList)")
    suspend fun removeByIds(idList: List<Long>)

    @Query("SELECT * FROM transaction_metadata_cache ORDER BY id")
    suspend fun load(): List<TransactionMetadataCacheItem>

    @Query("SELECT * FROM transaction_metadata_cache WHERE txId = :txId AND cacheTimestamp > :updatedAfter ORDER BY id")
    suspend fun findAfter(txId: Sha256Hash, updatedAfter: Long): List<TransactionMetadataCacheItem>

    @Query("SELECT * FROM transaction_metadata_cache WHERE cacheTimestamp <= :updatedBefore ORDER BY id")
    suspend fun findAllBefore(updatedBefore: Long): List<TransactionMetadataCacheItem>

    @Query("SELECT COUNT(1) FROM transaction_metadata_cache WHERE txid = :txId;")
    suspend fun exists(txId: Sha256Hash): Boolean

    @Query("SELECT * FROM transaction_metadata_cache WHERE txid = :txId")
    suspend fun load(txId: Sha256Hash): TransactionMetadataCacheItem?

    @Query("SELECT DISTINCT txId FROM transaction_metadata_cache")
    suspend fun getAllTransactionIds(): List<Sha256Hash>

    @Query(
        """INSERT INTO transaction_metadata_cache (txId, cacheTimestamp, taxCategory) 
           VALUES (:txId, :cacheTimestamp, :taxCategory)"""
    )
    suspend fun insertTaxCategory(
        txId: Sha256Hash,
        taxCategory: TaxCategory,
        cacheTimestamp: Long = System.currentTimeMillis()
    )

    @Query(
        """INSERT INTO transaction_metadata_cache (txId, cacheTimestamp, sentTimestamp) 
           VALUES (:txId, :cacheTimestamp, :sentTimestamp)"""
    )
    suspend fun insertSentTime(txId: Sha256Hash, sentTimestamp: Long, cacheTimestamp: Long = System.currentTimeMillis())

    @Query("INSERT INTO transaction_metadata_cache (txId, cacheTimestamp, memo) VALUES(:txId, :cacheTimestamp, :memo)")
    suspend fun insertMemo(txId: Sha256Hash, memo: String, cacheTimestamp: Long = System.currentTimeMillis())

    @Query(
        """INSERT INTO transaction_metadata_cache (txId, cacheTimestamp, service) 
           VALUES(:txId, :cacheTimestamp, :service)"""
    )
    suspend fun insertService(txId: Sha256Hash, service: String, cacheTimestamp: Long = System.currentTimeMillis())

    @Query(
        """INSERT INTO transaction_metadata_cache (txId, cacheTimestamp, currencyCode, rate) 
           VALUES (:txId, :cacheTimestamp, :currencyCode, :rate)"""
    )
    suspend fun insertExchangeRate(
        txId: Sha256Hash,
        currencyCode: String,
        rate: String,
        cacheTimestamp: Long = System.currentTimeMillis()
    )

    @Query(
        """INSERT INTO transaction_metadata_cache (txId, cacheTimestamp, customIconUrl) 
           VALUES (:txId, :cacheTimestamp, :customIconUrl)"""
    )
    suspend fun insertCustomIconUrl(
        txId: Sha256Hash,
        customIconUrl: String,
        cacheTimestamp: Long = System.currentTimeMillis()
    )

    @Query(
        """INSERT INTO transaction_metadata_cache (txId, cacheTimestamp, service, taxCategory, customIconUrl) 
           VALUES (:txId, :cacheTimestamp, :service, :taxCategory, :customIconUrl)"""
    )
    suspend fun markGiftCardTx(
        txId: Sha256Hash,
        service: String,
        taxCategory: TaxCategory,
        customIconUrl: String?,
        cacheTimestamp: Long = System.currentTimeMillis()
    )

    @Query(
        """INSERT INTO transaction_metadata_cache (txId, cacheTimestamp, giftCardNumber, giftCardPin, merchantName, originalPrice, merchantUrl)
           VALUES (:txId, :cacheTimestamp, :giftCardNumber, :giftCardPin, :merchantName, :originalPrice, :merchantUrl)"""
    )
    suspend fun insertGiftCardData(
        txId: Sha256Hash,
        giftCardNumber: String?,
        giftCardPin: String?,
        merchantName: String?,
        originalPrice: Double?,
        merchantUrl: String?,
        cacheTimestamp: Long = System.currentTimeMillis()
    )

    @Query(
        """INSERT INTO transaction_metadata_cache (txId, cacheTimestamp, barcodeValue, barcodeFormat) 
           VALUES (:txId, :cacheTimestamp, :barcodeValue, :barcodeFormat)"""
    )
    suspend fun insertBarcode(
        txId: Sha256Hash,
        barcodeValue: String?,
        barcodeFormat: String?,
        cacheTimestamp: Long = System.currentTimeMillis()
    )

    @Query("SELECT COUNT(*) FROM transaction_metadata_cache")
    suspend fun count(): Int

    @Query("SELECT COUNT(DISTINCT txId) FROM transaction_metadata_cache where cacheTimestamp < :beforeTimestamp")
    suspend fun countTransactions(beforeTimestamp: Long): Int

    @Query("DELETE FROM transaction_metadata_cache")
    suspend fun clear()

    @Query("SELECT MAX(cacheTimestamp) FROM transaction_metadata_cache")
    suspend fun lastTransactionTime(): Long

    @Query("""
        SELECT * FROM transaction_metadata_cache 
        WHERE txId IN (
            SELECT txId FROM transaction_metadata 
            WHERE timestamp < :maxTimestamp
        )
    """)
    suspend fun getCachedItemsBefore(maxTimestamp: Long): List<TransactionMetadataCacheItem>

}
