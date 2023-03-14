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
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.features.exploredash.data.explore.model.Atm

@Dao
interface AtmDao : BaseDao<Atm> {

    @Query(
        """
        SELECT * 
        FROM atm 
        WHERE type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        ORDER BY
            CASE WHEN :sortByDistance = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC, 
            CASE WHEN :sortByDistance = 0 THEN name END COLLATE NOCASE ASC
    """
    )
    fun pagingGetByCoordinates(
        types: List<String>,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double,
        sortByDistance: Boolean,
        anchorLat: Double,
        anchorLng: Double
    ): PagingSource<Int, Atm>

    @Query(
        """
        SELECT COUNT(*)
        FROM atm 
        WHERE type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
    """
    )
    suspend fun getByCoordinatesResultCount(
        types: List<String>,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Int

    @Query(
        """
        SELECT *
        FROM atm
        JOIN atm_fts ON atm.id = atm_fts.docid
        WHERE atm_fts MATCH :query
            AND type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        ORDER BY
            CASE WHEN :sortByDistance = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC, 
            CASE WHEN :sortByDistance = 0 THEN atm.name END COLLATE NOCASE ASC
    """
    )
    fun pagingSearchByCoordinates(
        query: String,
        types: List<String>,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double,
        sortByDistance: Boolean,
        anchorLat: Double,
        anchorLng: Double
    ): PagingSource<Int, Atm>

    @Query(
        """
        SELECT COUNT(*)
        FROM atm
        JOIN atm_fts ON atm.id = atm_fts.docid
        WHERE atm_fts MATCH :query
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
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Int

    @Query(
        """
        SELECT * 
        FROM atm 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter)
            AND type IN (:types)
        ORDER BY
            CASE WHEN :sortByDistance = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC, 
            CASE WHEN :sortByDistance = 0 THEN name END COLLATE NOCASE ASC
    """
    )
    fun pagingGetByTerritory(
        territoryFilter: String,
        types: List<String>,
        sortByDistance: Boolean,
        anchorLat: Double,
        anchorLng: Double
    ): PagingSource<Int, Atm>

    @Query(
        """
        SELECT COUNT(*) 
        FROM atm 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter)
            AND type IN (:types)
    """
    )
    suspend fun getByTerritoryResultCount(territoryFilter: String, types: List<String>): Int

    @Query(
        """
        SELECT *
        FROM atm
        JOIN atm_fts ON atm.id = atm_fts.docid
        WHERE atm_fts MATCH :query
            AND (:territoryFilter = '' OR territory = :territoryFilter)
            AND type IN (:types)
        ORDER BY
            CASE WHEN :sortByDistance = 1 THEN (latitude - :anchorLat)*(latitude - :anchorLat) + (longitude - :anchorLng)*(longitude - :anchorLng) END ASC, 
            CASE WHEN :sortByDistance = 0 THEN atm.name END COLLATE NOCASE ASC
    """
    )
    fun pagingSearchByTerritory(
        query: String,
        territoryFilter: String,
        types: List<String>,
        sortByDistance: Boolean,
        anchorLat: Double,
        anchorLng: Double
    ): PagingSource<Int, Atm>

    @Query(
        """
        SELECT COUNT(*)
        FROM atm
        JOIN atm_fts ON atm.id = atm_fts.docid
        WHERE atm_fts MATCH :query
            AND (:territoryFilter = '' OR territory = :territoryFilter)
            AND type IN (:types)
    """
    )
    suspend fun searchByTerritoryResultCount(query: String, territoryFilter: String, types: List<String>): Int

    @Query(
        """
        SELECT * 
        FROM atm 
        WHERE type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        ORDER BY name ASC
    """
    )
    fun observe(
        types: List<String>,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Flow<List<Atm>>

    @Query(
        """
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
    """
    )
    fun observeSearchResults(
        query: String,
        types: List<String>,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Flow<List<Atm>>

    @Query(
        """
        SELECT * 
        FROM atm 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter)
            AND type IN (:types)
        ORDER BY name ASC"""
    )
    fun observeByTerritory(territoryFilter: String, types: List<String>): Flow<List<Atm>>

    @Query(
        """
        SELECT *
        FROM atm
        JOIN atm_fts ON atm.id = atm_fts.docid
        WHERE atm_fts MATCH :query
            AND (:territoryFilter = '' OR territory = :territoryFilter)
            AND type IN (:types)
        ORDER BY name ASC
    """
    )
    fun searchByTerritory(query: String, territoryFilter: String, types: List<String>): Flow<List<Atm>>

    @Query("SELECT DISTINCT territory FROM atm")
    suspend fun getTerritories(): List<String>

    @Query("DELETE FROM atm")
    override suspend fun deleteAll(): Int

    @Query("SELECT count(*) FROM atm")
    suspend fun getCount(): Int
}
