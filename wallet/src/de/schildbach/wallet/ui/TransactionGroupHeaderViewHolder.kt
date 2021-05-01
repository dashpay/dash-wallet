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

package de.schildbach.wallet.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.transaction_group_header.view.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class TransactionGroupHeaderViewHolder(inflater: LayoutInflater, parent: ViewGroup)
    : RecyclerView.ViewHolder(inflater.inflate(R.layout.transaction_group_header, parent, false)) {

    fun bind(date: Date) {
        val isToday = DateUtils.isToday(date.time)
        val now = Calendar.getInstance()
        val isYesterday = now.time.time - date.time < TimeUnit.DAYS.toMillis(2)
        when {
            isToday -> {
                this.itemView.date_title.setText(R.string.today)
            }
            isYesterday -> {
                this.itemView.date_title.setText(R.string.yesterday)
            }
            else -> {
                val cal = Calendar.getInstance()
                cal.time = date
                var sdf = if (now.get(Calendar.YEAR) == cal.get(Calendar.YEAR)) {
                    SimpleDateFormat("dd MMMM", Locale.getDefault())
                } else {
                    SimpleDateFormat("dd MMMM, yyyy", Locale.getDefault())
                }
                sdf.timeZone = TimeZone.getTimeZone("GMT")
                val dateStr = sdf.format(date)
                this.itemView.date_title.text = dateStr
                sdf = SimpleDateFormat("EEEE", Locale.getDefault())
                this.itemView.date_weekday.text = sdf.format(date)
            }
        }
    }

}