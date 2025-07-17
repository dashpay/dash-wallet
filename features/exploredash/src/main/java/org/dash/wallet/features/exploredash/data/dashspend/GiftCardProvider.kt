package org.dash.wallet.features.exploredash.data.dashspend

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gift_card_providers")
data class GiftCardProvider(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val merchantId: String,
    val provider: String,
    val redeemType: String,
    val savingsPercentage: Int,
    val active: Boolean,
    val denominationsType: String,
    val sourceId: String
)