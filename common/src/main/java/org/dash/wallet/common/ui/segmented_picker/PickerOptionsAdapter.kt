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

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.SegmentedPickerOptionViewBinding

class PickerOptionsAdapter(
    private val options: List<SegmentedOption>,
    private val clickListener: (SegmentedOption, Int) -> Unit
) : ListAdapter<SegmentedOption, OptionViewHolder>(TaskDiffCallBack()) {

    class TaskDiffCallBack : DiffUtil.ItemCallback<SegmentedOption>() {
        override fun areItemsTheSame(oldItem: SegmentedOption, newItem: SegmentedOption): Boolean {
            return (oldItem.title == newItem.title)
        }

        override fun areContentsTheSame(oldItem: SegmentedOption, newItem: SegmentedOption): Boolean {
            return oldItem == newItem
        }
    }

    var selectedIndex: Int = 0
        set(value) {
            if (field != value) {
                val oldSelected = field
                field = value
                notifyItemChanged(oldSelected)
                notifyItemChanged(value)
            }
        }

    override fun getItemCount(): Int {
        return options.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionViewHolder {
        val binding = SegmentedPickerOptionViewBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        binding.root.layoutParams = ViewGroup.LayoutParams(
            parent.measuredWidth / options.size, ViewGroup.LayoutParams.WRAP_CONTENT)

        return OptionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OptionViewHolder, position: Int) {
        val item = options[position]
        holder.bind(item, position == selectedIndex)
        holder.binding.root.setOnClickListener {
            clickListener.invoke(item, position)
        }
    }
}

class OptionViewHolder(
    val binding: SegmentedPickerOptionViewBinding
): RecyclerView.ViewHolder(binding.root) {
    fun bind(option: SegmentedOption, isSelected: Boolean) {
        binding.name.text = option.title
        binding.name.setTextColor(binding.root.resources.getColor(
            if (isSelected) {
                R.color.content_primary
            } else {
                R.color.content_tertiary
            }, null
        ))
        binding.icon.isVisible = option.icon != null
        option.icon?.let {
            binding.icon.setImageResource(it)
            binding.icon.imageTintList = if (isSelected) {
                null
            } else {
                ColorStateList.valueOf(binding.root.resources.getColor(R.color.gray, null))
            }
        }
    }
}