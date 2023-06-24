/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.integration.uphold.api

import org.dash.wallet.common.util.ensureSuccessful
import org.dash.wallet.integration.uphold.data.UpholdCard
import org.dash.wallet.integration.uphold.data.UpholdConstants
import org.dash.wallet.integration.uphold.data.UpholdCryptoCardAddress
import org.dash.wallet.integration.uphold.data.UpholdException
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.math.BigDecimal
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

val UpholdClient.hasValidCredentials
    get() = UpholdConstants.hasValidCredentials()

suspend fun UpholdClient.getDashBalance(): BigDecimal {
    return suspendCoroutine { continuation ->
        this.getDashBalance(object : UpholdClient.Callback<BigDecimal> {
            override fun onSuccess(data: BigDecimal) {
                continuation.resume(data)
            }

            override fun onError(e: Exception, otpRequired: Boolean) {
                continuation.resumeWithException(e)
            }
        })
    }
}

fun UpholdClient.getDashBalance(callback: UpholdClient.Callback<BigDecimal>) {
    getCards(
        object : UpholdClient.Callback<String> {
            override fun onSuccess(data: String?) {}
            override fun onError(e: java.lang.Exception, otpRequired: Boolean) {
                UpholdClient.log.error("Error loading Dash balance: " + e.message)
                if (e is UpholdException) {
                    if (e.code == 401) {
                        // we don't have the correct access token, let's logout
                        accessToken = null
                        storeAccessToken()
                    }
                }
                callback.onError(e, otpRequired)
            }
        },
        object : UpholdClient.Callback<List<UpholdCard>> {
            override fun onSuccess(cards: List<UpholdCard>) {
                UpholdClient.log.info("Dash balance loaded")
                callback.onSuccess(cards.sumOf { BigDecimal(it.available) })
            }

            override fun onError(e: java.lang.Exception, otpRequired: Boolean) {
                UpholdClient.log.error("Error loading Dash balance: " + e.message)
                callback.onError(e, otpRequired)
            }
        }
    )
}

// TOOD: callers should handle
// UpholdClient.log.error("Error obtaining Uphold access token " + response.message() + " code: " + response.code())
suspend fun UpholdClient.getAccessToken(code: String?) {
    val response = service.getAccessToken(
        UpholdConstants.CLIENT_ID,
        UpholdConstants.CLIENT_SECRET,
        code,
        "authorization_code"
    )
    response.ensureSuccessful()

    if (response.isSuccessful) {
        UpholdClient.log.info("Uphold access token obtained")
        accessToken = response.body()!!.accessToken
        storeAccessToken()
        getCards(null, null)
    } else {
        throw UpholdException("Error obtaining Uphold access token", response.message(), response.code())
    }
}

fun UpholdClient.getCards(
    callback: UpholdClient.Callback<String>?,
    getDashCardCb: UpholdClient.Callback<List<UpholdCard>>?
) {
    service.cards.enqueue(object : Callback<List<UpholdCard>> {
        override fun onResponse(call: Call<List<UpholdCard>>, response: Response<List<UpholdCard>>) {
            val body = response.body()

            if (response.isSuccessful && body != null) {
                UpholdClient.log.info("get cards success")
                val dashCards = body.filter { it.currency.equals("dash", ignoreCase = true) }

                if (dashCards.isEmpty()) {
                    UpholdClient.log.info("Dash Card not available")
                    createDashCard(callback, getDashCardCb)
                } else {
                    // There could be several cards.
                    // Take a non-empty card if available, otherwise, take the first one
                    val nonEmptyCard = dashCards.firstOrNull { (it.available.toDoubleOrNull() ?: 0.0) != 0.0 }
                    dashCard = nonEmptyCard ?: dashCards.first()

                    if (dashCard.address.cryptoAddress == null) {
                        UpholdClient.log.info("Dash Card has no addresses")
                        createDashAddress(dashCard.id)
                    }
                    callback?.onSuccess(dashCard.id)
                    getDashCardCb?.onSuccess(listOf(dashCard))
                }
            } else {
                UpholdClient.log.error("Error obtaining cards " + response.message() + " code: " + response.code())
                callback?.onError(UpholdException("Error obtaining cards", response.message(), response.code()), false)
            }
        }

        override fun onFailure(call: Call<List<UpholdCard>>, t: Throwable) {
            UpholdClient.log.error("Error obtaining cards: " + t.message)
            callback?.onError(java.lang.Exception(t), false)
        }
    })
}

suspend fun UpholdClient.revokeAccessToken() {
    val response = service.revokeAccessToken(accessToken)
    response.ensureSuccessful()

    if (response.body()?.lowercase() == "ok") {
        UpholdClient.log.info("Uphold access token revoked")
        accessToken = null
        storeAccessToken()
    } else {
        UpholdClient.log.error(
            "Error revoking Uphold access token: " + response.message() + " code: " + response.code()
        )
        throw UpholdException("Error revoking Uphold access token", response.message(), response.code())
    }
}

suspend fun UpholdClient.checkCapabilities() {
    val operation = "withdrawals"
    val response = service.getCapabilities(operation)
    response.ensureSuccessful()
    val capability = response.body()

    if (capability != null && capability.key == operation) {
        requirements[capability.key] = capability.requirements
    }
}

fun UpholdClient.createDashCard(
    callback: UpholdClient.Callback<String>?,
    getDashCardCb: UpholdClient.Callback<List<UpholdCard>>?
) {
    val body: MutableMap<String, String> = HashMap()
    body["label"] = "Dash Card"
    body["currency"] = "DASH"

    service.createCard(body).enqueue(object : Callback<UpholdCard?> {
        override fun onResponse(call: Call<UpholdCard?>, response: Response<UpholdCard?>) {
            if (response.isSuccessful && response.body() != null) {
                UpholdClient.log.info("Dash Card created successfully")
                dashCard = response.body()!!
                val dashCardId: String = dashCard.id
                callback?.onSuccess(dashCardId)
                createDashAddress(dashCardId)
                getDashCardCb?.onSuccess(listOf(response.body()!!))
            } else {
                UpholdClient.log.error("Error creating Dash Card: " + response.message() + " code: " + response.code())
                callback?.onError(
                    UpholdException("Error creating Dash Card", response.message(), response.code()),
                    false
                )
            }
        }

        override fun onFailure(call: Call<UpholdCard?>, t: Throwable) {
            UpholdClient.log.error("Error creating Dash Card " + t.message)
        }
    })
}

fun UpholdClient.createDashAddress(cardId: String?) {
    val body: MutableMap<String, String> = HashMap()
    body["network"] = "dash"
    service.createCardAddress(cardId, body).enqueue(object : Callback<UpholdCryptoCardAddress?> {
        override fun onResponse(call: Call<UpholdCryptoCardAddress?>, response: Response<UpholdCryptoCardAddress?>) {
            UpholdClient.log.info("Dash Card address created")
        }

        override fun onFailure(call: Call<UpholdCryptoCardAddress?>, t: Throwable) {
            UpholdClient.log.error("Error creating Dash Card address: " + t.message)
        }
    })
}

fun UpholdClient.getWithdrawalRequirements(): List<String> {
    val result = requirements["withdrawals"]
    return result ?: emptyList()
}

fun UpholdClient.storeAccessToken() {
    prefs.edit().putString(UpholdClient.UPHOLD_ACCESS_TOKEN, accessToken).apply()
}

fun UpholdClient.getStoredAccessToken(): String? {
    return prefs.getString(UpholdClient.UPHOLD_ACCESS_TOKEN, null)
}

fun UpholdClient.isAuthenticated(): Boolean {
    return getStoredAccessToken() != null
}
