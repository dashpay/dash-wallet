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
import org.dash.wallet.common.payments.parsers.AddressFormatException
import org.dash.wallet.common.R
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.payments.parsers.PaymentIntentParserException
import org.dash.wallet.common.util.ResourceString
import org.slf4j.LoggerFactory

/**
 * Parser for Cardano (ADA) payment intents. Modern Shelley addresses are
 * Bech32-encoded with HRP `addr` and ~58-103 chars.
 */
open class CardanoPaymentIntentParser(
    currency: String = "ADA",
    asset: String = "ADA.ADA",
    shortAsset: String? = null
) : MayaPaymentIntentParser(currency, "cardano", asset, shortAsset) {
    private val log = LoggerFactory.getLogger(CardanoPaymentIntentParser::class.java)
    private val addressParser = CardanoAddressParser()

    override suspend fun parse(input: String): PaymentIntent = withContext(Dispatchers.Default) {
        if (input.startsWith("$uriPrefix:") || input.startsWith("${uriPrefix.uppercase()}:")) {
            try {
                val address = validate(input.substring(uriPrefix.length + 1))
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
                return@withContext createPaymentIntent(normalizeShelley(input))
            } catch (ex: AddressFormatException) {
                log.info("got invalid address", ex)
                throw PaymentIntentParserException(
                    ex,
                    ResourceString(R.string.error, listOf())
                )
            }
        }
        log.info("cannot classify as ADA address: '{}'", input)
        throw PaymentIntentParserException(
            IllegalArgumentException(input),
            ResourceString(R.string.error, listOf(input))
        )
    }

    /**
     * Shelley addresses are bech32 and may be scanned all-caps; lowercase is canonical.
     * Byron addresses are Base58 (case-sensitive) — left untouched.
     */
    private fun normalizeShelley(address: String) =
        if (address.startsWith("addr1", ignoreCase = true)) address.lowercase() else address

    /** URI payloads get the same validation as bare addresses before reaching the swap memo. */
    private fun validate(address: String): String {
        if (!addressParser.exactMatch(address)) {
            throw AddressFormatException("not a valid $currency address: $address")
        }
        return normalizeShelley(address)
    }
}
