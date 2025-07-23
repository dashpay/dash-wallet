/*
 * Copyright 2023 Dash Core Group.
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
package org.dash.wallet.features.exploredash.data.dashspend.ctx.model

sealed class DenominationType(val value: String) {
    object MinMax : DenominationType("min-max")
    object MinMaxMajor : DenominationType("min-max-major")
    object Fixed : DenominationType("fixed")

    companion object {
        fun fromString(value: String): DenominationType {
            return when (value) {
                MinMax.value -> MinMax
                MinMaxMajor.value -> MinMaxMajor
                Fixed.value -> Fixed
                else -> throw IllegalArgumentException("Unknown denomination type: $value")
            }
        }
    }
}

/**
 * denominations handling for merchant response
 */
data class GetMerchantResponse(
    val id: String,
    val denominations: List<String>,
    val denominationsType: String,
    val savingsPercentage: Int = 0,
    val redeemType: String = "",
    val enabled: Boolean = true
) {
    val denominationType: DenominationType
        get() = DenominationType.fromString(denominationsType)

    val minimumCardPurchase: Double
        get() {
            require(denominations.isNotEmpty())
            return denominations[0].toDouble()
        }
    val maximumCardPurchase: Double
        get() {
            return when (denominationType) {
                is DenominationType.MinMax -> {
                    require(denominations.size == 2)
                    denominations[1].toDouble()
                }
                is DenominationType.MinMaxMajor -> {
                    require(denominations.size == 3)
                    denominations[1].toDouble()
                }
                is DenominationType.Fixed -> {
                    require(denominations.isNotEmpty())
                    denominations.last().toDouble()
                }
            }
        }
}
