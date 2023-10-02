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
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat.animate
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.SegmentedPickerBinding
import org.dash.wallet.common.ui.setRoundedBackground
import kotlin.math.max
import kotlin.math.min

data class SegmentedOption(
    val title: String,
    @DrawableRes val icon: Int? = null
)

class SegmentedPicker(context: Context, attrs: AttributeSet): FrameLayout(context, attrs) {
    private val binding = SegmentedPickerBinding.inflate(LayoutInflater.from(context), this)
    private lateinit var adapter: PickerOptionsAdapter
    private var optionPickedListener: ((SegmentedOption, Int) -> Unit)? = null
    private val dividerDrawable = ContextCompat.getDrawable(
        context, R.drawable.segmented_picker_divider)!!

    private val trackWidth: Int
        get() {
            val optionsParams = binding.options.layoutParams as LayoutParams
            return measuredWidth - optionsParams.leftMargin - optionsParams.rightMargin
        }

    var pickedOptionIndex = 0
        private set

    val pickedOption: SegmentedOption
        get() = adapter.currentList[pickedOptionIndex]

    init {
        setRoundedBackground(R.style.SegmentedPickerBackground)
        binding.options.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)

        doOnLayout {
            binding.thumb.layoutParams = LayoutParams(
                (trackWidth / adapter.itemCount) + 4 * dividerDrawable.intrinsicWidth,
                binding.thumb.measuredHeight,
                Gravity.CENTER_VERTICAL
            )
            moveThumb(pickedOptionIndex, false)
        }
    }

    fun provideOptions(options: List<SegmentedOption>) {
        this.adapter = PickerOptionsAdapter(options) { option, index ->
            moveThumb(index)

            if (pickedOptionIndex != index) {
                pickedOptionIndex = index
                optionPickedListener?.invoke(option, index)
            }
        }
        binding.options.adapter = adapter
    }

    fun setOnOptionPickedListener(listener: (SegmentedOption, Int) -> Unit) {
        optionPickedListener = listener
    }

    fun setSelectedIndex(value: Int, animate: Boolean = false) {
        if (pickedOptionIndex != value) {
            pickedOptionIndex = value
            moveThumb(value, animate)
        }
    }

    private fun moveThumb(index: Int, animate: Boolean = true) {
        adapter.selectedIndex = index
        val optionsParams = binding.options.layoutParams as LayoutParams
        // Allows animating a thumb that's bigger than option size
        val optionWidth = trackWidth / adapter.itemCount
        val thumbWidth = binding.thumb.layoutParams.width
        val minX = optionsParams.leftMargin.toFloat()
        val maxX = trackWidth - thumbWidth + minX
        val position = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            adapter.itemCount - index - 1
        } else {
            index
        }

        val animateTo = optionWidth * position - (thumbWidth - optionWidth - minX) / 2f
        val actualX = max(minX, min(animateTo, maxX))

        if (animate) {
            animate(binding.thumb).apply {
                duration = 200
                translationX(actualX)
            }.start()
        } else {
            binding.thumb.translationX = actualX
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw vertical separators
        val width = trackWidth

        for (i in 1 until adapter.itemCount) {
            val leftBound = width / adapter.itemCount * i
            val rightBound = leftBound + dividerDrawable.intrinsicWidth

            dividerDrawable.setBounds(
                leftBound, measuredHeight / 4,
                rightBound, measuredHeight * 3/4
            )

            dividerDrawable.draw(canvas)
        }
    }
}
