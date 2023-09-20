package org.dash.wallet.integrations.maya

import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.slf4j.LoggerFactory
import retrofit2.Response
import retrofit2.http.GET
import java.io.IOException
import javax.inject.Inject

interface MayaEndpoint {
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
                log.error("getWithdrawalLimits not successful; ${response.code()} : ${response.message()}")
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
