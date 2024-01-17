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

package org.dash.wallet.common.payments.parsers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.AddressFormatException
import org.bitcoinj.core.Coin
import org.bitcoinj.script.ScriptBuilder
import org.dash.wallet.common.R
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.util.ResourceString
import org.slf4j.LoggerFactory

class EthereumPaymentIntentParser : PaymentIntentParser("ethereum", null) {
    private val log = LoggerFactory.getLogger(EthereumPaymentIntentParser::class.java)
    private val addressParser = AddressParser.getEthereumAddressParser()

    override suspend fun parse(input: String): PaymentIntent = withContext(Dispatchers.Default) {
        var inputStr = input

        if (inputStr.startsWith("$currency:") || inputStr.startsWith("${currency.uppercase()}:")) {
            try {
                val hexAddress = inputStr.substring(currency.length)
                return@withContext createPaymentIntent(hexAddress)
            } catch (ex: Exception) {
                log.info("got invalid uri: '$inputStr'", ex)
                throw PaymentIntentParserException(
                    ex,
                    ResourceString(
                        R.string.error,
                        listOf(inputStr)
                    )
                )
            }
        } else if (addressParser.exactMatch(inputStr)) {
            try {
                return@withContext createPaymentIntent(inputStr)
            } catch (ex: AddressFormatException) {
                log.info("got invalid address", ex)
                throw PaymentIntentParserException(
                    ex,
                    ResourceString(
                        R.string.error,
                        listOf()
                    )
                )
            }
        }

        log.info("cannot classify: '{}'", input)
        throw PaymentIntentParserException(
            IllegalArgumentException(input),
            ResourceString(
                R.string.error,
                listOf(input)
            )
        )
    }

    private fun createPaymentIntent(inputStr: String): PaymentIntent {
        val metadata = "SWAP:ETH.ETH:${inputStr.substring(2)}"
        return PaymentIntent(
            null, "ethereum", null,
            arrayOf(PaymentIntent.Output(Coin.ZERO, ScriptBuilder.createOpReturnScript(metadata.toByteArray()))),
            "ethereum network", null, null, null, null
        )
    }
}
