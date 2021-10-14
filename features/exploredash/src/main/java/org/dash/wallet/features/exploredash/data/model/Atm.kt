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
import androidx.room.Ignore
import com.google.firebase.database.PropertyName

object AtmType {
    const val BUY = "buy"
    const val SELL = "sell"
    const val BOTH = "both"
}

@Entity(tableName = "atm")
data class Atm(
    var city: String? = "",
    var image: String? = "",
    var phone: String? = "",
    var postcode: String? = "",
    var website: String? = "",
    var type: String? = "",
    var manufacturer: String? = "",

    @get:PropertyName("address_line_1") @set:PropertyName("address_line_1")
    var address1: String? = "",
    @get:PropertyName("display_address") @set:PropertyName("display_address")
    var displayAddress: String? = "",
    @get:PropertyName("lat") @set:PropertyName("lat")
//    var latitude: Double? = 0.0,
    var latitude: String? = "",
    @get:PropertyName("lng") @set:PropertyName("lng")
//    var longitude: Double? = 0.0,
    var longitude: String? = "",
    @get:PropertyName("state") @set:PropertyName("state")
    var territory: String? = "",
    @get:PropertyName("logo_location") @set:PropertyName("logo_location")
    var logoLocation: String? = "",
) : SearchResult()