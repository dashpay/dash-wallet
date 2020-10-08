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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.transactions_header_row.view.*

class TransactionsHeaderViewHolder(inflater: LayoutInflater, parent: ViewGroup, val onFilterListener: OnFilterListener)
    : RecyclerView.ViewHolder(inflater.inflate(R.layout.transactions_header_row, parent, false)) {

    init {
        val adapter = ArrayAdapter.createFromResource(itemView.context, R.array.history_filter, R.layout.custom_spinner_item)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        itemView.history_filter.adapter = adapter
        itemView.history_filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
                onFilterListener.onFilter(Filter.values()[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    fun showEmptyState(show: Boolean) {
        itemView.empty_view.visibility = if (show) View.VISIBLE else View.GONE
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