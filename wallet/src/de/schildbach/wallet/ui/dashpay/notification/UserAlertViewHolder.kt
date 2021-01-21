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

import android.view.LayoutInflater
import android.view.ViewGroup
import de.schildbach.wallet.data.NotificationItem
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.notification_alert_item.view.*

class UserAlertViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
        NotificationViewHolder(R.layout.notification_alert_item, inflater, parent) {

    override fun bind(notificationItem: NotificationItem, vararg args: Any) {
        bind(args[0] as Int, args[1] as Int)
    }

    private fun bind(textResId: Int, imageResId: Int) {
        itemView.apply {
            this.text.setText(textResId)
            this.icon.setImageResource(imageResId)
        }
    }

}