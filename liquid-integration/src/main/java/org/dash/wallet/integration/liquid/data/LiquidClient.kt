/*
 * Copyright 2014-2015 the original author or authors.
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
package org.dash.wallet.integration.liquid.data

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import com.securepreferences.SecurePreferences
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.dash.wallet.integration.liquid.currency.CurrencyResponse
import org.slf4j.LoggerFactory
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.nio.charset.StandardCharsets
import java.security.Key


class LiquidClient private constructor(context: Context, private val encryptionKey: String) {
    private val service: LiquidService
    private val prefs: SharedPreferences

    init {
        prefs = SecurePreferences(context, encryptionKey, LIQUID_PREFS)
        val interceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        interceptor.redactHeader("Authorization")
        val okClient = OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build()

        val baseUrl = LiquidConstants.CLIENT_BASE_URL
        val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okClient)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        this.service = retrofit.create(LiquidService::class.java)
    }

    companion object {
        private var instance: LiquidClient? = null
        private val log = LoggerFactory.getLogger(LiquidClient::class.java)
        private const val LIQUID_PREFS = "liquid_prefs.xml"
        private const val LIQUID_SESSION_ID = "session_id"
        private const val LIQUID_SESSION_SECRET = "session_secret"
        private const val LIQUID_USER_ID = "user_id"

        fun init(context: Context, prefsEncryptionKey: String): LiquidClient? {
            instance = LiquidClient(context, prefsEncryptionKey)
            return instance
        }

        fun getInstance(): LiquidClient? {
            checkNotNull(instance) { "You must call LiquidClient#init() first" }
            return instance
        }
    }


    fun getSessionId(publicApiKey: String, callback: Callback<String>) {
        //force_kyc commented param will take you to kyc process without creating new user
        log.info("liquid: requesting session id")
        service.getSessionId(publicApiKey/*,true*/).enqueue(object : retrofit2.Callback<LiquidSessionId?> {
            override fun onResponse(call: Call<LiquidSessionId?>, response: Response<LiquidSessionId?>) {
                if (response.isSuccessful) {
                    try {
                        val sessionId = response.body()?.payload?.sessionId
                        val sessionSecret = response.body()?.payload?.sessionSecret
                        if (!TextUtils.isEmpty(sessionId) && !TextUtils.isEmpty(sessionSecret)) {
                            storeSessionId(sessionId!!)
                            storeSessionSecret(sessionSecret!!)
                            log.info("liquid: successfully obtained session id")
                            callback.onSuccess(sessionId)
                        } else {
                            callback.onError(LiquidException("Error obtaining liquid sessionId", response.message(), response.code()))
                        }
                    } catch (e: Exception) {
                        callback.onError(LiquidException("Error obtaining liquid sessionId", response.message(), response.code()))
                    }
                } else {
                    callback.onError(LiquidException("Error obtaining liquid sessionId", response.message(), response.code()))
                }
            }

            override fun onFailure(call: Call<LiquidSessionId?>, t: Throwable) {
                callback.onError(Exception(t))
            }
        })
    }

    fun getUserKycState(sessionId: String, callback: Callback<String>) {
        try {

            log.info("liquid: requesting user kyc state")
            val token = getAuthToken("/api/v1/session/$sessionId/kyc_state")

            service.getUserKycState(sessionId, token).enqueue(object : retrofit2.Callback<UserKycState?> {

                override fun onResponse(call: Call<UserKycState?>, response: Response<UserKycState?>) {
                    if (response.isSuccessful) {
                        val userId = response.body()?.payload?.liquidUserId
                        if (!TextUtils.isEmpty(userId)) {
                            storeUserId(userId!!)
                            callback.onSuccess(userId)
                        } else {
                            callback.onError(LiquidException("Error obtaining liquid user kyc state", response.message(), response.code()))
                        }
                    } else {
                        callback.onError(LiquidException("Error obtaining liquid user kyc state", response.message(), response.code()))
                    }
                }

                override fun onFailure(call: Call<UserKycState?>, t: Throwable) {
                    callback.onError(Exception(t))
                }
            })

        } catch (e: Exception) {
            callback.onError(e)
        }
    }


    fun getUserAccountBalance(sessionId: String, callback: Callback<String>) {
        try {
            log.info("liquid: requesting user account balance")
            val token = getAuthToken("/api/v1/session/$sessionId/accounts")

            service.getUserAccount(sessionId, token).enqueue(object : retrofit2.Callback<String?> {

                override fun onResponse(call: Call<String?>, response: Response<String?>) {
                    if (response.isSuccessful) {
                        callback.onSuccess(response.body()!!)
                    } else {
                        val exception = when {
                            response.code() == 404 -> {
                                clearStoredSessionData()
                                LiquidUnauthorizedException("Error obtaining account balance: invalid creditials", response.message(), response.code())
                            }
                            else -> {
                                LiquidException("Error obtaining liquid account balance", response.message(), response.code())
                            }
                        }
                        callback.onError(exception)
                    }
                }


                override fun onFailure(call: Call<String?>, t: Throwable) {
                    callback.onError(Exception(t))
                }
            })

        } catch (e: Exception) {
            callback.onError(e)
        }
    }

    fun getUserAccountAddress(sessionId: String, require_address: Boolean, callback: Callback<String>) {
        try {
            log.info("liquid: requesting user account address")
            val token = getAuthToken("/api/v1/session/$sessionId/accounts/DASH")

            service.getUserAccountAddress(sessionId, require_address, token).enqueue(object : retrofit2.Callback<String?> {

                override fun onResponse(call: Call<String?>, response: Response<String?>) {
                    if (response.isSuccessful) {
                        callback.onSuccess(response.body()!!)
                    } else {
                        callback.onError(LiquidException("Error obtaining liquid user account address", response.message(), response.code()))
                    }
                }


                override fun onFailure(call: Call<String?>, t: Throwable) {
                    callback.onError(Exception(t))
                }
            })

        } catch (e: Exception) {
            callback.onError(e)
        }
    }


    fun revokeAccessToken(callback: Callback<String?>) {
        try {
            log.info("liquid: revoking access token")
            val token = getAuthToken("/api/v1/session/$storedSessionId/terminate")

            service.terminateSession(storedSessionId!!, token).enqueue(object : retrofit2.Callback<LiquidTerminateSession?> {

                override fun onResponse(call: Call<LiquidTerminateSession?>, response: Response<LiquidTerminateSession?>) {
                    if (response.isSuccessful) {
                        callback.onSuccess("")
                    } else {
                        val exception = when {
                            response.code() == 404 -> {
                                clearStoredSessionData()
                                LiquidUnauthorizedException("Error in terminate session: invalid creditials", response.message(), response.code())
                            }
                            else -> {
                                LiquidException("Error in terminate session", response.message(), response.code())
                            }
                        }
                        callback.onError(exception)
                    }
                }

                override fun onFailure(call: Call<LiquidTerminateSession?>, t: Throwable) {
                    callback.onError(Exception(t))
                }
            })
        } catch (e: Exception) {
            callback.onError(e)
        }
    }


    /**
     * call get all currency api
     */
    fun getAllCurrencies(callback: Callback<CurrencyResponse>) {
        try {
            log.info("liquid: requesting all currencies")

            service.getAllCurrencies(LiquidConstants.PUBLIC_API_KEY).enqueue(object : retrofit2.Callback<CurrencyResponse> {
                override fun onResponse(call: Call<CurrencyResponse>, response: Response<CurrencyResponse>) {
                    if (response.isSuccessful) {
                        response.body()?.let { callback.onSuccess(it) }
                    } else {
                        callback.onError(LiquidException("Error in fetching currencies", response.message(), response.code()))
                    }
                }

                override fun onFailure(call: Call<CurrencyResponse>, t: Throwable) {
                    callback.onError(Exception(t))
                }
            })
        } catch (e: Exception) {
            log.error("liquid: get all currencies exception:", e)
            callback.onError(e)
        }
    }

    private fun storeSessionId(sessionId: String) {
        prefs.edit().putString(LIQUID_SESSION_ID, sessionId).apply()
    }

    private fun storeSessionSecret(sessionSecret: String) {
        prefs.edit().putString(LIQUID_SESSION_SECRET, sessionSecret).apply()
    }

    private fun storeUserId(userId: String) {
        prefs.edit().putString(LIQUID_USER_ID, userId).apply()
    }

    fun clearStoredSessionData() {
        storeSessionId("")
        storeSessionSecret("")
        storeUserId("")
    }

    val storedSessionId: String? get() = prefs.getString(LIQUID_SESSION_ID, "")
    val storedUserId: String? get() = prefs.getString(LIQUID_USER_ID, "")
    val storedSessionSecret: String? get() = prefs.getString(LIQUID_SESSION_SECRET, "")

    val isAuthenticated: Boolean get() = storedUserId != "" && storedSessionId != "" && storedSessionSecret != ""


    interface Callback<T> {
        fun onSuccess(data: T)
        fun onError(e: Exception?)
    }


    private fun getAuthToken(url: String): String {

        val path = url
        val key: Key = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            Keys.hmacShaKeyFor(storedSessionSecret!!.toByteArray(StandardCharsets.UTF_8))
        } else {
            Keys.hmacShaKeyFor(storedSessionSecret!!.toByteArray())
        }
        val jwt: String = Jwts.builder()
                .signWith(key)
                .claim("path", path)
                .claim("session_id", storedSessionId)
                .claim("nonce", System.currentTimeMillis())
                .compact()

        val token = "Bearer $jwt"

        return token
    }
}