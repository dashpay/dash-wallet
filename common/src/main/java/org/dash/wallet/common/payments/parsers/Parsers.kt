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

// TODO: this may need to be a class, so we can define a custom set of parsers
// that a #[AddressInputViewModel] can use to parse addresses and payments intents
// for Maya, there may be custom parsers that generate the correct PaymentIntent
// that we don't want used or visible to the wallet module. Currently this has global scope.

object Parsers {
    private val paymentIntentParsers = hashMapOf<String, PaymentIntentParser>()
    private val addressParsers = hashMapOf<String, AddressParser>()

    init {
        add("bitcoin", "btc", BitcoinPaymentIntentParser(), AddressParser.getBitcoinAddressParser())
        add("ethereum", "eth", EthereumPaymentIntentParser(), AddressParser.getEthereumAddressParser())
    }

    @JvmStatic
    fun add(currency: String, code: String, paymentIntentParser: PaymentIntentParser, addressParser: AddressParser) {
        paymentIntentParsers[currency] = paymentIntentParser
        paymentIntentParsers[code] = paymentIntentParser
        addressParsers[currency] = addressParser
        addressParsers[code] = addressParser
    }

    @JvmStatic
    fun getPaymentIntentParser(currency: String): PaymentIntentParser? {
        return paymentIntentParsers[currency.lowercase()]
    }

    @JvmStatic
    fun getAddressParser(currency: String): AddressParser? {
        return addressParsers[currency.lowercase()]
    }
}
