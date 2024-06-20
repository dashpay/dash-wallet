/*
 * Copyright (c) 2024 Dash Core Group
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.dash.wallet.integrations.uphold

import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.dash.wallet.integrations.uphold.api.TopperClient
import org.dash.wallet.integrations.uphold.data.SupportedTopperPaymentMethods
import org.dash.wallet.integrations.uphold.data.TopperPaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import java.math.BigDecimal

class TopperClientTest {

    private val mockHttpClient: OkHttpClient = mock<OkHttpClient>()
    private val topperClient = TopperClient(mockHttpClient)
    private val paymentMethodsRawResult: String?
    private val paymentMethods: List<TopperPaymentMethod>
    private val paymentMethodsWithHigherLimits: List<TopperPaymentMethod>
    init {
        this::class.java.getResourceAsStream("payment-methods.json").use { stream ->
            paymentMethodsRawResult = stream?.let { String(it.readBytes()) }
            paymentMethods = Gson().fromJson(
                paymentMethodsRawResult,
                SupportedTopperPaymentMethods::class.java
            )?.paymentMethods!!
            paymentMethodsWithHigherLimits = scaleLimits(20)
        }
    }

    private fun scaleLimits(factor: Int) = paymentMethods.map { method ->
        method.copy(
            limits = method.limits.map { limit ->
                limit.copy(
                    minimum = (limit.minimum.toBigDecimal() * BigDecimal(factor)).toString(),
                    maximum = (limit.maximum.toBigDecimal() * BigDecimal(factor)).toString()
                )
            }
        )
    }

    @Test
    fun defaultValueTest() {
        assertEquals(100, topperClient.getDefaultValue("USD", paymentMethods))
        assertEquals(90, topperClient.getDefaultValue("EUR", paymentMethods))
        assertEquals(80, topperClient.getDefaultValue("GBP", paymentMethods))
        assertEquals(15000, topperClient.getDefaultValue("JPY", paymentMethods))

        // the limits are doubled
        assertEquals(100, topperClient.getDefaultValue("USD", scaleLimits(2)))
        // the limits are 9x higher
        assertEquals(100, topperClient.getDefaultValue("USD", scaleLimits(9)))
        // the limits are 11x higher, which means that the minimum is higher than the default
        assertEquals(110, topperClient.getDefaultValue("USD", scaleLimits(11)))

        // the minimum is $200, while the default value is $100
        assertEquals(200, topperClient.getDefaultValue("USD", paymentMethodsWithHigherLimits))
        assertEquals(31000, topperClient.getDefaultValue("JPY", paymentMethodsWithHigherLimits))
    }
}
