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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.common.payments.parsers

import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.util.Constants

/**
 * Thrown by [DashUri.parse] on invalid input. Wraps [PaymentURI.ParseException]
 * (same message) so callers can catch parse failures without knowing the parser type.
 */
class DashUriParseException(message: String?, cause: Throwable) : Exception(message, cause)

/**
 * Minimal representation of a `dash:` payment URI, for feature/integration modules.
 * Parsing uses the self-contained [PaymentURI] against the wallet's network, so accepted
 * URIs are exactly those the wallet accepts.
 */
data class DashUri(val address: String?, val amount: Dash?, val message: String?) {
    companion object {
        /** Mirrors `BitcoinURI(Constants.NETWORK_PARAMETERS, uri)`. */
        @Throws(DashUriParseException::class)
        fun parse(uri: String): DashUri {
            val parsed = try {
                PaymentURI(Constants.NETWORK, uri)
            } catch (e: PaymentURI.ParseException) {
                throw DashUriParseException(e.message, e)
            }
            return DashUri(parsed.address, parsed.amount?.let { Dash(it.value) }, parsed.message)
        }

        /**
         * Builds a `dash:` payment-request URI for [address] (base58, wallet's network) with an
         * optional [amount]. Mirrors `BitcoinURI.convertToBitcoinURI`; null and empty [label]/[message]
         * are both omitted, exactly like the dashj original.
         */
        fun toUri(address: String, amount: Dash? = null, label: String? = null, message: String? = null): String {
            AddressUtils.verify(Constants.NETWORK, address)
            return PaymentURI.convertToPaymentURI(
                Constants.NETWORK,
                address,
                amount?.let { org.dash.wallet.common.money.Coin.valueOf(it.duffs) },
                label,
                message
            )
        }
    }
}

/**
 * True when this throwable is a payment-URI parse failure ([PaymentURI.ParseException]
 * or the wrapping [DashUriParseException]).
 */
val Throwable.isPaymentUriParseError: Boolean
    get() = this is PaymentURI.ParseException || this is DashUriParseException
