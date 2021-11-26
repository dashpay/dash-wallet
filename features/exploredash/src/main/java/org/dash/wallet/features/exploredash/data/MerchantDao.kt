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
        GROUP BY merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY
            CASE WHEN :sortByDistance = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC, 
            CASE WHEN :sortByDistance = 0 THEN name END COLLATE NOCASE ASC
    """)
    fun pagingGetByCoordinates(
        types: List<String>,
        paymentMethod: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double,
        sortByDistance: Boolean,
        anchorLat: Double,
        anchorLng: Double
    ): PagingSource<Int, Merchant>

    @Query("""
        SELECT COUNT(*)
        FROM merchant 
        WHERE type IN (:types)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
    """)
    suspend fun getByCoordinatesResultCount(
        types: List<String>,
        paymentMethod: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Int

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
        GROUP BY merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY
            CASE WHEN :sortByDistance = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC, 
            CASE WHEN :sortByDistance = 0 THEN merchant.name END COLLATE NOCASE ASC
    """)
    fun pagingSearchByCoordinates(
        query: String,
        types: List<String>,
        paymentMethod: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double,
        sortByDistance: Boolean,
        anchorLat: Double,
        anchorLng: Double
    ): PagingSource<Int, Merchant>

    @Query("""
        SELECT COUNT(*)
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
    """)
    suspend fun searchByCoordinatesResultCount(
        query: String,
        types: List<String>,
        paymentMethod: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Int

    @Query("""
        SELECT * 
        FROM merchant 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
        GROUP BY merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY 
            CASE type
                WHEN "online"   THEN :onlineOrder
                WHEN "both"     THEN 1
                WHEN "physical" THEN 2 - :onlineOrder
            END,
            CASE WHEN :sortByDistance = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC,
            CASE WHEN :sortByDistance = 0 THEN merchant.name END COLLATE NOCASE ASC
    """)
    fun pagingGetByTerritory(
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String,
        sortByDistance: Boolean,
        anchorLat: Double,
        anchorLng: Double,
        onlineOrder: Int
    ): PagingSource<Int, Merchant>

    @Query("""
        SELECT COUNT(*)
        FROM merchant 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
    """)
    fun getByTerritoryResultCount(
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String
    ): Int

    @Query("""
        SELECT *
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:territoryFilter = '' OR merchant_fts.territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
        GROUP BY merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY
            CASE type
                WHEN "online"   THEN :onlineOrder
                WHEN "both"     THEN 1
                WHEN "physical" THEN 2 - :onlineOrder
            END,
            CASE WHEN :sortByDistance = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC,
            CASE WHEN :sortByDistance = 0 THEN merchant.name END COLLATE NOCASE ASC
    """)
    fun pagingSearchByTerritory(
        query: String,
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String,
        sortByDistance: Boolean,
        anchorLat: Double,
        anchorLng: Double,
        onlineOrder: Int
    ): PagingSource<Int, Merchant>

    @Query("""
        SELECT COUNT(*)
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:territoryFilter = '' OR merchant_fts.territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
    """)
    suspend fun searchByTerritoryResultCount(
        query: String,
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String
    ): Int

    @Query("""
        SELECT *
        FROM merchant
        WHERE (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
        GROUP BY merchantId
        HAVING Id = MIN(Id)
        ORDER BY name COLLATE NOCASE ASC
    """)
    fun pagingGetGrouped(
        types: List<String>,
        paymentMethod: String
    ): PagingSource<Int, Merchant>

    @Query("""
        SELECT COUNT(*)
        FROM merchant
        WHERE (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
        GROUP BY merchantId
        HAVING Id = MIN(Id)
    """)
    fun getGroupedResultCount(
        types: List<String>,
        paymentMethod: String
    ): Int

    @Query("""
        SELECT *
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
        GROUP BY merchantId
        HAVING Id = MIN(Id)
        ORDER BY name COLLATE NOCASE ASC
    """)
    fun pagingSearchGrouped(
        query: String,
        types: List<String>,
        paymentMethod: String
    ): PagingSource<Int, Merchant>

    @Query("""
        SELECT COUNT(*)
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
        GROUP BY merchantId
        HAVING Id = MIN(Id)
    """)
    fun searchGroupedResultCount(
        query: String,
        types: List<String>,
        paymentMethod: String
    ): Int

    @Query("""
        SELECT * 
        FROM merchant
        WHERE (:excludeType = '' OR type != :excludeType)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
    """)
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
    """)
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
                observe(MerchantType.ONLINE, paymentMethod, bounds.northLat,
                        bounds.eastLng, bounds.southLat, bounds.westLng)
            }
        }
    }

    // Sorting by distance is approximate - it's done using "flat-earth" Pythagorean formula.
    // It's good enough for our purposes, but if there is a need to display the distance
    // in UI it should be done using map APIs.
    fun observeAllPaging(
        query: String,
        territory: String,
        type: String,
        paymentMethod: String,
        bounds: GeoBounds,
        sortByDistance: Boolean,
        userLat: Double,
        userLng: Double,
        onlineFirst: Boolean
    ): PagingSource<Int, Merchant> {
        return when {
            type == MerchantType.ONLINE -> {
                // For Online merchants, need to get everything that can be used online
                // and group by merchant ID to avoid duplicates
                val types = listOf(MerchantType.ONLINE, MerchantType.BOTH)

                if (query.isNotBlank()) {
                    pagingSearchGrouped(sanitizeQuery(query), types, paymentMethod)
                } else {
                    pagingGetGrouped(types, paymentMethod)
                }
            }
            type == MerchantType.PHYSICAL && territory.isBlank() && bounds != GeoBounds.noBounds -> {
                // For physical merchants we search by coordinates (nearby)
                // if location services are enabled
                val types = listOf(MerchantType.PHYSICAL, MerchantType.BOTH)

                if (query.isNotBlank()) {
                    pagingSearchByCoordinates(sanitizeQuery(query), types, paymentMethod,
                            bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng,
                            sortByDistance, userLat, userLng)
                } else {
                    pagingGetByCoordinates(types, paymentMethod, bounds.northLat,
                            bounds.eastLng, bounds.southLat, bounds.westLng,
                            sortByDistance, userLat, userLng)
                }
            }
            else -> {
                // If location services are disabled or user picked a territory
                // or filter is All, we search everything and filter by territory
                val types = listOf(MerchantType.PHYSICAL, MerchantType.ONLINE, MerchantType.BOTH)
                val onlineOrder = if (onlineFirst) 0 else 2

                if (query.isNotBlank()) {
                    pagingSearchByTerritory(sanitizeQuery(query), territory,
                        types, paymentMethod, sortByDistance, userLat, userLng, onlineOrder)
                } else {
                    pagingGetByTerritory(territory, types, paymentMethod, sortByDistance, userLat, userLng, onlineOrder)
                }
            }
        }
    }

    suspend fun getPagingResultsCount(
        query: String,
        territory: String,
        type: String,
        paymentMethod: String,
        bounds: GeoBounds
    ): Int {
        return when {
            type == MerchantType.ONLINE -> {
                val types = listOf(MerchantType.ONLINE, MerchantType.BOTH)

                if (query.isNotBlank()) {
                    searchGroupedResultCount(sanitizeQuery(query), types, paymentMethod)
                } else {
                    getGroupedResultCount(types, paymentMethod)
                }
            }
            type == MerchantType.PHYSICAL && territory.isBlank() && bounds != GeoBounds.noBounds -> {
                val types = listOf(MerchantType.PHYSICAL, MerchantType.BOTH)

                if (query.isNotBlank()) {
                    searchByCoordinatesResultCount(sanitizeQuery(query), types, paymentMethod,
                            bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
                } else {
                    getByCoordinatesResultCount(types, paymentMethod, bounds.northLat,
                            bounds.eastLng, bounds.southLat, bounds.westLng)
                }
            }
            else -> {
                val types = listOf(MerchantType.PHYSICAL, MerchantType.ONLINE, MerchantType.BOTH)

                if (query.isNotBlank()) {
                    searchByTerritoryResultCount(sanitizeQuery(query), territory, types, paymentMethod)
                } else {
                    getByTerritoryResultCount(territory, types, paymentMethod)
                }
            }
        }
    }
}