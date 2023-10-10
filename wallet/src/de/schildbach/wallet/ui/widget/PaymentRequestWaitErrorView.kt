/*
 * Copyright 2020 Dash Core Group
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

package de.schildbach.wallet.ui.widget

import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.PaymentRequestWaitErrorViewBinding

class PaymentRequestWaitErrorView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {
    private val binding = PaymentRequestWaitErrorViewBinding.inflate(LayoutInflater.from(context), this)

    init {
        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.PaymentRequestWaitErrorView)
        try {
            when (attrsArray.getInt(R.styleable.PaymentRequestWaitErrorView_mode, -1)) {
                0 -> {
                    setBackgroundColor(ResourcesCompat.getColor(resources, R.color.dash_blue, null))
                    binding.titleView.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_white, null))
                    binding.messageView.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_white, null))
                    binding.buttons.visibility = View.GONE
                    binding.close.visibility = View.GONE
                }
                1 -> {
                    setBackgroundColor(ResourcesCompat.getColor(resources, R.color.dash_white, null))
                    binding.titleView.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_black, null))
                    binding.messageView.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_black, null))
                    binding.buttons.visibility = View.VISIBLE
                    binding.close.visibility = View.VISIBLE
                }
            }
            val iconSrc = attrsArray.getResourceId(R.styleable.PaymentRequestWaitErrorView_iconSrc, -1)
            if (iconSrc > -1) {
                binding.icon.setImageResource(iconSrc)
            }
        } finally {
            attrsArray.recycle()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val iconDrawable = binding.icon.drawable
        if (iconDrawable is AnimationDrawable) {
            iconDrawable.start()
        }
    }

    fun hideConfirmButton() {
        binding.confirm.visibility = View.GONE
    }

    fun setOnCloseClickListener(listener: OnClickListener) {
        binding.close.setOnClickListener(listener)
    }

    fun setOnConfirmClickListener(@StringRes testResId: Int, listener: OnClickListener) {
        binding.confirm.setText(testResId)
        binding.confirm.setOnClickListener(listener)
    }

    fun setOnCancelClickListener(listener: OnClickListener) {
        binding.cancel.setOnClickListener(listener)
    }

    fun setOnCancelClickListener(@StringRes testResId: Int, listener: OnClickListener) {
        binding.cancel.setText(testResId)
        binding.cancel.setOnClickListener(listener)
    }

    fun setMessage(message: String?) {
        binding.messageView.text = message
    }

    var message: Int = -1
        set(value) = binding.messageView.setText(value)

    var title: Int = -1
        set(value) = binding.titleView.setText(value)

    var details: String? = null
        set(value) = binding.icon.setOnLongClickListener {
            binding.detailsView.text = value

            if (binding.detailsView.text.isNotEmpty()) {
                binding.detailsView.visibility = View.VISIBLE
            }
            return@setOnLongClickListener true
        }
}
