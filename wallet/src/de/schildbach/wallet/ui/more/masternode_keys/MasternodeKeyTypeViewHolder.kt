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

import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.MasternodeKeyTypeRowBinding

class MasternodeKeyTypeViewHolder(val binding: MasternodeKeyTypeRowBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(data: MasternodeKeyTypeInfo, isTop: Boolean, isBottom: Boolean) {
        when (data.type) {
            MasternodeKeyType.OWNER -> {
                binding.keyTypeName.setText(R.string.masternode_key_type_owner)
            }
            MasternodeKeyType.VOTING -> {
                binding.keyTypeName.setText(R.string.masternode_key_type_voting)
            }
            MasternodeKeyType.OPERATOR -> {
                binding.keyTypeName.setText(R.string.masternode_key_type_operator)
            }
            MasternodeKeyType.PLATFORM -> {
                binding.keyTypeName.setText(R.string.masternode_key_type_platform)
            }
        }
        binding.keyTypeTotalCount.text =
            itemView.context.getString(R.string.masternode_key_type_total, data.totalKeys)
        binding.keyTypeUsedKeys.text = itemView.context.getString(R.string.masternode_key_type_used, data.usedKeys)
        when {
            isTop -> binding.root.setBackgroundResource(R.drawable.top_round_corners_white_bg)
            isBottom -> binding.root.setBackgroundResource(R.drawable.bottom_round_corners_white_bg)
            else -> binding.root.setBackgroundColor(itemView.context.resources.getColor(R.color.white))
        }
    }
}
