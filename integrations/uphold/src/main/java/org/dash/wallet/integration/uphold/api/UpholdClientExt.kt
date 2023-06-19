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

import org.dash.wallet.integration.uphold.data.UpholdCard
import org.dash.wallet.integration.uphold.data.UpholdConstants
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
        object : UpholdClient.Callback<UpholdCard> {
            override fun onSuccess(card: UpholdCard) {
                UpholdClient.log.info("Dash balance loaded")
                callback.onSuccess(BigDecimal(card.available))
            }

            override fun onError(e: java.lang.Exception, otpRequired: Boolean) {
                UpholdClient.log.error("Error loading Dash balance: " + e.message)
                callback.onError(e, otpRequired)
            }
        }
    )
}

suspend fun UpholdClient.getAccessToken(code: String): String? {
    return suspendCoroutine { continuation ->
        this.getAccessToken(
            code,
            object : UpholdClient.Callback<String?> {
                override fun onSuccess(dashCardId: String?) {
                    continuation.resume(dashCardId)
                }

                override fun onError(e: Exception, otpRequired: Boolean) {
                    continuation.resumeWithException(e)
                }
            }
        )
    }
}

fun UpholdClient.getCards(callback: UpholdClient.Callback<String>, getDashCardCb: UpholdClient.Callback<UpholdCard>?) {
    service.cards.enqueue(object : Callback<List<UpholdCard>> {
        override fun onResponse(call: Call<List<UpholdCard>>, response: Response<List<UpholdCard>>) {
            val body = response.body()

            if (response.isSuccessful && body != null) {
                UpholdClient.log.info("get cards success")
                dashCard = getDashCards(body).first { (it.balance.toDoubleOrNull() ?: 0.0) != 0.0 }

                if (dashCard == null) {
                    UpholdClient.log.info("Dash Card not available")
                    createDashCard(callback, getDashCardCb)
                } else {
                    if (dashCard.address.cryptoAddress == null) {
                        UpholdClient.log.info("Dash Card has no addresses")
                        createDashAddress(dashCard.id)
                    }
                    callback.onSuccess(dashCard.id)
                    getDashCardCb?.onSuccess(dashCard)
                }
            } else {
                UpholdClient.log.error("Error obtaining cards " + response.message() + " code: " + response.code())
                callback.onError(UpholdException("Error obtaining cards", response.message(), response.code()), false)
            }
        }

        override fun onFailure(call: Call<List<UpholdCard>>, t: Throwable) {
            UpholdClient.log.error("Error obtaining cards: " + t.message)
            callback.onError(java.lang.Exception(t), false)
        }
    })
}

fun UpholdClient.getDashCards(cards: List<UpholdCard>): List<UpholdCard> {
    return cards.filter { it.currency.equals("dash", ignoreCase = true) }
}
