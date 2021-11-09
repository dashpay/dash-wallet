/*
 * Copyright 2021 Dash Core Group
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

package org.dash.wallet.features.exploredash.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.RadiobuttonRowBinding

class RadioGroupAdapter(private var selectedIndex: Int = 0, private val clickListener: (String, Int) -> Unit)
    : ListAdapter<String, RadioButtonViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RadioButtonViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = RadiobuttonRowBinding.inflate(inflater, parent, false)

        return RadioButtonViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RadioButtonViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, position == selectedIndex)

        holder.binding.root.setOnClickListener {
            if (position != selectedIndex) {
                notifyItemChanged(selectedIndex)
                selectedIndex = position
                notifyItemChanged(position)
            }

            clickListener.invoke(item, position)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
    }
}

class RadioButtonViewHolder(val binding: RadiobuttonRowBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(option: String, isSelected: Boolean) {
        binding.name.text = option
        binding.name.setTextColor(
            binding.root.resources.getColor(
                if (isSelected) R.color.dash_blue else R.color.gray_900,
                null
            )
        )
        binding.checkmark.isVisible = isSelected
    }
}