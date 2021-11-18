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

package org.dash.wallet.features.exploredash.data.model

import androidx.room.Entity
import androidx.room.Fts4

// Virtual table for Full-Text Search over Atm table
@Entity(tableName = "atm_fts")
@Fts4(contentEntity = Atm::class)
data class AtmFTS(
    val name: String,
    val manufacturer: String,
    val address1: String,
    val address2: String,
    val address3: String,
    val address4: String,
    val city: String,
    val territory: String
)