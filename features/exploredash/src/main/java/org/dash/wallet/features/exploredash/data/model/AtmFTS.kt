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