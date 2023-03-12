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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.safeApiCall
import org.dash.wallet.features.exploredash.data.dashdirect.model.giftcard.GetGiftCardRequest
import org.dash.wallet.features.exploredash.data.dashdirect.model.giftcard.GetGiftCardResponse
import org.dash.wallet.features.exploredash.data.dashdirect.model.merchant.GetMerchantByIdRequest
import org.dash.wallet.features.exploredash.data.dashdirect.model.merchant.GetMerchantByIdResponse
import org.dash.wallet.features.exploredash.data.dashdirect.model.paymentstatus.PaymentStatusRequest
import org.dash.wallet.features.exploredash.data.dashdirect.model.paymentstatus.PaymentStatusResponse
import org.dash.wallet.features.exploredash.data.dashdirect.model.purchase.PurchaseGiftCardRequest
import org.dash.wallet.features.exploredash.data.dashdirect.model.purchase.PurchaseGiftCardResponse
import org.dash.wallet.features.exploredash.data.dashdirect.model.signin.VerifyEmailRequest
import org.dash.wallet.features.exploredash.network.service.DashDirectAuthApi
import org.dash.wallet.features.exploredash.network.service.DashDirectServicesApi
import org.dash.wallet.features.exploredash.utils.DashDirectConfig
import javax.inject.Inject

class DashDirectRepository
@Inject
constructor(
    private val servicesApi: DashDirectServicesApi,
    private val authApi: DashDirectAuthApi,
    private val config: DashDirectConfig
) : DashDirectRepositoryInt {

    override val userEmail: Flow<String?> = config.observeSecurePreference(DashDirectConfig.PREFS_KEY_DASH_DIRECT_EMAIL)

    override suspend fun signIn(email: String): ResponseResource<Boolean> = safeApiCall {
        authApi.signIn(email = email).also {
            it?.errorMessage?.let { errorMessage ->
                if (errorMessage.isNotEmpty()) {
                    throw Exception(errorMessage)
                }
            }
            if (it?.data?.statusCode == 0) {
                createUser(email)
            }
            config.setSecuredData(DashDirectConfig.PREFS_KEY_DASH_DIRECT_EMAIL, email)
        }
        true
    }

    override suspend fun createUser(email: String): ResponseResource<Boolean> = safeApiCall {
        authApi.createUser(email = email).also {
            it?.data?.errorMessage?.let { errorMessage ->
                if (errorMessage.isNotEmpty()) {
                    throw Exception(errorMessage)
                }
            }
            config.setSecuredData(DashDirectConfig.PREFS_KEY_DASH_DIRECT_EMAIL, email)
        }
        true
    }

    override suspend fun verifyEmail(code: String): ResponseResource<Boolean> = safeApiCall {
        authApi
            .verifyEmail(
                signInRequest =
                VerifyEmailRequest(
                    emailAddress = config.getSecuredData(DashDirectConfig.PREFS_KEY_DASH_DIRECT_EMAIL),
                    code = code
                )
            )
            .also {
                it?.data?.errorMessage?.let { errorMessage ->
                    if (it.data.hasError == true && errorMessage.isNotEmpty()) {
                        throw Exception(errorMessage)
                    }
                }
                it?.data?.accessToken?.let { token ->
                    config.setPreference(DashDirectConfig.PREFS_KEY_LAST_DASH_DIRECT_ACCESS_TOKEN, token)
                }
            }
        config.getPreference(DashDirectConfig.PREFS_KEY_LAST_DASH_DIRECT_ACCESS_TOKEN)?.isNotEmpty() ?: false
    }

    override fun isUserSignIn() = runBlocking {
        config.getPreference(DashDirectConfig.PREFS_KEY_LAST_DASH_DIRECT_ACCESS_TOKEN)?.isNotEmpty() ?: false
    }

    override suspend fun getDashDirectEmail(): String? {
        return config.getSecuredData(DashDirectConfig.PREFS_KEY_DASH_DIRECT_EMAIL)
    }

    override suspend fun logout() {
        config.clearAll()
    }

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
                giftCardAmount = 0.03,
                merchantId = 318
            ),
//             purchaseGiftCardRequest =
//                 PurchaseGiftCardRequest(currency = currency, giftCardAmount = giftCardAmount, merchantId = merchantId),
            email = userEmail
        )
    }

    override suspend fun getMerchantById(userEmail: String, merchantId: Long, includeLocations: Boolean?) =
        safeApiCall {
            servicesApi.getMerchantById(
                email = userEmail,
                getMerchantByIdRequest = GetMerchantByIdRequest(
                    id = 318, /*merchantId,*/
                    includeLocations = includeLocations
                )
            )
        }

    override suspend fun getPaymentStatus(userEmail: String, paymentId: String, orderId: String) = safeApiCall {
        servicesApi.getPaymentStatus(
            email = userEmail,
            paymentStatusRequest = PaymentStatusRequest(paymentId = paymentId, orderId = orderId)
        )
    }

    override suspend fun getGiftCardDetails(userEmail: String, giftCardId: Long) = safeApiCall {
        servicesApi.getGiftCard(email = userEmail, getGiftCardRequest = GetGiftCardRequest(id = giftCardId))
    }
}

interface DashDirectRepositoryInt {
    val userEmail: Flow<String?>
    suspend fun signIn(email: String): ResponseResource<Boolean>
    suspend fun createUser(email: String): ResponseResource<Boolean>
    suspend fun verifyEmail(code: String): ResponseResource<Boolean>
    fun isUserSignIn(): Boolean
    suspend fun getDashDirectEmail(): String?
    suspend fun logout()
    suspend fun purchaseGiftCard(
        deviceID: String,
        currency: String,
        giftCardAmount: Double,
        merchantId: Long,
        userEmail: String
    ): ResponseResource<PurchaseGiftCardResponse?>
    suspend fun getMerchantById(
        userEmail: String,
        merchantId: Long,
        includeLocations: Boolean? = false
    ): ResponseResource<GetMerchantByIdResponse?>
    suspend fun getPaymentStatus(
        userEmail: String,
        paymentId: String,
        orderId: String
    ): ResponseResource<PaymentStatusResponse?>
    suspend fun getGiftCardDetails(userEmail: String, giftCardId: Long): ResponseResource<GetGiftCardResponse?>
}
