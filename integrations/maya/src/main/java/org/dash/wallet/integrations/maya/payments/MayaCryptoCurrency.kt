/*
 * Copyright (c) 2024. Dash Core Group.
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

package org.dash.wallet.integrations.maya.payments

import androidx.annotation.StringRes
import org.dash.wallet.common.payments.parsers.AddressParser
import org.dash.wallet.common.payments.parsers.Bech32AddressParser
import org.dash.wallet.common.payments.parsers.BitcoinAddressParser
import org.dash.wallet.common.payments.parsers.BitcoinMainNetParams
import org.dash.wallet.common.payments.parsers.PaymentIntentParser
import org.dash.wallet.common.payments.parsers.PaymentParsers
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.payments.parsers.Bech32PaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.BitcoinPaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.EthereumPaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.RuneAddressParser
import org.dash.wallet.integrations.maya.payments.parsers.RunePaymentIntentProcessor
import java.math.BigDecimal
import java.math.RoundingMode

interface MayaCryptoCurrency {
    val code: String
    val name: String
    val asset: String
    val exampleAddress: String
    val paymentIntentParser: PaymentIntentParser
    val addressParser: AddressParser
    @get:StringRes
    val codeId: Int
    @get:StringRes
    val nameId: Int
    fun getPoolDepth(depthInSmallUnits: BigDecimal): BigDecimal
    fun getFee(feeInSmallUnits: BigDecimal): BigDecimal
}

open class MayaBitcoinCryptoCurrency : MayaCryptoCurrency {
    override val code: String = "BTC"
    override val name: String = "Bitcoin"
    override val asset: String = "BTC.BTC"
    override val exampleAddress: String = "bc1qxhgnnp745zryn2ud8hm6k3mygkkpkm35020js0"
    override val paymentIntentParser: PaymentIntentParser = BitcoinPaymentIntentParser()
    override val addressParser: AddressParser = BitcoinAddressParser(BitcoinMainNetParams())
    override val codeId: Int = R.string.cryptocurrency_bitcoin_code
    override val nameId: Int = R.string.cryptocurrency_bitcoin_network

    companion object {
        const val SATOSHIS_PER_COIN = 1_0000_0000
    }
    override fun getPoolDepth(depthInSmallUnits: BigDecimal): BigDecimal {
        return depthInSmallUnits.setScale(8, RoundingMode.HALF_UP).div(BigDecimal(SATOSHIS_PER_COIN))
    }

    override fun getFee(feeInSmallUnits: BigDecimal): BigDecimal {
        return feeInSmallUnits.setScale(8, RoundingMode.HALF_UP).div(BigDecimal(SATOSHIS_PER_COIN))
    }
}
class MayaDashCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "DASH"
    override val name: String = "Dash"
    override val asset: String = "DASH.DASH"
    override val exampleAddress: String = "XssjzLKgsfATYGqTQmiJURQzeKdpL5K1k3"
}

open class MayaEthereumCryptoCurrency : MayaCryptoCurrency {
    override val code: String = "ETH"
    override val name: String = "Ethereum"
    override val asset: String = "ETH.ETH"
    override val exampleAddress: String = "0x51a1449b3B6D635EddeC781cD47a99221712De97"
    override val paymentIntentParser: PaymentIntentParser = EthereumPaymentIntentParser("ethereum", "ETH.ETH")
    override val addressParser: AddressParser = AddressParser.getEthereumAddressParser()
    override val codeId: Int = R.string.cryptocurrency_ethereum_code
    override val nameId: Int = R.string.cryptocurrency_ethereum_network
    companion object {
        const val GWEI_PER_COIN = 1_000_000_000
    }
    override fun getPoolDepth(depthInSmallUnits: BigDecimal): BigDecimal {
        return depthInSmallUnits.setScale(8, RoundingMode.HALF_UP).div(BigDecimal(GWEI_PER_COIN))
    }

    override fun getFee(feeInSmallUnits: BigDecimal): BigDecimal {
        return feeInSmallUnits.setScale(8, RoundingMode.HALF_UP).div(BigDecimal(GWEI_PER_COIN))
    }
}

open class MayaKujiraCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "KUJI"
    override val name: String = "Kujira"
    override val asset: String = "KUJI.KUJI"
    override val exampleAddress: String = "kujira1r8egcurpwxftegr07gjv9gwffw4fk00960dj4f"
    override val paymentIntentParser: PaymentIntentParser = Bech32PaymentIntentParser(
        "kuji",
        "kujira",
        "kujira",
        38,
        "KIJI.KUJI"
    )
    override val addressParser: AddressParser = Bech32AddressParser("kujira", 38, null)
    override val codeId: Int = R.string.cryptocurrency_kuji_code
    override val nameId: Int = R.string.cryptocurrency_kuji_network
}

class MayaKujiraTokenCryptoCurrency(
    override val code: String,
    override val name: String,
    override val asset: String,
    override val codeId: Int,
    override val nameId: Int
) : MayaKujiraCryptoCurrency()

class MayaEthereumTokenCryptoCurrency(
    override val code: String,
    override val name: String,
    override val asset: String,
    override val paymentIntentParser: PaymentIntentParser,
    override val codeId: Int,
    override val nameId: Int
) : MayaEthereumCryptoCurrency()

open class MayaRuneCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "RUNE"
    override val name: String = "Rune"
    override val asset: String = "THOR.RUNE"
    override val exampleAddress: String = "thor166n4w5039meulfa3p6ydg60ve6ueac7tlt0jws"
    override val paymentIntentParser: PaymentIntentParser = RunePaymentIntentProcessor()
    override val addressParser: AddressParser = RuneAddressParser()
    override val codeId: Int = R.string.cryptocurrency_rune_code
    override val nameId: Int = R.string.cryptocurrency_rune_network
}

object MayaCurrencyList {
    private val currencyMap: Map<String, MayaCryptoCurrency>
    init {
        val currencyList = listOf(
            MayaBitcoinCryptoCurrency(),
            MayaEthereumCryptoCurrency(),
            MayaEthereumTokenCryptoCurrency(
                "PEPE",
                "PEPE",
                "ETH.PEPE-0X6982508145454CE325DDBE47A25D4EC3D2311933",
                EthereumPaymentIntentParser("pepe", "ETH.PEPE-11933"),
                R.string.cryptocurrency_pepe_code,
                R.string.cryptocurrency_pepe_ethereum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDC",
                "USD Coin",
                "ETH.USDC-0XA0B86991C6218B36C1D19D4A2E9EB0CE3606EB48",
                EthereumPaymentIntentParser("usdc", "ETH.USDC-6EB48"),
                R.string.cryptocurrency_usdcoin_code,
                R.string.cryptocurrency_usdcoin_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDT",
                "Tether",
                "ETH.USDT-0XDAC17F958D2EE523A2206206994597C13D831EC7",
                EthereumPaymentIntentParser("usdt", "ETH.USDT-31EC7"),
                R.string.cryptocurrency_tether_code,
                R.string.cryptocurrency_tether_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "WSTETH",
                "Wrapped Stable ETH",
                "ETH.WSTETH-0X7F39C581F595B53C5CB19BD0B3F8DA6C935E2CA0",
                EthereumPaymentIntentParser("wsteth", "ETH.WSTETH-E2CA0"),
                R.string.cryptocurrency_wsteth_code,
                R.string.cryptocurrency_wsteth_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "ARB",
                "Arbitrum",
                "ARB.ARB-0X912CE59144191C1204E64559FE8253A0E49E6548",
                EthereumPaymentIntentParser("arbitrum", "ARB.ARB-E6548"),
                R.string.cryptocurrency_arbitrum_code,
                R.string.cryptocurrency_arbitrum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "ETH",
                "Ethereum",
                "ARB.ETH",
                EthereumPaymentIntentParser("ethereum", "ARB.ETH"),
                R.string.cryptocurrency_ethereum_code,
                R.string.cryptocurrency_ethereum_arbitrum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "DAI",
                "Dai",
                "ARB.DAI-0XDA10009CBD5D07DD0CECC66161FC93D7C9000DA1",
                EthereumPaymentIntentParser("dai", "ARB.DAI-00DA1"),
                R.string.cryptocurrency_dai_code,
                R.string.cryptocurrency_dai_arbitrum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "GLD",
                "Goldario",
                "ARB.GLD-0XAFD091F140C21770F4E5D53D26B2859AE97555AA",
                EthereumPaymentIntentParser("gld", "ARB.GLD-555AA"),
                R.string.cryptocurrency_goldario_code,
                R.string.cryptocurrency_goldario_arbitrum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "LEO",
                "LEO",
                "ARB.LEO-0X93864D81175095DD93360FFA2A529B8642F76A6E",
                EthereumPaymentIntentParser("leo", "ARB.LEO-76A6E"),
                R.string.cryptocurrency_leo_code,
                R.string.cryptocurrency_leo_arbitrum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "LINK",
                "ChainLink",
                "ARB.LINK-0XF97F4DF75117A78C1A5A0DBB814AF92458539FB4",
                EthereumPaymentIntentParser("link", "ARB.LINK-39FB4"),
                R.string.cryptocurrency_link_code,
                R.string.cryptocurrency_link_arbitrum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "PEPE",
                "PEPE",
                "ARB.PEPE-0X25D887CE7A35172C62FEBFD67A1856F20FAEBB00",
                EthereumPaymentIntentParser("pepe", "ARB.PEPE-EBB00"),
                R.string.cryptocurrency_pepe_code,
                R.string.cryptocurrency_pepe_arbitrum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "TGT",
                "THORWallet",
                "ARB.TGT-0X429FED88F10285E61B12BDF00848315FBDFCC341",
                EthereumPaymentIntentParser("thorwallet", "ARB.TGT-CC341"),
                R.string.cryptocurrency_tgt_code,
                R.string.cryptocurrency_tgt_arbitrum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDC",
                "USD Coin",
                "ARB.USDC-0XAF88D065E77C8CC2239327C5EDB3A432268E5831",
                EthereumPaymentIntentParser("pepe", "ARB.USDC-0XE5831"),
                R.string.cryptocurrency_usdcoin_code,
                R.string.cryptocurrency_usdcoin_arbitrum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "WBTC",
                "Wrapped Bitcoin",
                "ARB.WBTC-0X2F2A2543B76A4166549F7AAB2E75BEF0AEFC5B0F",
                EthereumPaymentIntentParser("wbtc", "ARB.WBTC-C5B0F"),
                R.string.cryptocurrency_wbtc_code,
                R.string.cryptocurrency_wbtc_arbitrum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "WSTETH",
                "Wrapped stETH",
                "ARB.WSTETH-0X5979D7B546E38E414F7E9822514BE443A4800529",
                EthereumPaymentIntentParser("wsetth", "ARB.WSTETH-00529"),
                R.string.cryptocurrency_wsteth_code,
                R.string.cryptocurrency_wsteth_arbitrum_network
            ),

            MayaKujiraCryptoCurrency(),
            MayaKujiraTokenCryptoCurrency(
                "USK",
                "USK",
                "KUJI.USK",
                R.string.cryptocurrency_usk_code,
                R.string.cryptocurrency_usk_network
            ),

            MayaRuneCryptoCurrency()
        )
        currencyMap = currencyList.associateBy({ it.asset }, { it })
    }
    operator fun get(asset: String) = currencyMap[asset]
    val all: Collection<MayaCryptoCurrency>
        get() = currencyMap.values
    fun getPaymentProcessors(): PaymentParsers {
        val paymentProcessors = PaymentParsers()
        for (currency in all) {
            paymentProcessors.add(currency.name, currency.code, currency.paymentIntentParser, currency.addressParser)
        }
        return paymentProcessors
    }
}
