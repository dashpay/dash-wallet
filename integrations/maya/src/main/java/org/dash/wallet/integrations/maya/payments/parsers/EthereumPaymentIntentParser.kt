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

package org.dash.wallet.integrations.maya.payments.parsers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.AddressFormatException
import org.dash.wallet.common.R
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.payments.parsers.AddressParser
import org.dash.wallet.common.payments.parsers.PaymentIntentParserException
import org.dash.wallet.common.util.ResourceString
import org.slf4j.LoggerFactory

class EthereumPaymentIntentParser(uriPrefix: String, asset: String) : MayaPaymentIntentParser(
    "ethereum",
    uriPrefix,
    asset,
    null
) {
    private val log = LoggerFactory.getLogger(EthereumPaymentIntentParser::class.java)
    private val addressParser = AddressParser.getEthereumAddressParser()

    override suspend fun parse(input: String): PaymentIntent = withContext(Dispatchers.Default) {
        if (input.startsWith("$uriPrefix:") || input.startsWith("${uriPrefix.uppercase()}:")) {
            try {
                val hexAddress = input.substring(currency.length)
                return@withContext createPaymentIntent(hexAddress)
            } catch (ex: Exception) {
                log.info("got invalid uri: '$input'", ex)
                throw PaymentIntentParserException(
                    ex,
                    ResourceString(
                        R.string.error,
                        listOf(input)
                    )
                )
            }
        } else if (addressParser.exactMatch(input)) {
            try {
                return@withContext createPaymentIntent(input)
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
