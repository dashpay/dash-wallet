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
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.common.databinding.VerticalSegmentedPickerBinding

class VerticalSegmentedPicker(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {
    private val binding = VerticalSegmentedPickerBinding.inflate(LayoutInflater.from(context), this)
    private lateinit var adapter: VerticalPickerOptionsAdapter
    private var optionPickedListener: ((String, Int) -> Unit)? = null

    var pickedOptionIndex = 0
        set(value) {
            if (field != value) {
                setSelected(field, value)
                field = value
            }
        }

    val pickedOption: String
        get() = adapter.currentList[pickedOptionIndex].name

    init {
        binding.options.layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
    }

    fun provideOptions(options: List<String>) {
        val mapped = options.mapIndexed { index, option ->
            VerticalPickerOption(option, isSelected = pickedOptionIndex == index)
        }

        adapter = VerticalPickerOptionsAdapter { option, index ->
            if (pickedOptionIndex != index) {
                pickedOptionIndex = index
                optionPickedListener?.invoke(option.name, index)
            }
        }

        binding.options.adapter = adapter
        binding.options.itemAnimator = null
        adapter.submitList(mapped)
    }

    fun setOnOptionPickedListener(listener: (String, Int) -> Unit) {
        optionPickedListener = listener
    }

    private fun setSelected(oldIndex: Int, newIndex: Int) {
        if (this::adapter.isInitialized) {
            adapter.submitList(
                adapter.currentList.toMutableList().also {
                    it[newIndex] = it[newIndex].copy(isSelected = true)
                    it[oldIndex] = it[oldIndex].copy(isSelected = false)
                }
            )
        }
    }
}
