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
package org.dash.wallet.integration.coinbase_integration.repository.remote

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.dash.wallet.integration.coinbase_integration.CoinbaseConstants
import org.dash.wallet.integration.coinbase_integration.model.BaseIdForUSDModel
import org.dash.wallet.integration.coinbase_integration.utils.CoinbaseConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import javax.inject.Inject
import kotlin.time.Duration.Companion.days

class CustomCacheInterceptor @Inject constructor(
    private val context: Context,
    private val config: CoinbaseConfig
) : Interceptor {

    companion object {
        private const val BASE_IDS_FILENAME = "base_ids.json"
        private val log = LoggerFactory.getLogger(CustomCacheInterceptor::class.java)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!chain.request().url.toString().startsWith(
            "${CoinbaseConstants.BASE_URL}${CoinbaseConstants.BASE_IDS_REQUEST_URL}")
        ) {
            // We only need custom cash for the BASE_IDS_REQUEST_URL
            return chain.proceed(chain.request())
        }

        val gson = Gson()
        val shouldUpdateBaseIds = runBlocking { config.getPreference(CoinbaseConfig.UPDATE_BASE_IDS) ?: true }
        val cacheFile = File(CoinbaseConstants.getCacheDir(context), BASE_IDS_FILENAME)

        if (shouldUpdateBaseIds || !cacheFile.exists()) {
            // If UPDATE_BASE_IDS is set, refresh the cache
            val response = chain.proceed(chain.request())

            try {
                val baseIds = gson.fromJson(response.body?.string(), BaseIdForUSDModel::class.java)
                FileWriter(cacheFile, false).use {
                    gson.toJson(baseIds, it)
                }
                val cached = readCached(chain.request(), cacheFile)
                runBlocking { config.setPreference(CoinbaseConfig.UPDATE_BASE_IDS, false) }
                return cached
            } catch (ex: Exception) {
                log.error("Failed to cache baseId", ex)
                cacheFile.delete()
            }

            return response
        }

        val sixMonthsAgo = System.currentTimeMillis() - (30*6).days.inWholeMilliseconds

        if(cacheFile.lastModified() < sixMonthsAgo) {
            // Force update every ~ 6 months
            runBlocking { config.setPreference(CoinbaseConfig.UPDATE_BASE_IDS, true) }
        }

        return try {
            readCached(chain.request(), cacheFile)
        } catch (ex: Exception) {
            log.error("Failed to return cached baseId", ex)
            cacheFile.delete()
            // Retry with no cache
            runBlocking { config.setPreference(CoinbaseConfig.UPDATE_BASE_IDS, true) }
            intercept(chain)
        }
    }

    private fun readCached(request: Request, cacheFile: File): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("")
            .body(FileReader(cacheFile).readText()
                .toResponseBody("application/json".toMediaType())
            )
            .build()
    }
}
