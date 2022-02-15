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
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.ConvertViewBinding
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.ServiceWallet
import java.math.RoundingMode

class ConvertView(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs) {
    private val binding = ConvertViewBinding.inflate(LayoutInflater.from(context), this)
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(6).optionalDecimals()

    private var onCurrencyChooserClicked: (() -> Unit)? = null
    private var onSwapClicked: ((Boolean) -> Unit)? = null

    private var _input: ServiceWallet? = null
    var input: ServiceWallet?
        get() = _input
        set(value) {
            _input = value
            updateAmount()
        }

    var exchangeRate: ExchangeRate? = null
        set(value) {
            field = value
        }

    var dashToCrypto: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateUiWithSwap()
            }
        }

    init {

        binding.convertFromDashBalance.isVisible = input != null
        updateUiWithSwap()
        binding.swapBtn.setOnClickListener {
            dashToCrypto = !dashToCrypto
            updateUiWithSwap()
            onSwapClicked?.invoke(dashToCrypto)
        }
        binding.convertFromBtn.convertItemClickListener = object :
            CryptoConvertItem.ConvertItemClickListener {
            override fun onConvertItemClickListener() {
                if (!dashToCrypto) {
                    onCurrencyChooserClicked?.invoke()
                }
            }
        }
        binding.convertToBtn.convertItemClickListener = object :
            CryptoConvertItem.ConvertItemClickListener {
            override fun onConvertItemClickListener() {
                if (dashToCrypto) {
                    onCurrencyChooserClicked?.invoke()
                }
            }
        }
    }

    private fun updateUiWithSwap() {
        setConvertFromBtnData()
        setConvertToBtnData()
    }

    private fun setConvertFromBtnData() {
        binding.convertFromBtn.setCryptoItemArrowVisibility(!dashToCrypto)
        if (dashToCrypto) {
            binding.convertFromBtn.setCryptoItemGroupVisibility(true)
            binding.convertFromBtn.setConvertItemServiceName(R.string.dash_wallet_name)
            binding.convertFromBtn.setConvertItemTitle(R.string.dash)
            ContextCompat.getDrawable(context, R.drawable.ic_dash_blue_filled)
                ?.let { binding.convertFromBtn.setConvertItemIcon(it) }
        } else {
            setFromBtnData()
        }
    }

    private fun setConvertToBtnData() {
        binding.convertToBtn.setCryptoItemArrowVisibility(dashToCrypto)
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
        binding.convertFromDashBalance.isVisible = (input != null)
        binding.convertFromDashFiatAmount.isVisible = (input != null)
        input?.let {
            binding.convertFromBtn.setConvertItemServiceName(it.cryptoWalletService)
            binding.convertFromBtn.setConvertItemTitle(it.cryptoWalletName)
            binding.convertFromBtn.setConvertItemIcon(it.icon)

            exchangeRate?.let { currentExchangeRate ->

                val balance = it.balance.toBigDecimal().setScale(8, RoundingMode.HALF_UP).toString()
                val coin = try {
                    Coin.parseCoin(balance)
                } catch (x: Exception) {
                    Coin.ZERO
                }

                binding.convertFromDashBalance.text = "${context.getString(R.string.balance)} ${dashFormat.minDecimals(0)
                    .optionalDecimals(0,8).format(coin)} ${input?.currency}"

                binding.convertFromDashFiatAmount.text = "${Constants.PREFIX_ALMOST_EQUAL_TO} ${input?.faitAmount}"
            }
        }
    }

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

    fun setOnSwapClicked(listener: (Boolean) -> Unit) {
        onSwapClicked = listener
    }

    private fun updateAmount() {

        if (dashToCrypto) {
            setToBtnData()
        } else {
            setFromBtnData()
        }
    }
}