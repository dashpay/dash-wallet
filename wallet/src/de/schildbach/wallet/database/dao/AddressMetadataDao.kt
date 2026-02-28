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

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.entity.AddressMetadata
import org.dash.wallet.common.data.TaxCategory

/**
 * @author Eric Britten
 */
@Dao
interface AddressMetadataDao {
    @Query("SELECT COUNT(1) FROM address_metadata WHERE address = :address AND isInput = :isInput;")
    suspend fun exists(address: String, isInput: Boolean): Boolean

    @Query("SELECT * FROM address_metadata WHERE address = :address AND isInput = 0")
    suspend fun loadRecipient(address: String): AddressMetadata?

    @Query("SELECT * FROM address_metadata WHERE address = :address AND isInput = 1")
    suspend fun loadSender(address: String): AddressMetadata?

    @Query("INSERT into address_metadata (address, isInput, taxCategory, service) VALUES (:address, :isInput, :taxCategory, :service)")
    suspend fun markAddress(address: String, isInput: Boolean, taxCategory: TaxCategory, service: String)

    @Query("SELECT * FROM address_metadata WHERE address = :address")
    fun observe(address: Sha256Hash): Flow<AddressMetadata?>

    @Query("DELETE FROM address_metadata")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM address_metadata")
    suspend fun count(): Int
}