package de.schildbach.wallet.util

import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.head
import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.jvm.Throws

private val log = LoggerFactory.getLogger("TimeUtils")
private fun queryNtpTime(server: String): Long? {
    try {
        val address = InetAddress.getByName(server)
        val message = ByteArray(48)
        message[0] = 0b00100011 // NTP mode client (3) and version (4)

        val socket = DatagramSocket().apply {
            soTimeout = 3000 // Set timeout to 3000ms
        }

        val request = DatagramPacket(message, message.size, address, 123)
        socket.send(request) // Send request

        // Receive response
        val response = DatagramPacket(message, message.size)
        socket.receive(response)

        // Timestamp starts at byte 40 of the received packet and is four bytes,
        // or two words, long. First byte is the high-order byte of the integer;
        // the last byte is the low-order byte. The high word is the seconds field,
        // and the low word is the fractional field.
        val seconds = message[40].toLong() and 0xff shl 24 or
            (message[41].toLong() and 0xff shl 16) or
            (message[42].toLong() and 0xff shl 8) or
            (message[43].toLong() and 0xff)

        // Convert seconds to milliseconds and adjust from 1900 to epoch (1970)
        return (seconds - 2208988800L) * 1000
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

@Throws(NullPointerException::class)
suspend fun getTimeSkew(): Long {
    var networkTime: Long? = null
    var timeSource = "NTP"

    val networkTimes = arrayListOf<Long>()
    for (i in 0..3) {
        try {
            val time = queryNtpTime("pool.ntp.org")
            if (time != null && time > 0) { networkTimes.add(time) }
        } catch (e: SocketTimeoutException) {
            // swallow
        }
    }
    networkTimes.sort()
    when (networkTimes.size) {
        3 -> networkTime = networkTimes[2]
        2 -> networkTime = (networkTimes[0] + networkTimes[1]) / 2
        else -> { }
    }

    if (networkTime == null) {
        try {
            val result = Constants.HTTP_CLIENT.head("https://www.dash.org/")
            networkTime = result.headers.getDate("date")?.time
            timeSource = "dash.org"
        } catch (e: Exception) {
            // swallow
        }
        if (networkTime == null) {
            try {
                val result = Constants.HTTP_CLIENT.head("https://insight.dash.org/insight")
                networkTime = result.headers.getDate("date")?.time
                timeSource = "insight"
            } catch (e: Exception) {
                // swallow
            }
        }
        log.info("timeskew: network time is $networkTime")
        requireNotNull(networkTime)
    }

    val systemTimeMillis = System.currentTimeMillis()
    log.info("timeskew: $systemTimeMillis-$networkTime = ${systemTimeMillis - networkTime}; source: $timeSource")
    return systemTimeMillis - networkTime
}
