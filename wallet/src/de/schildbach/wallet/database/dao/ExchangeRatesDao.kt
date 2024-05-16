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

package de.schildbach.wallet.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.data.entity.ExchangeRate

@Dao
interface ExchangeRatesDao {
    @Query("SELECT * FROM exchange_rates ORDER BY currencyCode")
    fun observeAll(): Flow<List<ExchangeRate>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(exchangeRates: List<ExchangeRate>)

    @Query("SELECT * FROM exchange_rates WHERE currencyCode = :currencyCode LIMIT 1")
    fun observeRate(currencyCode: String): Flow<ExchangeRate>

    @Query("SELECT * FROM exchange_rates WHERE currencyCode = :currencyCode LIMIT 1")
    fun getRateSync(currencyCode: String?): ExchangeRate?

    @Query("SELECT * FROM exchange_rates WHERE currencyCode = :currencyCode LIMIT 1")
    suspend fun getExchangeRateForCurrency(currencyCode: String?): ExchangeRate?

    @Query("SELECT count(*) FROM exchange_rates")
    suspend fun count(): Int

    @Query("DELETE FROM exchange_rates WHERE currencyCode IN (:currencyCodes)")
    suspend fun delete(currencyCodes: Collection<String>)

    @Query("SELECT * FROM exchange_rates ORDER BY currencyCode")
    suspend fun getAll(): List<ExchangeRate>
}
