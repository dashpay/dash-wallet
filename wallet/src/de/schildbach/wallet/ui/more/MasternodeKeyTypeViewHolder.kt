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

import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.MasternodeKeyTypeRowBinding

class MasternodeKeyTypeViewHolder(val binding: MasternodeKeyTypeRowBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(type: MasternodeKeyType, isTop: Boolean, isBottom: Boolean) {
        when (type) {
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
            itemView.context.getString(R.string.masternode_key_type_total, 5)
        binding.keyTypeUsedKeys.text = itemView.context.getString(R.string.masternode_key_type_used, 3)
    }
}
