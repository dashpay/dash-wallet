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
import com.google.firebase.database.PropertyName

object AtmType {
    const val BUY = "Buy Only"
    const val SELL = "Sell Only"
    const val BOTH = "Buy and Sell"
}

@Entity(tableName = "atm")
data class Atm(
    var city: String? = "",
    var postcode: String? = "",
    var manufacturer: String? = "",

    @get:PropertyName("cover_image") @set:PropertyName("cover_image")
    var coverImage: String? = "",

    @get:PropertyName("buy_sell") @set:PropertyName("buy_sell")
    override var type: String? = "",

    @get:PropertyName("state") @set:PropertyName("state")
    override var territory: String? = ""
) : SearchResult()