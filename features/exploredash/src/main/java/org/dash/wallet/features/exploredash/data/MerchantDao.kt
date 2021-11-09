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

import android.util.Log
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.MerchantType
import org.dash.wallet.features.exploredash.services.GeoBounds

@Dao
interface MerchantDao : BaseDao<Merchant> {

    @Query("""
        SELECT * 
        FROM merchant 
        WHERE type IN (:types)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        ORDER BY name ASC""")
    fun pagingGetByCoordinates(
        types: List<String>,
        paymentMethod: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): PagingSource<Int, Merchant>

    @Query("""
        SELECT * 
        FROM merchant 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
        ORDER BY name ASC""")
    fun pagingGetByTerritory(
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String
    ): PagingSource<Int, Merchant>

    @Query("""
        SELECT *
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        ORDER BY name ASC
    """)
    fun pagingSearchByCoordinates(
        query: String,
        types: List<String>,
        paymentMethod: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): PagingSource<Int, Merchant>

    @Query("""
        SELECT *
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:territoryFilter = '' OR merchant_fts.territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
        ORDER BY name ASC
    """)
    fun pagingSearchByTerritory(
        query: String,
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String
    ): PagingSource<Int, Merchant>

    @Query("""
        SELECT * 
        FROM merchant
        WHERE (:excludeType = '' OR type != :excludeType)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        ORDER BY name ASC""")
    fun observe(
        excludeType: String,
        paymentMethod: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Flow<List<Merchant>>

    @Query("""
        SELECT *
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:excludeType = '' OR type != :excludeType)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        ORDER BY name ASC
    """)
    fun observeSearchResults(
        query: String,
        excludeType: String,
        paymentMethod: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Flow<List<Merchant>>

    @Query("SELECT * FROM merchant WHERE id = :merchantId LIMIT 1")
    suspend fun getMerchant(merchantId: Int): Merchant?

    @Query("SELECT * FROM merchant WHERE id = :merchantId LIMIT 1")
    fun observeMerchant(merchantId: Int): Flow<Merchant?>

    @Query("SELECT DISTINCT territory FROM merchant")
    suspend fun getTerritories(): List<String>

    @Query("DELETE FROM merchant WHERE source LIKE :source")
    override suspend fun deleteAll(source: String): Int

    fun observePhysical(
        query: String,
        territory: String,
        paymentMethod: String,
        bounds: GeoBounds
    ): Flow<List<Merchant>> {
        Log.i("EXPLOREDASH", "observePhysical: ${query}, ${paymentMethod}, ${territory}, ${bounds}")

        return if (query.isNotBlank()) {
            observeSearchResults(
                sanitizeQuery(query),
                MerchantType.ONLINE,
                paymentMethod,
                bounds.northLat,
                bounds.eastLng,
                bounds.southLat,
                bounds.westLng
            )
        } else {
            observe(
                MerchantType.ONLINE,
                paymentMethod,
                bounds.northLat,
                bounds.eastLng,
                bounds.southLat,
                bounds.westLng
            )
        }
    }

    fun observeAllPaging(
        query: String,
        territory: String,
        types: List<String>,
        paymentMethod: String,
        bounds: GeoBounds
    ): PagingSource<Int, Merchant> {
        Log.i("EXPLOREDASH", "observeAllPaging: ${query}, ${paymentMethod}, ${territory}, ${bounds}")
        return if (query.isNotBlank()) {
            pagingSearchByCoordinates(
                sanitizeQuery(query),
                types,
                paymentMethod,
                bounds.northLat,
                bounds.eastLng,
                bounds.southLat,
                bounds.westLng)
        } else {
            pagingGetByCoordinates(
                types,
                paymentMethod,
                bounds.northLat,
                bounds.eastLng,
                bounds.southLat,
                bounds.westLng
            )
        }
    }
}