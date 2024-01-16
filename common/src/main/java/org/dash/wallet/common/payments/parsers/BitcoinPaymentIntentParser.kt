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
import org.bitcoinj.core.Address
import org.bitcoinj.core.AddressFormatException
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.uri.BitcoinURI
import org.bitcoinj.uri.BitcoinURIParseException
import org.dash.wallet.common.R
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.util.ResourceString
import org.slf4j.LoggerFactory

class BitcoinPaymentIntentParser : PaymentIntentParser("bitcoin", BitcoinMainNetParams()) {
    private val log = LoggerFactory.getLogger(BitcoinPaymentIntentParser::class.java)
    private val addressParser = AddressParser.getBitcoinAddressParser()

    override suspend fun parse(input: String): PaymentIntent = withContext(Dispatchers.Default) {
        var inputStr = input

//        if (supportAnypayUrls) {
//            // replaces Anypay scheme with the Dash one
//            // ie "pay:?r=https://(...)" become "dash:?r=https://(...)"
//            if (input.startsWith(Constants.ANYPAY_SCHEME + ":")) {
//                inputStr = input.replaceFirst(Constants.ANYPAY_SCHEME.toRegex(), Constants.DASH_SCHEME)
//            }
//        }

        if (inputStr.startsWith("$currency:") || inputStr.startsWith("${currency.uppercase()}:")) {
            try {
                val bitcoinUri = BitcoinURI(null, inputStr)
                val address = bitcoinUri.address; // AddressUtil.getCorrectAddress(bitcoinUri)

                if (address != null && params != null && params != address.parameters) {
                    throw BitcoinURIParseException("mismatched network")
                }

                return@withContext PaymentIntent.fromBitcoinUri(bitcoinUri)
            } catch (ex: BitcoinURIParseException) {
                log.info("got invalid bitcoin uri: '$inputStr'", ex)
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
                val address = Address.fromString(params, inputStr)
                return@withContext PaymentIntent.fromAddress(address, null)
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
}
