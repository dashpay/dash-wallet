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
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import de.schildbach.wallet.ui.ForgotPinActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.PinPreviewViewBinding

class PinPreviewView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

    companion object {
        const val DEFAULT_PIN_LENGTH = 4
        const val CUSTOM_PIN_LENGTH = 100
    }

    private var lastIndex = 0
    private var activeIndex = 0

    private var drawableResId: Int

    private val binding = PinPreviewViewBinding.inflate(LayoutInflater.from(context), this)

    var mode: PinType = PinType.STANDARD
        set(value) {
            field = value
            if (value == PinType.STANDARD) {
                lastIndex = binding.standardPinPreview.childCount - 1
                binding.standardPinPreview.visibility = View.VISIBLE
                binding.customPinPreview.visibility = View.GONE
            } else {
                lastIndex = CUSTOM_PIN_LENGTH
                binding.standardPinPreview.visibility = View.GONE
                binding.customPinPreview.visibility = View.VISIBLE
            }
            clear()
        }

    private val pinItems = arrayListOf<View>()

    enum class PinType {
        STANDARD, // standard pin - 4 digits
        CUSTOM // custom pin - more or less than 4 digits
    }

    init {
        orientation = VERTICAL
        val itemSize: Int
        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.PinPreviewView)
        try {
            itemSize = attrsArray.getDimensionPixelSize(R.styleable.PinPreviewView_pp_item_size, 0)
            drawableResId = attrsArray.getResourceId(R.styleable.PinPreviewView_pp_custom_drawable, R.drawable.pin_item)
        } finally {
            attrsArray.recycle()
        }

        lastIndex = binding.standardPinPreview.childCount - 1
        for (i in 0 until binding.standardPinPreview.childCount) {
            val item = binding.standardPinPreview.getChildAt(i)
            item.setBackgroundResource(drawableResId)
            pinItems.add(item)
            if (itemSize > 0) {
                item.minimumWidth = itemSize
                item.minimumHeight = itemSize
            }
        }
        if (drawableResId == R.drawable.pin_item) {
            binding.customPinPreview.setBackgroundResource(R.drawable.custom_pin_preview_background_gray)
        } else {
            binding.customPinPreview.setBackgroundResource(R.drawable.custom_pin_preview_background)
        }

        binding.forgotPin.setOnClickListener {
            if (context is AppCompatActivity) {
                context.startActivity(ForgotPinActivity.createIntent(context))
            }
        }
    }

    fun clear() {
        activeIndex = 0
        for (i in 0 until binding.standardPinPreview.childCount) {
            val pinItemViewBackground = pinItems[i].background as TransitionDrawable
            pinItemViewBackground.resetTransition()
        }
        updateCustomPinPreview()
    }

    private fun setState(itemIndex: Int, active: Boolean) {
        if (itemIndex < binding.standardPinPreview.childCount) {
            val pinItemViewBackground = pinItems[itemIndex].background as TransitionDrawable
            pinItemViewBackground.isCrossFadeEnabled = true
            val durationMs = 100
            if (active) {
                pinItemViewBackground.startTransition(durationMs)
            } else {
                pinItemViewBackground.reverseTransition(durationMs)
            }
        }
    }

    fun next() {
        if (activeIndex <= lastIndex) {
            setState(activeIndex, true)
            activeIndex++
        }
        updateCustomPinPreview()
    }

    fun prev() {
        if (activeIndex > 0) {
            activeIndex--
            if (activeIndex <= lastIndex) {
                setState(activeIndex, false)
            }
        }
        updateCustomPinPreview()
    }

    private fun updateCustomPinPreview() {
        val numOfDots = binding.customPinPreview.childCount
        if (numOfDots > activeIndex) {
            binding.customPinPreview.removeViews(activeIndex, numOfDots - activeIndex)
        } else {
            val numOfMissingDots = activeIndex - numOfDots
            for (i in 0 until numOfMissingDots) {
                val dot = FrameLayout(context)
                dot.setBackgroundResource(R.drawable.custom_pin_preview_item_dot)
                if (binding.customPinPreview.childCount > 7) {
                    // avoid extending the width of custom_pin_preview when PIN is very long
                    dot.visibility = View.GONE
                }
                binding.customPinPreview.addView(dot, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            }
        }
    }

    fun shake() {
        val shakeAnimation = AnimationUtils.loadAnimation(context, R.anim.shake_pin)
        startAnimation(shakeAnimation)
    }

    fun badPin(remainingAttemptsMessage: String) {
        binding.badPin.text = resources.getString(R.string.wallet_lock_wrong_pin, remainingAttemptsMessage)
        binding.badPin.visibility = View.VISIBLE
    }

    fun clearBadPin() {
        binding.badPin.visibility = View.GONE
    }

    fun setTextColor(colorResId: Int) {
        binding.badPin.setTextColor(colorResId)
        binding.forgotPin.setTextColor(colorResId)
    }

    fun hideForgotPinAction() {
        binding.forgotPin.visibility = View.GONE
    }
}
