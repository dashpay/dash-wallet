
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

package org.dash.wallet.features.exploredash.repository

import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.safeApiCall
import org.dash.wallet.features.exploredash.data.model.purchase.PurchaseGiftCardRequest
import org.dash.wallet.features.exploredash.data.model.purchase.PurchaseGiftCardResponse
import org.dash.wallet.features.exploredash.data.model.signin.SignInRequest
import org.dash.wallet.features.exploredash.network.service.DashDirectAuthApi
import org.dash.wallet.features.exploredash.network.service.DashDirectServicesApi
import org.dash.wallet.features.exploredash.utils.DashDirectConfig
import javax.inject.Inject

class DashDirectRepository @Inject constructor(
    private val servicesApi: DashDirectServicesApi,
    private val authApi: DashDirectAuthApi,
    private val config: DashDirectConfig
) : DashDirectRepositoryInt {

    override suspend fun signIn(
        email: String,
        password: String
    ): ResponseResource<Boolean> = safeApiCall {
        authApi.signIn(signInRequest = SignInRequest(emailAddress = email, password = password))
            .also {
                it?.data?.token?.let { token ->
                    config.setPreference(DashDirectConfig.PREFS_KEY_LAST_DASH_DIRECT_ACCESS_TOKEN, token)
                }
            }
        config.getPreference(DashDirectConfig.PREFS_KEY_LAST_DASH_DIRECT_ACCESS_TOKEN)?.isNotEmpty() ?: false
    }

    override fun isUserSignIn() =
        runBlocking { config.getPreference(DashDirectConfig.PREFS_KEY_LAST_DASH_DIRECT_ACCESS_TOKEN)?.isNotEmpty() ?: true }

    fun reset() {
        runBlocking { config.setPreference(DashDirectConfig.PREFS_KEY_LAST_DASH_DIRECT_ACCESS_TOKEN, "") }
    }

    override suspend fun purchaseGiftCard(
        deviceID: String,
        currency: String,
        giftCardAmount: Double,
        merchantId: Long,
        userEmail: String
    ) = safeApiCall {
        servicesApi.purchaseGiftCard(
            deviceID = deviceID,
            purchaseGiftCardRequest = PurchaseGiftCardRequest(
                currency = currency,
                giftCardAmount = giftCardAmount,
                merchantId = merchantId
            ),
            email = userEmail
        )
    }
}
interface DashDirectRepositoryInt {
    suspend fun signIn(email: String, password: String): ResponseResource<Boolean>
    fun isUserSignIn(): Boolean
    suspend fun purchaseGiftCard(deviceID: String, currency: String, giftCardAmount: Double, merchantId: Long, userEmail: String):
        ResponseResource<PurchaseGiftCardResponse?>
}
