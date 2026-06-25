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
import org.bitcoinj.core.Address
import org.bitcoinj.core.AddressFormatException
import org.bitcoinj.core.Coin
import org.bitcoinj.uri.BitcoinURI
import org.dash.wallet.common.payments.parsers.AddressParser
import org.dash.wallet.common.payments.parsers.Bech32AddressParser
import org.dash.wallet.common.payments.parsers.BitcoinAddressParser
import org.dash.wallet.common.payments.parsers.BitcoinMainNetParams
import org.dash.wallet.common.payments.parsers.PaymentIntentParser
import org.dash.wallet.common.payments.parsers.PaymentParsers
import org.dash.wallet.common.payments.parsers.SegwitAddress
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.payments.parsers.Bech32PaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.BitcoinPaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.CardanoAddressParser
import org.dash.wallet.integrations.maya.payments.parsers.CardanoPaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.EthereumPaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.NearAddressParser
import org.dash.wallet.integrations.maya.payments.parsers.NearPaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.RuneAddressParser
import org.dash.wallet.integrations.maya.payments.parsers.RunePaymentIntentProcessor
import org.dash.wallet.integrations.maya.payments.parsers.SimpleBase58PaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.SolanaAddressParser
import org.dash.wallet.integrations.maya.payments.parsers.SolanaPaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.StarknetAddressParser
import org.dash.wallet.integrations.maya.payments.parsers.StarknetPaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.SuiAddressParser
import org.dash.wallet.integrations.maya.payments.parsers.SuiPaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.TonAddressParser
import org.dash.wallet.integrations.maya.payments.parsers.TonPaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.TronAddressParser
import org.dash.wallet.integrations.maya.payments.parsers.TronPaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.XrdPaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.XrpAddressParser
import org.dash.wallet.integrations.maya.payments.parsers.XrpPaymentIntentParser
import org.dash.wallet.integrations.maya.payments.parsers.ZcashAddressParser
import org.dash.wallet.integrations.maya.payments.parsers.ZcashPaymentIntentParser
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
    fun getPoolDepth(depthInSmallUnits: BigDecimal): BigDecimal = BigDecimal.ZERO
    fun getFee(feeInSmallUnits: BigDecimal): BigDecimal = BigDecimal.ZERO
    fun getPaymentRequestURI(address: String, amount: String): String
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
    override fun getPaymentRequestURI(address: String, amount: String): String {
        return try {
            BitcoinURI.convertToBitcoinURI(
                Address.fromBase58(BitcoinMainNetParams(), address),
                Coin.parseCoin(amount),
                null,
                null
            )
        } catch (e: AddressFormatException) {
            SegwitAddress.fromBech32(BitcoinMainNetParams(), address)
            "bitcoin:$address&amount=$amount"
        }
    }

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
    override fun getPaymentRequestURI(address: String, amount: String): String =
        "dash:$address?amount=$amount"
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
    // EIP-155 chain id, derived from the SwapKit chain prefix of [asset] (e.g. "ARB" in "ARB.ARB-…").
    open val chain: Int
        get() = EVM_CHAIN_IDS[asset.substringBefore(".")] ?: 1
    companion object {
        const val GWEI_PER_COIN = 1_000_000_000L
        // 18 decimals — every EVM native gas coin (ETH, AVAX, BNB, POL, …) uses 1e18 base units.
        const val NATIVE_DECIMALS = 18
        // SwapKit/Maya chain prefix -> EIP-155 chain id.
        val EVM_CHAIN_IDS = mapOf(
            "ETH" to 1,
            "OP" to 10,
            "BSC" to 56,
            "GNO" to 100,
            "POL" to 137,
            "MONAD" to 143,
            "XLAYER" to 196,
            "BASE" to 8453,
            "ARB" to 42161,
            "AVAX" to 43114,
            "BERA" to 80094
        )
    }
    override fun getPoolDepth(depthInSmallUnits: BigDecimal): BigDecimal {
        return depthInSmallUnits.setScale(8, RoundingMode.HALF_UP).div(BigDecimal(GWEI_PER_COIN))
    }

    override fun getFee(feeInSmallUnits: BigDecimal): BigDecimal {
        return feeInSmallUnits.setScale(8, RoundingMode.HALF_UP).div(BigDecimal(GWEI_PER_COIN))
    }

    // EIP-681 native transfer: ethereum:<recipient>@<chainId>?value=<wei> (value in 1e18 base units).
    override fun getPaymentRequestURI(address: String, amount: String): String {
        val weiAmount = BigDecimal(amount).movePointRight(NATIVE_DECIMALS).toBigInteger()
        return "ethereum:$address@$chain?value=$weiAmount"
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
    // No payment-URI scheme honored by Cosmos/Kujira wallets — encode the bare address.
    override fun getPaymentRequestURI(address: String, amount: String): String = address
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
    override val nameId: Int,
    // Token base-unit exponent. Defaults from the symbol (ERC-20 norm 18, with the usual
    // stablecoin / wrapped-BTC exceptions); pass explicitly for a token with non-standard decimals.
    val decimals: Int = defaultDecimals(code, asset)
) : MayaEthereumCryptoCurrency() {
    // ERC-20 contract address (the part after the "-" in the SwapKit asset id), lower-cased for the
    // EIP-681 target. Empty for native-coin entries like "ARB.ETH", which use the native value form.
    private val contract = asset.substringAfter("-", "").lowercase()

    companion object {
        fun defaultDecimals(code: String, asset: String): Int = when (code.uppercase()) {
            // Binance-Peg USDC/USDT on BSC are 18 decimals; elsewhere these stablecoins are 6.
            "USDC", "USDT" -> if (asset.substringBefore(".") == "BSC") 18 else 6
            "USDT0" -> 6
            "WBTC", "CBBTC" -> 8
            else -> 18
        }
    }

    // EIP-681 ERC-20 transfer:
    //   ethereum:<contract>@<chainId>/transfer?address=<recipient>&uint256=<amount-in-base-units>
    // e.g. 5 USDC (6 decimals) on Ethereum -> uint256=5000000. Entries with no contract (a native
    // coin on the chain, e.g. ARB.ETH) fall back to the native value form.
    override fun getPaymentRequestURI(address: String, amount: String): String {
        if (contract.isEmpty()) {
            return super.getPaymentRequestURI(address, amount)
        }
        val uint256Amount = BigDecimal(amount).movePointRight(decimals).toBigInteger()
        return "ethereum:$contract@$chain/transfer?address=$address&uint256=$uint256Amount"
    }
}

open class MayaRuneCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "RUNE"
    override val name: String = "Rune"
    override val asset: String = "THOR.RUNE"
    override val exampleAddress: String = "thor166n4w5039meulfa3p6ydg60ve6ueac7tlt0jws"
    override val paymentIntentParser: PaymentIntentParser = RunePaymentIntentProcessor()
    override val addressParser: AddressParser = RuneAddressParser()
    override val codeId: Int = R.string.cryptocurrency_rune_code
    override val nameId: Int = R.string.cryptocurrency_rune_network
    // No payment-URI scheme honored by THORChain wallets — encode the bare address.
    override fun getPaymentRequestURI(address: String, amount: String): String = address
}

open class MayaMayaTokenCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "MAYA"
    override val name: String = "Maya"
    override val asset: String = "MAYA.MAYA"
    override val exampleAddress: String = "maya1x9jj85ugrpf8j0nhq9p7c4qjn9a2ufnhmlvt5e"
    override val paymentIntentParser: PaymentIntentParser = Bech32PaymentIntentParser(
        "MAYA",
        "maya",
        "maya",
        38,
        "MAYA.MAYA"
    )
    override val addressParser: AddressParser = Bech32AddressParser("maya", 38, null)
    override val codeId: Int = R.string.cryptocurrency_maya_code
    override val nameId: Int = R.string.cryptocurrency_maya_network
    // No payment-URI scheme honored by Maya/Cosmos wallets — encode the bare address.
    override fun getPaymentRequestURI(address: String, amount: String): String = address
}

open class MayaCacaoCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "CACAO"
    override val name: String = "Maya Protocol"
    override val asset: String = "MAYA.CACAO"
    override val exampleAddress: String = "maya1x9jj85ugrpf8j0nhq9p7c4qjn9a2ufnhmlvt5e"
    override val paymentIntentParser: PaymentIntentParser = Bech32PaymentIntentParser(
        "CACAO",
        "maya",
        "maya",
        38,
        "MAYA.CACAO"
    )
    override val addressParser: AddressParser = Bech32AddressParser("maya", 38, null)
    override val codeId: Int = R.string.cryptocurrency_cacao_code
    override val nameId: Int = R.string.cryptocurrency_cacao_network
    // No payment-URI scheme honored by Maya/Cosmos wallets — encode the bare address.
    override fun getPaymentRequestURI(address: String, amount: String): String = address
}

open class MayaZcashCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "ZEC"
    override val name: String = "Zcash"
    override val asset: String = "ZEC.ZEC"
    override val exampleAddress: String = "t1K79TgQbqu74d6rBmsMu2oFEXEwAmdYiT7"
    override val paymentIntentParser: PaymentIntentParser = ZcashPaymentIntentParser()
    override val addressParser: AddressParser = ZcashAddressParser()
    override val codeId: Int = R.string.cryptocurrency_zec_code
    override val nameId: Int = R.string.cryptocurrency_zec_network
    // ZIP-321 transparent-address payment URI.
    override fun getPaymentRequestURI(address: String, amount: String): String =
        "zcash:$address?amount=$amount"
}

open class MayaRadixCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "XRD"
    override val name: String = "Radix"
    override val asset: String = "XRD.XRD"
    override val exampleAddress: String = "account_rdx1680ldd0sgl547sp05eqdpt3x8wvq004qeh7rk54t65t7yxn87ukunn"
    override val paymentIntentParser: PaymentIntentParser = XrdPaymentIntentParser()
    override val addressParser: AddressParser = Bech32AddressParser(
        "account_rdx",
        "1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{50,65}",
        null
    )
    override val codeId: Int = R.string.cryptocurrency_xrd_code
    override val nameId: Int = R.string.cryptocurrency_xrd_network
    // No payment-URI scheme honored by Radix wallets — encode the bare address.
    override fun getPaymentRequestURI(address: String, amount: String): String = address
}

// ---------------------------------------------------------------------------
// EVM L2 / sidechain native coin classes — share the Ethereum address format
// and 1e9 GWEI scaling. The asset string varies per chain (e.g. BASE.ETH).
// ---------------------------------------------------------------------------

open class MayaArbitrumCryptoCurrency : MayaEthereumCryptoCurrency() {
    override val code: String = "ARB"
    override val name: String = "Arbitrum"
    override val asset: String = "ARB.ARB-0X912CE59144191C1204E64559FE8253A0E49E6548"
    override val exampleAddress: String = "0x51a1449b3B6D635EddeC781cD47a99221712De97"
    override val paymentIntentParser: PaymentIntentParser = EthereumPaymentIntentParser("ethereum", "ARB.ARB-E6548")
    override val codeId: Int = R.string.cryptocurrency_arbitrum_code
    override val nameId: Int = R.string.cryptocurrency_arbitrum_network
}

open class MayaBaseCryptoCurrency : MayaEthereumCryptoCurrency() {
    override val code: String = "ETH"
    override val name: String = "Ethereum"
    override val asset: String = "BASE.ETH"
    override val exampleAddress: String = "0x51a1449b3B6D635EddeC781cD47a99221712De97"
    override val paymentIntentParser: PaymentIntentParser = EthereumPaymentIntentParser("ethereum", "BASE.ETH")
    override val codeId: Int = R.string.cryptocurrency_ethereum_code
    override val nameId: Int = R.string.cryptocurrency_ethereum_base_network
}

open class MayaOptimismCryptoCurrency : MayaEthereumCryptoCurrency() {
    override val code: String = "ETH"
    override val name: String = "Ethereum"
    override val asset: String = "OP.ETH"
    override val exampleAddress: String = "0x51a1449b3B6D635EddeC781cD47a99221712De97"
    override val paymentIntentParser: PaymentIntentParser = EthereumPaymentIntentParser("ethereum", "OP.ETH")
    override val codeId: Int = R.string.cryptocurrency_ethereum_code
    override val nameId: Int = R.string.cryptocurrency_ethereum_optimism_network
}

open class MayaAvalancheCryptoCurrency : MayaEthereumCryptoCurrency() {
    override val code: String = "AVAX"
    override val name: String = "Avalanche"
    override val asset: String = "AVAX.AVAX"
    override val exampleAddress: String = "0x51a1449b3B6D635EddeC781cD47a99221712De97"
    override val paymentIntentParser: PaymentIntentParser = EthereumPaymentIntentParser("avax", "AVAX.AVAX")
    override val codeId: Int = R.string.cryptocurrency_avax_code
    override val nameId: Int = R.string.cryptocurrency_avax_network
}

open class MayaBnbSmartChainCryptoCurrency : MayaEthereumCryptoCurrency() {
    override val code: String = "BNB"
    override val name: String = "BNB"
    override val asset: String = "BSC.BNB"
    override val exampleAddress: String = "0x51a1449b3B6D635EddeC781cD47a99221712De97"
    override val paymentIntentParser: PaymentIntentParser = EthereumPaymentIntentParser("bnb", "BSC.BNB")
    override val codeId: Int = R.string.cryptocurrency_bnb_code
    override val nameId: Int = R.string.cryptocurrency_bnb_network
}

open class MayaBeraCryptoCurrency : MayaEthereumCryptoCurrency() {
    override val code: String = "BERA"
    override val name: String = "Berachain"
    override val asset: String = "BERA.BERA"
    override val exampleAddress: String = "0x51a1449b3B6D635EddeC781cD47a99221712De97"
    override val paymentIntentParser: PaymentIntentParser = EthereumPaymentIntentParser("bera", "BERA.BERA")
    override val codeId: Int = R.string.cryptocurrency_bera_code
    override val nameId: Int = R.string.cryptocurrency_bera_network
}

open class MayaMonadCryptoCurrency : MayaEthereumCryptoCurrency() {
    override val code: String = "MON"
    override val name: String = "Monad"
    override val asset: String = "MONAD.MON"
    override val exampleAddress: String = "0x51a1449b3B6D635EddeC781cD47a99221712De97"
    override val paymentIntentParser: PaymentIntentParser = EthereumPaymentIntentParser("monad", "MONAD.MON")
    override val codeId: Int = R.string.cryptocurrency_mon_code
    override val nameId: Int = R.string.cryptocurrency_mon_network
}

open class MayaPolygonCryptoCurrency : MayaEthereumCryptoCurrency() {
    override val code: String = "POL"
    override val name: String = "POL"
    override val asset: String = "POL.POL"
    override val exampleAddress: String = "0x51a1449b3B6D635EddeC781cD47a99221712De97"
    override val paymentIntentParser: PaymentIntentParser = EthereumPaymentIntentParser("pol", "POL.POL")
    override val codeId: Int = R.string.cryptocurrency_pol_code
    override val nameId: Int = R.string.cryptocurrency_pol_network
}

open class MayaXLayerCryptoCurrency : MayaEthereumCryptoCurrency() {
    override val code: String = "OKB"
    override val name: String = "OKB"
    override val asset: String = "XLAYER.OKB"
    override val exampleAddress: String = "0x51a1449b3B6D635EddeC781cD47a99221712De97"
    override val paymentIntentParser: PaymentIntentParser = EthereumPaymentIntentParser("okb", "XLAYER.OKB")
    override val codeId: Int = R.string.cryptocurrency_okb_code
    override val nameId: Int = R.string.cryptocurrency_okb_network
}

open class MayaGnosisXdaiCryptoCurrency : MayaEthereumCryptoCurrency() {
    override val code: String = "XDAI"
    override val name: String = "xDAI"
    override val asset: String = "GNO.XDAI"
    override val exampleAddress: String = "0x51a1449b3B6D635EddeC781cD47a99221712De97"
    override val paymentIntentParser: PaymentIntentParser = EthereumPaymentIntentParser("xdai", "GNO.XDAI")
    override val codeId: Int = R.string.cryptocurrency_xdai_code
    override val nameId: Int = R.string.cryptocurrency_xdai_network
}

// ---------------------------------------------------------------------------
// Bitcoin-family L1 native classes
// ---------------------------------------------------------------------------

open class MayaBitcoinCashCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "BCH"
    override val name: String = "Bitcoin Cash"
    override val asset: String = "BCH.BCH"
    override val exampleAddress: String = "qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a"
    override val paymentIntentParser: PaymentIntentParser = SimpleBase58PaymentIntentParser(
        "BCH",
        "bitcoincash",
        "BCH.BCH",
        "(bitcoincash:)?[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{42,55}|[13][1-9A-HJ-NP-Za-km-z]{25,34}"
    )
    override val addressParser: AddressParser = AddressParser(
        "(bitcoincash:)?[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{42,55}|[13][1-9A-HJ-NP-Za-km-z]{25,34}",
        null
    )
    override val codeId: Int = R.string.cryptocurrency_bch_code
    override val nameId: Int = R.string.cryptocurrency_bch_network
    override fun getPaymentRequestURI(address: String, amount: String): String =
        "bitcoincash:${address.removePrefix("bitcoincash:")}?amount=$amount"
}

open class MayaLitecoinCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "LTC"
    override val name: String = "Litecoin"
    override val asset: String = "LTC.LTC"
    override val exampleAddress: String = "ltc1qd5wm03t5kcdupjuyq5jffpuacnaqahvfsdu8smf8z0u0pqdqpatqsdrn8h"
    override val paymentIntentParser: PaymentIntentParser = SimpleBase58PaymentIntentParser(
        "LTC",
        "litecoin",
        "LTC.LTC",
        "(ltc1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{38,71})|([LM3][1-9A-HJ-NP-Za-km-z]{26,33})"
    )
    override val addressParser: AddressParser = AddressParser(
        "(ltc1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{38,71})|([LM3][1-9A-HJ-NP-Za-km-z]{26,33})",
        null
    )
    override val codeId: Int = R.string.cryptocurrency_ltc_code
    override val nameId: Int = R.string.cryptocurrency_ltc_network
    override fun getPaymentRequestURI(address: String, amount: String): String =
        "litecoin:$address?amount=$amount"
}

open class MayaDogecoinCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "DOGE"
    override val name: String = "Dogecoin"
    override val asset: String = "DOGE.DOGE"
    override val exampleAddress: String = "DH5yaieqoZN36fDVciNyRueRGvGLR3mr7L"
    override val paymentIntentParser: PaymentIntentParser = SimpleBase58PaymentIntentParser(
        "DOGE",
        "dogecoin",
        "DOGE.DOGE",
        "[DA9][1-9A-HJ-NP-Za-km-z]{32,33}"
    )
    override val addressParser: AddressParser = AddressParser(
        "[DA9][1-9A-HJ-NP-Za-km-z]{32,33}",
        null
    )
    override val codeId: Int = R.string.cryptocurrency_doge_code
    override val nameId: Int = R.string.cryptocurrency_doge_network
    override fun getPaymentRequestURI(address: String, amount: String): String =
        "dogecoin:$address?amount=$amount"
}

// ---------------------------------------------------------------------------
// Other L1 native chains (custom parsers)
// ---------------------------------------------------------------------------

open class MayaCardanoCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "ADA"
    override val name: String = "Cardano"
    override val asset: String = "ADA.ADA"
    override val exampleAddress: String =
        "addr1q9c8e2wjwj4uxsmrk2lqkkpqalwzvxgyx7uxjkfeg7xc3xa07c6qzwrcfh2x4f4z4uyez5lpd07v3jkh3ttn0xc2x7qspewtaa"
    override val paymentIntentParser: PaymentIntentParser = CardanoPaymentIntentParser()
    override val addressParser: AddressParser = CardanoAddressParser()
    override val codeId: Int = R.string.cryptocurrency_ada_code
    override val nameId: Int = R.string.cryptocurrency_ada_network
    // CIP-13 (web+cardano:) is rarely scannable — encode the bare address.
    override fun getPaymentRequestURI(address: String, amount: String): String = address
}

open class MayaSolanaCryptoCurrency : MayaCryptoCurrency {
    override val code: String = "SOL"
    override val name: String = "Solana"
    override val asset: String = "SOL.SOL"
    override val exampleAddress: String = "DYw8jCTfwHNRJhhmFcbXvVDTqWMEVFBX6ZKUmG5CNSKK"
    override val paymentIntentParser: PaymentIntentParser = SolanaPaymentIntentParser()
    override val addressParser: AddressParser = SolanaAddressParser()
    override val codeId: Int = R.string.cryptocurrency_sol_code
    override val nameId: Int = R.string.cryptocurrency_sol_network
    override fun getPaymentRequestURI(address: String, amount: String): String {
        return "solana:$address?amount=$amount"
    }
}

open class MayaNearCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "NEAR"
    override val name: String = "NEAR Protocol"
    override val asset: String = "NEAR.NEAR"
    override val exampleAddress: String = "alice.near"
    override val paymentIntentParser: PaymentIntentParser = NearPaymentIntentParser()
    override val addressParser: AddressParser = NearAddressParser()
    override val codeId: Int = R.string.cryptocurrency_near_code
    override val nameId: Int = R.string.cryptocurrency_near_network
    // NEAR is account-based with no standard payment URI — encode the bare address.
    override fun getPaymentRequestURI(address: String, amount: String): String = address
}

open class MayaTronCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "TRX"
    override val name: String = "TRON"
    override val asset: String = "TRON.TRX"
    override val exampleAddress: String = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
    override val paymentIntentParser: PaymentIntentParser = TronPaymentIntentParser()
    override val addressParser: AddressParser = TronAddressParser()
    override val codeId: Int = R.string.cryptocurrency_trx_code
    override val nameId: Int = R.string.cryptocurrency_trx_network
    // No payment-URI scheme honored by TRON wallets — encode the bare address.
    override fun getPaymentRequestURI(address: String, amount: String): String = address
}

open class MayaXrpCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "XRP"
    override val name: String = "XRP"
    override val asset: String = "XRP.XRP"
    override val exampleAddress: String = "rEb8TK3gBgk5auZkwc6sHnwrGVJH8DuaLh"
    override val paymentIntentParser: PaymentIntentParser = XrpPaymentIntentParser()
    override val addressParser: AddressParser = XrpAddressParser()
    override val codeId: Int = R.string.cryptocurrency_xrp_code
    override val nameId: Int = R.string.cryptocurrency_xrp_network
    // XRP deposits often need a destination tag too; no universally scannable URI — bare address.
    override fun getPaymentRequestURI(address: String, amount: String): String = address
}

open class MayaTonCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "TON"
    override val name: String = "Toncoin"
    override val asset: String = "TON.TON"
    override val exampleAddress: String = "EQDrjaLahLkMB-hMCmkzOyBuHJ139ZUYmPHu6RRBKnbdLIYI"
    override val paymentIntentParser: PaymentIntentParser = TonPaymentIntentParser()
    override val addressParser: AddressParser = TonAddressParser()
    override val codeId: Int = R.string.cryptocurrency_ton_code
    override val nameId: Int = R.string.cryptocurrency_ton_network
    // No payment-URI scheme honored by TON wallets — encode the bare address.
    override fun getPaymentRequestURI(address: String, amount: String): String = address
}

open class MayaSuiCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "SUI"
    override val name: String = "Sui"
    override val asset: String = "SUI.SUI"
    override val exampleAddress: String =
        "0xd1b72982e40348d069bb1ff701e634c117bb5f741f44dff91e472d3b01461e55"
    override val paymentIntentParser: PaymentIntentParser = SuiPaymentIntentParser()
    override val addressParser: AddressParser = SuiAddressParser()
    override val codeId: Int = R.string.cryptocurrency_sui_code
    override val nameId: Int = R.string.cryptocurrency_sui_network
    // No payment-URI scheme honored by Sui wallets — encode the bare address.
    override fun getPaymentRequestURI(address: String, amount: String): String = address
}

open class MayaStarknetCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "STRK"
    override val name: String = "Starknet"
    override val asset: String = "STRK.STRK"
    override val exampleAddress: String =
        "0x04718f5a0fc34cc1af16a1cdee98ffb20c31f5cd61d6ab07201858f4287c938d"
    override val paymentIntentParser: PaymentIntentParser = StarknetPaymentIntentParser()
    override val addressParser: AddressParser = StarknetAddressParser()
    override val codeId: Int = R.string.cryptocurrency_strk_code
    override val nameId: Int = R.string.cryptocurrency_strk_network
    // No payment-URI scheme honored by Starknet wallets — encode the bare address.
    override fun getPaymentRequestURI(address: String, amount: String): String = address
}

// ---------------------------------------------------------------------------
// Token wrapper classes for chains where many SwapKit identifiers exist
// (one wrapper per chain reuses the chain's address parser; the per-token
// payment intent parser carries the short asset alias used in Maya memos).
// ---------------------------------------------------------------------------

class MayaSolanaTokenCryptoCurrency(
    override val code: String,
    override val name: String,
    override val asset: String,
    shortAsset: String,
    override val codeId: Int,
    override val nameId: Int
) : MayaSolanaCryptoCurrency() {
    override val paymentIntentParser: PaymentIntentParser =
        SolanaPaymentIntentParser(code, asset, shortAsset)
    override val addressParser: AddressParser = SolanaAddressParser()
    val tokenContract = asset.substring(4)
    override fun getPaymentRequestURI(address: String, amount: String): String {
        return "solana:$address?amount=$amount&spl-token=$tokenContract"
    }
}

class MayaNearTokenCryptoCurrency(
    override val code: String,
    override val name: String,
    override val asset: String,
    shortAsset: String,
    override val codeId: Int,
    override val nameId: Int
) : MayaNearCryptoCurrency() {
    override val paymentIntentParser: PaymentIntentParser =
        NearPaymentIntentParser(code, asset, shortAsset)
    override val addressParser: AddressParser = NearAddressParser()
}

class MayaTonTokenCryptoCurrency(
    override val code: String,
    override val name: String,
    override val asset: String,
    shortAsset: String,
    override val codeId: Int,
    override val nameId: Int
) : MayaTonCryptoCurrency() {
    override val paymentIntentParser: PaymentIntentParser =
        TonPaymentIntentParser(code, asset, shortAsset)
    override val addressParser: AddressParser = TonAddressParser()
}

class MayaTronTokenCryptoCurrency(
    override val code: String,
    override val name: String,
    override val asset: String,
    shortAsset: String,
    override val codeId: Int,
    override val nameId: Int
) : MayaTronCryptoCurrency() {
    override val paymentIntentParser: PaymentIntentParser =
        TronPaymentIntentParser(code, asset, shortAsset)
    override val addressParser: AddressParser = TronAddressParser()
}

class MayaSuiTokenCryptoCurrency(
    override val code: String,
    override val name: String,
    override val asset: String,
    shortAsset: String,
    override val codeId: Int,
    override val nameId: Int
) : MayaSuiCryptoCurrency() {
    override val paymentIntentParser: PaymentIntentParser =
        SuiPaymentIntentParser(code, asset, shortAsset)
    override val addressParser: AddressParser = SuiAddressParser()
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
            MayaArbitrumCryptoCurrency(),
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
            MayaEthereumTokenCryptoCurrency(
                "LLD",
                "LLD",
                "ETH.LLD-0X054C9D4C6F4EA4E14391ADDD1812106C97D05690",
                EthereumPaymentIntentParser("lld", "ETH.LLD-05690"),
                R.string.cryptocurrency_lld_code,
                R.string.cryptocurrency_lld_ethereum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "MOCA",
                "Mocaverse",
                "ETH.MOCA-0X53312F85BBA24C8CB99CFFC13BF82420157230D3",
                EthereumPaymentIntentParser("moca", "ETH.MOCA-230D3"),
                R.string.cryptocurrency_moca_code,
                R.string.cryptocurrency_moca_ethereum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "YUM",
                "YUM",
                "ARB.YUM-0X9F41B34F42058A7B74672055A5FAE22C4B113FD1",
                EthereumPaymentIntentParser("yum", "ARB.YUM-13FD1"),
                R.string.cryptocurrency_yum_code,
                R.string.cryptocurrency_yum_arbitrum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDT",
                "Tether",
                "ARB.USDT-0XFD086BC7CD5C481DCC9C85EBE478A1C0B69FCBB9",
                EthereumPaymentIntentParser("usdt", "ARB.USDT-FCBB9"),
                R.string.cryptocurrency_tether_code,
                R.string.cryptocurrency_tether_arbitrum_network
            ),

            // ----- ETH chain new tokens -----
            MayaEthereumTokenCryptoCurrency(
                "ADI",
                "ADI",
                "ETH.ADI-0X8B1484D57ABBE239BB280661377363B03C89CAEA",
                EthereumPaymentIntentParser("adi", "ETH.ADI-9CAEA"),
                R.string.cryptocurrency_adi_code,
                R.string.cryptocurrency_adi_ethereum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "AURORA",
                "Aurora",
                "ETH.AURORA-0XAAAAAA20D9E0E2461697782EF11675F668207961",
                EthereumPaymentIntentParser("aurora", "ETH.AURORA-07961"),
                R.string.cryptocurrency_aurora_code,
                R.string.cryptocurrency_aurora_ethereum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "CBBTC",
                "Coinbase Wrapped BTC",
                "ETH.CBBTC-0XCBB7C0000AB88B473B1F5AFD9EF808440EED33BF",
                EthereumPaymentIntentParser("cbbtc", "ETH.CBBTC-D33BF"),
                R.string.cryptocurrency_cbbtc_code,
                R.string.cryptocurrency_cbbtc_ethereum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "DAI",
                "Dai",
                "ETH.DAI-0X6B175474E89094C44DA98B954EEDEAC495271D0F",
                EthereumPaymentIntentParser("dai", "ETH.DAI-71D0F"),
                R.string.cryptocurrency_dai_code,
                R.string.cryptocurrency_dai_ethereum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "MOG",
                "Mog Coin",
                "ETH.MOG-0XAAEE1A9723AADB7AFA2810263653A34BA2C21C7A",
                EthereumPaymentIntentParser("mog", "ETH.MOG-21C7A"),
                R.string.cryptocurrency_mog_code,
                R.string.cryptocurrency_mog_ethereum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "SAFE",
                "Safe",
                "ETH.SAFE-0X5AFE3855358E112B5647B952709E6165E1C1EEEE",
                EthereumPaymentIntentParser("safe", "ETH.SAFE-1EEEE"),
                R.string.cryptocurrency_safe_code,
                R.string.cryptocurrency_safe_ethereum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "SHIB",
                "Shiba Inu",
                "ETH.SHIB-0X95AD61B0A150D79219DCF64E1E6CC01F0B64C4CE",
                EthereumPaymentIntentParser("shib", "ETH.SHIB-4C4CE"),
                R.string.cryptocurrency_shib_code,
                R.string.cryptocurrency_shib_ethereum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "TURBO",
                "Turbo",
                "ETH.TURBO-0XA35923162C49CF95E6BF26623385EB431AD920D3",
                EthereumPaymentIntentParser("turbo", "ETH.TURBO-920D3"),
                R.string.cryptocurrency_turbo_code,
                R.string.cryptocurrency_turbo_ethereum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USD1",
                "USD1",
                "ETH.USD1-0X8D0D000EE44948FC98C9B98A4FA4921476F08B0D",
                EthereumPaymentIntentParser("usd1", "ETH.USD1-08B0D"),
                R.string.cryptocurrency_usd1_code,
                R.string.cryptocurrency_usd1_ethereum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDF",
                "Falcon USD",
                "ETH.USDF-0XFA2B947EEC368F42195F24F36D2AF29F7C24CEC2",
                EthereumPaymentIntentParser("usdf", "ETH.USDF-4CEC2"),
                R.string.cryptocurrency_usdf_code,
                R.string.cryptocurrency_usdf_ethereum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "WBTC",
                "Wrapped Bitcoin",
                "ETH.WBTC-0X2260FAC5E5542A773AA44FBCFEDF7C193BC2C599",
                EthereumPaymentIntentParser("wbtc", "ETH.WBTC-2C599"),
                R.string.cryptocurrency_wbtc_code,
                R.string.cryptocurrency_wbtc_ethereum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "WETH",
                "WETH",
                "ETH.WETH-0XC02AAA39B223FE8D0A0E5C4F27EAD9083C756CC2",
                EthereumPaymentIntentParser("weth", "ETH.WETH-56CC2"),
                R.string.cryptocurrency_weth_code,
                R.string.cryptocurrency_weth_ethereum_network
            ),

            // ----- ARB chain new tokens -----
            MayaEthereumTokenCryptoCurrency(
                "USDT0",
                "USDT0",
                "ARB.USDT0-0XFD086BC7CD5C481DCC9C85EBE478A1C0B69FCBB9",
                EthereumPaymentIntentParser("usdt0", "ARB.USDT0-FCBB9"),
                R.string.cryptocurrency_usdt0_code,
                R.string.cryptocurrency_usdt0_arbitrum_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "WETH",
                "Arbitrum Bridged WETH",
                "ARB.WETH-0X82AF49447D8A07E3BD95BD0D56F35241523FBAB1",
                EthereumPaymentIntentParser("weth", "ARB.WETH-FBAB1"),
                R.string.cryptocurrency_weth_code,
                R.string.cryptocurrency_weth_arbitrum_network
            ),

            MayaKujiraCryptoCurrency(),
            MayaKujiraTokenCryptoCurrency(
                "USK",
                "USK",
                "KUJI.USK",
                R.string.cryptocurrency_usk_code,
                R.string.cryptocurrency_usk_network
            ),

            MayaRuneCryptoCurrency(),
            MayaZcashCryptoCurrency(),
            MayaRadixCryptoCurrency(),
            MayaMayaTokenCryptoCurrency(),
            MayaCacaoCryptoCurrency(),

            // ----- New L1 native coins (BTC family) -----
            MayaBitcoinCashCryptoCurrency(),
            MayaLitecoinCryptoCurrency(),
            MayaDogecoinCryptoCurrency(),

            // ----- New L1 native coins (other) -----
            MayaCardanoCryptoCurrency(),
            MayaSolanaCryptoCurrency(),
            MayaNearCryptoCurrency(),
            MayaTronCryptoCurrency(),
            MayaXrpCryptoCurrency(),
            MayaTonCryptoCurrency(),
            MayaSuiCryptoCurrency(),
            MayaStarknetCryptoCurrency(),

            // ----- EVM-style native coins on L2s / sidechains -----
            MayaBaseCryptoCurrency(),
            MayaOptimismCryptoCurrency(),
            MayaAvalancheCryptoCurrency(),
            MayaBnbSmartChainCryptoCurrency(),
            MayaBeraCryptoCurrency(),
            MayaMonadCryptoCurrency(),
            MayaPolygonCryptoCurrency(),
            MayaXLayerCryptoCurrency(),
            MayaGnosisXdaiCryptoCurrency(),

            // ----- BASE chain tokens -----
            MayaEthereumTokenCryptoCurrency(
                "CBBTC",
                "Coinbase Wrapped BTC",
                "BASE.CBBTC-0XCBB7C0000AB88B473B1F5AFD9EF808440EED33BF",
                EthereumPaymentIntentParser("cbbtc", "BASE.CBBTC-D33BF"),
                R.string.cryptocurrency_cbbtc_code,
                R.string.cryptocurrency_cbbtc_base_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "CFI",
                "ConsumerFi Protocol",
                "BASE.CFI-0X0382E3FEE4A420BD446367D468A6F00225853420",
                EthereumPaymentIntentParser("cfi", "BASE.CFI-53420"),
                R.string.cryptocurrency_cfi_code,
                R.string.cryptocurrency_cfi_base_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDC",
                "USD Coin",
                "BASE.USDC-0X833589FCD6EDB6E08F4C7C32D4F71B54BDA02913",
                EthereumPaymentIntentParser("usdc", "BASE.USDC-02913"),
                R.string.cryptocurrency_usdcoin_code,
                R.string.cryptocurrency_usdcoin_base_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "WETH",
                "L2 Standard Bridged WETH",
                "BASE.WETH-0X4200000000000000000000000000000000000006",
                EthereumPaymentIntentParser("weth", "BASE.WETH-00006"),
                R.string.cryptocurrency_weth_code,
                R.string.cryptocurrency_weth_base_network
            ),

            // ----- OP chain tokens -----
            MayaEthereumTokenCryptoCurrency(
                "OP",
                "OP",
                "OP.OP-0X4200000000000000000000000000000000000042",
                EthereumPaymentIntentParser("op", "OP.OP-00042"),
                R.string.cryptocurrency_op_code,
                R.string.cryptocurrency_op_optimism_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDC",
                "USD Coin",
                "OP.USDC-0X0B2C639C533813F4AA9D7837CAF62653D097FF85",
                EthereumPaymentIntentParser("usdc", "OP.USDC-7FF85"),
                R.string.cryptocurrency_usdcoin_code,
                R.string.cryptocurrency_usdcoin_optimism_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDT",
                "Tether",
                "OP.USDT-0X94B008AA00579C1307B0EF2C499AD98A8CE58E58",
                EthereumPaymentIntentParser("usdt", "OP.USDT-58E58"),
                R.string.cryptocurrency_tether_code,
                R.string.cryptocurrency_tether_optimism_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "WETH",
                "WETH",
                "OP.WETH-0X4200000000000000000000000000000000000006",
                EthereumPaymentIntentParser("weth", "OP.WETH-00006"),
                R.string.cryptocurrency_weth_code,
                R.string.cryptocurrency_weth_optimism_network
            ),

            // ----- AVAX chain tokens -----
            MayaEthereumTokenCryptoCurrency(
                "USDC",
                "USD Coin",
                "AVAX.USDC-0XB97EF9EF8734C71904D8002F8B6BC66DD9C48A6E",
                EthereumPaymentIntentParser("usdc", "AVAX.USDC-48A6E"),
                R.string.cryptocurrency_usdcoin_code,
                R.string.cryptocurrency_usdcoin_avalanche_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDT",
                "Tether",
                "AVAX.USDT-0X9702230A8EA53601F5CD2DC00FDBC13D4DF4A8C7",
                EthereumPaymentIntentParser("usdt", "AVAX.USDT-4A8C7"),
                R.string.cryptocurrency_tether_code,
                R.string.cryptocurrency_tether_avalanche_network
            ),

            // ----- BSC chain tokens -----
            MayaEthereumTokenCryptoCurrency(
                "ASTER",
                "Aster",
                "BSC.ASTER-0X000AE314E2A2172A039B26378814C252734F556A",
                EthereumPaymentIntentParser("aster", "BSC.ASTER-F556A"),
                R.string.cryptocurrency_aster_code,
                R.string.cryptocurrency_aster_bsc_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "NEAR",
                "Binance-Peg NEAR Protocol",
                "BSC.NEAR-0X1FA4A73A3F0133F0025378AF00236F3ABDEE5D63",
                EthereumPaymentIntentParser("near", "BSC.NEAR-E5D63"),
                R.string.cryptocurrency_near_code,
                R.string.cryptocurrency_near_bsc_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "RHEA",
                "RHEA",
                "BSC.RHEA-0X4C067DE26475E1CEFEE8B8D1F6E2266B33A2372E",
                EthereumPaymentIntentParser("rhea", "BSC.RHEA-2372E"),
                R.string.cryptocurrency_rhea_code,
                R.string.cryptocurrency_rhea_bsc_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDC",
                "Binance Bridged USDC",
                "BSC.USDC-0X8AC76A51CC950D9822D68B83FE1AD97B32CD580D",
                EthereumPaymentIntentParser("usdc", "BSC.USDC-D580D"),
                R.string.cryptocurrency_usdcoin_code,
                R.string.cryptocurrency_usdcoin_bsc_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDT",
                "USDT",
                "BSC.USDT-0X55D398326F99059FF775485246999027B3197955",
                EthereumPaymentIntentParser("usdt", "BSC.USDT-97955"),
                R.string.cryptocurrency_tether_code,
                R.string.cryptocurrency_tether_bsc_network
            ),

            // ----- BERA chain tokens -----
            MayaEthereumTokenCryptoCurrency(
                "USDT0",
                "USDT0",
                "BERA.USDT0-0X779DED0C9E1022225F8E0630B35A9B54BE713736",
                EthereumPaymentIntentParser("usdt0", "BERA.USDT0-13736"),
                R.string.cryptocurrency_usdt0_code,
                R.string.cryptocurrency_usdt0_bera_network
            ),

            // ----- POL chain tokens -----
            MayaEthereumTokenCryptoCurrency(
                "USDC",
                "USD Coin",
                "POL.USDC-0X3C499C542CEF5E3811E1192CE70D8CC03D5C3359",
                EthereumPaymentIntentParser("usdc", "POL.USDC-C3359"),
                R.string.cryptocurrency_usdcoin_code,
                R.string.cryptocurrency_usdcoin_polygon_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDT",
                "Tether",
                "POL.USDT-0XC2132D05D31C914A87C6611C10748AEB04B58E8F",
                EthereumPaymentIntentParser("usdt", "POL.USDT-58E8F"),
                R.string.cryptocurrency_tether_code,
                R.string.cryptocurrency_tether_polygon_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "WETH",
                "Polygon PoS Bridged WETH",
                "POL.WETH-0X7CEB23FD6BC0ADD59E62AC25578270CFF1B9F619",
                EthereumPaymentIntentParser("weth", "POL.WETH-9F619"),
                R.string.cryptocurrency_weth_code,
                R.string.cryptocurrency_weth_polygon_network
            ),

            // ----- MONAD chain tokens -----
            MayaEthereumTokenCryptoCurrency(
                "USDC",
                "USD Coin",
                "MONAD.USDC-0X754704BC059F8C67012FED69BC8A327A5AAFB603",
                EthereumPaymentIntentParser("usdc", "MONAD.USDC-FB603"),
                R.string.cryptocurrency_usdcoin_code,
                R.string.cryptocurrency_usdcoin_monad_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDT0",
                "USDT0",
                "MONAD.USDT0-0XE7CD86E13AC4309349F30B3435A9D337750FC82D",
                EthereumPaymentIntentParser("usdt0", "MONAD.USDT0-FC82D"),
                R.string.cryptocurrency_usdt0_code,
                R.string.cryptocurrency_usdt0_monad_network
            ),

            // ----- XLAYER chain tokens -----
            MayaEthereumTokenCryptoCurrency(
                "USDC",
                "USD Coin",
                "XLAYER.USDC-0X74B7F16337B8972027F6196A17A631AC6DE26D22",
                EthereumPaymentIntentParser("usdc", "XLAYER.USDC-26D22"),
                R.string.cryptocurrency_usdcoin_code,
                R.string.cryptocurrency_usdcoin_xlayer_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDT0",
                "USDT0",
                "XLAYER.USDT0-0X779DED0C9E1022225F8E0630B35A9B54BE713736",
                EthereumPaymentIntentParser("usdt0", "XLAYER.USDT0-13736"),
                R.string.cryptocurrency_usdt0_code,
                R.string.cryptocurrency_usdt0_xlayer_network
            ),

            // ----- GNO (Gnosis chain) tokens -----
            MayaEthereumTokenCryptoCurrency(
                "COW",
                "COW",
                "GNO.COW-0X177127622C4A00F3D409B75571E12CB3C8973D3C",
                EthereumPaymentIntentParser("cow", "GNO.COW-73D3C"),
                R.string.cryptocurrency_cow_code,
                R.string.cryptocurrency_cow_gnosis_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "EURE",
                "EURe",
                "GNO.EURE-0X420CA0F9B9B604CE0FD9C18EF134C705E5FA3430",
                EthereumPaymentIntentParser("eure", "GNO.EURE-A3430"),
                R.string.cryptocurrency_eure_code,
                R.string.cryptocurrency_eure_gnosis_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "GNO",
                "GNO",
                "GNO.GNO-0X9C58BACC331C9AA871AFD802DB6379A98E80CEDB",
                EthereumPaymentIntentParser("gno", "GNO.GNO-0CEDB"),
                R.string.cryptocurrency_gno_code,
                R.string.cryptocurrency_gno_gnosis_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "SAFE",
                "SAFE",
                "GNO.SAFE-0X4D18815D14FE5C3304E87B3FA18318BAA5C23820",
                EthereumPaymentIntentParser("safe", "GNO.SAFE-23820"),
                R.string.cryptocurrency_safe_code,
                R.string.cryptocurrency_safe_gnosis_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDC",
                "USDC",
                "GNO.USDC-0X2A22F9C3B484C3629090FEED35F17FF8F88F76F0",
                EthereumPaymentIntentParser("usdc", "GNO.USDC-F76F0"),
                R.string.cryptocurrency_usdcoin_code,
                R.string.cryptocurrency_usdcoin_gnosis_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "USDT",
                "USDT",
                "GNO.USDT-0X4ECABA5870353805A9F068101A40E0F32ED605C6",
                EthereumPaymentIntentParser("usdt", "GNO.USDT-605C6"),
                R.string.cryptocurrency_tether_code,
                R.string.cryptocurrency_tether_gnosis_network
            ),
            MayaEthereumTokenCryptoCurrency(
                "WETH",
                "WETH",
                "GNO.WETH-0X6A023CCD1FF6F2045C3309768EAD9E68F978F6E1",
                EthereumPaymentIntentParser("weth", "GNO.WETH-F6E1F"),
                R.string.cryptocurrency_weth_code,
                R.string.cryptocurrency_weth_gnosis_network
            ),

            // ----- SOL chain tokens -----
            MayaSolanaTokenCryptoCurrency(
                "\$WIF",
                "dogwifhat",
                "SOL.\$WIF-EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm",
                "SOL.WIF-zcjm",
                R.string.cryptocurrency_wif_code,
                R.string.cryptocurrency_wif_solana_network
            ),
            MayaSolanaTokenCryptoCurrency(
                "PENGU",
                "Pudgy Penguins",
                "SOL.PENGU-2zMMhcVQEXDtdE6vsFS7S7D5oUodfJHE8vd1gnBouauv",
                "SOL.PENGU-uauv",
                R.string.cryptocurrency_pengu_code,
                R.string.cryptocurrency_pengu_solana_network
            ),
            MayaSolanaTokenCryptoCurrency(
                "SPX",
                "SPX6900",
                "SOL.SPX-J3NKxxXZcnNiMjKw9hYb2K4LUxgwB6t1FtPtQVsv3KFr",
                "SOL.SPX-3KFr",
                R.string.cryptocurrency_spx_code,
                R.string.cryptocurrency_spx_solana_network
            ),
            MayaSolanaTokenCryptoCurrency(
                "TRUMP",
                "Official Trump",
                "SOL.TRUMP-6p6xgHyF7AeE6TZkSmFsko444wqoP15icUSqi2jfGiPN",
                "SOL.TRUMP-GiPN",
                R.string.cryptocurrency_trump_code,
                R.string.cryptocurrency_trump_solana_network
            ),
            MayaSolanaTokenCryptoCurrency(
                "TURBO",
                "Turbo",
                "SOL.TURBO-2Dyzu65QA9zdX1UeE7Gx71k7fiwyUK6sZdrvJ7auq5wm",
                "SOL.TURBO-q5wm",
                R.string.cryptocurrency_turbo_code,
                R.string.cryptocurrency_turbo_solana_network
            ),
            MayaSolanaTokenCryptoCurrency(
                "USDC",
                "USDC",
                "SOL.USDC-EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                "SOL.USDC-Dt1v",
                R.string.cryptocurrency_usdcoin_code,
                R.string.cryptocurrency_usdcoin_solana_network
            ),
            MayaSolanaTokenCryptoCurrency(
                "USDT",
                "Tether",
                "SOL.USDT-Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
                "SOL.USDT-wNYB",
                R.string.cryptocurrency_tether_code,
                R.string.cryptocurrency_tether_solana_network
            ),
            MayaSolanaTokenCryptoCurrency(
                "ZEC",
                "OmniBridge Bridged Zcash",
                "SOL.ZEC-A7bdiYdS5GjqGFtxf17ppRHtDKPkkRqbKtR27dxvQXaS",
                "SOL.ZEC-QXaS",
                R.string.cryptocurrency_zec_code,
                R.string.cryptocurrency_zec_solana_network
            ),
            MayaSolanaTokenCryptoCurrency(
                "xBTC",
                "OKX Wrapped BTC",
                "SOL.xBTC-CtzPWv73Sn1dMGVU3ZtLv9yWSyUAanBni19YWDaznnkn",
                "SOL.xBTC-nnkn",
                R.string.cryptocurrency_xbtc_code,
                R.string.cryptocurrency_xbtc_solana_network
            ),

            // ----- NEAR chain tokens -----
            MayaNearTokenCryptoCurrency(
                "AURORA",
                "AURORA",
                "NEAR.AURORA-aaaaaa20d9e0e2461697782ef11675f668207961.factory.bridge.near",
                "NEAR.AURORA",
                R.string.cryptocurrency_aurora_code,
                R.string.cryptocurrency_aurora_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "BTC",
                "BTC",
                "NEAR.BTC-nbtc.bridge.near",
                "NEAR.BTC",
                R.string.cryptocurrency_bitcoin_code,
                R.string.cryptocurrency_bitcoin_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "CFI",
                "CFI",
                "NEAR.CFI-cfi.consumer-fi.near",
                "NEAR.CFI",
                R.string.cryptocurrency_cfi_code,
                R.string.cryptocurrency_cfi_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "ETH",
                "ETH",
                "NEAR.ETH-eth.bridge.near",
                "NEAR.ETH",
                R.string.cryptocurrency_ethereum_code,
                R.string.cryptocurrency_ethereum_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "FRAX",
                "FRAX",
                "NEAR.FRAX-853d955acef822db058eb8505911ed77f175b99e.factory.bridge.near",
                "NEAR.FRAX",
                R.string.cryptocurrency_frax_code,
                R.string.cryptocurrency_frax_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "ITLX",
                "Intellex",
                "NEAR.ITLX-itlx.intellex_xyz.near",
                "NEAR.ITLX",
                R.string.cryptocurrency_itlx_code,
                R.string.cryptocurrency_itlx_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "JAMBO",
                "JAMBO",
                "NEAR.JAMBO-jambo-1679.meme-cooking.near",
                "NEAR.JAMBO",
                R.string.cryptocurrency_jambo_code,
                R.string.cryptocurrency_jambo_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "NOEAR",
                "NOEAR",
                "NEAR.NOEAR-noear-324.meme-cooking.near",
                "NEAR.NOEAR",
                R.string.cryptocurrency_noear_code,
                R.string.cryptocurrency_noear_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "NPRO",
                "NPRO",
                "NEAR.NPRO-npro.nearmobile.near",
                "NEAR.NPRO",
                R.string.cryptocurrency_npro_code,
                R.string.cryptocurrency_npro_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "NearKat",
                "NearKat",
                "NEAR.NearKat-kat.token0.near",
                "NEAR.NearKat",
                R.string.cryptocurrency_nearkat_code,
                R.string.cryptocurrency_nearkat_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "PUBLIC",
                "PublicAI",
                "NEAR.PUBLIC-token.publicailab.near",
                "NEAR.PUBLIC",
                R.string.cryptocurrency_public_code,
                R.string.cryptocurrency_public_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "PURGE",
                "PURGE",
                "NEAR.PURGE-purge-558.meme-cooking.near",
                "NEAR.PURGE",
                R.string.cryptocurrency_purge_code,
                R.string.cryptocurrency_purge_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "RHEA",
                "RHEA",
                "NEAR.RHEA-token.rhealab.near",
                "NEAR.RHEA",
                R.string.cryptocurrency_rhea_code,
                R.string.cryptocurrency_rhea_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "SHITZU",
                "Shitzu",
                "NEAR.SHITZU-token.0xshitzu.near",
                "NEAR.SHITZU",
                R.string.cryptocurrency_shitzu_code,
                R.string.cryptocurrency_shitzu_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "STJACK",
                "STJACK",
                "NEAR.STJACK-stjack.tkn.primitives.near",
                "NEAR.STJACK",
                R.string.cryptocurrency_stjack_code,
                R.string.cryptocurrency_stjack_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "SWEAT",
                "SWEAT",
                "NEAR.SWEAT-token.sweat",
                "NEAR.SWEAT",
                R.string.cryptocurrency_sweat_code,
                R.string.cryptocurrency_sweat_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "TURBO",
                "TURBO",
                "NEAR.TURBO-a35923162c49cf95e6bf26623385eb431ad920d3.factory.bridge.near",
                "NEAR.TURBO",
                R.string.cryptocurrency_turbo_code,
                R.string.cryptocurrency_turbo_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "USDC",
                "USDC",
                "NEAR.USDC-17208628f84f5d6ad33f0da3bbbeb27ffcb398eac501a31bd6ad2011e36133a1",
                "NEAR.USDC",
                R.string.cryptocurrency_usdcoin_code,
                R.string.cryptocurrency_usdcoin_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "USDT",
                "USDT",
                "NEAR.USDT-usdt.tether-token.near",
                "NEAR.USDT",
                R.string.cryptocurrency_tether_code,
                R.string.cryptocurrency_tether_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "ZEC",
                "ZEC",
                "NEAR.ZEC-zec.omft.near",
                "NEAR.ZEC",
                R.string.cryptocurrency_zec_code,
                R.string.cryptocurrency_zec_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "mpDAO",
                "Meta Pool DAO",
                "NEAR.mpDAO-mpdao-token.near",
                "NEAR.mpDAO",
                R.string.cryptocurrency_mpdao_code,
                R.string.cryptocurrency_mpdao_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "nrUsdt",
                "nrUsdt",
                "NEAR.nrUsdt-lsd-usdt.rhealab.near",
                "NEAR.nrUsdt",
                R.string.cryptocurrency_nrusdt_code,
                R.string.cryptocurrency_nrusdt_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "stNEAR",
                "Staked NEAR",
                "NEAR.stNEAR-meta-pool.near",
                "NEAR.stNEAR",
                R.string.cryptocurrency_stnear_code,
                R.string.cryptocurrency_stnear_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "wBTC",
                "wBTC",
                "NEAR.wBTC-2260fac5e5542a773aa44fbcfedf7c193bc2c599.factory.bridge.near",
                "NEAR.wBTC",
                R.string.cryptocurrency_wbtc_code,
                R.string.cryptocurrency_wbtc_near_network
            ),
            MayaNearTokenCryptoCurrency(
                "wNEAR",
                "Wrapped Near",
                "NEAR.wNEAR-wrap.near",
                "NEAR.wNEAR",
                R.string.cryptocurrency_wnear_code,
                R.string.cryptocurrency_wnear_near_network
            ),

            // ----- TON chain tokens -----
            MayaTonTokenCryptoCurrency(
                "USDT",
                "USDT",
                "TON.USDT-EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs",
                "TON.USDT",
                R.string.cryptocurrency_tether_code,
                R.string.cryptocurrency_tether_ton_network
            ),

            // ----- TRON chain tokens -----
            MayaTronTokenCryptoCurrency(
                "USDT",
                "USDT",
                "TRON.USDT-TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
                "TRON.USDT",
                R.string.cryptocurrency_tether_code,
                R.string.cryptocurrency_tether_tron_network
            ),

            // ----- SUI chain tokens -----
            MayaSuiTokenCryptoCurrency(
                "USDC",
                "USDC",
                "SUI.USDC-0xdba34672e30cb065b1f93e3ab55318768fd6fef66c15942c9f7cb846e2f900e7::usdc::USDC",
                "SUI.USDC",
                R.string.cryptocurrency_usdcoin_code,
                R.string.cryptocurrency_usdcoin_sui_network
            )
        )
        currencyMap = currencyList.associateBy({ it.asset }, { it })
    }
    operator fun get(asset: String) = currencyMap[asset]
    val all: Collection<MayaCryptoCurrency>
        get() = currencyMap.values
    fun getPaymentProcessors(): PaymentParsers {
        val paymentProcessors = PaymentParsers()
        val registeredCodes = mutableSetOf<String>()
        for (currency in all) {
            if (registeredCodes.add(currency.code.lowercase())) {
                paymentProcessors.add(
                    currency.name,
                    currency.code,
                    currency.paymentIntentParser,
                    currency.addressParser
                )
            }
        }
        return paymentProcessors
    }

    fun getPaymentProcessorForAsset(asset: String): PaymentParsers? {
        val currency = currencyMap[asset] ?: return null
        return PaymentParsers().apply {
            add(currency.name, currency.code, currency.paymentIntentParser, currency.addressParser)
        }
    }
}
