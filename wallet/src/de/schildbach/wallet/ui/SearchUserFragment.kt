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

import android.content.Intent
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.ChangeBounds
import androidx.transition.Transition
import androidx.transition.TransitionManager
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.Constants.USERNAME_MIN_LENGTH
import org.dash.wallet.common.data.entity.BlockchainState
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivitySearchDashpayProfileRootBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.onUserInteraction
import org.dash.wallet.common.util.safeNavigate

@AndroidEntryPoint
class SearchUserFragment : Fragment(R.layout.activity_search_dashpay_profile_root), OnItemClickListener, OnContactRequestButtonClickListener {
    private val dashPayViewModel: DashPayViewModel by viewModels()
    private val binding by viewBinding(ActivitySearchDashpayProfileRootBinding::bind)
    private val args by navArgs<SearchUserFragmentArgs>()
    private var handler: Handler = Handler()
    private lateinit var searchUserRunnable: Runnable
    private val adapter: UsernameSearchResultsAdapter = UsernameSearchResultsAdapter(this)
    private var query = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = binding.appBarLayout.toolbar
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        toolbar.title = getString(R.string.add_new_contact)

        binding.profile1.apply {
            searchResultsRv.layoutManager = LinearLayoutManager(requireContext())
            searchResultsRv.adapter = adapter
            adapter.itemClickListener = this@SearchUserFragment

            initViewModel()

            var setChanged = false
            val constraintSet1 = ConstraintSet()
            constraintSet1.clone(root)
            val constraintSet2 = ConstraintSet()
            constraintSet2.clone(requireContext(), R.layout.activity_search_dashpay_profile_2)

            search.doAfterTextChanged {
                it?.let {
                    requireActivity().onUserInteraction()
                    query = it.toString()
                    searchUser()
                }
            }

            search.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && !setChanged) {
                    val transition: Transition = ChangeBounds()
                    transition.addListener(object : Transition.TransitionListener {
                        override fun onTransitionEnd(transition: Transition) {
                            finalizeViewsTransition()
                            searchResultsRv.visibility = View.VISIBLE
                        }

                        override fun onTransitionResume(transition: Transition) {
                        }

                        override fun onTransitionPause(transition: Transition) {
                        }

                        override fun onTransitionCancel(transition: Transition) {
                        }

                        override fun onTransitionStart(transition: Transition) {
                            layoutTitle.visibility = View.GONE
                            findAUserLabel.visibility = View.GONE
                            inviteFriendHintViewDashpayProfile1.root.visibility = View.GONE
                        }

                    })
                    TransitionManager.beginDelayedTransition(root, transition)
                    constraintSet2.applyTo(root)
                    setChanged = true
                }
            }

            val initQuery = args.query
            if (!TextUtils.isEmpty(initQuery)) {
                constraintSet2.applyTo(root)
                setChanged = true
                layoutTitle.visibility = View.GONE
                findAUserLabel.visibility = View.GONE
                finalizeViewsTransition()
                search.setText(initQuery)
            }

            inviteFriendHintViewDashpayProfile1.root.setOnClickListener {
                startInviteFlow()
            }
            //TODO: remove this line when INVITES are supported
            inviteFriendHintViewDashpayProfile1.root.isVisible = Constants.SUPPORTS_INVITES
            userSearchEmptyResult.inviteFriendHintViewEmptyResult.root.setOnClickListener {
                startInviteFlow()
            }
            //TODO: remove this line when INVITES are supported
            userSearchEmptyResult.inviteFriendHintViewEmptyResult.root.isVisible = Constants.SUPPORTS_INVITES
        }

        binding.networkUnavailable.networkErrorSubtitle.setText(R.string.network_error_user_search)
    }

    private fun startInviteFlow() {
        lifecycleScope.launch {
            if (dashPayViewModel.getInviteCount() == 0) {
                safeNavigate(SearchUserFragmentDirections.searchUserToInviteFee("search_user"))
            } else {
                safeNavigate(SearchUserFragmentDirections.searchUserToInviteHistory("search_user"))
            }
        }
    }

    private fun finalizeViewsTransition() {
        binding.profile1.search.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val searchPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60f, resources.displayMetrics).toInt()
            setPadding(searchPadding, 0, 0, 0)
            typeface = ResourcesCompat.getFont(requireContext(), R.font.inter_semibold)
        }
    }

    private fun initViewModel() {
        dashPayViewModel.searchUsernamesLiveData.observe(viewLifecycleOwner) {
            onUserInteraction()
            if (Status.LOADING == it.status) {
                if (clearList) {
                    startLoading()
                }
            } else {
                if (clearList) {
                    stopLoading()
                }
                if (it.data != null) {
                    adapter.results = it.data
                    if (it.data.isEmpty()) {
                        showEmptyResult()
                    } else {
                        hideEmptyResult()
                    }
                    dashPayViewModel.reportUsernameSearchTime(it.data.size, query.length)
                } else {
                    showEmptyResult()
                }
            }
        }
        dashPayViewModel.sendContactRequestState.observe(viewLifecycleOwner) {
            onUserInteraction()
            adapter.sendContactRequestWorkStateMap = it
        }
        dashPayViewModel.blockchainStateData.observe(this) {
            it?.apply {
                val networkError = impediments.contains(BlockchainState.Impediment.NETWORK)
                updateNetworkErrorVisibility(networkError)
            }
        }
    }

    private fun updateNetworkErrorVisibility(networkError: Boolean) {
        binding.networkError.visibility = if (networkError) View.VISIBLE else View.GONE
        binding.networkUnavailable.root.visibility = if (networkError) View.VISIBLE else View.GONE
    }

    private fun startLoading() {
        binding.profile1.apply {
            val query = search.text.toString()
            hideEmptyResult()
            userSearchLoading.root.visibility = View.VISIBLE
            var loadingText = getString(R.string.search_user_loading)
            loadingText = loadingText.replace("%", "\"<b>$query</b>\"")
            userSearchLoading.searchLoadingLabel.text = HtmlCompat.fromHtml(loadingText,
                HtmlCompat.FROM_HTML_MODE_COMPACT)
            (userSearchLoading.searchLoadingIcon.drawable as AnimationDrawable).start()
        }
    }

    private fun stopLoading() {
        binding.profile1.apply {
            userSearchLoading.root.visibility = View.GONE
            (userSearchLoading.searchLoadingIcon.drawable as AnimationDrawable).start()
        }
    }

    private fun showEmptyResult() {
        binding.profile1.apply {
            userSearchEmptyResult.root.visibility = View.VISIBLE
            var emptyResultText = getString(R.string.search_user_no_results)
            emptyResultText += " \"<b>$query</b>\""
            userSearchEmptyResult.noResultsLabel.text = HtmlCompat.fromHtml(emptyResultText,
                HtmlCompat.FROM_HTML_MODE_COMPACT)
        }
    }

    private fun hideEmptyResult() {
        binding.profile1.userSearchEmptyResult.root.visibility = View.GONE
    }

    var clearList: Boolean = true

    private fun searchUser(clearList: Boolean = true) {
        this.clearList = clearList
        if (clearList) {
            adapter.results = listOf()
            hideEmptyResult()
        }
        if (this::searchUserRunnable.isInitialized) {
            handler.removeCallbacks(searchUserRunnable)
        }
        if (query.length >= USERNAME_MIN_LENGTH) {
            dashPayViewModel.startUsernameSearchTimer()
            searchUserRunnable = Runnable {
                dashPayViewModel.searchUsernames(query)
            }
            handler.postDelayed(searchUserRunnable, 500)
        }
    }

    override fun onItemClicked(view: View, usernameSearchResult: UsernameSearchResult) {
        startActivityForResult(DashPayUserActivity.createIntent(requireContext(), usernameSearchResult),
                DashPayUserActivity.REQUEST_CODE_DEFAULT)

        //overridePendingTransition(R.anim.slide_in_bottom, R.anim.activity_stay)
    }

//    override fun onOptionsItemSelected(item: MenuItem): Boolean {
//        when (item.itemId) {
//            android.R.id.home -> {
//                onBackPressed()
//                return true
//            }
//        }
//        return super.onOptionsItemSelected(item)
//    }

    override fun onAcceptRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
        dashPayViewModel.logEvent(AnalyticsConstants.UsersContacts.ACCEPT_REQUEST)
        // need to check balance
        dashPayViewModel.sendContactRequest(usernameSearchResult.fromContactRequest!!.userId)

        lifecycleScope.launch {
            val enough = dashPayViewModel.hasEnoughCredits()
            val shouldWarn = enough.isBalanceWarning()
            val isEmpty = enough.isBalanceWarning()

            if (shouldWarn || isEmpty) {
                val answer = AdaptiveDialog.create(
                    R.drawable.ic_warning_yellow_circle,
                    if (isEmpty) getString(R.string.credit_balance_empty_warning_title) else getString(R.string.credit_balance_low_warning_title),
                    if (isEmpty) getString(R.string.credit_balance_empty_warning_message) else getString(R.string.credit_balance_low_warning_message),
                    getString(R.string.credit_balance_button_maybe_later),
                    getString(R.string.credit_balance_button_buy)
                ).showAsync(requireActivity())

                if (answer == true) {
                    SendCoinsActivity.startBuyCredits(requireActivity())
                } else {
                    if (shouldWarn)
                        dashPayViewModel.sendContactRequest(usernameSearchResult.fromContactRequest!!.userId)
                }
            } else {
                dashPayViewModel.sendContactRequest(usernameSearchResult.fromContactRequest!!.userId)
            }
        }
    }

    override fun onIgnoreRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DashPayUserActivity.REQUEST_CODE_DEFAULT && resultCode == DashPayUserActivity.RESULT_CODE_CHANGED) {
            searchUser(false)
        }
    }
}
