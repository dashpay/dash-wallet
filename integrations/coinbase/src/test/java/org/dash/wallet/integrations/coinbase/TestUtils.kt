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
package org.dash.wallet.integrations.coinbase

import com.google.gson.GsonBuilder
import org.dash.wallet.integrations.coinbase.model.PaymentMethodsResponse
import org.dash.wallet.integrations.coinbase.model.SendTransactionToWalletResponse
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

object TestUtils {
    @Throws(IOException::class)
    fun readFileWithoutNewLineFromResources(fileName: String): String {
        var inputStream: InputStream? = null
        try {
            inputStream = javaClass.classLoader?.getResourceAsStream(fileName)
            val builder = StringBuilder()
            val reader = BufferedReader(InputStreamReader(inputStream))

            var str: String? = reader.readLine()
            while (str != null) {
                builder.append(str)
                str = reader.readLine()
            }
            return builder.toString()
        } finally {
            inputStream?.close()
        }
    }

    private class ResourceGenerator<T>(val type: Class<T>)

    private fun <T> generateResource(dataSetAsString: String, K: Class<T>): T {
        val gsonBuilder = GsonBuilder()
        val gson = gsonBuilder.create()
        val resourceGenerator = ResourceGenerator(K)
        return gson.fromJson(dataSetAsString, resourceGenerator.type)
    }

    val paymentMethodsData = getPaymentMethodsApiResponse().paymentMethods

    fun getPaymentMethodsApiResponse(): PaymentMethodsResponse {
        val apiResponse = readFileWithoutNewLineFromResources("payment_methods.json")
        return generateResource(apiResponse, PaymentMethodsResponse::class.java)
    }

    fun sendFundsToWalletApiResponse(): SendTransactionToWalletResponse {
        val apiResponse = readFileWithoutNewLineFromResources("send_funds_to_wallet.json")
        return generateResource(apiResponse, SendTransactionToWalletResponse::class.java)
    }
}

