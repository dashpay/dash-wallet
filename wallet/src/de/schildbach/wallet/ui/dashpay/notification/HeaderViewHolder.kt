/*
 * Copyright 2020 Dash Core Group.
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
package de.schildbach.wallet.ui.dashpay.notification

import de.schildbach.wallet.data.NotificationItem
import de.schildbach.wallet_test.databinding.NotificationHeaderRowBinding

@Deprecated("this may no longer be needed")
class HeaderViewHolder(val binding: NotificationHeaderRowBinding) :
        NotificationViewHolder(binding.root) {

    override fun bind(notificationItem: NotificationItem, vararg args: Any) {
        bind(args[0] as Int)
    }

    private fun bind(textResId: Int) {
        binding.apply {
            title.text = itemView.context.getString(textResId)
        }
    }
}