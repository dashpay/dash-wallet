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

import android.text.TextUtils
import de.schildbach.wallet.ui.main.HistoryViewHolder
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionGroupHeaderBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class TransactionGroupHeaderViewHolder(
    val binding: TransactionGroupHeaderBinding
): HistoryViewHolder(binding.root) {
    fun bind(date: LocalDate) {
        val now = LocalDate.now()
        val isToday = now == date
        val isYesterday = !isToday && date == now.minusDays(1)

        when {
            isToday -> binding.dateTitle.setText(R.string.today)
            isYesterday -> binding.dateTitle.setText(R.string.yesterday)
            else -> {
                val formatter = if (now.year == date.year) {
                    DateTimeFormatter.ofPattern("MMMM")
                } else {
                    DateTimeFormatter.ofPattern("MMMM, yyyy")
                }

                val dateStr = formatter.format(date)
                val dayStr = DateTimeFormatter.ofPattern("dd").format(date)

                binding.dateTitle.text = TextUtils.concat(dayStr, " ", dateStr)
            }
        }

        val formatter = DateTimeFormatter.ofPattern("EEEE")
        binding.dateWeekday.text = formatter.format(date)
    }
}
