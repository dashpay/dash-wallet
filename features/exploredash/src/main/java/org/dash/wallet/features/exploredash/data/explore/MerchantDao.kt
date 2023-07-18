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

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.data.explore.model.MerchantInfo

@Dao
interface MerchantDao : BaseDao<Merchant> {
    // Sorting by distance is approximate - it's done using "flat-earth" Pythagorean formula.
    // It's good enough for our purposes, but if there is a need to display the distance
    // in UI it should be done using map APIs.
    @Query(
        """
        SELECT *
        FROM merchant
        WHERE (:merchantId = -1 OR merchantId = :merchantId)
            AND (:source = '' OR source = :source COLLATE NOCASE)
            AND (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
        ORDER BY
            CASE WHEN :sortByDistance = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC,
            CASE WHEN :sortByDistance = 0 THEN merchant.name END COLLATE NOCASE ASC
        LIMIT :limit
    """
    )
    suspend fun getByTerritory(
        merchantId: Long,
        source: String,
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String,
        sortByDistance: Boolean,
        anchorLat: Double,
        anchorLng: Double,
        limit: Int
    ): List<Merchant>

    @Transaction
    @Query(
        """
        SELECT *, COUNT(*) AS physical_amount
        FROM merchant 
        WHERE type IN (:types)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        GROUP BY source, merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY
            CASE WHEN :sortByDistance = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC, 
            CASE WHEN :sortByDistance = 0 THEN name END COLLATE NOCASE ASC
    """
    )
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
    ): PagingSource<Int, MerchantInfo>

    @Query(
        """
        SELECT COUNT(DISTINCT merchantId)
        FROM merchant
        WHERE type IN (:types)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
    """
    )
    suspend fun getByCoordinatesResultCount(
        types: List<String>,
        paymentMethod: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Int

    @Transaction
    @Query(
        """
        SELECT *, COUNT(*) AS physical_amount
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        GROUP BY source, merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY
            CASE WHEN :sortByDistance = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC, 
            CASE WHEN :sortByDistance = 0 THEN merchant.name END COLLATE NOCASE ASC
    """
    )
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
    ): PagingSource<Int, MerchantInfo>

    @Query(
        """
        SELECT COUNT(DISTINCT merchantId)
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
    """
    )
    suspend fun searchByCoordinatesResultCount(
        query: String,
        types: List<String>,
        paymentMethod: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Int

    @Transaction
    @Query(
        """
        SELECT *, COUNT(*) AS physical_amount
        FROM merchant 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
        GROUP BY source, merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY 
            CASE type
                WHEN "online"   THEN :onlineOrder
                WHEN "both"     THEN 1
                WHEN "physical" THEN :physicalOrder
            END,
            CASE WHEN :sortByDistance = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC,
            CASE WHEN :sortByDistance = 0 THEN merchant.name END COLLATE NOCASE ASC
    """
    )
    fun pagingGetByTerritory(
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String,
        sortByDistance: Boolean,
        anchorLat: Double,
        anchorLng: Double,
        onlineOrder: Int,
        physicalOrder: Int
    ): PagingSource<Int, MerchantInfo>

    @Query(
        """
        SELECT COUNT(DISTINCT merchantId)
        FROM merchant 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
    """
    )
    suspend fun getByTerritoryResultCount(territoryFilter: String, types: List<String>, paymentMethod: String): Int

    @Transaction
    @Query(
        """
        SELECT *, COUNT(*) AS physical_amount
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
        GROUP BY source, merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY
            CASE type
                WHEN "online"   THEN :onlineOrder
                WHEN "both"     THEN 1
                WHEN "physical" THEN :physicalOrder
            END,
            CASE WHEN :sortByDistance = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC,
            CASE WHEN :sortByDistance = 0 THEN merchant.name END COLLATE NOCASE ASC
    """
    )
    fun pagingSearchByTerritory(
        query: String,
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String,
        sortByDistance: Boolean,
        anchorLat: Double,
        anchorLng: Double,
        onlineOrder: Int,
        physicalOrder: Int
    ): PagingSource<Int, MerchantInfo>

    @Query(
        """
        SELECT COUNT(DISTINCT merchantId)
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
    """
    )
    suspend fun searchByTerritoryResultCount(
        query: String,
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String
    ): Int

    @Transaction
    @Query(
        """
        SELECT *, COUNT(*) AS physical_amount
        FROM merchant
        WHERE (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
        GROUP BY source, merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY name COLLATE NOCASE ASC
    """
    )
    fun pagingGetGrouped(
        types: List<String>,
        paymentMethod: String,
        anchorLat: Double,
        anchorLng: Double
    ): PagingSource<Int, MerchantInfo>

    @Query(
        """
        SELECT COUNT(DISTINCT merchantId)
        FROM merchant
        WHERE (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
    """
    )
    suspend fun getGroupedResultCount(types: List<String>, paymentMethod: String): Int

    @Transaction
    @Query(
        """
        SELECT *, COUNT(*) AS physical_amount
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
        GROUP BY source, merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY name COLLATE NOCASE ASC
    """
    )
    fun pagingSearchGrouped(
        query: String,
        types: List<String>,
        paymentMethod: String,
        anchorLat: Double,
        anchorLng: Double
    ): PagingSource<Int, MerchantInfo>

    @Query(
        """
        SELECT COUNT(DISTINCT merchantId)
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND type IN (:types)
    """
    )
    suspend fun searchGroupedResultCount(query: String, types: List<String>, paymentMethod: String): Int

    @Query(
        """
        SELECT * 
        FROM merchant
        WHERE (:merchantId = -1 OR merchantId = :merchantId)
            AND (:source = '' OR source = :source COLLATE NOCASE)
            AND (:excludeType = '' OR type != :excludeType)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        LIMIT :limit
    """
    )
    fun observe(
        merchantId: Long,
        source: String,
        excludeType: String,
        paymentMethod: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double,
        limit: Int
    ): Flow<List<Merchant>>

    @Query(
        """
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
    """
    )
    fun observeSearchResults(
        query: String,
        excludeType: String,
        paymentMethod: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Flow<List<Merchant>>

    @Query(
        """
        SELECT * 
        FROM merchant 
        WHERE (:merchantId = -1 OR merchantId = :merchantId)
            AND (:source = '' OR source = :source COLLATE NOCASE) 
            AND (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:excludeType = '' OR type != :excludeType)
        LIMIT :limit
    """
    )
    fun observeByTerritory(
        merchantId: Long,
        source: String,
        territoryFilter: String,
        excludeType: String,
        paymentMethod: String,
        limit: Int
    ): Flow<List<Merchant>>

    @Query(
        """
        SELECT *
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:excludeType = '' OR type != :excludeType)
    """
    )
    fun searchByTerritory(
        query: String,
        territoryFilter: String,
        excludeType: String,
        paymentMethod: String
    ): Flow<List<Merchant>>

    @Query("SELECT DISTINCT territory FROM merchant")
    suspend fun getTerritories(): List<String>

    @Query("DELETE FROM merchant")
    override suspend fun deleteAll(): Int

    @Query("SELECT count(*) FROM merchant")
    suspend fun getCount(): Int
}
