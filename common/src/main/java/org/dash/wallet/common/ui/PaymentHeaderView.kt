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
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.PaymentHeaderViewBinding

class PaymentHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private var binding: PaymentHeaderViewBinding = PaymentHeaderViewBinding.inflate(LayoutInflater.from(context), this, true)

    private var revealBalance = false

    private var onHideBalanceClickedListener: OnHideBalanceClickedListener? = null

    init {
        binding.hideButton.setOnClickListener {
            revealBalance = !revealBalance
            if (revealBalance) {
                binding.hideButton.setImageResource(R.drawable.ic_show)
            } else {
                binding.hideButton.setImageResource(R.drawable.ic_hide)
            }
            onHideBalanceClickedListener?.onHideBalanceClicked(it)
        }
    }

    fun setPaymentAddressViewIcon(drawable: Int) {
        binding.paymentAddressViewIcon.setImageResource(drawable)
        binding.paymentAddressViewIcon.isVisible = true
    }

    fun setPaymentAddressViewIcon(imageUrl: String?) {
        imageUrl?.let {
            Glide.with(context)
                .load(it)
                .placeholder(R.drawable.ic_image_placeholder)
                .error(R.drawable.ic_image_placeholder)
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .into(binding.paymentAddressViewIcon)
            binding.paymentAddressViewIcon.isVisible = true
        }
    }

    fun setPaymentAddressViewTitle(title: String) {
        binding.paymentAddressViewTitle.text = title
    }

    fun setPaymentAddressViewProposition(title: String) {
        binding.paymentAddressViewProposition.text = title
    }

    fun setPaymentAddressViewSubtitle(title: String) {
        binding.paymentAddressViewSubtitle.text = title
    }

    fun setBalanceValue(balanceText: String) {
        if (revealBalance) {
            binding.balanceLabel.text = balanceText
        } else {
            binding.balanceLabel.text = "**********"
        }
    }

    fun setOnHideBalanceClickedListener(onHideBalanceClickedListener: OnHideBalanceClickedListener) {
        this.onHideBalanceClickedListener = onHideBalanceClickedListener
    }
}

interface OnHideBalanceClickedListener {
    fun onHideBalanceClicked(view: View)
}
