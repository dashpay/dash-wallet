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

package org.dash.wallet.common.ui.radio_group

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.RadiobuttonRowBinding
import org.dash.wallet.common.ui.ListDividerDecorator

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

fun RecyclerView.setupRadioGroup(radioGroupAdapter: RadioGroupAdapter) {
    val divider = ContextCompat.getDrawable(context, R.drawable.list_divider)!!
    val decorator = ListDividerDecorator(
            divider,
            showAfterLast = false,
            marginStart = resources.getDimensionPixelOffset(R.dimen.divider_margin_start)
    )
    addItemDecoration(decorator)
    adapter = radioGroupAdapter
}