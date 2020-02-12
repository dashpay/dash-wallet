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

package de.schildbach.wallet.ui.widget

import android.content.Context
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import de.schildbach.wallet.ui.RestoreWalletFromSeedDialogFragment
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.pin_preview_view.view.*


class PinPreviewView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

    private var lastIndex = 0
    private var activeIndex = 0

    private var drawableResId: Int

    var mode: PinType = PinType.STANDARD
        set(value) {
            field = value
            clear()
        }

    private val pinItems = arrayListOf<View>()

    enum class PinType {
        STANDARD,   // standard pin - 4 digits
        EXTENDED    // extended pin - more than 4 digits
    }

    init {
        inflate(context, R.layout.pin_preview_view, this)
        orientation = VERTICAL
        val itemSize: Int
        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.PinPreviewView)
        try {
            itemSize = attrsArray.getDimensionPixelSize(R.styleable.PinPreviewView_pp_item_size, 0)
            drawableResId = attrsArray.getResourceId(R.styleable.PinPreviewView_pp_custom_drawable, R.drawable.pin_item)
        } finally {
            attrsArray.recycle()
        }

        lastIndex = items.childCount - 1
        for (i in 0..lastIndex) {
            val item = items.getChildAt(i)
            item.setBackgroundResource(drawableResId)
            pinItems.add(item)
            if (itemSize > 0) {
                item.minimumWidth = itemSize
                item.minimumHeight = itemSize
            }
        }
        forgot_pin.setOnClickListener {
            if (context is AppCompatActivity) {
                RestoreWalletFromSeedDialogFragment.show(context.supportFragmentManager)
            }
        }
    }

    fun clear() {
        activeIndex = 0
        pinItems[lastIndex].setBackgroundResource(if (mode == PinType.EXTENDED) R.drawable.pin_item_more else drawableResId)
        for (i in 0..lastIndex) {
            val pinItemViewBackground = pinItems[i].background as TransitionDrawable
            pinItemViewBackground.resetTransition()
        }
    }

    private fun setState(itemIndex: Int, active: Boolean) {
        val pinItemViewBackground = pinItems[itemIndex].background as TransitionDrawable
        pinItemViewBackground.isCrossFadeEnabled = true
        val durationMs = 100
        if (active) {
            pinItemViewBackground.startTransition(durationMs)
        } else {
            pinItemViewBackground.reverseTransition(durationMs)
        }
    }

    fun next() {
        if (activeIndex <= lastIndex) {
            setState(activeIndex, true)
            activeIndex++
        } else {
            if (mode == PinType.EXTENDED) {
                blinkLastItem()
                activeIndex++
            }
        }
    }

    fun prev() {
        if (activeIndex > 0) {
            activeIndex--
            if (activeIndex > lastIndex) {
                blinkLastItem()
            } else {
                setState(activeIndex, false)
            }
        }
    }

    private fun blinkLastItem() {
        val pinItemViewBackground = pinItems[lastIndex].background as TransitionDrawable
        pinItemViewBackground.resetTransition()
        Handler().postDelayed({ setState(lastIndex, true) }, 100)
    }

    fun shake() {
        val shakeAnimation = AnimationUtils.loadAnimation(context, R.anim.shake_pin)
        startAnimation(shakeAnimation)
    }

    fun badPin(remainingAttemptsMessage: String) {
        bad_pin.text = resources.getString(R.string.wallet_lock_wrong_pin, remainingAttemptsMessage)
        bad_pin.visibility = View.VISIBLE
    }

    fun clearBadPin() {
        bad_pin.visibility = View.GONE
    }

    fun setTextColor(colorResId: Int) {
        bad_pin.setTextColor(colorResId)
        forgot_pin.setTextColor(colorResId)
    }

    fun hideForgotPinAction() {
        forgot_pin.visibility = View.GONE
    }
}
