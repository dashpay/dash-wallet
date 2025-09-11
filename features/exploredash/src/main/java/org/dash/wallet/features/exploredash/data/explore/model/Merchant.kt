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

package org.dash.wallet.features.exploredash.data.explore.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider

object PaymentMethod {
    const val DASH = "dash"
    const val GIFT_CARD = "gift card"
}

object MerchantType {
    const val ONLINE = "online"
    const val PHYSICAL = "physical"
    const val BOTH = "both"
}

@Entity(
    tableName = "merchant",
    indices =
    [
        Index("latitude"),
        Index("longitude")
    ]
)
data class Merchant(
    var deeplink: String? = "",
    var plusCode: String? = "",
    var addDate: String? = "",
    var updateDate: String? = "",
    var paymentMethod: String? = "",
    var merchantId: String? = null,
    var redeemType: String? = "",
    var savingsPercentage: Int? = 0, // in basis points 1 = 0.01%
    var denominationsType: String? = "",
    @Ignore var minCardPurchase: Double? = null,
    @Ignore var maxCardPurchase: Double? = null,
    @Ignore var physicalAmount: Int = 0,
    @Ignore var fixedDenomination: Boolean = false,
    @Ignore var denominations: List<Int> = listOf(),
    @Ignore var giftCardProviders: List<GiftCardProvider> = listOf()
) : SearchResult() {

    // 1% discount is 0.01
    val savingsFraction: Double
        get() = (savingsPercentage?.toDouble() ?: 0.0) / 10000

    // 1% discount is 1.00
    val savingsPercentageAsDouble: Double
        get() = savingsFraction * 100

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Merchant) return false
        
        return id == other.id && 
           name == other.name &&
           active == other.active &&
           giftCardProviders.map { it.active } == other.giftCardProviders.map { it.active }
    }
    
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (active?.hashCode() ?: 0)
        result = 31 * result + giftCardProviders.map { it.active }.hashCode()
        return result
    }

    fun deepCopy(
        savingsPercentage: Int? = null,
        giftCardProviders: List<GiftCardProvider>? = null
    ): Merchant =
        this.copy(
            savingsPercentage = savingsPercentage ?: this.savingsPercentage,
            giftCardProviders = giftCardProviders ?: this.giftCardProviders
        ).also { copy ->
            // copy over SearchResult in bulk
            copy.applySearchResultFrom(this)
        }

    private fun applySearchResultFrom(other: Merchant) {
        this.id = other.id
        this.active = other.active
        this.name = other.name
        this.address1 = other.address1
        this.address2 = other.address2
        this.address3 = other.address3
        this.address4 = other.address4
        this.latitude = other.latitude
        this.longitude = other.longitude
        this.website = other.website
        this.phone = other.phone
        this.territory = other.territory
        this.city = other.city
        this.source = other.source
        this.sourceId = other.sourceId
        this.logoLocation = other.logoLocation
        this.googleMaps = other.googleMaps
        this.coverImage = other.coverImage
        this.type = other.type
        this.distance = other.distance
    }
}
