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

package org.dash.wallet.common.ui.payment_method_picker

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import dagger.hilt.android.internal.managers.ViewComponentManager
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.ViewPaymentMethodBinding
import org.dash.wallet.common.ui.getRoundedRippleBackground
import org.dash.wallet.common.ui.radio_group.IconifiedViewItem
import org.dash.wallet.common.ui.radio_group.OptionPickerDialog

class PaymentMethodPicker(context: Context, attrs: AttributeSet): ConstraintLayout(context, attrs) {
    private val binding = ViewPaymentMethodBinding.inflate(LayoutInflater.from(context), this)

    var paymentMethods: List<PaymentMethod> = listOf()
        set(value) {
            field = value
            setDisplayedInfo(value[selectedMethodIndex])
        }

    var selectedMethodIndex = 0
        set(value) {
            if (field != value) {
                field = value
                setDisplayedInfo(paymentMethods[value])
            }
        }

    init {
        background = resources.getRoundedRippleBackground(R.style.ListViewButtonBackground)
        val paddingStart = resources.getDimensionPixelOffset(R.dimen.payment_method_padding_start)
        val paddingEnd = resources.getDimensionPixelOffset(R.dimen.payment_method_padding_end)
        updatePadding(left=paddingStart, right=paddingEnd)

        setOnClickListener {
            val itemList = paymentMethods.map { method ->
                val name = if (method.name.isEmpty() && method.paymentMethodType == PaymentMethodType.Card) {
                    context.getString(R.string.debit_credit_card)
                } else {
                    method.name
                }

                IconifiedViewItem(
                    name,
                    listOf(method.account, method.accountType).filterNot { it.isNullOrEmpty() }.joinToString(" • "),
                    getPaymentMethodIcon(method.paymentMethodType),
                    method.paymentMethodType != PaymentMethodType.Unknown,
                    getCardIcon(method.paymentMethodType, method.account)
                )
            }

            getFragmentManager(context)?.let { fragmentManager ->
                OptionPickerDialog(
                    resources.getString(R.string.choose_payment_method),
                    itemList,
                    selectedMethodIndex,
                    showSearch = false
                ) { _, index, dialog ->
                    dialog.dismiss()
                    selectedMethodIndex = index
                }.show(fragmentManager, "payment_method")
            }
        }
    }

    private fun setDisplayedInfo(paymentMethod: PaymentMethod) {
        binding.paymentMethodName.text = paymentMethod.name
        val cardIcon = getCardIcon(paymentMethod.paymentMethodType, paymentMethod.account)
        binding.paymentMethodName.isVisible = cardIcon == null
        binding.paymentMethodIcon.setImageResource(cardIcon ?: 0)
        binding.account.text = paymentMethod.account

        val canOpenPicker = paymentMethods.size > 1
        binding.navIcon.isVisible = canOpenPicker
        isClickable = canOpenPicker
        isFocusable = canOpenPicker
    }

    @DrawableRes
    private fun getPaymentMethodIcon(paymentMethodType: PaymentMethodType): Int? {
        return when(paymentMethodType) {
            PaymentMethodType.Card -> R.drawable.ic_card
            PaymentMethodType.BankAccount -> R.drawable.ic_bank
            PaymentMethodType.PayPal -> R.drawable.ic_paypal
            else -> null
        }
    }

    @DrawableRes
    private fun getCardIcon(paymentMethodType: PaymentMethodType, account: String?): Int? {
        if (paymentMethodType == PaymentMethodType.Card && !account.isNullOrEmpty()) {
            cardTypes.forEach { (regex, drawableRes) ->
                if (regex.toRegex().containsMatchIn(account)) {
                    return drawableRes
                }
            }
        }

        return null
    }

    private fun getFragmentManager(context: Context?): FragmentManager? {
        return when (context) {
            is AppCompatActivity -> context.supportFragmentManager
            is ContextThemeWrapper -> getFragmentManager(context.baseContext)
            is ViewComponentManager.FragmentContextWrapper -> getFragmentManager(context.baseContext)
            else -> null
        }
    }

    private val cardTypes = mapOf(
        "^4[0-9]{3}?" to R.drawable.ic_visa,
        "^5[1-5][0-9]{2}|222[1-9]|22[3-9][0-9]|2[3-6][0-9]{2}|27[01][0-9]|2720" to R.drawable.ic_mastercard,
        "^3[47][0-9]{2}" to R.drawable.ic_american_express,
        "^6(?:011|5[0-9]{2})" to R.drawable.ic_card_discover,
        "^3(?:0[0-5]|[68][0-9])[0-9]" to R.drawable.ic_diners_club,
        "^35[0-9]{2}" to R.drawable.ic_card_jcb
    )
}