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

object PaymentMethod {
    const val DASH = "dash"
    const val GIFT_CARD = "gift_card"
}

object MerchantType {
    const val ONLINE = "online"
    const val PHYSICAL = "physical"
    const val BOTH = "both"
}

@Entity(tableName = "merchant")
data class Merchant(
    @PropertyName("pluscode")
    var plusCode: String? = "",

    @PropertyName("adddate")
    var addDate: String? = "",

    @PropertyName("updatedate")
    var updateDate: String? = "",

    var address1: String? = "",
    var address2: String? = "",
    var address3: String? = "",
    var address4: String? = "",
    var latitude: Double? = 0.0,
    var longitude: Double? = 0.0,
    var territory: String? = "",
    var website: String? = "",
    var type: String? = "",

    @PropertyName("logolocation")
    var logoLocation: String? = "",

    @PropertyName("paymentmethod")
    var paymentMethod: String? = ""
) : SearchResult()