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

package de.schildbach.wallet.ui.more

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet_test.databinding.MasternodeKeyTypeRowBinding

enum class MasternodeKeyType(val type: Int) {
    OWNER(0),
    VOTING(1),
    OPERATOR(2),
    PLATFORM(3),
    ;

    companion object {
        fun fromCode(code: Int): MasternodeKeyType {
            return values().find { code == it.type }!!
        }
    }
}

class MasternodeKeyTypeAdapter(
    private val clickListener: (MasternodeKeyType) -> Unit
) : RecyclerView.Adapter<MasternodeKeyTypeViewHolder>() {

    val viewHolders = listOf(
        MasternodeKeyType.OWNER,
        MasternodeKeyType.VOTING,
        MasternodeKeyType.OPERATOR,
        MasternodeKeyType.PLATFORM,
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MasternodeKeyTypeViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = MasternodeKeyTypeRowBinding.inflate(inflater, parent, false)
        return MasternodeKeyTypeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MasternodeKeyTypeViewHolder, position: Int) {
        val type = MasternodeKeyType.fromCode(position)
        holder.bind(type, position == 0, position == 3)
        holder.binding.root.setOnClickListener { clickListener.invoke(type) }
    }

    override fun getItemCount(): Int {
        return viewHolders.size
    }

    override fun getItemId(position: Int): Long {
        return MasternodeKeyType.fromCode(position).type.toLong()
    }
}
