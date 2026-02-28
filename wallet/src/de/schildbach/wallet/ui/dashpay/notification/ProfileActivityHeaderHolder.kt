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

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import de.schildbach.wallet.data.NotificationItem
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ProfileActivityHeaderRowBinding

class ProfileActivityHeaderHolder(val binding: ProfileActivityHeaderRowBinding,
                                  val onFilterListener: OnFilterListener,
                                  private val fromStrangerQr: Boolean = false) :
        NotificationViewHolder(binding.root) {

    init {
        val adapter = ArrayAdapter.createFromResource(itemView.context, R.array.history_filter, R.layout.custom_spinner_item)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.historyFilter.adapter = adapter
        binding.historyFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
                onFilterListener.onFilter(Filter.values()[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun bind(notificationItem: NotificationItem, vararg args: Any) {
        bind(args[0] as Int)
    }

    private fun bind(textResId: Int) {
        if (fromStrangerQr) {
            binding.activityInfo.visibility = View.VISIBLE
        }
        itemView.apply {
            binding.title.text = context.getString(textResId)
            binding.activityInfo.setOnClickListener {
                val dialogBuilder = AlertDialog.Builder(itemView.context)
                dialogBuilder.setMessage(R.string.stranger_activity_disclaimer_text)
                dialogBuilder.setPositiveButton(android.R.string.ok, null)
                dialogBuilder.show()
            }
        }
    }

    interface OnFilterListener {
        fun onFilter(filter: Filter)
    }

    enum class Filter {
        ALL,
        INCOMING,
        OUTGOING
    }
}