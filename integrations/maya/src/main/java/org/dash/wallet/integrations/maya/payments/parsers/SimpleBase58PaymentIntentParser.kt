/*
 * Copyright 2026 Dash Core Group.
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

/**
 * Generic Base58 / pattern-driven payment intent parser. Used for L1 chains
 * (LTC, DOGE, BCH, etc.) where SwapKit performs the final address validation
 * server-side and the wallet only needs lexical screening.
 */
open class SimpleBase58PaymentIntentParser(
    currency: String,
    uriPrefix: String,
    asset: String,
    private val pattern: String,
    shortAsset: String? = null
) : MayaPaymentIntentParser(currency, uriPrefix, asset, shortAsset, null) {
    private val log = LoggerFactory.getLogger(SimpleBase58PaymentIntentParser::class.java)
    private val addressParser = AddressParser(pattern, null)

    override suspend fun parse(input: String): PaymentIntent = withContext(Dispatchers.Default) {
        if (input.startsWith("$uriPrefix:") || input.startsWith("${uriPrefix.uppercase()}:")) {
            try {
                val address = input.substring(uriPrefix.length + 1)
                return@withContext createPaymentIntent(address)
            } catch (ex: Exception) {
                log.info("got invalid uri: '$input'", ex)
                throw PaymentIntentParserException(
                    ex,
                    ResourceString(R.string.error, listOf(input))
                )
            }
        } else if (addressParser.exactMatch(input)) {
            try {
                return@withContext createPaymentIntent(input)
            } catch (ex: AddressFormatException) {
                log.info("got invalid address", ex)
                throw PaymentIntentParserException(
                    ex,
                    ResourceString(R.string.error, listOf())
                )
            }
        }
        log.info("cannot classify as $currency address: '{}'", input)
        throw PaymentIntentParserException(
            IllegalArgumentException(input),
            ResourceString(R.string.error, listOf(input))
        )
    }
}
