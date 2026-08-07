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

package org.dash.wallet.features.exploredash.data.explore

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.google.zxing.BarcodeFormat
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.data.entity.GiftCard

/**
 * txId query parameters are the raw 32 bytes of the transaction id (`Sha256Hash.bytes` /
 * `TxIds.toBytes(hex)`) — the same BLOB the Room type converter stores for [GiftCard.txId].
 */
@Dao
interface GiftCardDao {
    @Insert
    suspend fun insertGiftCard(giftCard: GiftCard)

    @Insert
    suspend fun insertGiftCards(giftCards: List<GiftCard>)

    @Update(entity = GiftCard::class)
    suspend fun updateGiftCard(giftCard: GiftCard): Int

    @Query("SELECT COUNT(*) FROM gift_cards WHERE txId = :txId")
    suspend fun getCardCountForTransaction(txId: ByteArray): Int

    @Query("SELECT * FROM gift_cards WHERE txId = :txId ORDER BY `index` ASC")
    suspend fun getCardForTransaction(txId: ByteArray): List<GiftCard>

    @Query("SELECT * FROM gift_cards WHERE txId = :txId ORDER BY `index` ASC")
    fun observeCardForTransaction(txId: ByteArray): Flow<List<GiftCard>>

    @Query(
        """
        UPDATE gift_cards SET barcodeValue = :value, barcodeFormat = :barcodeFormat
        WHERE txId = :txId AND `index` = :index
    """
    )
    suspend fun updateBarcode(txId: ByteArray, index: Int, value: String, barcodeFormat: BarcodeFormat)

    @Query("SELECT * FROM gift_cards ORDER BY `index` ASC")
    fun observeGiftCards(): Flow<List<GiftCard>>
}
