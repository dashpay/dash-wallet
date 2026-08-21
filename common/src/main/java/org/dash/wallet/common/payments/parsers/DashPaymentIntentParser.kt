/*
 * Copyright 2024 Dash Core Group.
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

package org.dash.wallet.common.payments.parsers

import com.google.common.hash.Hashing
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.UninitializedMessageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dash.wallet.common.payments.bip70.Protos.PaymentRequest
import org.dash.wallet.common.payments.bip70.TrustStoreLoader.DefaultTrustStoreLoader
import org.dash.wallet.common.payments.bip70.PaymentProtocol
import org.dash.wallet.common.payments.bip70.PaymentProtocolException
import org.dash.wallet.common.payments.bip70.PaymentProtocolException.Expired
import org.dash.wallet.common.payments.bip70.PaymentProtocolException.InvalidNetwork
import org.dash.wallet.common.payments.bip70.PaymentProtocolException.InvalidPaymentURL
import org.dash.wallet.common.payments.bip70.PaymentProtocolException.PkiVerificationException
import org.dash.wallet.common.R
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.util.AddressUtil
import org.dash.wallet.common.util.Base43
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.Io
import org.dash.wallet.common.util.ResourceString
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.KeyStoreException
import java.util.*

class DashPaymentIntentParser(params: AddressNetwork) : PaymentIntentParser("dash", "dash", params) {
    private val log = LoggerFactory.getLogger(DashPaymentIntentParser::class.java)
    private val addressParser = AddressParser.getDashAddressParser(params)

    init {
        log.info("network parameters = {}", params.id)
    }
    override suspend fun parse(input: String): PaymentIntent {
        return parse(input, true)
    }
    suspend fun parse(input: String, supportAnypayUrls: Boolean): PaymentIntent = withContext(Dispatchers.Default) {
        var inputStr = input

        if (supportAnypayUrls) {
            // replaces Anypay scheme with the Dash one
            // ie "pay:?r=https://(...)" become "dash:?r=https://(...)"
            if (input.startsWith(Constants.ANYPAY_SCHEME + ":")) {
                inputStr = input.replaceFirst(Constants.ANYPAY_SCHEME.toRegex(), Constants.DASH_SCHEME)
            }
        }

        if (inputStr.startsWith(Constants.DASH_SCHEME.uppercase(Locale.getDefault()) + ":-")) {
            val serializedPaymentRequest = try {
                Base43.decode(inputStr.substring(9))
            } catch (ex: IllegalArgumentException) {
                log.info("error while decoding request", ex)
                throw PaymentIntentParserException(
                    ex,
                    ResourceString(
                        R.string.input_parser_io_error,
                        listOf(ex.message ?: "")
                    )
                )
            }

            return@withContext parseRequest(serializedPaymentRequest)
        } else if (inputStr.startsWith(Constants.DASH_SCHEME + ":")) {
            try {
                val paymentUri = PaymentURI(null, inputStr)
                val address = AddressUtil.getCorrectAddress(paymentUri, params)

                if (address != null && !params!!.acceptsVersion(AddressUtils.decode(address).version)) {
                    throw PaymentURI.ParseException("mismatched network")
                }

                return@withContext PaymentIntent.fromPaymentUri(paymentUri)
            } catch (ex: PaymentURI.ParseException) {
                log.info("got invalid bitcoin uri: '$inputStr'", ex)
                throw PaymentIntentParserException(
                    ex,
                    ResourceString(
                        R.string.input_parser_invalid_bitcoin_uri,
                        listOf(inputStr)
                    )
                )
            }
        } else if (addressParser.exactMatch(inputStr)) {
            try {
                AddressUtils.verify(params!!, inputStr)
                return@withContext PaymentIntent.fromAddress(inputStr, null)
            } catch (ex: AddressFormatException) {
                log.info("got invalid address", ex)
                throw PaymentIntentParserException(
                    ex,
                    ResourceString(
                        R.string.input_parser_invalid_address,
                        listOf()
                    )
                )
            }
        }

        log.info("cannot classify: '{}'", input)
        throw PaymentIntentParserException(
            IllegalArgumentException(input),
            ResourceString(
                R.string.input_parser_cannot_classify,
                listOf(input)
            )
        )
    }

    suspend fun parse(inputStream: InputStream, inputType: String): PaymentIntent = withContext(Dispatchers.IO) {
        if (PaymentProtocol.MIMETYPE_PAYMENTREQUEST == inputType) {
            val byteArray = inputStream.use {
                ByteArrayOutputStream().use { outputStream ->
                    try {
                        Io.copy(inputStream, outputStream)
                        outputStream.toByteArray()
                    } catch (ex: IOException) {
                        log.info("i/o error while fetching payment request", ex)
                        throw PaymentIntentParserException(
                            ex,
                            ResourceString(
                                R.string.input_parser_io_error,
                                listOf(ex.message ?: "")
                            )
                        )
                    }
                }
            }

            return@withContext parseRequest(byteArray)
        }

        log.info("cannot classify: '{}'", inputType)
        throw PaymentIntentParserException(
            IllegalArgumentException(inputType),
            ResourceString(
                R.string.input_parser_io_error,
                listOf(inputType)
            )
        )
    }

    private fun parseRequest(byteArray: ByteArray): PaymentIntent {
        return try {
            parsePaymentRequest(byteArray)
        } catch (ex: PkiVerificationException) {
            log.info("got unverifiable payment request", ex)
            throw PaymentIntentParserException(
                ex,
                ResourceString(
                    R.string.input_parser_unverifyable_paymentrequest,
                    listOf(ex.message ?: "")
                )
            )
        } catch (ex: PaymentProtocolException) {
            log.info("got invalid payment request", ex)
            throw PaymentIntentParserException(
                ex,
                ResourceString(
                    R.string.input_parser_invalid_paymentrequest,
                    listOf(ex.message ?: "")
                )
            )
        }
    }

    @Suppress("UnstableApiUsage")
    private fun parsePaymentRequest(serializedPaymentRequest: ByteArray): PaymentIntent {
        try {
            if (serializedPaymentRequest.size > 50000) {
                throw PaymentProtocolException("payment request too big: " + serializedPaymentRequest.size)
            }

            val paymentRequest = PaymentRequest.parseFrom(serializedPaymentRequest)
            var pkiName: String? = null
            var pkiCaName: String? = null

            if (paymentRequest.pkiType != "none") {
                val keystore = DefaultTrustStoreLoader().keyStore
                val verificationData = PaymentProtocol.verifyPaymentRequestPki(
                    paymentRequest,
                    keystore
                )
                pkiName = verificationData!!.displayName
                pkiCaName = verificationData.rootAuthorityName
            }

            val paymentSession = PaymentProtocol.parsePaymentRequest(paymentRequest)

            if (paymentSession.isExpired) {
                throw Expired(
                    "payment details expired: current time " + Date() +
                        " after expiry time " + paymentSession.expires
                )
            }

            if (paymentSession.networkParameters != params) {
                throw InvalidNetwork(
                    "cannot handle payment request network: " + paymentSession.networkParameters
                )
            }

            val outputs = paymentSession.outputs.map { PaymentIntent.Output.valueOf(it) }
            val paymentRequestHash = Hashing.sha256().hashBytes(serializedPaymentRequest).asBytes()
            val paymentIntent = PaymentIntent(
                PaymentIntent.Standard.BIP70,
                pkiName,
                pkiCaName,
                outputs.toTypedArray(),
                paymentSession.memo,
                paymentSession.paymentUrl,
                paymentSession.merchantData,
                null,
                paymentRequestHash,
                paymentSession.expires,
                null,
                null
            )

            if (paymentIntent.hasPaymentUrl() && !paymentIntent.isSupportedPaymentUrl) {
                throw InvalidPaymentURL(
                    "cannot handle payment url: " + paymentIntent.paymentUrl
                )
            }

            return paymentIntent
        } catch (x: InvalidProtocolBufferException) {
            throw PaymentProtocolException(x)
        } catch (x: UninitializedMessageException) {
            throw PaymentProtocolException(x)
        } catch (x: FileNotFoundException) {
            throw RuntimeException(x)
        } catch (x: KeyStoreException) {
            throw RuntimeException(x)
        }
    }
}
