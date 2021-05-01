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

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.util.getDateAtHourZero
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.transaction_group_header.view.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class TransactionGroupHeaderViewHolder(inflater: LayoutInflater, parent: ViewGroup)
    : RecyclerView.ViewHolder(inflater.inflate(R.layout.transaction_group_header, parent, false)) {

    fun bind(date: Date) {
        val bigSize = itemView.context.resources.getDimensionPixelSize(R.dimen.date_text_big)
        val smallSize = itemView.context.resources.getDimensionPixelSize(R.dimen.date_text_small)

        val isToday = Date().getDateAtHourZero().time == date.time
        val now = Calendar.getInstance()
        val isYesterday = !isToday && now.time.time - date.time < TimeUnit.DAYS.toMillis(2)
        when {
            isToday -> {
                this.itemView.date_title.setText(R.string.today)
                this.itemView.date_title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }
            isYesterday -> {
                this.itemView.date_title.setText(R.string.yesterday)
                this.itemView.date_title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }
            else -> {
                this.itemView.date_title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                val cal = Calendar.getInstance()
                cal.time = date
                val sdf = if (now.get(Calendar.YEAR) == cal.get(Calendar.YEAR)) {
                    SimpleDateFormat("MMMM", Locale.getDefault())
                } else {
                    SimpleDateFormat("MMMM, yyyy", Locale.getDefault())
                }
                sdf.timeZone = TimeZone.getTimeZone("GMT")

                val dateStr = sdf.format(date)
                val dayStr = SimpleDateFormat("dd", Locale.getDefault()).format(date)
                val span1 = SpannableString(dayStr)
                span1.setSpan(AbsoluteSizeSpan(bigSize), 0, dayStr.length, SPAN_INCLUSIVE_INCLUSIVE)
                span1.setSpan(StyleSpan(Typeface.BOLD), 0, dayStr.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                val span2 = SpannableString(dateStr)
                span2.setSpan(AbsoluteSizeSpan(smallSize), 0, dateStr.length, SPAN_INCLUSIVE_INCLUSIVE)

                this.itemView.date_title.text = TextUtils.concat(span1, " ", span2)

            }

        }
        val sdf = SimpleDateFormat("EEEE", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        this.itemView.date_weekday.text = sdf.format(date)
    }

}