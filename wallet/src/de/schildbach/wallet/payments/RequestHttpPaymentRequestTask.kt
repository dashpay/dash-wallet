package de.schildbach.wallet.payments

import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.ui.util.InputParser.StreamInputParser
import de.schildbach.wallet_test.R
import okhttp3.CacheControl
import okhttp3.Request
import org.bitcoinj.protocols.payments.PaymentProtocol
import org.slf4j.LoggerFactory
import java.io.IOException

abstract class RequestHttpPaymentRequestTask(private val resultCallback: RequestPaymentRequestTask.ResultCallback) {

    abstract suspend fun requestPaymentRequest(url: String)

    protected open fun onPaymentIntent(paymentIntent: PaymentIntent?) {
        resultCallback.onPaymentIntent(paymentIntent)
    }

    protected open fun onFail(
        ex: java.lang.Exception?,
        messageResId: Int,
        vararg messageArgs: Any?
    ) {
        resultCallback.onFail(ex, messageResId, *messageArgs)
    }
}

class HttpRequestTask(
    resultCallback: RequestPaymentRequestTask.ResultCallback,
    private val userAgent: String?
) :
    RequestHttpPaymentRequestTask(resultCallback) {
    private val log = LoggerFactory.getLogger(
        HttpRequestTask::class.java
    )
    override suspend fun requestPaymentRequest(url: String) {
        log.info("trying to request payment request from {}", url)

        val requestBuilder = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.Builder().noCache().build())
            .header("Accept", PaymentProtocol.MIMETYPE_PAYMENTREQUEST)
        if (userAgent != null) requestBuilder.header("User-Agent", userAgent)

        val call = Constants.HTTP_CLIENT.newCall(requestBuilder.build())
        try {
            val response = call.execute()
            if (response.isSuccessful) {
                val contentType = response.header("Content-Type")
                val bs = response.body?.byteStream()
                object : StreamInputParser(contentType, bs) {
                    override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                        log.info("received {} via http", paymentIntent)
                        onPaymentIntent(paymentIntent)
                    }

                    override fun error(
                        x: java.lang.Exception,
                        messageResId: Int,
                        vararg messageArgs: Any
                    ) {
                        onFail(x, messageResId, *messageArgs)
                    }
                }.parse()
                bs?.close()
            } else {
                val responseCode = response.code
                val responseMessage = response.message
                log.info(
                    "got http error {}: {}",
                    responseCode,
                    responseMessage
                )
                onFail(null, R.string.error_http, responseCode, responseMessage)
            }
        } catch (x: IOException) {
            log.info("problem sending", x)
            onFail(x, R.string.error_io, x.message)
        }
    }
}
