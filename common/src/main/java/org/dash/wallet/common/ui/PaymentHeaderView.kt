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

package org.dash.wallet.common.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import coil.load
import coil.transform.CircleCropTransformation
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.PaymentHeaderViewBinding

class PaymentHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private var binding = PaymentHeaderViewBinding.inflate(LayoutInflater.from(context), this, true)
    private var onShowHideBalanceClicked: ((Boolean) -> Unit)? = null
    var revealBalance = false
        private set

    init {
        binding.hideButton.setOnClickListener {
            onShowHideBalanceClicked?.invoke(revealBalance)
        }
    }

    fun setPaymentAddressViewIcon(drawable: Int) {
        binding.paymentAddressViewIcon.setImageResource(drawable)
        binding.paymentAddressViewIcon.isVisible = true
    }

    fun setPaymentAddressViewIcon(imageUrl: String?, @DrawableRes placeholder: Int) {
        imageUrl?.let {
            binding.paymentAddressViewIcon.load(it) {
                crossfade(200)
                placeholder(placeholder)
                error(placeholder)
                transformations(CircleCropTransformation())
            }
            binding.paymentAddressViewIcon.isVisible = true
        }
    }

    fun setTitle(title: String) {
        binding.paymentAddressViewTitle.text = title
    }

    fun setProposition(title: String) {
        binding.paymentAddressViewProposition.text = title
    }

    fun setSubtitle(title: String) {
        binding.paymentAddressViewSubtitle.text = title
    }

    fun setBalanceValue(balanceText: String) {
        if (revealBalance) {
            binding.balanceLabel.text = balanceText
        } else {
            binding.balanceLabel.text = "**********"
        }
    }

    fun setOnShowHideBalanceClicked(listener: (Boolean) -> Unit) {
        this.onShowHideBalanceClicked = listener
    }

    fun triggerRevealBalance() {
        revealBalance = !revealBalance
        binding.hideButton.setImageResource(
            if (revealBalance) {
                R.drawable.ic_show
            } else {
                R.drawable.ic_hide
            }
        )
    }
}
