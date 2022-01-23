/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.features.exploredash.data

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import org.dash.wallet.features.exploredash.data.model.SearchResult

interface BaseDao<T: SearchResult> {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(list: List<T>)

    // add @Query(...) in sub classes to avoid build failures
    suspend fun deleteAll(): Int
}