/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dash.wallet.features.exploredash.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.features.exploredash.data.model.Atm
import org.dash.wallet.features.exploredash.data.model.Merchant

@Dao
interface MerchantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(list: List<Merchant>)

    @Query("SELECT * FROM merchant WHERE :territoryFilter = '' OR territory = :territoryFilter")
    fun pagingGet(territoryFilter: String): PagingSource<Int, Merchant>

    @Query("""
        SELECT *
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query AND (:territoryFilter = '' OR merchant_fts.territory = :territoryFilter)
    """)
    fun pagingSearch(query: String, territoryFilter: String): PagingSource<Int, Merchant>

    @Query("SELECT * FROM merchant WHERE :territoryFilter = '' OR territory = :territoryFilter")
    fun observe(territoryFilter: String): Flow<List<Merchant>>

    @Query("""
        SELECT *
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query AND (:territoryFilter = '' OR merchant_fts.territory = :territoryFilter)
    """)
    fun observeSearchResults(query: String, territoryFilter: String): Flow<List<Merchant>>

    @Query("SELECT * FROM merchant WHERE id = :merchantId LIMIT 1")
    suspend fun getMerchant(merchantId: Int): Merchant?

    @Query("SELECT * FROM merchant WHERE id = :merchantId LIMIT 1")
    fun observeMerchant(merchantId: Int): Flow<Merchant?>

    @Query("SELECT DISTINCT territory FROM merchant")
    suspend fun getTerritories(): List<String>
}