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
package org.dash.wallet.features.exploredash.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import org.dash.wallet.features.exploredash.repository.CTXSpendException

class ErrorHandlingInterceptor(private val serviceName: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            throw CTXSpendException(
                "$serviceName api error with ${response.request.url}: ${response.code}; ${response.body}",
                serviceName = serviceName,
                errorCode = response.code,
                errorBody = errorBody
            )
        }
        
        return response
    }
}