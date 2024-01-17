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

import org.dash.wallet.common.payments.parsers.AddressParser
import org.dash.wallet.common.payments.parsers.PaymentParsers

class MayaPaymentParsers : PaymentParsers() {
    init {
        add("bitcoin", "btc", BitcoinPaymentIntentParser(), AddressParser.getBitcoinAddressParser())
        add("ethereum", "eth", EthereumPaymentIntentParser("ETH.ETH"), AddressParser.getEthereumAddressParser())
        add("usdc", "usdc", EthereumPaymentIntentParser("ETH.USDC"), AddressParser.getEthereumAddressParser())
        add("tether", "usdt", EthereumPaymentIntentParser("ETH.USDC"), AddressParser.getEthereumAddressParser())
        add(
            "Wrapped stETH",
            "wsteth",
            EthereumPaymentIntentParser("ETH.WSTETH"),
            AddressParser.getEthereumAddressParser()
        )
        add("rune", "rune", RunePaymentIntentProcessor(), RuneAddressParser())
        add("kujira", "kuji", BEP2PaymentIntentParser("kuji", "kujira", "KIJI.KUJI"), BEP2AddressParser("kujira"))
        add("usk", "usk", BEP2PaymentIntentParser("usk", "usk", "KUJI.USK"), BEP2AddressParser("usk"))
    }
}
