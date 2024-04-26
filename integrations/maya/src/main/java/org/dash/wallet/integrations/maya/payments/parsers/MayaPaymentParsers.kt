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
import org.dash.wallet.common.payments.parsers.Bech32AddressParser
import org.dash.wallet.common.payments.parsers.BitcoinAddressParser
import org.dash.wallet.common.payments.parsers.BitcoinMainNetParams
import org.dash.wallet.common.payments.parsers.PaymentParsers

class MayaPaymentParsers : PaymentParsers() {
    init {
        add("bitcoin", "btc", BitcoinPaymentIntentParser(), BitcoinAddressParser(BitcoinMainNetParams()))
        add(
            "ethereum",
            "eth",
            EthereumPaymentIntentParser("ethereum", "ETH.ETH"),
            AddressParser.getEthereumAddressParser()
        )
        add("usdc", "usdc", EthereumPaymentIntentParser("usdc", "ETH.USDC-6EB48"), AddressParser.getEthereumAddressParser())
        add("tether", "usdt", EthereumPaymentIntentParser("usdt", "ETH.USDT-31EC7"), AddressParser.getEthereumAddressParser())
        add(
            "Wrapped stETH",
            "wsteth",
            EthereumPaymentIntentParser("wsteth", "ETH.WSTETH-E2CA0"),
            AddressParser.getEthereumAddressParser()
        )
        add("rune", "rune", RunePaymentIntentProcessor(), RuneAddressParser())
        add(
            "kujira",
            "kuji",
            Bech32PaymentIntentParser("kuji", "kujira", "kujira", 38, "KIJI.KUJI"),
            Bech32AddressParser("kujira", 38, null)
        )
        add(
            "usk",
            "usk",
            Bech32PaymentIntentParser("usk", "usk", "kajira", 38, "KUJI.USK"),
            Bech32AddressParser("usk", 38, null)
        )
    }
}
