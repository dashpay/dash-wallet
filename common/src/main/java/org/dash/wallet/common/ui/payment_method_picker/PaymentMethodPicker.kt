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
import org.dash.wallet.common.ui.radio_group.IconSelectMode
import org.dash.wallet.common.ui.radio_group.IconifiedViewItem
import org.dash.wallet.common.ui.radio_group.OptionPickerDialog

class PaymentMethodPicker(context: Context, attrs: AttributeSet): ConstraintLayout(context, attrs) {
    private val binding = ViewPaymentMethodBinding.inflate(LayoutInflater.from(context), this)
    private var onPaymentMethodChanged: ((PaymentMethod) -> Unit)? = null
    private var onClickListener: OnClickListener? = null

    var paymentMethods: List<PaymentMethod> = listOf()
        set(value) {
            field = value
            setDisplayedInfo(value[selectedMethodIndex])
        }

    var selectedMethodIndex = 0
        set(value) {
            if (field != value) {
                field = value
                val method = paymentMethods[value]
                setDisplayedInfo(method)
                onPaymentMethodChanged?.invoke(method)
            }
        }

    init {
        background = resources.getRoundedRippleBackground(R.style.ListViewButtonBackground)
        val paddingStart = resources.getDimensionPixelOffset(R.dimen.default_horizontal_padding)
        val paddingEnd = resources.getDimensionPixelOffset(R.dimen.payment_method_padding_end)
        updatePadding(left=paddingStart, right=paddingEnd)

        super.setOnClickListener {
            onClickListener?.onClick(it)

            val itemList = paymentMethods.map { method ->
                val name = if (method.name.isEmpty() && method.paymentMethodType == PaymentMethodType.Card) {
                    context.getString(R.string.debit_credit_card)
                } else {
                    method.name
                }

                val paymentMethodIcon = getPaymentMethodIcon(method.paymentMethodType)
                val cardIcon = if (method.paymentMethodType == PaymentMethodType.Card) {
                    CardUtils.getCardIcon(method.account)
                } else {
                    null
                }
                IconifiedViewItem(
                    name,
                    listOf(method.account, method.accountType).filterNot { it.isNullOrEmpty() }.joinToString(" • "),
                    paymentMethodIcon,
                    null,
                    if (paymentMethodIcon != null) IconSelectMode.Encircle else IconSelectMode.Tint,
                    null,
                    cardIcon
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

    fun setOnPaymentMethodChanged(listener: (PaymentMethod) -> Unit) {
        onPaymentMethodChanged = listener
    }

    override fun setOnClickListener(listener: OnClickListener?) {
        onClickListener = listener
    }

    private fun setDisplayedInfo(paymentMethod: PaymentMethod) {
        binding.paymentMethodName.text = paymentMethod.name
        val cardIcon = if (paymentMethod.paymentMethodType == PaymentMethodType.Card) {
            CardUtils.getCardIcon(paymentMethod.account)
        } else {
            null
        }
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
        return when (paymentMethodType) {
            PaymentMethodType.Card -> R.drawable.ic_card
            PaymentMethodType.BankAccount -> R.drawable.ic_bank
            PaymentMethodType.PayPal -> R.drawable.ic_paypal
            PaymentMethodType.Fiat -> R.drawable.ic_cash_account
            PaymentMethodType.ApplePay -> R.drawable.ic_apple_pay
            else -> null
        }
    }

    private fun getFragmentManager(context: Context?): FragmentManager? {
        return when (context) {
            is AppCompatActivity -> context.supportFragmentManager
            is ContextThemeWrapper -> getFragmentManager(context.baseContext)
            is ViewComponentManager.FragmentContextWrapper -> getFragmentManager(context.baseContext)
            else -> null
        }
    }
}