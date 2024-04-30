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

import java.math.BigDecimal
import java.math.RoundingMode

interface MayaCryptoCurrency {
    val code: String
    val name: String
    val asset: String
    val exampleAddress: String
    fun getPoolDepth(depthInSmallUnits: BigDecimal): BigDecimal
    fun getFee(feeInSmallUnits: BigDecimal): BigDecimal
}

open class MayaBitcoinCryptoCurrency : MayaCryptoCurrency {
    override val code: String = "BTC"
    override val name: String = "Bitcoin"
    override val asset: String = "BTC.BTC"
    override val exampleAddress: String = "bc1qxhgnnp745zryn2ud8hm6k3mygkkpkm35020js0"
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
}

class MayaKujiraTokenCryptoCurrency(
    override val code: String,
    override val name: String,
    override val asset: String
) : MayaKujiraCryptoCurrency()

class MayaEthereumTokenCryptoCurrency(
    override val code: String,
    override val name: String,
    override val asset: String
) : MayaEthereumCryptoCurrency()

open class MayaRuneCryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "RUNE"
    override val name: String = "Rune"
    override val asset: String = "THOR.RUNE"
    override val exampleAddress: String = "thor166n4w5039meulfa3p6ydg60ve6ueac7tlt0jws"
}

object MayaCurrencyList {
    private val currencyMap: Map<String, MayaCryptoCurrency>
    init {
        val currencyList = listOf(
            MayaBitcoinCryptoCurrency(),
            MayaEthereumCryptoCurrency(),
            MayaEthereumTokenCryptoCurrency("USDC", "USD Coin", "ETH.USDC-0XA0B86991C6218B36C1D19D4A2E9EB0CE3606EB48"),
            MayaEthereumTokenCryptoCurrency("USDT", "Tether", "ETH.USDT-0XDAC17F958D2EE523A2206206994597C13D831EC7"),
            MayaEthereumTokenCryptoCurrency("WSTETH", "Wrapped Stable ETH", "ETH.WSTETH-0X7F39C581F595B53C5CB19BD0B3F8DA6C935E2CA0"),
            MayaKujiraCryptoCurrency(),
            MayaKujiraTokenCryptoCurrency("USK", "USK", "KUJI.USK"),
            MayaRuneCryptoCurrency()
        )
        currencyMap = currencyList.associateBy({ it.asset }, { it })
    }
    operator fun get(asset: String) = currencyMap[asset]
}
