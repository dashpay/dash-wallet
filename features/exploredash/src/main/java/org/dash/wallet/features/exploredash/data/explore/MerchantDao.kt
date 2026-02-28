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
        SELECT merchant.id, merchant.deeplink, merchant.plusCode, merchant.addDate, merchant.updateDate, merchant.paymentMethod, merchant.merchantId, merchant.redeemType, COALESCE((SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId), merchant.savingsPercentage, 0) as savingsPercentage, merchant.denominationsType, merchant.name, merchant.active, merchant.address1, merchant.address2, merchant.address3, merchant.address4, merchant.latitude, merchant.longitude, merchant.website, merchant.phone, merchant.territory, merchant.city, merchant.source, merchant.sourceId, merchant.logoLocation, merchant.googleMaps, merchant.coverImage, merchant.type
        FROM merchant
        WHERE (:merchantId = '' OR merchantId = :merchantId)
            AND (:source = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE source = :source COLLATE NOCASE))
            AND (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND type IN (:types)
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
        ORDER BY
            CASE WHEN :sortOption = 0 THEN merchant.name END COLLATE NOCASE ASC,
            CASE WHEN :sortOption = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC,
            CASE WHEN :sortOption = 2 THEN (SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId) END DESC
        LIMIT :limit
    """
    )
    suspend fun getByTerritory(
        merchantId: String,
        source: String,
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String,
        denomType: String,
        provider: String,
        sortOption: Int,
        anchorLat: Double,
        anchorLng: Double,
        limit: Int
    ): List<Merchant>

    @Transaction
    @Query(
        """
        SELECT merchant.id, merchant.deeplink, merchant.plusCode, merchant.addDate, merchant.updateDate, merchant.paymentMethod, merchant.merchantId, merchant.redeemType, COALESCE((SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId), merchant.savingsPercentage, 0) as savingsPercentage, merchant.denominationsType, merchant.name, merchant.active, merchant.address1, merchant.address2, merchant.address3, merchant.address4, merchant.latitude, merchant.longitude, merchant.website, merchant.phone, merchant.territory, merchant.city, merchant.source, merchant.sourceId, merchant.logoLocation, merchant.googleMaps, merchant.coverImage, merchant.type, COUNT(*) AS physical_amount
        FROM merchant 
        WHERE type IN (:types)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
        GROUP BY merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY
            CASE WHEN :sortOption = 0 THEN merchant.name END COLLATE NOCASE ASC,
            CASE WHEN :sortOption = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC,
            CASE WHEN :sortOption = 2 THEN (SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId) END DESC
    """
    )
    fun pagingGetByCoordinates(
        types: List<String>,
        paymentMethod: String,
        denomType: String,
        provider: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double,
        sortOption: Int,
        anchorLat: Double,
        anchorLng: Double
    ): PagingSource<Int, MerchantInfo>

    @Query(
        """
        SELECT COUNT(DISTINCT merchantId)
        FROM merchant
        WHERE type IN (:types)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
    """
    )
    suspend fun getByCoordinatesResultCount(
        types: List<String>,
        paymentMethod: String,
        denomType: String,
        provider: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Int

    @Transaction
    @Query(
        """
        SELECT merchant.id, merchant.deeplink, merchant.plusCode, merchant.addDate, merchant.updateDate, merchant.paymentMethod, merchant.merchantId, merchant.redeemType, COALESCE((SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId), merchant.savingsPercentage, 0) as savingsPercentage, merchant.denominationsType, merchant.name, merchant.active, merchant.address1, merchant.address2, merchant.address3, merchant.address4, merchant.latitude, merchant.longitude, merchant.website, merchant.phone, merchant.territory, merchant.city, merchant.source, merchant.sourceId, merchant.logoLocation, merchant.googleMaps, merchant.coverImage, merchant.type, COUNT(*) AS physical_amount
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
        GROUP BY merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY
            CASE WHEN :sortOption = 0 THEN merchant.name END COLLATE NOCASE ASC,
            CASE WHEN :sortOption = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC,
            CASE WHEN :sortOption = 2 THEN (SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId) END DESC
    """
    )
    fun pagingSearchByCoordinates(
        query: String,
        types: List<String>,
        paymentMethod: String,
        denomType: String,
        provider: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double,
        sortOption: Int,
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
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
    """
    )
    suspend fun searchByCoordinatesResultCount(
        query: String,
        types: List<String>,
        paymentMethod: String,
        denomType: String,
        provider: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Int

    @Transaction
    @Query(
        """
        SELECT merchant.id, merchant.deeplink, merchant.plusCode, merchant.addDate, merchant.updateDate, merchant.paymentMethod, merchant.merchantId, merchant.redeemType, COALESCE((SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId), merchant.savingsPercentage, 0) as savingsPercentage, merchant.denominationsType, merchant.name, merchant.active, merchant.address1, merchant.address2, merchant.address3, merchant.address4, merchant.latitude, merchant.longitude, merchant.website, merchant.phone, merchant.territory, merchant.city, merchant.source, merchant.sourceId, merchant.logoLocation, merchant.googleMaps, merchant.coverImage, merchant.type, COUNT(*) AS physical_amount
        FROM merchant 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND type IN (:types)
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
        GROUP BY merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY 
            CASE type
                WHEN "online"   THEN :onlineOrder
                WHEN "both"     THEN 1
                WHEN "physical" THEN :physicalOrder
            END,
            CASE WHEN :sortOption = 0 THEN merchant.name END COLLATE NOCASE ASC,
            CASE WHEN :sortOption = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC,
            CASE WHEN :sortOption = 2 THEN (SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId) END DESC
    """
    )
    fun pagingGetByTerritory(
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String,
        denomType: String,
        provider: String,
        sortOption: Int,
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
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND type IN (:types)
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
    """
    )
    suspend fun getByTerritoryResultCount(
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String,
        denomType: String,
        provider: String
    ): Int

    @Transaction
    @Query(
        """
        SELECT merchant.id, merchant.deeplink, merchant.plusCode, merchant.addDate, merchant.updateDate, merchant.paymentMethod, merchant.merchantId, merchant.redeemType, COALESCE((SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId), merchant.savingsPercentage, 0) as savingsPercentage, merchant.denominationsType, merchant.name, merchant.active, merchant.address1, merchant.address2, merchant.address3, merchant.address4, merchant.latitude, merchant.longitude, merchant.website, merchant.phone, merchant.territory, merchant.city, merchant.source, merchant.sourceId, merchant.logoLocation, merchant.googleMaps, merchant.coverImage, merchant.type, COUNT(*) AS physical_amount
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND type IN (:types)
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
        GROUP BY merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY
            CASE type
                WHEN "online"   THEN :onlineOrder
                WHEN "both"     THEN 1
                WHEN "physical" THEN :physicalOrder
            END,
            CASE WHEN :sortOption = 0 THEN merchant.name END COLLATE NOCASE ASC,
            CASE WHEN :sortOption = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC,
            CASE WHEN :sortOption = 2 THEN (SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId) END DESC
    """
    )
    fun pagingSearchByTerritory(
        query: String,
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String,
        denomType: String,
        provider: String,
        sortOption: Int,
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
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND type IN (:types)
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
    """
    )
    suspend fun searchByTerritoryResultCount(
        query: String,
        territoryFilter: String,
        types: List<String>,
        paymentMethod: String,
        denomType: String,
        provider: String
    ): Int

    @Transaction
    @Query(
        """
        SELECT merchant.id, merchant.deeplink, merchant.plusCode, merchant.addDate, merchant.updateDate, merchant.paymentMethod, merchant.merchantId, merchant.redeemType, COALESCE((SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId), merchant.savingsPercentage, 0) as savingsPercentage, merchant.denominationsType, merchant.name, merchant.active, merchant.address1, merchant.address2, merchant.address3, merchant.address4, merchant.latitude, merchant.longitude, merchant.website, merchant.phone, merchant.territory, merchant.city, merchant.source, merchant.sourceId, merchant.logoLocation, merchant.googleMaps, merchant.coverImage, merchant.type, COUNT(*) AS physical_amount
        FROM merchant
        WHERE (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND type IN (:types)
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
        GROUP BY merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY
            CASE WHEN :sortByDiscount = 0 THEN name END COLLATE NOCASE ASC,
            CASE WHEN :sortByDiscount = 1 THEN (SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId) END DESC
    """
    )
    fun pagingGetGrouped(
        types: List<String>,
        paymentMethod: String,
        denomType: String,
        provider: String,
        sortByDiscount: Boolean,
        anchorLat: Double,
        anchorLng: Double
    ): PagingSource<Int, MerchantInfo>

    @Query(
        """
        SELECT COUNT(DISTINCT merchantId)
        FROM merchant
        WHERE (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND type IN (:types)
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
    """
    )
    suspend fun getGroupedResultCount(
        types: List<String>,
        paymentMethod: String,
        denomType: String,
        provider: String
    ): Int

    @Transaction
    @Query(
        """
        SELECT merchant.id, merchant.deeplink, merchant.plusCode, merchant.addDate, merchant.updateDate, merchant.paymentMethod, merchant.merchantId, merchant.redeemType, COALESCE((SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId), merchant.savingsPercentage, 0) as savingsPercentage, merchant.denominationsType, merchant.name, merchant.active, merchant.address1, merchant.address2, merchant.address3, merchant.address4, merchant.latitude, merchant.longitude, merchant.website, merchant.phone, merchant.territory, merchant.city, merchant.source, merchant.sourceId, merchant.logoLocation, merchant.googleMaps, merchant.coverImage, merchant.type, COUNT(*) AS physical_amount
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND type IN (:types)
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
        GROUP BY merchantId
        HAVING (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) = MIN((latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng))
        ORDER BY
            CASE WHEN :sortByDiscount = 0 THEN merchant.name END COLLATE NOCASE ASC,
            CASE WHEN :sortByDiscount = 1 THEN (SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId) END DESC
    """
    )
    fun pagingSearchGrouped(
        query: String,
        types: List<String>,
        paymentMethod: String,
        denomType: String,
        provider: String,
        sortByDiscount: Boolean,
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
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND type IN (:types)
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
    """
    )
    suspend fun searchGroupedResultCount(
        query: String,
        types: List<String>,
        paymentMethod: String,
        denomType: String,
        provider: String
    ): Int

    @Query(
        """
        SELECT * 
        FROM merchant
        WHERE (:merchantId = '' OR merchantId = :merchantId)
            AND (:source = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE source = :source COLLATE NOCASE))
            AND (:excludeType = '' OR type != :excludeType)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
        LIMIT :limit
    """
    )
    fun observe(
        merchantId: String,
        source: String,
        excludeType: String,
        paymentMethod: String,
        denomType: String,
        provider: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double,
        limit: Int
    ): Flow<List<Merchant>>

    @Query(
        """
        SELECT merchant.id, merchant.deeplink, merchant.plusCode, merchant.addDate, merchant.updateDate, merchant.paymentMethod, merchant.merchantId, merchant.redeemType, COALESCE((SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId), merchant.savingsPercentage, 0) as savingsPercentage, merchant.denominationsType, merchant.name, merchant.active, merchant.address1, merchant.address2, merchant.address3, merchant.address4, merchant.latitude, merchant.longitude, merchant.website, merchant.phone, merchant.territory, merchant.city, merchant.source, merchant.sourceId, merchant.logoLocation, merchant.googleMaps, merchant.coverImage, merchant.type
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:excludeType = '' OR type != :excludeType)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
    """
    )
    fun observeSearchResults(
        query: String,
        excludeType: String,
        paymentMethod: String,
        denomType: String,
        provider: String,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Flow<List<Merchant>>

    @Query(
        """
        SELECT merchant.id, merchant.deeplink, merchant.plusCode, merchant.addDate, merchant.updateDate, merchant.paymentMethod, merchant.merchantId, merchant.redeemType, COALESCE((SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId), merchant.savingsPercentage, 0) as savingsPercentage, merchant.denominationsType, merchant.name, merchant.active, merchant.address1, merchant.address2, merchant.address3, merchant.address4, merchant.latitude, merchant.longitude, merchant.website, merchant.phone, merchant.territory, merchant.city, merchant.source, merchant.sourceId, merchant.logoLocation, merchant.googleMaps, merchant.coverImage, merchant.type 
        FROM merchant 
        WHERE (:merchantId = '' OR merchantId = :merchantId)
            AND (:source = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE source = :source COLLATE NOCASE))
            AND (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND (:excludeType = '' OR type != :excludeType)
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
        LIMIT :limit
    """
    )
    fun observeByTerritory(
        merchantId: String,
        source: String,
        territoryFilter: String,
        excludeType: String,
        paymentMethod: String,
        denomType: String,
        provider: String,
        limit: Int
    ): Flow<List<Merchant>>

    @Query(
        """
        SELECT merchant.id, merchant.deeplink, merchant.plusCode, merchant.addDate, merchant.updateDate, merchant.paymentMethod, merchant.merchantId, merchant.redeemType, COALESCE((SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId), merchant.savingsPercentage, 0) as savingsPercentage, merchant.denominationsType, merchant.name, merchant.active, merchant.address1, merchant.address2, merchant.address3, merchant.address4, merchant.latitude, merchant.longitude, merchant.website, merchant.phone, merchant.territory, merchant.city, merchant.source, merchant.sourceId, merchant.logoLocation, merchant.googleMaps, merchant.coverImage, merchant.type
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
            AND (:territoryFilter = '' OR territory = :territoryFilter)
            AND (:paymentMethod = '' OR paymentMethod = :paymentMethod)
            AND (:denomType = '' OR paymentMethod = 'dash' OR (:provider != '' AND merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider AND denominationsType = :denomType)) OR (:provider = '' AND denominationsType = :denomType))
            AND (:excludeType = '' OR type != :excludeType)
            AND redeemType <> 'url'
            AND (:provider = '' OR merchantId IN (SELECT DISTINCT merchantId FROM gift_card_providers WHERE provider = :provider))
    """
    )
    fun searchByTerritory(
        query: String,
        territoryFilter: String,
        excludeType: String,
        paymentMethod: String,
        denomType: String,
        provider: String
    ): Flow<List<Merchant>>

    @Query("SELECT DISTINCT territory FROM merchant WHERE territory IS NOT NULL")
    suspend fun getTerritories(): List<String>

    @Query("DELETE FROM merchant")
    override suspend fun deleteAll(): Int

    @Query("SELECT count(*) FROM merchant")
    suspend fun getCount(): Int

    @Query(
        """
        SELECT merchant.id, merchant.deeplink, merchant.plusCode, merchant.addDate, merchant.updateDate, merchant.paymentMethod, merchant.merchantId, merchant.redeemType, COALESCE((SELECT MAX(savingsPercentage) FROM gift_card_providers WHERE merchantId = merchant.merchantId), merchant.savingsPercentage, 0) as savingsPercentage, merchant.denominationsType, merchant.name, merchant.active, merchant.address1, merchant.address2, merchant.address3, merchant.address4, merchant.latitude, merchant.longitude, merchant.website, merchant.phone, merchant.territory, merchant.city, merchant.source, merchant.sourceId, merchant.logoLocation, merchant.googleMaps, merchant.coverImage, merchant.type FROM merchant where merchantId = :merchantId
        """
    )
    suspend fun getMerchantById(merchantId: String): Merchant?
}
