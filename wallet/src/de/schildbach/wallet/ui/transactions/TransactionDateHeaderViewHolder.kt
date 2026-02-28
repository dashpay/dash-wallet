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

package de.schildbach.wallet.ui.transactions

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import de.schildbach.wallet.ui.main.HistoryViewHolder
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionDateHeaderBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class TransactionDateHeaderViewHolder(
    val binding: TransactionDateHeaderBinding
): HistoryViewHolder(binding.root) {
    fun bind(date: LocalDate) {
        val bigSize = itemView.context.resources.getDimensionPixelSize(R.dimen.date_text_big)
        val smallSize = itemView.context.resources.getDimensionPixelSize(R.dimen.date_text_small)

        val now = LocalDate.now()
        val isToday = now == date
        val isYesterday = !isToday && date == now.minusDays(1)

        when {
            isToday -> {
                binding.dateTitle.setText(R.string.today)
                binding.dateTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }
            isYesterday -> {
                binding.dateTitle.setText(R.string.yesterday)
                binding.dateTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }
            else -> {
                binding.dateTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                val formatter = if (now.year == date.year) {
                    DateTimeFormatter.ofPattern("MMMM")
                } else {
                    DateTimeFormatter.ofPattern("MMMM, yyyy")
                }

                val dateStr = formatter.format(date)
                val dayStr = DateTimeFormatter.ofPattern("dd").format(date)
                val span1 = SpannableString(dayStr)
                span1.setSpan(AbsoluteSizeSpan(bigSize), 0, dayStr.length, SPAN_INCLUSIVE_INCLUSIVE)
                span1.setSpan(StyleSpan(Typeface.BOLD), 0, dayStr.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                val span2 = SpannableString(dateStr)
                span2.setSpan(AbsoluteSizeSpan(smallSize), 0, dateStr.length, SPAN_INCLUSIVE_INCLUSIVE)

                binding.dateTitle.text = TextUtils.concat(span1, " ", span2)
            }
        }
        val formatter = DateTimeFormatter.ofPattern("EEEE")
        binding.dateWeekday.text = formatter.format(date)
    }
}