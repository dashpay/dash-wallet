package org.dash.wallet.features.exploredash.data.explore.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gift_card_providers")
data class GiftCardProvider(
    @PrimaryKey val id: Int,
    val merchantId: Int,
    val provider: String,
    val redeemType: String,
    val savingsPercentage: Int,
    val active: Int,
    val denominationsType: String,
    val sourceId: Int
)
