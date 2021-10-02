package org.dash.wallet.features.exploredash.data.model

import androidx.room.Entity
import androidx.room.Fts4

// Virtual table for Full-Text Search over Merchant table
@Entity(tableName = "merchant_fts")
@Fts4(contentEntity = Merchant::class)
data class MerchantFTS(
    val name: String,
    val address1: String,
    val address2: String,
    val address3: String,
    val address4: String,
    var territory: String,
)