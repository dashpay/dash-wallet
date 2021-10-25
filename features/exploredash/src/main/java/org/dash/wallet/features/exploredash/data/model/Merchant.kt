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
import androidx.annotation.Keep
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

@Keep
@Entity(tableName = "merchant")
data class Merchant(
    var deeplink: String? = "",

    @get:PropertyName("source_id") @set:PropertyName("source_id")
    var sourceId: Long? = null,

    var source: String? = "",

    @get:PropertyName("plus_code") @set:PropertyName("plus_code")
    var plusCode: String? = "",

    @get:PropertyName("add_date") @set:PropertyName("add_date")
    var addDate: String? = "",

    @get:PropertyName("update_date") @set:PropertyName("update_date")
    var updateDate: String? = "",

    @get:PropertyName("payment_method") @set:PropertyName("payment_method")
    var paymentMethod: String? = "",

    @get:PropertyName("merchant_id") @set:PropertyName("merchant_id")
    var merchantId: Long? = null
) : SearchResult()