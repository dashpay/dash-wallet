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

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.view.*
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.*
import de.schildbach.wallet.ui.main.MainViewModel
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ARG_SOURCE
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet.ui.util.InputParser
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentContactsRootBinding
import kotlinx.coroutines.launch
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.observeOnDestroy
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate

enum class ContactsScreenMode {
    SEARCH_CONTACTS,
    SELECT_CONTACT,
    VIEW_REQUESTS
}

@AndroidEntryPoint
class ContactsFragment : Fragment(),
        ContactSearchResultsAdapter.Listener,
        OnItemClickListener {

    private val binding by viewBinding(FragmentContactsRootBinding::bind)
    private val dashPayViewModel by viewModels<DashPayViewModel>()
    private val mainViewModel by activityViewModels<MainViewModel>()
    private var searchHandler: Handler = Handler()
    private lateinit var searchContactsRunnable: Runnable
    private lateinit var contactsAdapter: ContactSearchResultsAdapter
    private var query = ""
    private var direction = UsernameSortOrderBy.USERNAME
    private val args by navArgs<ContactsFragmentArgs>()
    private var initialSearch = true
    private var searchEventSent = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        if (!mainViewModel.hasIdentity) {
            safeNavigate(ContactsFragmentDirections.contactsToEvoUpgrade())
            return null
        }

        return inflater.inflate(R.layout.fragment_contacts_root, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        enterTransition = MaterialFadeThrough()
        binding.appBar.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        if (args.mode == ContactsScreenMode.SEARCH_CONTACTS) {
            binding.appBar.toolbar.inflateMenu(R.menu.contacts_menu)
        }

        binding.appBar.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.contacts_add_contact -> {
                    dashPayViewModel.logEvent(AnalyticsConstants.UsersContacts.SEARCH_USER_ICON)
                    searchUser()
                    true
                }
                else -> false
            }
        }

        contactsAdapter = ContactSearchResultsAdapter(this) {
            // TODO: this is weird. Better to have another fragment to display all requests.
            safeNavigate(ContactsFragmentDirections.contactsToContacts(mode = ContactsScreenMode.VIEW_REQUESTS))
        }

        binding.contactList.apply {
            contactsRv.layoutManager = LinearLayoutManager(requireContext())
            contactsRv.adapter = contactsAdapter
            viewLifecycleOwner.observeOnDestroy {
                contactsRv.adapter = null
            }
            contactsAdapter.itemClickListener = this@ContactsFragment

            initViewModel()

            when (args.mode) {
                ContactsScreenMode.VIEW_REQUESTS -> {
                    search.visibility = View.GONE
                    icon.visibility = View.GONE
                    binding.appBar.toolbar.title = getString(R.string.contact_requests_title)
                }
                ContactsScreenMode.SEARCH_CONTACTS -> {
                    // search should be available for all other modes
                    search.doAfterTextChanged { afterSearchTextChanged(it) }
                    search.visibility = View.VISIBLE
                    icon.visibility = View.VISIBLE
                    binding.appBar.toolbar.title = getString(R.string.contacts_title)
                }
                ContactsScreenMode.SELECT_CONTACT -> {
                    search.doAfterTextChanged { afterSearchTextChanged(it) }
                    search.visibility = View.VISIBLE
                    icon.visibility = View.VISIBLE
                    binding.appBar.toolbar.title = getString(R.string.contacts_send_to_contact_title)
                }
            }

            emptyStatePane.searchForUser.setOnClickListener {
                dashPayViewModel.logEvent(AnalyticsConstants.UsersContacts.SEARCH_DASH_NETWORK)
                searchUser()
            }

            searchContacts()

            // Developer Mode Feature
            // Hide the invite UI
            emptyStatePane.inviteHintLayout.root.isVisible = de.schildbach.wallet.Constants.SUPPORTS_INVITES
            emptyStatePane.inviteHintLayout.inviteFriendHint.setOnClickListener {
                dashPayViewModel.logEvent(AnalyticsConstants.UsersContacts.INVITE_CONTACTS)

                lifecycleScope.launch {
                    if (dashPayViewModel.getInviteCount() == 0) {
                        safeNavigate(ContactsFragmentDirections.contactsToInviteFee())
                    } else {
                        safeNavigate(ContactsFragmentDirections.contactsToInviteHistory("contacts"))
                    }
                }
            }

            networkErrorLayout.networkErrorSubtitle.setText(R.string.network_error_contact_suggestions)
        }

        binding.root.updatePadding(bottom =
            if (args.ShowNavBar) {
                resources.getDimensionPixelOffset(R.dimen.bottom_nav_bar_height)
            } else {
                0
            }
        )

        lifecycleScope.launch {
            mainViewModel.dismissUsernameCreatedCardIfDone()
        }
    }

    private fun showEmptyPane() {
        binding.contactList.suggestionsSearchNoResult.isVisible = true
    }

    private fun initViewModel() {
        dashPayViewModel.searchContactsLiveData.observe(viewLifecycleOwner) {
            imitateUserInteraction()
            if (Status.SUCCESS == it.status) {
                if (initialSearch && query.isEmpty() && (args.mode != ContactsScreenMode.VIEW_REQUESTS)) {
                    if (it.data == null || it.data.isEmpty() || it.data.find { u -> u.requestReceived } == null) {
                        binding.contactList.apply {
                            emptyStatePane.root.isVisible = true
                            search.isVisible = false
                            icon.isVisible = false
                        }
                    } else {
                        binding.contactList.apply {
                            search.isVisible = true
                            icon.isVisible = true
                            emptyStatePane.root.isVisible = false
                        }
                    }
                    initialSearch = false
                }
                if (it.data != null) {
                    processResults(it.data)
                }
            }
        }

        dashPayViewModel.sendContactRequestState.observe(viewLifecycleOwner) {
            imitateUserInteraction()
            contactsAdapter.sendContactRequestWorkStateMap = it
        }
        dashPayViewModel.contactsUpdatedLiveData.observe(viewLifecycleOwner) {
            if (it?.data != null && it.data) {
                searchContacts()
            }
        }
        dashPayViewModel.searchUsernamesLiveData.observe(viewLifecycleOwner) {
            if (it.data != null && it.data.isNotEmpty()) {
                showSuggestedUsers(it.data)
            } else {
                showSuggestedUsers(null)
            }
        }
        dashPayViewModel.blockchainStateData.observe(viewLifecycleOwner) {
            it?.apply {
                val networkError = impediments.contains(BlockchainState.Impediment.NETWORK)
                updateNetworkErrorVisibility(networkError)
                contactsAdapter.networkAvailable = !networkError
            }
        }
    }

    private fun updateNetworkErrorVisibility(networkError: Boolean) {
        binding.contactList.networkErrorContainer.isVisible = networkError
    }

    private fun showSuggestedUsers(users: List<UsernameSearchResult>?) {
        val results = contactsAdapter.results

        if (results.isEmpty()) {
            results.add(ContactSearchResultsAdapter.ViewItem(null,
                    ContactSearchResultsAdapter.CONTACT_NO_RESULTS))
        }

        if (users != null) {
            results.add(ContactSearchResultsAdapter.ViewItem(null,
                    ContactSearchResultsAdapter.CONTACTS_SUGGESTIONS_HEADER))
            for (user in users) {
                results.add(ContactSearchResultsAdapter.ViewItem(user,
                        ContactSearchResultsAdapter.CONTACT_SUGGESTION_ROW))
            }
        }

        contactsAdapter.results = results
    }

    private fun showEmptySuggestions() {
        binding.contactList.suggestionsSearchNoResult.isVisible = true
        KeyboardUtil.hideKeyboard(requireContext(), requireView())
        val searchUsersBtn = binding.contactList.suggestionsSearchNoResult.findViewById<View>(R.id.search_for_user_suggestions)
        searchUsersBtn.setOnClickListener {
            dashPayViewModel.logEvent(AnalyticsConstants.UsersContacts.SEARCH_DASH_NETWORK)
            searchUser()
        }
        val text = getString(R.string.suggestions_empty_result_part_1) +
                " \"<b>$query</b>\" " + getString(R.string.suggestions_empty_result_part_2)
        val noResultsLabel = binding.contactList.suggestionsSearchNoResult.findViewById<TextView>(R.id.no_results_label)
        noResultsLabel.text = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_COMPACT)
    }

    override fun onResume() {
        super.onResume()
        dashPayViewModel.updateDashPayState()
    }

    private fun processResults(data: List<UsernameSearchResult>) {
        val results = ArrayList<ContactSearchResultsAdapter.ViewItem>()
        // process the requests
        val requests = if (args.mode != ContactsScreenMode.SELECT_CONTACT) {
            data.filter { r -> r.isPendingRequest }.toMutableList()
        } else {
            ArrayList()
        }

        val requestCount = requests.size
        if (args.mode != ContactsScreenMode.VIEW_REQUESTS) {
            while (requests.size > 3) {
                requests.remove(requests[requests.size - 1])
            }
        }

        if (requests.isNotEmpty() && args.mode == ContactsScreenMode.SEARCH_CONTACTS) {
            results.add(ContactSearchResultsAdapter.ViewItem(null, ContactSearchResultsAdapter.CONTACT_REQUEST_HEADER, requestCount = requestCount))
            requests.forEach { r -> results.add(ContactSearchResultsAdapter.ViewItem(r, ContactSearchResultsAdapter.CONTACT)) }
        } else if (args.mode == ContactsScreenMode.VIEW_REQUESTS) {
            requests.forEach { r -> results.add(ContactSearchResultsAdapter.ViewItem(r, ContactSearchResultsAdapter.CONTACT)) }
        }
        // process contacts
        val contacts = if (args.mode != ContactsScreenMode.VIEW_REQUESTS)
            data.filter { r -> r.requestSent && r.requestReceived }
        else ArrayList()

        if (contacts.isNotEmpty() && args.mode != ContactsScreenMode.VIEW_REQUESTS) {
            results.add(ContactSearchResultsAdapter.ViewItem(null, ContactSearchResultsAdapter.CONTACT_HEADER))
            contacts.forEach { r -> results.add(ContactSearchResultsAdapter.ViewItem(r, ContactSearchResultsAdapter.CONTACT)) }
        }
        contactsAdapter.results = results
    }

    override fun onSortOrderChanged(direction: UsernameSortOrderBy) {
        this.direction = direction
        searchContacts()
    }

    override fun onSearchUser() {
        dashPayViewModel.logEvent(AnalyticsConstants.UsersContacts.SEARCH_DASH_NETWORK)
        searchUser()
    }

    private fun searchUser() {
        safeNavigate(ContactsFragmentDirections.contactsToSearchUser())
        //startActivity(SearchUserActivity.createIntent(requireContext(), query))
    }

    private fun searchContacts() {
        binding.contactList.suggestionsSearchNoResult.isVisible = false

        if (this::searchContactsRunnable.isInitialized) {
            searchHandler.removeCallbacks(searchContactsRunnable)
        }

        searchContactsRunnable = Runnable {
            contactsAdapter.query = query
            dashPayViewModel.searchContacts(query, direction)
            if (args.mode == ContactsScreenMode.SEARCH_CONTACTS) {
                if (!initialSearch && query.isNotEmpty()) {
                    dashPayViewModel.searchUsernames(query, limit = 3, removeContacts = true)
                }
            }
        }
        searchHandler.postDelayed(searchContactsRunnable, 500)

        if (!searchEventSent && query.isNotEmpty()) {
            searchEventSent = true
            dashPayViewModel.logEvent(AnalyticsConstants.UsersContacts.SEARCH_CONTACTS)
        }
    }

    private fun afterSearchTextChanged(s: Editable?) {
        s?.let {
            query = it.toString()
            searchContacts()
        }
    }

    private fun imitateUserInteraction() {
        (requireActivity() as LockScreenActivity).imitateUserInteraction()
    }

    override fun onItemClicked(view: View, usernameSearchResult: UsernameSearchResult) {
        when (args.mode) {
            ContactsScreenMode.SEARCH_CONTACTS, ContactsScreenMode.VIEW_REQUESTS -> {
                startActivity(DashPayUserActivity.createIntent(requireContext(), usernameSearchResult))
            }
            ContactsScreenMode.SELECT_CONTACT -> {
                handleString(usernameSearchResult.toContactRequest!!.toUserId, true, R.string.scan_to_pay_username_dialog_message)
            }
        }
    }

    override fun onAcceptRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
        dashPayViewModel.logEvent(AnalyticsConstants.UsersContacts.ACCEPT_REQUEST)
        dashPayViewModel.sendContactRequest(usernameSearchResult.fromContactRequest!!.userId)
    }

    override fun onIgnoreRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DashPayUserActivity.REQUEST_CODE_DEFAULT && resultCode == DashPayUserActivity.RESULT_CODE_CHANGED) {
            searchContacts()
        }
    }

    private fun handleString(input: String, fireAction: Boolean, errorDialogTitleResId: Int) {
        object : InputParser.StringInputParser(input, true) {

            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                if (fireAction) {
                    paymentIntent.source = arguments?.getString(ARG_SOURCE) ?: ""
                    SendCoinsActivity.start(requireContext(), null, paymentIntent, true)
                } else {

                }
            }

            override fun error(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                if (fireAction) {
                    val dialog = AdaptiveDialog.create(
                        R.drawable.ic_error,
                        getString(errorDialogTitleResId),
                        if (messageArgs.isNotEmpty()) {
                            getString(messageResId, messageArgs)
                        } else {
                            getString(messageResId)
                        },
                        getString(R.string.button_close),
                        null
                    )
                    dialog.isMessageSelectable = true
                    dialog.show(requireActivity())
                } else {
                    // pass
                }
            }

            override fun handlePrivateKey(key: PrefixedChecksummedBytes) {
                // ignore
            }

            @Throws(VerificationException::class)
            override fun handleDirectTransaction(tx: Transaction) {
                // ignore
            }
        }.parse()
    }
}
