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

package org.dash.wallet.integrations.maya.api

import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.slf4j.LoggerFactory
import retrofit2.Response
import retrofit2.http.GET
import java.io.IOException
import javax.inject.Inject

/**
 * https://docs.mayaprotocol.com/introduction/readme
 */
interface MayaEndpoint {
    /**
     * https://docs.mayaprotocol.com/dev-docs/mayachain/concepts/querying-mayachain
     */
    @GET("pools")
    suspend fun getPoolInfo(): Response<List<PoolInfo>>
}

open class MayaWebApi @Inject constructor(
    private val endpoint: MayaEndpoint,
    private val analyticsService: AnalyticsService
) {
    companion object {
        private val log = LoggerFactory.getLogger(MayaWebApi::class.java)
    }

    suspend fun getPoolInfo(): List<PoolInfo> {
        return try {
            val response = endpoint.getPoolInfo()
            log.info("maya: response: {}", response)

            return if (response.isSuccessful && response.body()?.isNotEmpty() == true) {
                response.body()!!.toList()
            } else {
                log.error("getPoolInfo not successful; ${response.code()} : ${response.message()}")
                listOf()
            }
        } catch (ex: Exception) {
            log.error("Error in getPoolInfo: $ex")

            if (ex !is IOException) {
                analyticsService.logError(ex)
            }

            listOf()
        }
    }
}
