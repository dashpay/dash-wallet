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
import de.schildbach.wallet.data.NotificationItemUserAlert
import de.schildbach.wallet_test.databinding.NotificationAlertItemBinding

class UserAlertViewHolder(val binding: NotificationAlertItemBinding) :
        NotificationViewHolder(binding.root) {

    override fun bind(notificationItem: NotificationItem, vararg args: Any) {
        bind(notificationItem as NotificationItemUserAlert, args[0] as (Int) -> Unit)
    }

    private fun bind(notificationItem: NotificationItemUserAlert, onUserAlertDismiss: (alertId: Int) -> Unit) {
        binding.apply {
            text.setText(notificationItem.stringResId)
            icon.setImageResource(notificationItem.iconResId)
            closeBtn.setOnClickListener {
                onUserAlertDismiss.invoke(notificationItem.stringResId)
            }
        }
    }
}