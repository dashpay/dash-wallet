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

package de.schildbach.wallet.ui.more.masternode_keys

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet_test.databinding.MasternodeKeyTypeRowBinding

class MasternodeKeyTypeAdapter(
    private val keyChainMap: MutableMap<MasternodeKeyType, MasternodeKeyTypeInfo>,
    private val clickListener: (MasternodeKeyType) -> Unit,
) : RecyclerView.Adapter<MasternodeKeyTypeViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MasternodeKeyTypeViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = MasternodeKeyTypeRowBinding.inflate(inflater, parent, false)
        return MasternodeKeyTypeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MasternodeKeyTypeViewHolder, position: Int) {
        val type = MasternodeKeyType.fromCode(position)
        holder.bind(keyChainMap[type]!!, position == 0, position == 3)
        holder.binding.root.setOnClickListener { clickListener.invoke(type) }
    }

    override fun getItemCount(): Int {
        return keyChainMap.size
    }

    override fun getItemId(position: Int): Long {
        return MasternodeKeyType.fromCode(position).type.toLong()
    }
}
