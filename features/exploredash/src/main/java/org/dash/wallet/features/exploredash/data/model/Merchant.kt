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
import androidx.annotation.Keep
import androidx.room.Index
import com.google.firebase.database.PropertyName

object PaymentMethod {
    const val DASH = "dash"
    const val GIFT_CARD = "gift card"
}

object MerchantType {
    const val ONLINE = "online"
    const val PHYSICAL = "physical"
    const val BOTH = "both"
}

@Keep
@Entity(
    tableName = "merchant",
    indices = [
        Index("latitude"),
        Index("longitude"),
    ]
)
data class Merchant(
    var deeplink: String? = "",

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