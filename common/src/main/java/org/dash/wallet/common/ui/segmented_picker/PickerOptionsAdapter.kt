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

package org.dash.wallet.common.ui.segmented_picker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.common.databinding.PickerOptionViewBinding

class PickerOptionsAdapter(
    private val options: List<String>,
    private val clickListener: (String, Int) -> Unit
) : RecyclerView.Adapter<OptionViewHolder>() {

    override fun getItemCount(): Int {
        return options.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionViewHolder {
        val binding = PickerOptionViewBinding.inflate(
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

class OptionViewHolder(val binding: PickerOptionViewBinding): RecyclerView.ViewHolder(binding.root) {
    fun bind(option: String) {
        binding.name.text = option
    }
}