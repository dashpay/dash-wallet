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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.VerticalSegmentedPickerOptionViewBinding

class VerticalPickerOptionsAdapter(
    private val clickListener: (VerticalPickerOptions, Int) -> Unit
) : ListAdapter<VerticalPickerOptions, VerticalOptionViewHolder>(TaskDiffCallBack()) {

    // This check runs on background thread
    class TaskDiffCallBack : DiffUtil.ItemCallback<VerticalPickerOptions>() {
        override fun areItemsTheSame(oldItem: VerticalPickerOptions, newItem: VerticalPickerOptions): Boolean {
            return (oldItem.name == newItem.name) && (oldItem.isSelected == newItem.isSelected)
        }

        override fun areContentsTheSame(oldItem: VerticalPickerOptions, newItem: VerticalPickerOptions): Boolean {
            return oldItem == newItem
        }
    }

    override fun getItemCount(): Int {
        return currentList.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VerticalOptionViewHolder {
        val binding = VerticalSegmentedPickerOptionViewBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )

        return VerticalOptionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VerticalOptionViewHolder, position: Int) {
        val item = currentList[position]
        holder.bind(item)
        holder.binding.root.setOnClickListener {
            clickListener.invoke(item, position)
        }
    }
}

class VerticalOptionViewHolder(val binding: VerticalSegmentedPickerOptionViewBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(option: VerticalPickerOptions) {
        binding.name.setTextAppearance(R.style.Overline)
        binding.name.text = option.name

        if (option.isSelected) {
            binding.name.background =
                ContextCompat.getDrawable(itemView.context, R.drawable.bg_vertical_segment_rounded)
        } else {
            binding.name.setBackgroundColor(itemView.context.getColor(R.color.gray_100))
        }
    }
}

data class VerticalPickerOptions(val name: String, val isSelected: Boolean = false)
