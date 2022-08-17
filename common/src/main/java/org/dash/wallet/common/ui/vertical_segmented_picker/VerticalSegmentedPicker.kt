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

package org.dash.wallet.common.ui.vertical_segmented_picker

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.VerticalSegmentedPickerBinding

class VerticalSegmentedPicker(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {
    private val binding = VerticalSegmentedPickerBinding.inflate(LayoutInflater.from(context), this)
    private lateinit var options: List<VerticalPickerOptions>
    private lateinit var adapter: VerticalPickerOptionsAdapter
    private var optionPickedListener: ((String, Int) -> Unit)? = null

    var pickedOptionIndex = 0
        set(value) {
            if (field != value) {
                field = value
            }
        }

    val pickedOption: String
        get() = options[pickedOptionIndex].name

    init {
        setBackgroundResource(R.drawable.segmented_picker_background)
        binding.options.layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
    }

    fun provideOptions(options: List<String>) {
        this.options = options.mapIndexed { index, option ->
            if (pickedOptionIndex == index)
                VerticalPickerOptions(option, true)
            else
                VerticalPickerOptions(option, false)
        }

        adapter = VerticalPickerOptionsAdapter() { option, index ->

            if (pickedOptionIndex != index) {

                submitListToAdapter(
                    this.options.toMutableList().also {
                        it[index] = it[index].copy(isSelected = true)
                        it[pickedOptionIndex] = it[pickedOptionIndex].copy(isSelected = false)
                    }.toList()
                )

                pickedOptionIndex = index
                optionPickedListener?.invoke(option.name, index)
            }
        }

        binding.options.adapter = adapter
        binding.options.itemAnimator = null
        submitListToAdapter(this.options)
    }

    private fun submitListToAdapter(list: List<VerticalPickerOptions>) {
        this.options = list
        adapter.submitList(list)
    }

    fun setOnOptionPickedListener(listener: (String, Int) -> Unit) {
        optionPickedListener = listener
    }
}
