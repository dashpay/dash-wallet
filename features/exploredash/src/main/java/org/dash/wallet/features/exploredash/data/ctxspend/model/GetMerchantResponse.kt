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
package org.dash.wallet.features.exploredash.data.ctxspend.model

/**
 * denominationType: "min-max", "min-max-major" or "fixed"
 */
data class GetMerchantResponse(
    val id: String,
    val denominations: List<String>,
    val denominationsType: String,
    val savingsPercentage: Int = 0,
    val redeemType: String = "",
    val enabled: Boolean = true
) {
    val minimumCardPurchase: Double
        get() {
            require(denominations.isNotEmpty())
            return denominations[0].toDouble()
        }
    val maximumCardPurchase: Double
        get() {
            return when (denominationsType) {
                "min-max" -> {
                    require(denominations.size == 2)
                    denominations[1].toDouble()
                }
                "min-max-major" -> {
                    require(denominations.size == 3)
                    denominations[1].toDouble()
                }
                "fixed" -> {
                    require(denominations.isNotEmpty())
                    denominations.last().toDouble()
                }
                else -> error("unknown denomination type")
            }
        }
}
