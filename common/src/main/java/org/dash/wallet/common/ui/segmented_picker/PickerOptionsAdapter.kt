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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.common.databinding.SegmentedPickerOptionViewBinding

class PickerOptionsAdapter(
    private val options: List<String>,
    private val clickListener: (String, Int) -> Unit
) : RecyclerView.Adapter<OptionViewHolder>() {

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
        holder.bind(item)
        holder.binding.root.setOnClickListener {
            clickListener.invoke(item, position)
        }
    }
}

class OptionViewHolder(val binding: SegmentedPickerOptionViewBinding): RecyclerView.ViewHolder(binding.root) {
    fun bind(option: String) {
        binding.name.text = option
    }
}