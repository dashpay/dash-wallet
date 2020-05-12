package de.schildbach.wallet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dashpay_profile")
data class DashPayProfile(@PrimaryKey val userId: String, val displayName: String, val publicMessage: String,
                          val avatarUrl: String)