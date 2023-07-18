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
import kotlinx.coroutines.flow.Flow
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.entity.IconBitmap

@Dao
interface IconBitmapDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) // Ignore if the image hash already exists
    suspend fun addBitmap(bitmap: IconBitmap)

    @Query("SELECT * FROM icon_bitmaps WHERE id = :id")
    suspend fun getBitmap(id: Sha256Hash): IconBitmap?

    @MapInfo(keyColumn = "id")
    @Query("SELECT * FROM icon_bitmaps")
    fun observeBitmaps(): Flow<Map<Sha256Hash, IconBitmap>>

    @Query("DELETE FROM icon_bitmaps")
    suspend fun clear()
}

