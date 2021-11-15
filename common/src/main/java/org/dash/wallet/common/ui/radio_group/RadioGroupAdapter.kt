/*
 *
 *  * Copyright 2021 Dash Core Group
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.dash.wallet.common.ui.radio_group

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.common.databinding.RadiobuttonRowBinding

class RadioGroupAdapter(
    defaultSelectedIndex: Int = 0,
    private val clickListener: (IconifiedViewItem, Int) -> Unit
): ListAdapter<IconifiedViewItem, RadioButtonViewHolder>(DiffCallback()) {

    var selectedIndex: Int = defaultSelectedIndex
        set(newIndex) {
            if (field != newIndex) {
                val oldIndex = field
                field = newIndex
                notifyItemChanged(oldIndex)
                notifyItemChanged(newIndex)
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RadioButtonViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = RadiobuttonRowBinding.inflate(inflater, parent, false)

        return RadioButtonViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RadioButtonViewHolder, position: Int) {
        holder.itemView.isSelected = position == selectedIndex
        val item = getItem(position)
        holder.bind(item, position == selectedIndex)

        holder.binding.root.setOnClickListener {
            if (holder.adapterPosition != selectedIndex) {
                notifyItemChanged(selectedIndex)
                selectedIndex = holder.adapterPosition
                notifyItemChanged(holder.adapterPosition)
            }

            clickListener.invoke(item, position)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<IconifiedViewItem>() {
        override fun areItemsTheSame(oldItem: IconifiedViewItem, newItem: IconifiedViewItem): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: IconifiedViewItem, newItem: IconifiedViewItem): Boolean {
            return oldItem.name == newItem.name &&
                    oldItem.icon == newItem.icon
        }
    }
}

class RadioButtonViewHolder(val binding: RadiobuttonRowBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(option: IconifiedViewItem, isSelected: Boolean) {
        val resources = binding.root.resources
        binding.name.text = option.name
        binding.icon.isVisible = option.icon != null
        option.icon?.let {
            binding.icon.setImageDrawable(
                ResourcesCompat.getDrawable(
                    resources, option.icon, null
                )
            )
        }
        binding.checkmark.isVisible = isSelected
    }
}