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

package org.dash.wallet.integrations.maya.ui.convert_currency

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
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.databinding.ConverterViewBinding
import org.dash.wallet.integrations.maya.ui.convert_currency.model.ServiceWallet
import java.math.RoundingMode

class ConverterView(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs) {
    private val binding = ConverterViewBinding.inflate(LayoutInflater.from(context), this)
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(6).optionalDecimals()

    private var onCurrencyChooserClicked: (() -> Unit)? = null

    private var _isSellSwapEnabled: Boolean = false
    var isSellSwapEnabled: Boolean
        get() = _isSellSwapEnabled
        set(value) {
            _isSellSwapEnabled = value
            updateSellSwapBtn()
        }

    private var _input: ServiceWallet? = null
    var input: ServiceWallet?
        get() = _input
        set(value) {
            _input = value
            updateAmount()
        }

    private var _dashInput: Coin? = null
    var dashInput: Coin?
        get() = _dashInput
        set(value) {
            _dashInput = value
            updateUiWithSwap()
        }

    var exchangeRate: ExchangeRate? = null
        set(value) {
            field = value
        }

    var dashToCrypto: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                updateUiWithSwap()
            }
        }

    var fiatInput: Fiat? = null
        set(value) {
            if (field != value) {
                field = value
                updateUiWithSwap()
            }
        }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        binding.convertFromBtn.isEnabled = enabled
        binding.convertToBtn.isEnabled = enabled
    }

    init {
        updateUiWithSwap()
        updateSellSwapBtn()

        binding.convertFromBtn.setConvertItemClickListener {
            if (!dashToCrypto) {
                onCurrencyChooserClicked?.invoke()
            }
        }
        binding.convertToBtn.setConvertItemClickListener {
            if (dashToCrypto) {
                onCurrencyChooserClicked?.invoke()
            }
        }
    }

    private fun updateSellSwapBtn() {
        binding.swapBtn.isEnabled = _isSellSwapEnabled
        binding.swapBtn.isVisible = _isSellSwapEnabled
    }

    private fun updateUiWithSwap() {
        setConvertFromBtnData()
        setConvertToBtnData()
    }

    private fun setConvertFromBtnData() {
        if (dashToCrypto) {
            binding.convertFromBtn.setCryptoItemGroupVisibility(true)
            binding.convertFromBtn.setConvertItemServiceName(R.string.dash_wallet_name)
            binding.convertFromBtn.setConvertItemTitle(R.string.dash)
            ContextCompat.getDrawable(context, R.drawable.ic_dash_blue_filled)
                ?.let { binding.convertFromBtn.setConvertItemIcon(it) }

            if (dashInput != null && fiatInput != null) {
                binding.convertFromBtn.setConvertItemAmounts(
                    "${dashFormat.minDecimals(2).optionalDecimals(2,4).format(dashInput ?: Coin.ZERO)}",
                    "${Constants.PREFIX_ALMOST_EQUAL_TO} ${ (fiatInput ?: Fiat.valueOf("USD", 0)).toFormattedString() }"
                )
            }
        } else {
            setFromBtnData()
        }
    }

    private fun setConvertToBtnData() {
        binding.convertToBtn.setCryptoItemAmountVisibility(false)
        if (!dashToCrypto) {
            binding.convertToBtn.setCryptoItemGroupVisibility(true)
            binding.convertToBtn.setConvertItemServiceName(R.string.dash_wallet_name)
            binding.convertToBtn.setConvertItemTitle(R.string.dash)
            ContextCompat.getDrawable(context, R.drawable.ic_dash_blue_filled)
                ?.let { binding.convertToBtn.setConvertItemIcon(it) }
        } else {
            setToBtnData()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setFromBtnData() {
        binding.convertFromBtn.setCryptoItemGroupVisibility(input != null)
        input?.let {
            binding.convertFromBtn.setConvertItemServiceName(it.cryptoWalletService)
            binding.convertFromBtn.setConvertItemTitle(it.cryptoWalletName)
            binding.convertFromBtn.setConvertItemIcon(it.icon)

            exchangeRate?.let { _ ->

                val balance = it.balance.toBigDecimal().setScale(8, RoundingMode.HALF_UP).toString()
                val coin = try {
                    Coin.parseCoin(balance)
                } catch (x: Exception) {
                    Coin.ZERO
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setToBtnData() {
        binding.convertToBtn.setCryptoItemGroupVisibility(input != null)
        input?.let {
            binding.convertToBtn.setConvertItemServiceName(it.cryptoWalletService)
            binding.convertToBtn.setConvertItemTitle(it.cryptoWalletName)
            binding.convertToBtn.setConvertItemIcon(it.icon)
        }
    }

    fun setOnCurrencyChooserClicked(listener: () -> Unit) {
        onCurrencyChooserClicked = listener
    }

    private fun updateAmount() {
        if (dashToCrypto) {
            setToBtnData()
        } else {
            setFromBtnData()
        }
    }
}
