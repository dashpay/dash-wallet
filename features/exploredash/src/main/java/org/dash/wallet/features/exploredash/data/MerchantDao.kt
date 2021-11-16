/*
 *
 *  * Copyright 2021 Dash Core Group.
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.dash.wallet.features.exploredash.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.MerchantType
import org.dash.wallet.features.exploredash.data.model.GeoBounds

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
        WHERE (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
        GROUP BY merchantId
        HAVING Id = MIN(Id)
        ORDER BY name ASC
    """)
    fun pagingGroupByMerchantId(
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
        GROUP BY merchantId
        HAVING Id = MIN(Id)
        ORDER BY name ASC
    """)
    fun pagingSearchGroupByMerchantId(
        query: String,
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

    @Query("""
        SELECT * 
        FROM merchant 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:excludeType = '' OR type != :excludeType)
        ORDER BY name ASC""")
    fun observeByTerritory(
        territoryFilter: String,
        excludeType: String,
        paymentMethod: String
    ): Flow<List<Merchant>>

    @Query("""
        SELECT *
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:territoryFilter = '' OR merchant_fts.territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:excludeType = '' OR type != :excludeType)
        ORDER BY name ASC
    """)
    fun searchByTerritory(
        query: String,
        territoryFilter: String,
        excludeType: String,
        paymentMethod: String
    ): Flow<List<Merchant>>

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
        return if (territory.isNotBlank()) {
            if (query.isNotBlank()) {
                searchByTerritory(sanitizeQuery(query), territory, MerchantType.ONLINE, paymentMethod)
            } else {
                observeByTerritory(territory, MerchantType.ONLINE, paymentMethod)
            }
        } else {
            if (query.isNotBlank()) {
                observeSearchResults(sanitizeQuery(query), MerchantType.ONLINE, paymentMethod,
                    bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
            } else {
                observe(MerchantType.ONLINE, paymentMethod,
                    bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
            }
        }
    }

    fun observeAllPaging(
        query: String,
        territory: String,
        type: String,
        paymentMethod: String,
        bounds: GeoBounds
    ): PagingSource<Int, Merchant> {
        return when {
            type == MerchantType.ONLINE -> {
                // For Online merchants, need to get everything that can be used online
                // and group by merchant ID to avoid duplicates
                val types = listOf(MerchantType.ONLINE, MerchantType.BOTH)

                if (query.isNotBlank()) {
                    pagingSearchGroupByMerchantId(query, types, paymentMethod)
                } else {
                    pagingGroupByMerchantId(types, paymentMethod)
                }
            }
            type == MerchantType.PHYSICAL && territory.isBlank() && bounds != GeoBounds.noBounds -> {
                // For physical merchants we search by coordinates (nearby)
                // if location services are enabled
                val types = listOf(MerchantType.PHYSICAL, MerchantType.BOTH)

                if (query.isNotBlank()) {
                    pagingSearchByCoordinates(sanitizeQuery(query), types, paymentMethod,
                        bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
                } else {
                    pagingGetByCoordinates(types, paymentMethod,
                            bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
                }
            }
            else -> {
                // If location services are disabled or user picked a territory
                // or filter is All, we search everything and filter by territory
                val types = listOf(MerchantType.PHYSICAL, MerchantType.ONLINE, MerchantType.BOTH)

                if (query.isNotBlank()) {
                    pagingSearchByTerritory(sanitizeQuery(query), territory, types, paymentMethod)
                } else {
                    pagingGetByTerritory(territory, types, paymentMethod)
                }
            }
        }
    }
}