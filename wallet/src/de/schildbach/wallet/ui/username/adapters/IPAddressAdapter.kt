/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.ui.username.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet_test.databinding.ViewMasternodeIpBinding

class IPAddressAdapter(private val deleteClickListener: (String) -> Unit) : ListAdapter<String, IPAddressViewHolder>(DiffCallback()) {
    class DiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IPAddressViewHolder {
        val binding = ViewMasternodeIpBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )

        return IPAddressViewHolder(binding, deleteClickListener)
    }

    override fun onBindViewHolder(holder: IPAddressViewHolder, position: Int) {
        val item = currentList[position]
        holder.bind(item)
    }
}

class IPAddressViewHolder(val binding: ViewMasternodeIpBinding, private val deleteClickListener: (String) -> Unit): RecyclerView.ViewHolder(binding.root) {
    fun bind(ipAddress: String) {
        binding.ipAddress.text = ipAddress
        binding.removeMasternode.setOnClickListener {
            deleteClickListener.invoke(ipAddress)
        }
    }
}
