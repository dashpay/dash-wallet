package org.dash.wallet.common.util

import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.util.Collections
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import com.google.gson.Gson
import org.dash.wallet.common.util.Constants.HTTP_CLIENT

import org.slf4j.LoggerFactory
import okhttp3.Request

private val log = LoggerFactory.getLogger("Networks")

fun getDeviceIPAddress(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (networkInterface in Collections.list(interfaces)) {
            val addresses = networkInterface.inetAddresses
            for (address in Collections.list(addresses)) {
                if (!address.isLoopbackAddress) {
                    val hostAddress = address.hostAddress
                    // Filter out IPv6 addresses if you only want IPv4
                    val isIPv4 = hostAddress?.indexOf(':') == -1
                    if (isIPv4) {
                        return hostAddress
                    }
                }
            }
        }
    } catch (e: Exception) {
        log.warn("Failed to get device IP address", e)
    }
    return null
}

suspend fun getPublicIPAddress(): String? = withContext(Dispatchers.IO) {
    try {
        val url = URL("https://api.ipify.org")
        val connection = url.openConnection()
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        BufferedReader(InputStreamReader(connection.getInputStream())).use { reader ->
            reader.readText().trim()
        }
    } catch (e: Exception) {
        log.warn("Failed to fetch public IP address", e)
        null
    }
}

suspend fun getCountryCodeFromIP(): String {
    try {
        val request = Request.Builder()
            .url("http://ip-api.com/json/?fields=status,message,countryCode")
            .build()

        val response = HTTP_CLIENT.call(request)
        response.ensureSuccessful()
        val body = response.body
        val gson = Gson()
        val mapper = gson.fromJson(body?.string() ?: "", Map::class.java)
        return mapper["countryCode"] as String
    } catch (e: Exception) {
        log.error("error obtaining country code for this IP", e)
        return ""
    }
}
