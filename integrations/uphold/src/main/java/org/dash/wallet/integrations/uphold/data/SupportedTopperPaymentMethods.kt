/*
 * Copyright 2024 Dash Core Group.
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

package org.dash.wallet.integrations.uphold.data

data class SupportedTopperPaymentMethods(
    val paymentMethods: List<TopperPaymentMethod>
)

data class TopperPaymentMethod(
    val billingAsset: String,
    val countries: List<String>,
    val limits: List<PaymentMethodLimit>,
    val network: String,
    val type: String

)

data class PaymentMethodLimit(
    val asset: String,
    val maximum: String,
    val minimum: String
)
