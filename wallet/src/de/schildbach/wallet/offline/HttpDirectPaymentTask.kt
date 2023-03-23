
/*
* Copyright 2013-2015 the original author or authors.
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

package de.schildbach.wallet.offline

import de.schildbach.wallet.offline.DirectPaymentTask.ResultCallback
import de.schildbach.wallet_test.R
import okhttp3.CacheControl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.bitcoin.protocols.payments.Protos.Payment
import org.bitcoin.protocols.payments.Protos.PaymentACK
import org.bitcoinj.protocols.payments.PaymentProtocol
import org.dash.wallet.common.util.Constants
import org.slf4j.LoggerFactory
import java.io.IOException

abstract class HttpDirectPaymentTask(
    private val resultCallback: ResultCallback
) {

    class HttpPaymentTask(
        resultCallback: ResultCallback,
        private val url: String,
        private val userAgent: String?
    ) :
        HttpDirectPaymentTask(resultCallback) {
        override suspend fun send(payment: Payment) {
            log.info(
                "trying to send tx to {}",
                url
            )
            val requestBuilder = Request.Builder()
                .url(url)
                .cacheControl(CacheControl.Builder().noCache().build())
                .header("Accept", PaymentProtocol.MIMETYPE_PAYMENTACK)
                .post(object : RequestBody() {
                    override fun contentType(): MediaType? {
                        return PaymentProtocol.MIMETYPE_PAYMENT.toMediaTypeOrNull()
                    }

                    @Throws(IOException::class)
                    override fun contentLength(): Long {
                        return payment.serializedSize.toLong()
                    }

                    @Throws(IOException::class)
                    override fun writeTo(sink: BufferedSink) {
                        payment.writeTo(sink.outputStream())
                    }
                })
            if (userAgent != null) requestBuilder.header("User-Agent", userAgent)
            val call =
                Constants.HTTP_CLIENT.newCall(requestBuilder.build())
            try {
                val response = call.execute()
                if (response.isSuccessful) {
                    log.info("tx sent via http")
                    val `is` = response.body!!.byteStream()
                    val paymentAck =
                        PaymentACK.parseFrom(`is`)
                    `is`.close()
                    val ack = "nack" != PaymentProtocol.parsePaymentAck(paymentAck).memo
                    log.info(
                        "received {} via http",
                        if (ack) "ack" else "nack"
                    )
                    onResult(ack)
                } else {
                    val responseCode = response.code
                    val responseMessage = response.message
                    log.info(
                        "got http error {}: {}",
                        responseCode,
                        responseMessage
                    )
                    onFail(R.string.error_http, responseCode, responseMessage)
                }
            } catch (x: IOException) {
                log.info(
                    "problem sending",
                    x
                )
                onFail(R.string.error_io, x.message)
            }
        }
    }

    abstract suspend fun send(payment: Payment)
    protected fun onResult(ack: Boolean) {
        resultCallback.onResult(ack) }

    protected fun onFail(messageResId: Int, vararg messageArgs: Any?) {
        resultCallback.onFail(messageResId, *messageArgs) }

    companion object {
        private val log = LoggerFactory.getLogger(HttpDirectPaymentTask::class.java)
    }
}
