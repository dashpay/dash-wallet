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
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.RadiobuttonRowBinding
import org.dash.wallet.common.ui.getRoundedBackground

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
            return oldItem.title == newItem.title &&
                    oldItem.icon == newItem.icon
        }
    }
}

class RadioButtonViewHolder(val binding: RadiobuttonRowBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(option: IconifiedViewItem, isSelected: Boolean) {
        val resources = binding.root.resources

        binding.title.text = option.title
        binding.title.setTextColor(resources.getColorStateList(
            if (option.isIconEncircled) R.color.gray_900 else R.color.radiobutton_text_color,
            null
        ))

        binding.subtitle.isVisible = option.subtitle.isNotEmpty()
        binding.subtitle.text = option.subtitle

        binding.iconWrapper.isVisible = option.icon != null
        option.icon?.let {
            binding.icon.setImageDrawable(
                ResourcesCompat.getDrawable(
                    resources, option.icon, null
                )
            )
            val tint = if (option.isIconEncircled) R.color.radiobutton_icon_color else R.color.radiobutton_text_color
            binding.icon.imageTintList = resources.getColorStateList(tint, null)

            binding.iconWrapper.background = when {
                !option.isIconEncircled -> null
                isSelected -> resources.getRoundedBackground(R.style.EncircledIconSelectedTheme)
                else -> resources.getRoundedBackground(R.style.EncircledIconTheme)
            }
        }

        option.subtitleDrawable?.let { res ->
            val drawable = ResourcesCompat.getDrawable(resources, res, null)
            drawable?.let { img ->
                val height = resources.getDimensionPixelSize(R.dimen.payment_method_image_height)
                val ratio = height.toFloat() / img.minimumHeight
                val width = (img.minimumWidth * ratio).toInt()
                img.setBounds(0, 0, width, height)
                binding.subtitle.setCompoundDrawables(img, null, null, null)
            }
        }

        binding.checkmark.isVisible = isSelected
    }
}