/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.integration.coinbase_integration.ui.convert_currency

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.VALUE_ZERO
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.ConvertViewBinding
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.BaseServiceWallet
import java.math.BigDecimal
import java.math.RoundingMode

class TransferView(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs) {

    private val binding = ConvertViewBinding.inflate(LayoutInflater.from(context), this)
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(6).optionalDecimals()

    private var onTransferDirectionBtnClicked: (() -> Unit)? = null

    var inputInDash: Coin = Coin.ZERO
    var fiatAmount: Fiat = Fiat.valueOf("USD", 0)
        private set

    var exchangeRate: ExchangeRate? = null
    var balanceOnCoinbase: BaseServiceWallet? = null
    var walletToCoinbase: Boolean = false
        set(value) {
            if (field != value){
                field = value
                updateSymbols()
                updateAmount()
            }
        }

    private fun updateSymbols() {
        if (walletToCoinbase){
            binding.convertFromBtn.setConvertItemTitle(R.string.dash_wallet_name)
            binding.convertToBtn.setConvertItemTitle(R.string.coinbase)
            ContextCompat.getDrawable(context, R.drawable.ic_dash_blue_filled)
                ?.let { binding.convertFromBtn.setConvertItemIcon(it) }
            ContextCompat.getDrawable(context, R.drawable.ic_coinbase)
                ?.let { binding.convertToBtn.setConvertItemIcon(it) }
        } else {
            binding.convertFromBtn.setConvertItemTitle(R.string.coinbase)
            binding.convertToBtn.setConvertItemTitle(R.string.dash_wallet_name)
            ContextCompat.getDrawable(context, R.drawable.ic_coinbase)
                ?.let { binding.convertFromBtn.setConvertItemIcon(it) }
            ContextCompat.getDrawable(context, R.drawable.ic_dash_blue_filled)
                ?.let { binding.convertToBtn.setConvertItemIcon(it) }
        }
    }

    init {
        initUI()
        updateSymbols()
        updateAmount()
        binding.swapBtn.setOnClickListener {
            walletToCoinbase = !walletToCoinbase
            onTransferDirectionBtnClicked?.invoke()
        }
    }

    fun setOnTransferDirectionBtnClicked(listener: () -> Unit){
        onTransferDirectionBtnClicked = listener
    }

    private fun initUI(){
        binding.convertFromBtn.setCryptoItemGroupVisibility(true)
        binding.convertToBtn.setCryptoItemGroupVisibility(true)
        binding.convertFromDashBalance.isVisible = true
        binding.convertFromDashFiatAmount.isVisible = true
        binding.convertFromBtn.hideComponents()
        binding.convertToBtn.hideComponents()
        binding.convertFromBtn.setIconConstraint()
        binding.convertFromBtn.setTitleConstraint()
        binding.convertToBtn.setIconConstraint()
        binding.convertToBtn.setTitleConstraint()
        binding.convertFromDashBalance.isVisible = (balanceOnCoinbase != null)
    }

    @SuppressLint("SetTextI18n")
    private fun updateAmount(){
        if (walletToCoinbase){
            val currencyRate = ExchangeRate(Coin.COIN, exchangeRate?.fiat)
            val fiatAmount = GenericUtils.fiatToString(currencyRate.coinToFiat(inputInDash))
            binding.convertFromDashBalance.text = "${dashFormat.minDecimals(0)
                .optionalDecimals(0,8).format(inputInDash)} Dash"
            binding.convertFromDashFiatAmount.text = "${Constants.PREFIX_ALMOST_EQUAL_TO} $fiatAmount"
        } else {
            balanceOnCoinbase?.let {
                exchangeRate?.let { _ ->
                    val balance = it.balance?.toBigDecimal()?.setScale(8, RoundingMode.HALF_UP).toString()
                    val coin = try {
                        Coin.parseCoin(balance)
                    } catch (x: Exception) {
                        Coin.ZERO
                    }

                    binding.convertFromDashBalance.text = "${dashFormat.minDecimals(0)
                        .optionalDecimals(0,8).format(coin)} ${it.currency}"

                    binding.convertFromDashFiatAmount.text = "${Constants.PREFIX_ALMOST_EQUAL_TO} ${it.faitAmount}"
                }
            }
            binding.convertFromDashBalance.isVisible = (balanceOnCoinbase != null)
            binding.convertFromDashFiatAmount.isVisible = (balanceOnCoinbase != null)
            //binding.walletIcon.isVisible = (balanceOnCoinbase != null)
        }
    }
}