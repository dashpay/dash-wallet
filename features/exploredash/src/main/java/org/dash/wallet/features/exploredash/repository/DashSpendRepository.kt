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

package org.dash.wallet.features.exploredash.repository

import kotlinx.coroutines.flow.Flow
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardInfo
import org.dash.wallet.features.exploredash.data.dashspend.model.UpdatedMerchantDetails

interface DashSpendRepository {
    val userEmail: Flow<String?>

    suspend fun signup(email: String): Boolean
    suspend fun login(email: String): Boolean
    suspend fun verifyEmail(code: String): Boolean
    suspend fun isUserSignedIn(): Boolean
    suspend fun logout()
    suspend fun refreshToken(): Boolean // TODO

    /** obtain up-to-date information about the merchant from the service */
    suspend fun getMerchant(merchantId: String): UpdatedMerchantDetails?
    suspend fun orderGiftcard(
        cryptoCurrency: String,
        fiatCurrency: String,
        fiatAmount: String,
        merchantId: String
    ): GiftCardInfo
    suspend fun getGiftCard(giftCardId: String): GiftCardInfo?
    fun getGiftCardDiscount(merchantId: String, denomination: Double): Double
}
