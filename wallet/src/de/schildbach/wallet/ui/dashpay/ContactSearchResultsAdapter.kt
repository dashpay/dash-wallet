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

package de.schildbach.wallet.ui.dashpay

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.ContactSuggestionViewHolder
import de.schildbach.wallet.ui.ContactViewHolder
import de.schildbach.wallet.ui.OnContactRequestButtonClickListener
import de.schildbach.wallet.ui.OnItemClickListener
import de.schildbach.wallet.util.PlatformUtils
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ContactHeaderRowBinding
import de.schildbach.wallet_test.databinding.ContactRequestHeaderRowBinding
import de.schildbach.wallet_test.databinding.ContactsSuggestionsHeaderBinding
import de.schildbach.wallet_test.databinding.DashpayContactRowBinding
import de.schildbach.wallet_test.databinding.DashpayContactSuggestionRowBinding
import de.schildbach.wallet_test.databinding.NoContactsResultsBinding


class ContactSearchResultsAdapter(private val listener: Listener,
                                  private val onViewAllRequestsListener: () -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var query = ""

    companion object {
        const val CONTACT_REQUEST_HEADER = 0
        const val CONTACT_HEADER = 2
        const val CONTACT = 3
        const val CONTACT_NO_RESULTS = 4
        const val CONTACTS_SUGGESTIONS_HEADER = 5
        const val CONTACT_SUGGESTION_ROW = 6
    }

    class ViewItem(val usernameSearchResult: UsernameSearchResult?, val viewType: Int, val sortOrder: Int = 0, val requestCount: Int = 0)

    init {
        setHasStableIds(true)
    }

    var itemClickListener: OnItemClickListener? = null
    var results: ArrayList<ViewItem> = arrayListOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var networkAvailable = true
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            CONTACT_REQUEST_HEADER -> {
                val binding = ContactRequestHeaderRowBinding.inflate(inflater, parent, false)
                ContactRequestHeaderViewHolder(binding)
            }
            CONTACT_HEADER -> {
                val binding = ContactHeaderRowBinding.inflate(inflater, parent, false)
                ContactHeaderViewHolder(binding)
            }
            CONTACT -> {
                val binding = DashpayContactRowBinding.inflate(inflater, parent, false)
                ContactViewHolder(binding, useFriendsIcon = false)
            }
            CONTACT_NO_RESULTS -> {
                val binding = NoContactsResultsBinding.inflate(inflater)
                ContactsNoResultsViewHolder(binding)
            }
            CONTACTS_SUGGESTIONS_HEADER -> {
                val binding = ContactsSuggestionsHeaderBinding.inflate(inflater, parent, false)
                ContactsSuggestionsHeaderViewHolder(binding)
            }
            CONTACT_SUGGESTION_ROW -> {
                val binding = DashpayContactSuggestionRowBinding.inflate(inflater)
                ContactSuggestionViewHolder(binding, isSuggestion = true)
            }
            else -> throw IllegalArgumentException("Invalid viewType $viewType")
        }
    }

    override fun getItemCount(): Int {
        return results.size
    }

    var sendContactRequestWorkStateMap: Map<String, Resource<WorkInfo>> = mapOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemId(position: Int): Long {
        val item = results[position]
        return when (item.viewType) {
            CONTACT -> {
                if (item.usernameSearchResult!!.type == UsernameSearchResult.Type.CONTACT_ESTABLISHED) {
                    PlatformUtils.longHashFromEncodedString(item.usernameSearchResult.toContactRequest!!.toUserId)
                } else {
                    PlatformUtils.longHashFromEncodedString(item.usernameSearchResult.fromContactRequest!!.userId)
                }
            }
            CONTACT_REQUEST_HEADER -> 1L
            CONTACT_HEADER -> 2L
            CONTACT_NO_RESULTS -> 3L
            CONTACTS_SUGGESTIONS_HEADER -> 4L
            CONTACT_SUGGESTION_ROW -> PlatformUtils.longHashFromEncodedString(item.usernameSearchResult!!.dashPayProfile.userId)
            else -> throw IllegalArgumentException("Invalid viewType ${item.viewType}")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = results[position]
        when (item.viewType) {
            CONTACT -> {
                val sendContactRequestWorkState = sendContactRequestWorkStateMap[item.usernameSearchResult!!.dashPayProfile.userId]
                (holder as ContactViewHolder).bind(item.usernameSearchResult, sendContactRequestWorkState,
                        itemClickListener, listener, networkAvailable)
            }
            CONTACT_REQUEST_HEADER -> (holder as ContactRequestHeaderViewHolder).bind(results[position].requestCount)
            CONTACT_HEADER -> (holder as ContactHeaderViewHolder).bind()
            CONTACT_NO_RESULTS -> (holder as ContactsNoResultsViewHolder).bind()
            CONTACTS_SUGGESTIONS_HEADER -> (holder as ContactsSuggestionsHeaderViewHolder).bind(query)
            CONTACT_SUGGESTION_ROW -> (holder as ContactSuggestionViewHolder).bind(item.usernameSearchResult!!, null, itemClickListener, listener)
            else -> throw IllegalArgumentException("Invalid viewType ${item.viewType}")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any?>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.itemView.alpha = 1f
        }
    }

    override fun getItemViewType(position: Int): Int {
        return results[position].viewType
    }

    inner class ContactHeaderViewHolder(val binding: ContactHeaderRowBinding) :
            RecyclerView.ViewHolder(binding.root) {

        var direction = UsernameSortOrderBy.DISPLAY_NAME
        fun bind() {
            var firstTime = true
            binding.apply {
                val adapter = ArrayAdapter.createFromResource(sortFilter.context, R.array.contacts_sort, R.layout.custom_spinner_item)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                sortFilter.adapter = adapter
                sortFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
                        if (firstTime) {
                            // don't process this the first time (during loading)
                            firstTime = false
                            return
                        }
                        when (position) {
                            0 -> direction = UsernameSortOrderBy.DISPLAY_NAME
                            1 -> direction = UsernameSortOrderBy.USERNAME
                            2 -> direction = UsernameSortOrderBy.DATE_ADDED
                            3 -> direction = UsernameSortOrderBy.LAST_ACTIVITY
                            else -> UsernameSortOrderBy.DISPLAY_NAME
                        }
                        firstTime = false
                        //searchContacts(direction)

                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
                sortFilter.setSelection(direction.ordinal, false)
            }
        }
    }

    inner class ContactRequestHeaderViewHolder(val binding: ContactRequestHeaderRowBinding) :
            RecyclerView.ViewHolder(binding.root) {

        fun bind(requestCount: Int) {
            binding.apply {
                contactRequestHeader.text = itemView.resources.getString(R.string.contacts_contact_requests_count, requestCount)

                viewAllContacts.isVisible = requestCount > 3
                viewAllContacts.setOnClickListener {
                    onViewAllRequestsListener.invoke()
                }
            }
        }
    }

    inner class ContactsNoResultsViewHolder(val binding: NoContactsResultsBinding) :
            RecyclerView.ViewHolder(binding.root) {

        fun bind() {
            binding.searchForUserSuggestions.setOnClickListener {
                listener.onSearchUser()
            }
        }
    }

    inner class ContactsSuggestionsHeaderViewHolder(val binding: ContactsSuggestionsHeaderBinding) :
            RecyclerView.ViewHolder(binding.root) {

        fun bind(query: String) {
            val suggestionsSubtitle = itemView.context.getString(R.string.users_that_matches) + " \"<b>$query</b>\" " +
                    itemView.context.getString(R.string.not_in_your_contacts)
            binding.suggestionsSubtitle.text = HtmlCompat.fromHtml(suggestionsSubtitle, HtmlCompat.FROM_HTML_MODE_COMPACT)
        }
    }

    interface Listener : OnContactRequestButtonClickListener {
        fun onSortOrderChanged(direction: UsernameSortOrderBy)
        fun onSearchUser()
    }

    fun searchContacts(direction: UsernameSortOrderBy) {
        listener.onSortOrderChanged(direction)
    }
}
