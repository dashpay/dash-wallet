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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.common.payments.parsers

/**
 * Payment parsers include PaymentIntentParsers and AddressParsers for a list of coins
 *
 * @constructor Create empty object with no parsers
 */

open class PaymentParsers {
    private val paymentIntentParsers = hashMapOf<String, PaymentIntentParser>()
    private val addressParsers = hashMapOf<String, AddressParser>()

    fun add(currency: String, code: String, paymentIntentParser: PaymentIntentParser, addressParser: AddressParser) {
        paymentIntentParsers[currency.lowercase()] = paymentIntentParser
        paymentIntentParsers[code.lowercase()] = paymentIntentParser
        addressParsers[currency.lowercase()] = addressParser
        addressParsers[code.lowercase()] = addressParser
    }

    fun getPaymentIntentParser(currency: String): PaymentIntentParser? {
        return paymentIntentParsers[currency.lowercase()]
    }

    fun getAddressParser(currency: String): AddressParser? {
        return addressParsers[currency.lowercase()]
    }
}
