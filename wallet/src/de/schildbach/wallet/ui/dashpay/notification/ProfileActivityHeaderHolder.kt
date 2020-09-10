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
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import de.schildbach.wallet.data.NotificationItem
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.notification_header_row.view.title
import kotlinx.android.synthetic.main.profile_activity_header_row.view.*

class ProfileActivityHeaderHolder(inflater: LayoutInflater, parent: ViewGroup,
                                  private val fromStrangerQr: Boolean = false) :
        NotificationViewHolder(R.layout.profile_activity_header_row, inflater, parent) {

    override fun bind(notificationItem: NotificationItem, vararg args: Any) {
        bind(args[0] as Int)
    }

    private fun bind(textResId: Int) {
        if (fromStrangerQr) {
            itemView.activity_info.visibility = View.VISIBLE
        }
        itemView.apply {
            title.text = context.getString(textResId)
        }
        itemView.activity_info.setOnClickListener {
            val dialogBuilder = AlertDialog.Builder(itemView.context)
            dialogBuilder.setMessage(R.string.stranger_activity_disclaimer_text)
            dialogBuilder.setPositiveButton(android.R.string.ok, null)
            dialogBuilder.show()
        }
    }
}