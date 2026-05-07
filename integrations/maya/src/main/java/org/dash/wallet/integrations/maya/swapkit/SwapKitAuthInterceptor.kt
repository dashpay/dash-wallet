/*
 * Copyright 2026 Dash Core Group.
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

package org.dash.wallet.integrations.maya.swapkit

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the `x-api-key` header to every SwapKit request. If the configured key is
 * blank the header is omitted — the API will reject the call with 401, which the
 * web layer maps to a regular failure. This keeps non-credentialed builds usable
 * (they will just not return successful SwapKit data).
 */
class SwapKitAuthInterceptor(private val apiKey: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (apiKey.isBlank()) return chain.proceed(original)

        val authed = original.newBuilder()
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .build()
        return chain.proceed(authed)
    }
}