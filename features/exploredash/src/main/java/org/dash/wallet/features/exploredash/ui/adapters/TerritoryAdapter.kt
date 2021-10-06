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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.features.exploredash.databinding.TerritoryRowBinding
import org.dash.wallet.features.exploredash.ui.viewitems.TerritoryViewItem

class TerritoryAdapter(private val clickListener: (TerritoryViewItem, TerritoryViewHolder) -> Unit)
    : ListAdapter<TerritoryViewItem, TerritoryViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TerritoryViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        val binding = TerritoryRowBinding.inflate(inflater, parent, false)
        return TerritoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TerritoryViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)

        fun triggerItem() {
            val currentSelectedItem = currentList.find { it.isSelected }
            if (currentSelectedItem != null && currentSelectedItem.name != item.name) {
                currentSelectedItem.isSelected = false
                notifyItemChanged(currentList.indexOf(currentSelectedItem))
            }

            item.isSelected = true
            holder.binding.checkbox.isChecked = true
            clickListener.invoke(item, holder)
        }

        holder.binding.root.setOnClickListener { triggerItem() }
        holder.binding.checkbox.setOnClickListener { triggerItem() }
    }

    class DiffCallback : DiffUtil.ItemCallback<TerritoryViewItem>() {
        override fun areItemsTheSame(oldItem: TerritoryViewItem, newItem: TerritoryViewItem): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: TerritoryViewItem, newItem: TerritoryViewItem): Boolean {
            return oldItem.name == newItem.name
        }
    }
}

class TerritoryViewHolder(val binding: TerritoryRowBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(territory: TerritoryViewItem) {
        binding.name.text = territory.name
        binding.checkbox.isChecked = territory.isSelected
    }
}