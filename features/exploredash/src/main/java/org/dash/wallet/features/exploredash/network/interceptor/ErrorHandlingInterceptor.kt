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
        val request = chain.request()
        val response = chain.proceed(request)

        val isRefresh401 =
            response.code == 401 && request.url.encodedPath.contains("/refresh-token")

        if (response.isSuccessful || isRefresh401) return response

        // Read up to 256 KB without consuming the original stream
        val snapshot = response.peekBody(256 * 1024)
        val err = snapshot.string()

        response.close() // release connection since we're throwing

        throw CTXSpendException(
            "$serviceName error ${response.code} ${request.method} ${request.url}",
            serviceName = serviceName,
            errorCode = response.code,
            errorBody = err
        )
    }
}