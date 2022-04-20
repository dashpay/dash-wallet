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
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.VALUE_ZERO
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.ConvertViewBinding

class TransferView(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs) {

    private val binding = ConvertViewBinding.inflate(LayoutInflater.from(context), this)
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(6).optionalDecimals()

    private var onTransferDirectionBtnClicked: (() -> Unit)? = null

    var dashBalance: Coin = Coin.ZERO
    var fiatAmount: Fiat = Fiat.valueOf("USD", 0)
        private set

    var exchangeRate: ExchangeRate? = null

    var walletToCoinbase: Boolean = false
        set(value) {
            if (field != value){
                field = value
                updateSymbols()
            }
        }

    private fun updateSymbols() {
        if (walletToCoinbase){
            binding.convertFromBtn.setConvertItemTitle(R.string.dash_wallet_name)
            ContextCompat.getDrawable(context, R.drawable.ic_dash_blue_filled)
                ?.let { binding.convertFromBtn.setConvertItemIcon(it) }
        } else {
            binding.convertFromBtn.setConvertItemTitle(R.string.coinbase)
            ContextCompat.getDrawable(context, R.drawable.ic_coinbase)
                ?.let { binding.convertFromBtn.setConvertItemIcon(it) }
        }
    }

    private var _input = "0"
    var input: String
        get() = _input
        set(value) {
            _input = value.ifEmpty { VALUE_ZERO }
        }

    init {
        initUI()
        binding.swapBtn.setOnClickListener {
            walletToCoinbase = !walletToCoinbase
            onTransferDirectionBtnClicked?.invoke()
        }
    }

    fun setOnTransferDirectionBtnClicked(listener: () -> Unit){
        onTransferDirectionBtnClicked = listener
    }

    private fun initUI(){
        binding.convertFromDashBalance.isVisible = true
        binding.convertFromDashFiatAmount.isVisible = true
        binding.convertFromBtn.showCryptoTitle = false
        binding.convertToBtn.showCryptoTitle = false
        binding.convertFromBtn.setIconConstraint()
        binding.convertFromBtn.setTitleConstraint()
        binding.convertToBtn.setIconConstraint()
        binding.convertToBtn.setTitleConstraint()
        binding.convertFromBtn.setCryptoItemGroupVisibility(true)
        binding.convertToBtn.setCryptoItemGroupVisibility(true)
        binding.convertFromBtn.showSubTitle = false
        binding.convertToBtn.showSubTitle = false
    }
}