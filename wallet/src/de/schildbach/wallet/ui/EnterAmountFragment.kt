/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.send.EnterAmountSharedViewModel
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.enter_amount_fragment.*
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Monetary
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl
import org.dash.wallet.common.util.GenericUtils
import de.schildbach.wallet.ui.ExchangeRatesFragment.ARG_SHOW_AS_DIALOG
import de.schildbach.wallet.rates.ExchangeRate
import java.util.*
import android.content.Intent
import android.app.Activity
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.ExchangeRatesFragment.BUNDLE_EXCHANGE_RATE
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import org.dash.wallet.common.util.FiatAmountFormat


class EnterAmountFragment : Fragment() {

    companion object {
        private const val DECIMAL_SEPARATOR = '.'

        private const val ARGUMENT_INITIAL_AMOUNT = "argument_initial_amount"
        private const val RC_FIAT_CURRENCY_SELECTED = 99

        @JvmStatic
        fun newInstance(initialAmount: Monetary = Coin.ZERO): EnterAmountFragment {
            val args = Bundle()
            args.putSerializable(ARGUMENT_INITIAL_AMOUNT, initialAmount)
            val enterAmountFragment = EnterAmountFragment()
            enterAmountFragment.arguments = args
            return enterAmountFragment
        }
    }

    private val dashFormat = MonetaryFormat().noCode().minDecimals(6).optionalDecimals()
    private val fiatFormat = Constants.SEND_PAYMENT_LOCAL_FORMAT
    private lateinit var viewModel: EnterAmountViewModel
    private lateinit var sharedViewModel: EnterAmountSharedViewModel
    private val analytics = FirebaseAnalyticsServiceImpl.getInstance()
    private lateinit var fiatValue: CharSequence
    private var fiatAmountFormat: FiatAmountFormat ? = null
    var maxAmountSelected: Boolean = false
    private var shouldNotConvertFiatToDash: Boolean = false
    private lateinit var configuration: Configuration
    private var isInit: Boolean = true


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.enter_amount_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configuration = WalletApplication.getInstance().configuration
        convert_direction.setOnClickListener {
            viewModel.setDashToFiatDirection(!viewModel.dashToFiatDirectionValue)
            if (viewModel.dashToFiatDirectionValue) {
                analytics.logEvent(AnalyticsConstants.SendReceive.ENTER_AMOUNT_DASH, bundleOf())
            } else {
                analytics.logEvent(AnalyticsConstants.SendReceive.ENTER_AMOUNT_FIAT, bundleOf())
            }
        }
        confirm_button.setOnClickListener {
            sharedViewModel.buttonClickEvent.call(sharedViewModel.dashAmount)
            when (confirm_button.text.toString().toLowerCase(Locale.getDefault())) {
                "send" -> analytics.logEvent(AnalyticsConstants.SendReceive.ENTER_AMOUNT_SEND, bundleOf())
                "receive" -> analytics.logEvent(AnalyticsConstants.SendReceive.ENTER_AMOUNT_RECEIVE, bundleOf())
                else -> { }
            }
        }
        max_button.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.SendReceive.ENTER_AMOUNT_MAX, bundleOf())
            sharedViewModel.maxButtonClickEvent.call(true)
        }
        numeric_keyboard.isDecSeparatorEnabled = true
        numeric_keyboard.onKeyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {

            var value = StringBuilder()

            fun refreshValue() {
                value.clear()
                value.append(input_amount.text)
            }

            override fun onNumber(number: Int) {
                refreshValue()
                if(value.toString() == "0") {
                    // avoid entering leading zeros without decimal separator
                    return
                }
                val formattedValue = GenericUtils.formatFiatWithoutComma(value.toString())
                val isFraction = formattedValue.indexOf(DECIMAL_SEPARATOR) > -1
                if (isFraction) {
                    val lengthOfDecimalPart = formattedValue.length - formattedValue.indexOf(DECIMAL_SEPARATOR)
                    val decimalsThreshold = if (viewModel.dashToFiatDirectionValue) 8 else 2
                    if (lengthOfDecimalPart > decimalsThreshold) {
                        return
                    }
                }
                if (!maxAmountSelected) {
                    try {
                        appendIfValidAfter(number.toString())
                        applyNewValue(value.toString())
                    } catch (x: Exception) {
                        value.deleteCharAt(value.length - 1)
                        applyNewValue(value.toString())
                    }
                }
            }

            override fun onBack(longClick: Boolean) {
                refreshValue()
                if (longClick || maxAmountSelected) {
                    value.clear()
                } else if (value.isNotEmpty()) {
                    value.deleteCharAt(value.length - 1)
                }
                applyNewValue(value.toString())
                maxAmountSelected = false
            }

            override fun onFunction() {
                if (maxAmountSelected) {
                    return
                }
                refreshValue()
                if (value.indexOf(DECIMAL_SEPARATOR) == -1) {
                    value.append(DECIMAL_SEPARATOR)
                }
                applyNewValue(value.toString())
            }

            private fun appendIfValidAfter(number: String) {
                try {
                    value.append(number)
                    val formattedValue = GenericUtils.formatFiatWithoutComma(value.toString())

                    Coin.parseCoin(formattedValue)
                } catch (e: Exception) {
                    value.deleteCharAt(value.length - 1)
                }
            }
        }
        input_select_currency_toggle.setOnClickListener {
            val intent = Intent(activity, ExchangeRatesActivity::class.java)
            intent.putExtra(ARG_SHOW_AS_DIALOG, true)
            startActivityForResult(intent, RC_FIAT_CURRENCY_SELECTED)
        }

        calc_select_currency_toggle.setOnClickListener {
            val intent = Intent(activity, ExchangeRatesActivity::class.java)
            intent.putExtra(ARG_SHOW_AS_DIALOG, true)
            startActivityForResult(intent, RC_FIAT_CURRENCY_SELECTED)
        }
    }

    fun applyNewValue(value: String) {
        input_amount.text = value
        val formattedValue = GenericUtils.formatFiatWithoutComma(value)
        if (viewModel.dashToFiatDirectionValue) {

            val dashAmount = try {
                Coin.parseCoin(formattedValue)
            } catch (x: Exception) {
                Coin.ZERO
            }
            viewModel.setDashAmount(dashAmount)

        } else {

            val currencyCode = sharedViewModel.exchangeRateData.value?.currencyCode
                    ?: viewModel.fiatAmountLiveData.value!!.currencyCode

            val fiatAmount = try {
                Fiat.parseFiat(currencyCode, formattedValue)
            } catch (x: Exception) {
                Fiat.valueOf(currencyCode, 0)
            }
            viewModel.setFiatAmount(fiatAmount)
        }

        sharedViewModel.exchangeRateData.value?.run {
            viewModel.calculateDependent(sharedViewModel.exchangeRate!!)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        calc_pane.visibility = View.GONE
        convert_direction.visibility = View.GONE
        initViewModels()
        if (arguments != null) {
            val initialAmount = requireArguments().getSerializable(ARGUMENT_INITIAL_AMOUNT) as Monetary
            if (viewModel.dashToFiatDirectionValue) {
                viewModel.setDashAmount(Coin.valueOf(initialAmount.value))
            } else {
                viewModel.setFiatAmount(Fiat.valueOf(configuration.exchangeCurrencyCode, initialAmount.value))
            }
        } else {
            viewModel.setDashAmount(Coin.ZERO)
        }
    }

    private fun initViewModels() {
        viewModel = ViewModelProviders.of(this)[EnterAmountViewModel::class.java]
        sharedViewModel = activity?.run {
            ViewModelProviders.of(this)[EnterAmountSharedViewModel::class.java]
        } ?: throw Exception("Invalid Activity")
        if (configuration.isDashToFiatDirection) {
            viewModel.setDashToFiatDirection(configuration.isDashToFiatDirection)
        }
        sharedViewModel.exchangeRateData.observe(viewLifecycleOwner, Observer {
            it?.also {
                if (!configuration.isDashToFiatDirection && isInit){ //this is done only ONCE we enter this UI, to make sure we get the dashToFiat state after getting the current exchange rate
                    viewModel.setDashToFiatDirection(configuration.isDashToFiatDirection)
                    isInit = false
                }
                calc_pane.visibility = View.VISIBLE
                convert_direction.visibility = View.VISIBLE
                val rate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, it.fiat)
                if(shouldNotConvertFiatToDash && !viewModel.dashToFiatDirectionValue){
                    val currentDashAmount = viewModel.dashAmountLiveData.value!!
                    val newFiatAmount = rate.coinToFiat(currentDashAmount)
                    viewModel.setFiatAmount(newFiatAmount)
                    fiatValue = if (viewModel.fiatAmountLiveData.value!!.isZero) "" else fiatFormat.format(viewModel.fiatAmountLiveData.value)
                    if (fiatValue.isNotEmpty()) {
                        fiatAmountFormat = GenericUtils.formatFiatFromLocale(fiatValue)
                    }
                    input_amount.text = if (fiatValue.isEmpty()) "" else fiatAmountFormat?.formattedAmount?.let { amount -> if(amount.isBlank()) "" else amount }
                } else {
                    viewModel.calculateDependent(rate)
                }
            }
        })

        // changes to the active currency should be propagated to the viewModel
        sharedViewModel.dashToFiatDirectionLiveData.observe(viewLifecycleOwner) {
            viewModel.setDashToFiatDirection(it)
        }

        viewModel.dashToFiatDirectionLiveData.observe(viewLifecycleOwner, Observer {
            if (it) {
                val dashAmount = viewModel.dashAmountLiveData.value!!
                input_amount.text = if (dashAmount.isZero) "" else dashFormat.format(viewModel.dashAmountLiveData.value)
            } else {
                val sharedCode = sharedViewModel.exchangeRateData.value?.currencyCode
                val fiatCode = viewModel.fiatAmountLiveData.value?.currencyCode
                val currencyCode = sharedCode ?: fiatCode
                val parseFiatWithoutComma = GenericUtils.formatFiatWithoutComma(calc_amount.text.toString())
                viewModel.setFiatAmount(Fiat.parseFiat(currencyCode, parseFiatWithoutComma))
                fiatValue = if (viewModel.fiatAmountLiveData.value!!.isZero) "" else fiatFormat.format(viewModel.fiatAmountLiveData.value)
                if (fiatValue.isNotEmpty()) {
                    fiatAmountFormat = GenericUtils.formatFiatFromLocale(fiatValue)
                }
                input_amount.text = if (fiatValue.isEmpty()) "" else fiatAmountFormat?.formattedAmount?.let { amount -> if (amount.isBlank()) "" else amount }
            }
            val utilsExRate = sharedViewModel.exchangeRate
            viewModel.calculateDependent(utilsExRate)
            setupSymbolsVisibility()
            configuration.isDashToFiatDirection = it
        })
        viewModel.dashAmountLiveData.observe(viewLifecycleOwner, Observer {
            if (!viewModel.dashToFiatDirectionValue) {
                calc_amount.text = dashFormat.format(it)
            }
            sharedViewModel.dashAmountData.value = it
        })
        viewModel.fiatAmountLiveData.observe(viewLifecycleOwner, Observer {
            if (viewModel.dashToFiatDirectionValue) {
                fiatValue = fiatFormat.format(it)
                fiatAmountFormat = GenericUtils.formatFiatFromLocale(fiatValue)
                calc_amount.text = fiatAmountFormat?.formattedAmount?.let { amount -> if(amount.isBlank()) "" else amount }
            }
            applyCurrencySymbol(GenericUtils.setCurrentCurrencySymbolWithCode(it.currencyCode))
        })

        sharedViewModel.directionChangeEnabledData.observe(viewLifecycleOwner, Observer {
            convert_direction.isEnabled = it
        })
        sharedViewModel.buttonEnabledData.observe(viewLifecycleOwner, Observer {
            confirm_button.isEnabled = it
        })
        sharedViewModel.editableData.observe(viewLifecycleOwner, Observer {
            numeric_keyboard.isEnabled = it
        })
        sharedViewModel.maxButtonVisibleData.observe(viewLifecycleOwner, Observer {
            max_button.visibility = if (it) View.VISIBLE else View.GONE
        })
        sharedViewModel.buttonTextData.observe(viewLifecycleOwner, Observer {
            when {
                it > 0 -> confirm_button.setText(it)
                else -> confirm_button.text = null
            }
        })
        sharedViewModel.messageTextStringData.observe(viewLifecycleOwner, Observer {
            message.text = it
            message.visibility = if (it != null) View.VISIBLE else View.INVISIBLE
        })
        sharedViewModel.messageTextData.observe(viewLifecycleOwner, Observer {
            when {
                it > 0 -> message.setText(it)
                else -> message.text = null
            }
            message.visibility = if (it > 0) View.VISIBLE else View.INVISIBLE
        })
        sharedViewModel.changeDashAmountEvent.observe(viewLifecycleOwner, Observer {
            applyNewValue(it.toPlainString())
        })

        sharedViewModel.applyMaxAmountEvent.observe(viewLifecycleOwner, Observer {
            maxAmountSelected = true
            if (!viewModel.dashToFiatDirectionValue) {
                viewModel.setDashToFiatDirection(true)
            }
            applyNewValue(it.toPlainString())
        })
    }

    private fun applyCurrencySymbol(symbol: String) {
        left_input_symbol.text = symbol
        right_input_symbol.text = symbol
        right_calc_amount_symbol.text = symbol
        left_calc_amount_symbol.text = symbol
        setupSymbolsVisibility()
    }

    private fun setupSymbolsVisibility() {
        val amountPositionState = fiatAmountFormat?.isAmountToLeftPosition
        if (viewModel.dashToFiatDirectionValue) {
            left_input_symbol.visibility = View.GONE
            right_input_symbol.visibility = View.GONE
            if(amountPositionState != null) {
                left_calc_amount_symbol.visibility = if (amountPositionState) View.GONE else View.VISIBLE
                right_calc_amount_symbol.visibility = if (amountPositionState) View.VISIBLE else View.GONE
            }
        } else {
            left_calc_amount_symbol.visibility = View.GONE
            right_calc_amount_symbol.visibility = View.GONE
            if(amountPositionState != null) {
                left_input_symbol.visibility = if (amountPositionState) View.GONE else View.VISIBLE
                right_input_symbol.visibility = if (amountPositionState) View.VISIBLE else View.GONE
            }
        }

        input_symbol_dash.visibility = if (viewModel.dashToFiatDirectionValue) View.VISIBLE else View.GONE
        calc_amount_symbol_dash.visibility = if (viewModel.dashToFiatDirectionValue) View.GONE else View.VISIBLE
        input_select_currency_toggle.visibility = if (viewModel.dashToFiatDirectionValue) View.GONE else View.VISIBLE
        calc_select_currency_toggle.visibility = if (viewModel.dashToFiatDirectionValue) View.VISIBLE else View.GONE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if(resultCode == Activity.RESULT_OK && requestCode == RC_FIAT_CURRENCY_SELECTED) {
            val exchangeRate: ExchangeRate? = data?.getParcelableExtra(BUNDLE_EXCHANGE_RATE)
            if(exchangeRate != null){
                shouldNotConvertFiatToDash = true
                sharedViewModel.setCurrentExchangeRate(exchangeRate)
                applyCurrencySymbol(GenericUtils.setCurrentCurrencySymbolWithCode(exchangeRate.currencyCode))
            }
        } else
            super.onActivityResult(requestCode, resultCode, data)
    }

}
