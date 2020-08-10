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
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.notification_contact_added_row.view.*

class ContactViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
        NotificationViewHolder(R.layout.notification_contact_added_row, inflater, parent) {

    override fun bind(usernameSearchResult: UsernameSearchResult, isNew: Boolean) {
        super.bind(usernameSearchResult, isNew)

        itemView.apply {
            if (usernameSearchResult.requestSent && usernameSearchResult.requestReceived) {
                contact_added.visibility = View.VISIBLE
            } else {
                contact_added.visibility = View.GONE
            }
        }
    }
}