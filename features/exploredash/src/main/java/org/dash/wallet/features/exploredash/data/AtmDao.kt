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
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.features.exploredash.data.model.Atm
import org.dash.wallet.features.exploredash.services.GeoBounds

@Dao
interface AtmDao : BaseDao<Atm> {

    @Query("""
        SELECT * 
        FROM atm 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter) 
            AND type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        ORDER BY name ASC
    """)
    fun pagingGet(
        territoryFilter: String,
        types: List<String>,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): PagingSource<Int, Atm>

    @Query("""
        SELECT *
        FROM atm
        JOIN atm_fts ON atm.id = atm_fts.docid
        WHERE atm_fts MATCH :query 
            AND (:territoryFilter = '' OR atm_fts.territory = :territoryFilter)
            AND type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        ORDER BY name ASC
    """)
    fun pagingSearch(
        query: String,
        territoryFilter: String,
        types: List<String>,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): PagingSource<Int, Atm>

    @Query("""
        SELECT * 
        FROM atm 
        WHERE (:territoryFilter = '' OR territory = :territoryFilter) 
            AND type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        ORDER BY name ASC
    """)
    fun observe(
        territoryFilter: String,
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
            AND (:territoryFilter = '' OR atm_fts.territory = :territoryFilter)
            AND type IN (:types)
            AND latitude < :northLat
            AND latitude > :southLat
            AND longitude < :eastLng
            AND longitude > :westLng
        ORDER BY name ASC
    """)
    fun observeSearchResults(
        query: String,
        territoryFilter: String,
        types: List<String>,
        northLat: Double,
        eastLng: Double,
        southLat: Double,
        westLng: Double
    ): Flow<List<Atm>>

    @Query("SELECT * FROM atm WHERE id = :atmId LIMIT 1")
    suspend fun getAtm(atmId: Int): Atm?

    @Query("SELECT * FROM atm WHERE id = :atmId LIMIT 1")
    fun observeAtm(atmId: Int): Flow<Atm?>

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
        return if (query.isNotBlank()) {
            observeSearchResults(
                sanitizeQuery(query),
                territory,
                types,
                bounds.northLat,
                bounds.eastLng,
                bounds.southLat,
                bounds.westLng
            )
        } else {
            observe(
                territory,
                types,
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
        bounds: GeoBounds
    ): PagingSource<Int, Atm> {
        return if (query.isNotBlank()) {
            pagingSearch(
                sanitizeQuery(query),
                territory,
                types,
                bounds.northLat,
                bounds.eastLng,
                bounds.southLat,
                bounds.westLng
            )
        } else {
            pagingGet(
                territory,
                types,
                bounds.northLat,
                bounds.eastLng,
                bounds.southLat,
                bounds.westLng
            )
        }
    }
}