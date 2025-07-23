/*
 * Copyright 2025 Dash Core Group.
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
package org.dash.wallet.features.exploredash.data.dashspend.piggycards.model

import com.google.gson.annotations.SerializedName

data class MerchantDetails(
    @SerializedName("id")
    val id: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("discount")
    val discount: Double?,
    @SerializedName("imageUrl")
    val imageUrl: String?,
    @SerializedName("fixedDenominations")
    val fixedDenominations: List<Double>?,
    @SerializedName("variableDenomination")
    val variableDenomination: Boolean,
    @SerializedName("minAmount")
    val minAmount: Double?,
    @SerializedName("maxAmount")
    val maxAmount: Double?
)