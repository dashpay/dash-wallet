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

import org.dash.wallet.integration.uphold.data.UpholdConstants
import java.lang.Exception
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

suspend fun UpholdClient.getAccessToken(code: String): String? {
    return suspendCoroutine { continuation ->
        this.getAccessToken(code, object : UpholdClient.Callback<String?> {
            override fun onSuccess(dashCardId: String?) {
                continuation.resume(dashCardId)
            }

            override fun onError(e: Exception, otpRequired: Boolean) {
                continuation.resumeWithException(e)
            }
        })
    }
}