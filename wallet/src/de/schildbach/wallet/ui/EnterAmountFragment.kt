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
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.send.EnterAmountSharedViewModel
import de.schildbach.wallet.ui.widget.NumericKeyboardView
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.enter_amount_fragment.*
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Monetary
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.util.GenericUtils

class EnterAmountFragment : Fragment() {

    companion object {
        private const val DECIMAL_SEPARATOR = '.'

        private const val ARGUMENT_INITIAL_AMOUNT = "argument_initial_amount"

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
    private val fiatFormat = Constants.LOCAL_FORMAT

    private lateinit var viewModel: EnterAmountViewModel
    private lateinit var sharedViewModel: EnterAmountSharedViewModel

    var maxAmountSelected: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.enter_amount_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        convert_direction.setOnClickListener {
            viewModel.dashToFiatDirectionData.value = !viewModel.dashToFiatDirectionValue
        }
        confirm_button.setOnClickListener {
            sharedViewModel.buttonClickEvent.call(sharedViewModel.dashAmount)
        }
        max_button.setOnClickListener {
            sharedViewModel.maxButtonClickEvent.call(true)
        }
        numeric_keyboard.enableDecSeparator(true);
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
                val isFraction = value.indexOf(DECIMAL_SEPARATOR) > -1
                if (isFraction) {
                    val lengthOfDecimalPart = value.length - value.indexOf(DECIMAL_SEPARATOR)
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
                    Coin.parseCoin(value.toString())
                } catch (e: Exception) {
                    value.deleteCharAt(value.length - 1)
                }
            }
        }
    }

    fun applyNewValue(value: String) {
        input_amount.text = value

        if (viewModel.dashToFiatDirectionValue) {

            val dashAmount = try {
                Coin.parseCoin(value)
            } catch (x: Exception) {
                Coin.ZERO
            }
            viewModel.dashAmountData.value = dashAmount

        } else {

            val currencyCode = sharedViewModel.exchangeRateData.value?.currencyCode
                    ?: viewModel.fiatAmountData.value!!.currencyCode

            val fiatAmount = try {
                Fiat.parseFiat(currencyCode, value)
            } catch (x: Exception) {
                Fiat.valueOf(currencyCode, 0)
            }
            viewModel.fiatAmountData.value = fiatAmount
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
            val initialAmount = arguments!!.getSerializable(ARGUMENT_INITIAL_AMOUNT) as Monetary
            viewModel.dashToFiatDirectionData.value = initialAmount is Coin
            if (viewModel.dashToFiatDirectionValue) {
                viewModel.dashAmountData.value = initialAmount as Coin
            } else {
                viewModel.fiatAmountData.value = initialAmount as Fiat
            }
        } else {
            viewModel.dashToFiatDirectionData.value = true
            viewModel.dashAmountData.value = Coin.ZERO
        }
    }

    private fun initViewModels() {
        viewModel = ViewModelProviders.of(this)[EnterAmountViewModel::class.java]

        viewModel.dashToFiatDirectionData.observe(viewLifecycleOwner, Observer {
            if (it) {
                val dashAmount = viewModel.dashAmountData.value!!
                input_amount.text = if (dashAmount.isZero) "" else dashFormat.format(viewModel.dashAmountData.value)
            } else {
                val currencyCode = sharedViewModel.exchangeRateData.value?.currencyCode ?: viewModel.fiatAmountData.value!!.currencyCode
                viewModel.fiatAmountData.value = Fiat.parseFiat(currencyCode, calc_amount.text.toString())
                input_amount.text = if (viewModel.fiatAmountData.value!!.isZero) "" else fiatFormat.format(viewModel.fiatAmountData.value)
            }
            viewModel.calculateDependent(sharedViewModel.exchangeRate)
            setupSymbolsVisibility()
        })
        viewModel.dashAmountData.observe(viewLifecycleOwner, Observer {
            if (!viewModel.dashToFiatDirectionValue) {
                calc_amount.text = dashFormat.format(it)
            }
            sharedViewModel.dashAmountData.value = it
        })
        viewModel.fiatAmountData.observe(viewLifecycleOwner, Observer {
            if (viewModel.dashToFiatDirectionValue) {
                calc_amount.text = fiatFormat.format(it)
            }
            applyCurrencySymbol(GenericUtils.currencySymbol(it.currencyCode))
        })
        sharedViewModel = activity?.run {
            ViewModelProviders.of(this)[EnterAmountSharedViewModel::class.java]
        } ?: throw Exception("Invalid Activity")
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
        sharedViewModel.exchangeRateData.observe(viewLifecycleOwner, Observer {
            it?.also {
                calc_pane.visibility = View.VISIBLE
                convert_direction.visibility = View.VISIBLE
                viewModel.calculateDependent(sharedViewModel.exchangeRate)
            }
        })
        sharedViewModel.changeDashAmountEvent.observe(viewLifecycleOwner, Observer {
            applyNewValue(it.toPlainString())
        })

        sharedViewModel.applyMaxAmountEvent.observe(viewLifecycleOwner, Observer {
            maxAmountSelected = true
            if (!viewModel.dashToFiatDirectionValue) {
                viewModel.dashToFiatDirectionData.value = true
            }
            applyNewValue(it.toPlainString())
        })
    }

    private fun applyCurrencySymbol(symbol: String) {
        input_symbol.text = symbol
        calc_amount_symbol.text = symbol
        setupSymbolsVisibility()
    }

    private fun setupSymbolsVisibility() {
        input_symbol.visibility = if (viewModel.dashToFiatDirectionValue) View.GONE else View.VISIBLE
        input_symbol_dash.visibility = if (viewModel.dashToFiatDirectionValue) View.VISIBLE else View.GONE
        calc_amount_symbol.visibility = if (viewModel.dashToFiatDirectionValue) View.VISIBLE else View.GONE
        calc_amount_symbol_dash.visibility = if (viewModel.dashToFiatDirectionValue) View.GONE else View.VISIBLE
    }
}
