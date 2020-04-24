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
import android.view.View
import android.view.View.OnLongClickListener
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.payment_request_wait_error_view.view.*


class PaymentRequestWaitErrorView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    init {
        inflate(context, R.layout.payment_request_wait_error_view, this)

        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.PaymentRequestWaitErrorView)
        try {
            when (attrsArray.getInt(R.styleable.PaymentRequestWaitErrorView_mode, -1)) {
                0 -> {
                    setBackgroundColor(ResourcesCompat.getColor(resources, R.color.dash_blue, null))
                    title_view.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_white, null))
                    message_view.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_white, null))
                    buttons.visibility = View.GONE
                    close.visibility = View.GONE
                }
                1 -> {
                    setBackgroundColor(ResourcesCompat.getColor(resources, R.color.dash_white, null))
                    title_view.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_black, null))
                    message_view.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_black, null))
                    buttons.visibility = View.VISIBLE
                    close.visibility = View.VISIBLE
                }
            }
            val iconSrc = attrsArray.getResourceId(R.styleable.PaymentRequestWaitErrorView_iconSrc, -1)
            if (iconSrc > -1) {
                icon.setImageResource(iconSrc)
            }
        } finally {
            attrsArray.recycle()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val iconDrawable = icon.drawable
        if (iconDrawable is AnimationDrawable) {
            iconDrawable.start()
        }
    }

    fun hideConfirmButton() {
        confirm.visibility = View.GONE
    }

    fun setOnCloseClickListener(listener: OnClickListener) {
        close.setOnClickListener(listener)
    }

    fun setOnConfirmClickListener(@StringRes testResId: Int, listener: OnClickListener) {
        confirm.setText(testResId)
        confirm.setOnClickListener(listener)
    }

    fun setOnCancelClickListener(listener: OnClickListener) {
        cancel.setOnClickListener(listener)
    }

    fun setOnCancelClickListener(@StringRes testResId: Int, listener: OnClickListener) {
        cancel.setText(testResId)
        cancel.setOnClickListener(listener)
    }

    fun setMessage(message: String?) {
        message_view.text = message
    }

    var message: Int = -1
        set(value) = message_view.setText(value)

    var title: Int = -1
        set(value) = title_view.setText(value)

    var details: String? = null
        set(value) = icon.setOnLongClickListener(OnLongClickListener {
            details_view.text = value
            if (details_view.text.isNotEmpty()) {
                details_view.visibility = View.VISIBLE
            }
            return@OnLongClickListener true
        })

}
