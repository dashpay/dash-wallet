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

package org.dash.wallet.features.exploredash.data.dashdirect

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapInfo
import androidx.room.Query
import androidx.room.Update
import com.google.zxing.BarcodeFormat
import kotlinx.coroutines.flow.Flow
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.features.exploredash.data.dashdirect.model.GiftCard

@Dao
interface GiftCardDao {
    @Insert
    suspend fun insertGiftCard(giftCard: GiftCard)

    @Update(entity = GiftCard::class)
    suspend fun updateGiftCard(giftCard: GiftCard)

    @Query("SELECT * FROM gift_cards WHERE txId = :txId")
    suspend fun getCardForTransaction(txId: Sha256Hash): GiftCard?

    @Query("SELECT * FROM gift_cards WHERE txId = :txId")
    fun observeCardForTransaction(txId: Sha256Hash): Flow<GiftCard?>

    @Query("UPDATE gift_cards SET barcodeValue = :value, barcodeFormat = :barcodeFormat WHERE txId = :txId")
    suspend fun updateBarcode(txId: Sha256Hash, value: String, barcodeFormat: BarcodeFormat)

    @MapInfo(keyColumn = "txId")
    @Query("SELECT * FROM gift_cards")
    fun observeGiftCards(): Flow<Map<Sha256Hash, GiftCard>>
}
