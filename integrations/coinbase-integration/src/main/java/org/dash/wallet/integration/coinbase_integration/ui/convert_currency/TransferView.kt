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
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.DASH_CURRENCY
import org.dash.wallet.integration.coinbase_integration.VALUE_ZERO
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.ConvertViewBinding
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.BaseServiceWallet
import java.math.BigDecimal
import java.math.RoundingMode

class TransferView(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs) {

    private val binding = ConvertViewBinding.inflate(LayoutInflater.from(context), this)
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(2).optionalDecimals(0,6)

    private var onTransferDirectionBtnClicked: (() -> Unit)? = null

    var inputInDash: Coin = Coin.ZERO

    var exchangeRate: ExchangeRate? = null
    var balanceOnCoinbase: BaseServiceWallet? = null
        set(value) {
            field = value
            updateAmount()
        }

    var walletToCoinbase: Boolean = false
        set(value) {
            if (field != value){
                field = value
                updateSymbols(isDeviceConnectedToInternet)
                updateAmount()
            }
        }

    var isDeviceConnectedToInternet: Boolean = true
        set(value) {
            if (field != value){
                field = value
                updateSymbols(value)
            }
        }

    private fun updateSymbols(isOnline: Boolean) {
        if (walletToCoinbase){
            binding.convertFromBtn.setConvertItemTitle(R.string.dash_wallet_name)
            binding.convertToBtn.setConvertItemTitle(R.string.coinbase)
            ContextCompat.getDrawable(context,
                if (isOnline)
                R.drawable.ic_dash_blue_filled else org.dash.wallet.common.R.drawable.ic_dash_saturated)
                ?.let { binding.convertFromBtn.setConvertItemIcon(it) }
            ContextCompat.getDrawable(context, if (isOnline)
                R.drawable.ic_coinbase else R.drawable.ic_coinbase_saturated)
                ?.let { binding.convertToBtn.setConvertItemIcon(it) }
        } else {
            binding.convertFromBtn.setConvertItemTitle(R.string.coinbase)
            binding.convertToBtn.setConvertItemTitle(R.string.dash_wallet_name)
            ContextCompat.getDrawable(context, if (isOnline)
                R.drawable.ic_coinbase else R.drawable.ic_coinbase_saturated)
                ?.let { binding.convertFromBtn.setConvertItemIcon(it) }
            ContextCompat.getDrawable(context,
                if (isOnline) R.drawable.ic_dash_blue_filled else org.dash.wallet.common.R.drawable.ic_dash_saturated)
                ?.let { binding.convertToBtn.setConvertItemIcon(it) }
        }
    }

    init {
        initUI()
        updateSymbols(isDeviceConnectedToInternet)
        updateAmount()
        binding.swapBtn.setOnClickListener {
            onTransferDirectionBtnClicked?.invoke()
        }
    }

    fun setOnTransferDirectionBtnClicked(listener: () -> Unit){
        onTransferDirectionBtnClicked = listener
    }

    private fun initUI(){
        binding.convertFromBtn.setCryptoItemGroupVisibility(true)
        binding.convertToBtn.setCryptoItemGroupVisibility(true)
        binding.convertFromBtn.hideComponents()
        binding.convertToBtn.hideComponents()
        binding.convertFromBtn.setIconConstraint()
        binding.convertFromBtn.setTitleConstraint()
        binding.convertToBtn.setIconConstraint()
        binding.convertToBtn.setTitleConstraint()
        binding.walletIcon.visibility = View.INVISIBLE
        binding.fromLabel.updateLayoutParams<ConstraintLayout.LayoutParams> {
            bottomToBottom = binding.convertFromBtn.id
        }

        binding.convertDashDivider.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToBottom = binding.walletIcon.id
        }

        binding.convertFromBtn.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topMargin = 5
            bottomMargin = 5
        }

        binding.convertFromDashBalance.updateLayoutParams<ConstraintLayout.LayoutParams> {
            startToStart = LayoutParams.UNSET
            topToBottom = LayoutParams.UNSET
            bottomToTop = LayoutParams.UNSET
            startToEnd = binding.walletIcon.id
            topToTop = binding.walletIcon.id
            bottomToBottom = binding.walletIcon.id
            topMargin = 0
            marginStart = 8
        }

        binding.convertFromDashFiatAmount.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = binding.walletIcon.id
            bottomToBottom = binding.walletIcon.id
        }

        binding.convertToBtn.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topMargin = 6
        }

        binding.swapBtn.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToBottom = binding.walletGuideline.id
            topMargin = 0
            bottomMargin = 0
            bottomToBottom = LayoutParams.UNSET
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateAmount(){
        if (walletToCoinbase){
            exchangeRate?.let { rate ->
                val fiatAmount = GenericUtils.fiatToString(rate.coinToFiat(inputInDash))
                binding.convertFromDashBalance.text = "${dashFormat
                    .format(inputInDash)} $DASH_CURRENCY"
                binding.convertFromDashFiatAmount.text = "${Constants.PREFIX_ALMOST_EQUAL_TO} $fiatAmount"
                if (inputInDash.isGreaterThan(Coin.ZERO)){
                    binding.convertFromDashBalance.isVisible = true
                    binding.convertFromDashFiatAmount.isVisible = true
                    binding.walletIcon.isVisible = true
                }
            }
        } else {
            balanceOnCoinbase?.let {
                val balance = it.balance?: ""
                if (balance.isNotEmpty() && balance != VALUE_ZERO){
                    val formattedAmount = GenericUtils.formatFiatWithoutComma(balance)
                    val coin = try {
                        Coin.parseCoin(formattedAmount)
                    } catch (x: Exception) {
                        Coin.ZERO
                    }

                    val formatDash = dashFormat.minDecimals(2)
                        .format(coin).toString()
                    binding.convertFromDashBalance.text = "$formatDash $DASH_CURRENCY"
                    binding.convertFromDashFiatAmount.text = "${Constants.PREFIX_ALMOST_EQUAL_TO} ${it.faitAmount}"

                    binding.convertFromDashBalance.isVisible = true
                    binding.convertFromDashFiatAmount.isVisible = true

                    binding.walletIcon.isVisible = true

                }
            }
        }
    }
}