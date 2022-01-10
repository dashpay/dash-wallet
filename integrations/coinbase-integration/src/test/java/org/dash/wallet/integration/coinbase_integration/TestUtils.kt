package org.dash.wallet.integration.coinbase_integration

import com.google.gson.GsonBuilder
import org.dash.wallet.integration.coinbase_integration.model.BuyOrderResponse
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountInfo
import org.dash.wallet.integration.coinbase_integration.model.PaymentMethodsResponse
import org.dash.wallet.integration.coinbase_integration.model.SendTransactionToWalletResponse
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

    fun getUserAccountApiResponse(): CoinBaseUserAccountInfo {
        val apiResponse = readFileWithoutNewLineFromResources("user_accounts.json")
        return generateResource(apiResponse, CoinBaseUserAccountInfo::class.java)
    }

    val paymentMethodsData = getPaymentMethodsApiResponse().data

    fun getPaymentMethodsApiResponse(): PaymentMethodsResponse {
        val apiResponse = readFileWithoutNewLineFromResources("payment_methods.json")
        return generateResource(apiResponse, PaymentMethodsResponse::class.java)
    }

    fun placeBuyOrderApiResponse(): BuyOrderResponse {
        val apiResponse = readFileWithoutNewLineFromResources("place_buy_order.json")
        return generateResource(apiResponse, BuyOrderResponse::class.java)
    }

    val buyOrderData = placeBuyOrderApiResponse().data

    fun sendFundsToWalletApiResponse(): SendTransactionToWalletResponse {
        val apiResponse = readFileWithoutNewLineFromResources("send_funds_to_wallet.json")
        return generateResource(apiResponse, SendTransactionToWalletResponse::class.java)
    }
}

