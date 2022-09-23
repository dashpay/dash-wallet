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
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.annotation.DrawableRes
import androidx.annotation.IntegerRes
import androidx.annotation.StringRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.withStyledAttributes
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import coil.load
import coil.size.Scale
import coil.transform.CircleCropTransformation
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.ItemCyrptoConvertBinding

class CryptoConvertItem @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val binding = ItemCyrptoConvertBinding.inflate(LayoutInflater.from(context), this)

    private var convertItemClickListener: (() -> Unit)? = null
    init {
        obtainStyledAttributes(context, attrs, defStyleAttr)
        binding.itemConvertCl.setOnClickListener {
            convertItemClickListener?.invoke()
        }
    }

    private fun obtainStyledAttributes(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
        context.withStyledAttributes(
            set = attrs,
            attrs = R.styleable.CryptoConvertItem,
            defStyleAttr = defStyleAttr
        ) {
            getString(R.styleable.CryptoConvertItem_crypto_convert_item_title)?.also {
                setConvertItemTitle(it)
            }

            getString(R.styleable.CryptoConvertItem_crypto_convert_item_service_title)?.also {
                setConvertItemServiceName(it)
            }

            getDrawable(R.styleable.CryptoConvertItem_crypto_convert_item_icon)?.also {
                setConvertItemIcon(it)
            }

            getBoolean(R.styleable.CryptoConvertItem_is_crypto_convert_item_group_visible, true).also {
                setCryptoItemGroupVisibility(it)
            }

            getBoolean(R.styleable.CryptoConvertItem_is_crypto_convert_item_arrow_visible, true).also {
                setCryptoItemArrowVisibility(it)
            }
        }
    }

    fun setCryptoItemArrowVisibility(isGroupVisible: Boolean) {
        binding.convertFormDashArrow.isVisible = isGroupVisible
    }
    fun setCryptoItemGroupVisibility(isGroupVisible: Boolean) {
        binding.fromDataGroup.isVisible = isGroupVisible
        binding.selectTheCoinTitle.isVisible = !isGroupVisible
    }

    fun setSyncingVisibility(isGroupVisible: Boolean) {
        binding.syncingProgressContainer.isVisible = isGroupVisible
    }

    fun setConvertItemTitle(title: String) {9
        binding.convertFromDashTitle.text = title
    }

    fun setConvertItemServiceName(name: String) {
        binding.convertFromDashSubtitle.text = name
    }

    fun setConvertItemTitle(@StringRes title: Int) {
        binding.convertFromDashTitle.setText(title)
    }

    fun setConvertItemServiceName(@StringRes name: Int) {
        binding.convertFromDashSubtitle.setText(name)
    }

    fun setConvertItemIcon(url: String?) {
        url?.let {
            binding.convertFromDashIcon.load(it) {
                crossfade(true)
                scale(Scale.FILL)
                placeholder(org.dash.wallet.common.R.drawable.ic_default_flag)
                transformations(CircleCropTransformation())
            }
        }
    }

    fun setConvertItemIcon(cryptoCurrencyIcon: Drawable) {
        binding.convertFromDashIcon.setImageDrawable(cryptoCurrencyIcon)
    }

    fun setConvertItemClickListener(listener: () -> Unit) {
        convertItemClickListener = listener
    }

    fun setIconConstraint(){
        binding.convertFromDashIcon.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = ConstraintSet.PARENT_ID
            bottomToBottom = ConstraintSet.PARENT_ID
        }
    }

    fun setTitleConstraint(){
        binding.convertFromDashTitle.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = binding.convertFromDashIcon.id
            bottomToBottom = binding.convertFromDashIcon.id
        }
    }

    fun hideComponents(){
        binding.convertFromDashSubtitle.isVisible = false
        binding.selectTheCoinTitle.isVisible = false
        binding.convertFormDashArrow.isVisible = false
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        binding.itemConvertCl.isEnabled = enabled
    }
}
