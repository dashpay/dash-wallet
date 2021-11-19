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
import org.dash.wallet.features.exploredash.data.model.Atm
import org.dash.wallet.features.exploredash.data.model.GeoBounds

@Dao
interface AtmDao : BaseDao<Atm> {

    @Query("""
        SELECT * 
        FROM atm 
        WHERE type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        ORDER BY name ASC
    """)
    fun pagingGetByCoordinates(
        types: List<String>,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): PagingSource<Int, Atm>

    @Query("""
        SELECT COUNT(*)
        FROM atm 
        WHERE type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
    """)
    fun getByCoordinatesResultCount(
        types: List<String>,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Int

    @Query("""
        SELECT *
        FROM atm
        JOIN atm_fts ON atm.id = atm_fts.docid
        WHERE atm_fts MATCH :query
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
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): PagingSource<Int, Atm>

    @Query("""
        SELECT COUNT(*)
        FROM atm
        JOIN atm_fts ON atm.id = atm_fts.docid
        WHERE atm_fts MATCH :query
            AND type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
    """)
    fun searchByCoordinatesResultCount(
        query: String,
        types: List<String>,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Int

    @Query("""
        SELECT * 
        FROM atm 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter)
            AND type IN (:types)
        ORDER BY name ASC""")
    fun pagingGetByTerritory(
        territoryFilter: String,
        types: List<String>
    ): PagingSource<Int, Atm>

    @Query("""
        SELECT COUNT(*) 
        FROM atm 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter)
            AND type IN (:types)
    """)
    fun getByTerritoryResultCount(
        territoryFilter: String,
        types: List<String>
    ): Int

    @Query("""
        SELECT *
        FROM atm
        JOIN atm_fts ON atm.id = atm_fts.docid
        WHERE atm_fts MATCH :query
            AND (:territoryFilter = '' OR atm_fts.territory = :territoryFilter)
            AND type IN (:types)
        ORDER BY name ASC
    """)
    fun pagingSearchByTerritory(
        query: String,
        territoryFilter: String,
        types: List<String>
    ): PagingSource<Int, Atm>

    @Query("""
        SELECT COUNT(*)
        FROM atm
        JOIN atm_fts ON atm.id = atm_fts.docid
        WHERE atm_fts MATCH :query
            AND (:territoryFilter = '' OR atm_fts.territory = :territoryFilter)
            AND type IN (:types)
    """)
    fun searchByTerritoryResultCount(
        query: String,
        territoryFilter: String,
        types: List<String>
    ): Int

    @Query("""
        SELECT * 
        FROM atm 
        WHERE type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        ORDER BY name ASC
    """)
    fun observe(
        types: List<String>,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Flow<List<Atm>>

    @Query("""
        SELECT *
        FROM atm
        JOIN atm_fts ON atm.id = atm_fts.docid
        WHERE atm_fts MATCH :query
            AND type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        ORDER BY name ASC
    """)
    fun observeSearchResults(
        query: String,
        types: List<String>,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Flow<List<Atm>>

    @Query("""
        SELECT * 
        FROM atm 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter)
            AND type IN (:types)
        ORDER BY name ASC""")
    fun observeByTerritory(
        territoryFilter: String,
        types: List<String>
    ): Flow<List<Atm>>

    @Query("""
        SELECT *
        FROM atm
        JOIN atm_fts ON atm.id = atm_fts.docid
        WHERE atm_fts MATCH :query
            AND (:territoryFilter = '' OR atm_fts.territory = :territoryFilter)
            AND type IN (:types)
        ORDER BY name ASC
    """)
    fun searchByTerritory(
        query: String,
        territoryFilter: String,
        types: List<String>
    ): Flow<List<Atm>>

    @Query("SELECT DISTINCT territory FROM atm")
    suspend fun getTerritories(): List<String>

    @Query("DELETE FROM atm WHERE source LIKE :source")
    override suspend fun deleteAll(source: String): Int

    fun observePhysical(
        query: String,
        territory: String,
        types: List<String>,
        bounds: GeoBounds
    ): Flow<List<Atm>> {
        return if (territory.isNotBlank()) {
            if (query.isNotBlank()) {
                searchByTerritory(sanitizeQuery(query), territory, types)
            } else {
                observeByTerritory(territory, types)
            }
        } else {
            if (query.isNotBlank()) {
                observeSearchResults(sanitizeQuery(query), types, bounds.northLat,
                    bounds.eastLng, bounds.southLat, bounds.westLng)
            } else {
                observe(types, bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
            }
        }
    }

    fun observeAllPaging(
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
                pagingSearchByCoordinates(sanitizeQuery(query), types,
                    bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
            } else {
                pagingGetByCoordinates(types, bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
            }
        } else {
            // If location services are disabled or user picked a territory
            // we search everything and filter by territory
            if (query.isNotBlank()) {
                pagingSearchByTerritory(sanitizeQuery(query), territory, types)
            } else {
                pagingGetByTerritory(territory, types)
            }
        }
    }

    suspend fun getPhysicalResultsCount(
        query: String,
        types: List<String>,
        territoryFilter: String,
        bounds: GeoBounds
    ): Int {
        return if (territoryFilter.isNotBlank()) {
            if (query.isNotBlank()) {
                searchByTerritoryResultCount(sanitizeQuery(query), territoryFilter, types)
            } else {
                getByTerritoryResultCount(territoryFilter, types)
            }
        } else {
            if (query.isNotBlank()) {
                searchByCoordinatesResultCount(sanitizeQuery(query), types,
                    bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
            } else {
                getByCoordinatesResultCount(types,
                    bounds.northLat, bounds.eastLng, bounds.southLat, bounds.westLng)
            }
        }
    }
}