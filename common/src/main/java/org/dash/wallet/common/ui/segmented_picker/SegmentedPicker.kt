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

package org.dash.wallet.common.ui.segmented_picker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat.animate
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.SegmentedPickerBinding
import kotlin.math.max
import kotlin.math.min

class SegmentedPicker(context: Context, attrs: AttributeSet): FrameLayout(context, attrs) {
    private val binding = SegmentedPickerBinding.inflate(LayoutInflater.from(context), this)
    private lateinit var options: List<String>
    private var optionPickedListener: ((String, Int) -> Unit)? = null
    private val dividerDrawable = ContextCompat.getDrawable(
        context, R.drawable.segmented_picker_divider)!!

    private val trackWidth: Int
        get() {
            val optionsParams = binding.options.layoutParams as LayoutParams
            return measuredWidth - optionsParams.leftMargin - optionsParams.rightMargin
        }

    var pickedOptionIndex = 0
        private set

    val pickedOption: String
        get() = options[pickedOptionIndex]

    init {
        setBackgroundResource(R.drawable.segmented_picker_background)
        binding.options.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)

        doOnLayout {
            binding.thumb.layoutParams = LayoutParams(
                (trackWidth / options.size) + 4 * dividerDrawable.intrinsicWidth,
                binding.thumb.measuredHeight,
                Gravity.CENTER_VERTICAL
            )
            animateThumb(pickedOptionIndex)
        }
    }

    fun provideOptions(options: List<String>) {
        this.options = options
        val adapter = PickerOptionsAdapter(options) { option, index ->
            animateThumb(index)

            if (pickedOptionIndex != index) {
                pickedOptionIndex = index
                optionPickedListener?.invoke(option, index)
            }
        }
        binding.options.adapter = adapter
    }

    fun setOnOptionPickedListener(listener: (String, Int) -> Unit) {
        optionPickedListener = listener
    }

    private fun animateThumb(index: Int) {
        val optionsParams = binding.options.layoutParams as LayoutParams
        // Allows animating a thumb that's bigger than option size
        val optionWidth = trackWidth / options.size
        val thumbWidth = binding.thumb.layoutParams.width
        val minX = optionsParams.leftMargin.toFloat()
        val maxX = trackWidth - thumbWidth + minX
        val animateTo = optionWidth * index - (thumbWidth - optionWidth - minX) / 2f
        val actualX = max(minX, min(animateTo, maxX))

        animate(binding.thumb).apply {
            duration = 200
            translationX(actualX)
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = trackWidth

        for (i in 1 until options.size) {
            val leftBound = width / options.size * i
            val rightBound = leftBound + dividerDrawable.intrinsicWidth

            dividerDrawable.setBounds(
                leftBound, measuredHeight / 4,
                rightBound, measuredHeight * 3/4
            )

            dividerDrawable.draw(canvas)
        }
    }
}
