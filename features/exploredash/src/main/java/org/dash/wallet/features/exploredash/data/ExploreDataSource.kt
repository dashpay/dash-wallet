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
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.features.exploredash.data.model.*
import javax.inject.Inject

interface ExploreDataSource {
    fun observePhysicalMerchants(
        query: String,
        territory: String,
        paymentMethod: String,
        bounds: GeoBounds
    ): Flow<List<Merchant>>

    fun observeMerchantsPaging(
        query: String,
        territory: String,
        type: String,
        paymentMethod: String,
        bounds: GeoBounds,
        sortByDistance: Boolean,
        userLat: Double,
        userLng: Double,
        onlineFirst: Boolean
    ): PagingSource<Int, MerchantInfo>

    suspend fun getMerchantsResultCount(
        query: String,
        territory: String,
        type: String,
        paymentMethod: String,
        bounds: GeoBounds
    ): Int

    fun observePhysicalAtms(
        query: String,
        territory: String,
        types: List<String>,
        bounds: GeoBounds
    ): Flow<List<Atm>>

    fun observeAtmsPaging(
        query: String,
        territory: String,
        types: List<String>,
        bounds: GeoBounds,
        sortByDistance: Boolean,
        userLat: Double,
        userLng: Double
    ): PagingSource<Int, Atm>

    suspend fun getAtmsResultsCount(
        query: String,
        types: List<String>,
        territory: String,
        bounds: GeoBounds
    ): Int

    suspend fun observeMerchantLocations(
        merchantId: Long,
        source: String,
        territory: String,
        paymentMethod: String,
        bounds: GeoBounds,
        limit: Int
    ): Flow<List<Merchant>>

    suspend fun getMerchantTerritories(): List<String>
    suspend fun getAtmTerritories(): List<String>
    fun sanitizeQuery(query: String): String
}

open class MerchantAtmDataSource @Inject constructor(
    private val merchantDao: MerchantDao,
    private val atmDao: AtmDao
): ExploreDataSource {

    override fun observePhysicalMerchants(
        query: String,
        territory: String,
        paymentMethod: String,
        bounds: GeoBounds
    ): Flow<List<Merchant>> {
        return if (territory.isNotBlank()) {
            if (query.isNotBlank()) {
                merchantDao.searchByTerritory(sanitizeQuery(query), territory, MerchantType.ONLINE, paymentMethod)
            } else {
                merchantDao.observeByTerritory(-1, "", territory, MerchantType.ONLINE, paymentMethod, -1)
            }
        } else {
            if (query.isNotBlank()) {
                merchantDao.observeSearchResults(sanitizeQuery(query), MerchantType.ONLINE,
                    paymentMethod, bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
            } else {
                merchantDao.observe(-1, "", MerchantType.ONLINE, paymentMethod,
                    bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng, -1)
            }
        }
    }

    override fun observeMerchantsPaging(
        query: String,
        territory: String,
        type: String,
        paymentMethod: String,
        bounds: GeoBounds,
        sortByDistance: Boolean,
        userLat: Double,
        userLng: Double,
        onlineFirst: Boolean
    ): PagingSource<Int, MerchantInfo> {
        return when {
            type == MerchantType.ONLINE -> {
                // For Online merchants, need to get everything that can be used online
                // and group by merchant ID to avoid duplicates
                val types = listOf(MerchantType.ONLINE, MerchantType.BOTH)

                if (query.isNotBlank()) {
                    merchantDao.pagingSearchGrouped(sanitizeQuery(query), types, paymentMethod, userLat, userLng)
                } else {
                    merchantDao.pagingGetGrouped(types, paymentMethod, userLat, userLng)
                }
            }
            type == MerchantType.PHYSICAL && territory.isBlank() && bounds != GeoBounds.noBounds -> {
                // For physical merchants we search by coordinates (nearby)
                // if location services are enabled
                val types = listOf(MerchantType.PHYSICAL, MerchantType.BOTH)

                if (query.isNotBlank()) {
                    merchantDao.pagingSearchByCoordinates(sanitizeQuery(query), types, paymentMethod,
                        bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng,
                        sortByDistance, userLat, userLng)
                } else {
                    merchantDao.pagingGetByCoordinates(types, paymentMethod,
                        bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng,
                        sortByDistance, userLat, userLng)
                }
            }
            else -> {
                // If location services are disabled or user picked a territory
                // or filter is All, we search everything and filter by territory
                val types = if (type == MerchantType.PHYSICAL) {
                    listOf(MerchantType.PHYSICAL, MerchantType.BOTH)
                } else {
                    listOf(MerchantType.PHYSICAL, MerchantType.ONLINE, MerchantType.BOTH)
                }

                val onlineOrder = if (onlineFirst) 0 else 2
                val physicalOrder = if (onlineFirst) 2 else 1

                if (query.isNotBlank()) {
                    merchantDao.pagingSearchByTerritory(sanitizeQuery(query), territory, types,
                        paymentMethod, sortByDistance, userLat, userLng, onlineOrder, physicalOrder)
                } else {
                    merchantDao.pagingGetByTerritory(territory, types,
                        paymentMethod, sortByDistance, userLat, userLng, onlineOrder, physicalOrder)
                }
            }
        }
    }

    override suspend fun getMerchantsResultCount(
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
                    merchantDao.searchGroupedResultCount(sanitizeQuery(query), types, paymentMethod)
                } else {
                    merchantDao.getGroupedResultCount(types, paymentMethod)
                }
            }
            type == MerchantType.PHYSICAL && territory.isBlank() && bounds != GeoBounds.noBounds -> {
                val types = listOf(MerchantType.PHYSICAL, MerchantType.BOTH)

                if (query.isNotBlank()) {
                    merchantDao.searchByCoordinatesResultCount(sanitizeQuery(query), types, paymentMethod,
                        bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
                } else {
                    merchantDao.getByCoordinatesResultCount(types, paymentMethod, bounds.northLat,
                        bounds.eastLng, bounds.southLat, bounds.westLng)
                }
            }
            else -> {
                val types = listOf(MerchantType.PHYSICAL, MerchantType.ONLINE, MerchantType.BOTH)

                if (query.isNotBlank()) {
                    merchantDao.searchByTerritoryResultCount(sanitizeQuery(query), territory, types, paymentMethod)
                } else {
                    merchantDao.getByTerritoryResultCount(territory, types, paymentMethod)
                }
            }
        }
    }

    override fun observePhysicalAtms(
        query: String,
        territory: String,
        types: List<String>,
        bounds: GeoBounds
    ): Flow<List<Atm>> {
        return if (territory.isNotBlank()) {
            if (query.isNotBlank()) {
                atmDao.searchByTerritory(sanitizeQuery(query), territory, types)
            } else {
                atmDao.observeByTerritory(territory, types)
            }
        } else {
            if (query.isNotBlank()) {
                atmDao.observeSearchResults(sanitizeQuery(query), types, bounds.northLat,
                    bounds.eastLng, bounds.southLat, bounds.westLng)
            } else {
                atmDao.observe(types, bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
            }
        }
    }

    override fun observeAtmsPaging(
        query: String,
        territory: String,
        types: List<String>,
        bounds: GeoBounds,
        sortByDistance: Boolean,
        userLat: Double,
        userLng: Double
    ): PagingSource<Int, Atm> {
        return if (territory.isBlank() && bounds != GeoBounds.noBounds) {
            // Search by coordinates (nearby) if territory isn't specified
            if (query.isNotBlank()) {
                atmDao.pagingSearchByCoordinates(sanitizeQuery(query), types, bounds.northLat, bounds.eastLng,
                    bounds.southLat, bounds.westLng, sortByDistance, userLat, userLng)
            } else {
                atmDao.pagingGetByCoordinates(types, bounds.northLat, bounds.eastLng, bounds.southLat,
                    bounds.westLng, sortByDistance, userLat, userLng)
            }
        } else {
            // If location services are disabled or user picked a territory
            // we search everything and filter by territory
            if (query.isNotBlank()) {
                atmDao.pagingSearchByTerritory(sanitizeQuery(query), territory,
                    types, sortByDistance, userLat, userLng)
            } else {
                atmDao.pagingGetByTerritory(territory, types, sortByDistance, userLat, userLng)
            }
        }
    }

    override suspend fun getAtmsResultsCount(
        query: String,
        types: List<String>,
        territory: String,
        bounds: GeoBounds
    ): Int {
        return if (territory.isBlank() && bounds != GeoBounds.noBounds) {
            if (query.isNotBlank()) {
                atmDao.searchByCoordinatesResultCount(sanitizeQuery(query), types,
                    bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
            } else {
                atmDao.getByCoordinatesResultCount(types, bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
            }
        } else {
            if (query.isNotBlank()) {
                atmDao.searchByTerritoryResultCount(sanitizeQuery(query), territory, types)
            } else {
                atmDao.getByTerritoryResultCount(territory, types)
            }
        }
    }

    override suspend fun getMerchantTerritories(): List<String> {
        return merchantDao.getTerritories()
    }

    override suspend fun getAtmTerritories(): List<String> {
        return atmDao.getTerritories()
    }

    override suspend fun observeMerchantLocations(
        merchantId: Long,
        source: String,
        territory: String,
        paymentMethod: String,
        bounds: GeoBounds,
        limit: Int
    ): Flow<List<Merchant>> {
        return if (territory.isBlank() && bounds != GeoBounds.noBounds) {
            merchantDao.observe(merchantId, source, MerchantType.ONLINE, paymentMethod,
                bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng, limit)
        } else {
            merchantDao.observeByTerritory(merchantId, source, territory,
                MerchantType.ONLINE, paymentMethod, limit)
        }
    }

    override fun sanitizeQuery(query: String): String {
        val escapedQuotes = query.replace(Regex.fromLiteral("\""), "\"\"")
        return "\"$escapedQuotes*\""
    }
}