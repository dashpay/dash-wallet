/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.ui.username

import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.database.entity.UsernameVote
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.work.BroadcastUsernameVotesWorker
import de.schildbach.wallet.ui.main.HistoryRowView
import de.schildbach.wallet.ui.username.adapters.UsernameRequestGroupAdapter
import de.schildbach.wallet.ui.username.adapters.UsernameRequestGroupView
import de.schildbach.wallet.ui.username.adapters.UsernameRequestRowView
import de.schildbach.wallet.ui.username.utils.votingViewModels
import de.schildbach.wallet.ui.username.voting.OneVoteLeftDialogFragment
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentUsernameRequestsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import org.dashj.platform.dpp.voting.AbstainVoteChoice
import org.dashj.platform.dpp.voting.LockVoteChoice
import org.dashj.platform.dpp.voting.ResourceVoteChoice
import org.dashj.platform.dpp.voting.TowardsIdentity
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId

@AndroidEntryPoint
class UsernameRequestsFragment : Fragment(R.layout.fragment_username_requests) {
    companion object {
        private val log = LoggerFactory.getLogger(UsernameRequestsFragment::class.java)
    }
    private val viewModel by votingViewModels<UsernameRequestsViewModel>()
    private val binding by viewBinding(FragmentUsernameRequestsBinding::bind)
    private var itemList = listOf<UsernameRequestGroupView>()
    private lateinit var keyboardUtil: KeyboardUtil
    private var isShowingFirstTimeDialog = false
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = this.binding
        // TODO: remove when development is complete
//        binding.toolbar.setOnClickListener {
//            viewModel.prepopulateList()
//        }

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.filterBtn.setOnClickListener {
            safeNavigate(UsernameRequestsFragmentDirections.requestsToFilters())
        }
        val adapter = UsernameRequestGroupAdapter(
            { request ->
                lifecycleScope.launch {
                    if (request.requestId != "") {
                        viewModel.logEvent(AnalyticsConstants.UsernameVoting.DETAILS)
                        val votingStartDate = withContext(Dispatchers.IO) {
                            viewModel.getVotingStartDate(request.normalizedLabel)
                        }
                        safeNavigate(UsernameRequestsFragmentDirections.requestsToDetails(request.requestId, votingStartDate))
                    } else {
                        performVote(request)
                    }
                }
            },
            { request -> performVote(request) }
        )
        binding.requestGroups.adapter = adapter

        binding.search.setOnFocusChangeListener { _, isFocused ->
            if (isFocused) {
                binding.mainScroll.viewTreeObserver.addOnGlobalLayoutListener(
                    object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            binding.mainScroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            binding.mainScroll.smoothScrollBy(
                                0,
                                resources.getDimensionPixelOffset(R.dimen.username_search_focused_scroll)
                            )
                        }
                    }
                )
            }
        }
        binding.search.doOnTextChanged { text, _, _, _ ->
            binding.clearBtn.isVisible = !text.isNullOrEmpty()
            adapter.submitList(filterByQuery(itemList, text.toString()))
        }
        binding.clearBtn.setOnClickListener { binding.search.text.clear() }
        binding.appliedFiltersPanel.setOnClickListener {
            safeNavigate(UsernameRequestsFragmentDirections.requestsToFilters())
        }

        binding.quickVoteButton.setOnClickListener {
            QuickVoteDialogFragment().show(requireActivity())
        }

        keyboardUtil = KeyboardUtil(requireActivity().window, binding.root)
        keyboardUtil.setOnKeyboardShownChanged { isShown ->
            binding.appliedFiltersPanel.isVisible = !viewModel.filterState.value.isDefault() && !isShown
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            if (state.showFirstTimeInfo) {
                showFirstTimeInfo()
            }

            setState(state.filteredUsernameRequests)
            setList(adapter, state.filteredUsernameRequests)
        }

        viewModel.filterState.observe(viewLifecycleOwner) { state ->
            val isDefault = state.isDefault()
            binding.appliedFiltersPanel.isVisible = !isDefault && !keyboardUtil.isKeyboardShown
            val typeOptionNames = binding.root.resources.getStringArray(R.array.usernames_type_options)
            binding.filterTitle.text = typeOptionNames[state.typeOption.ordinal]

            if (!isDefault) {
                populateAppliedFilters(state)
            }
        }

        // currentWorkId is updated before a vote is broadcasts
        // this block will then create an observer for that vote
        viewModel.currentWorkId.observe(viewLifecycleOwner) {
            if (it.isNotEmpty()) {
                val liveData = viewModel.voteObserver(it)
                liveData.observe(viewLifecycleOwner) { resource ->
                    // show vote info dialogs and toasts
                    log.info("current work id: {}: {}", it, resource)
                    try {
                        val outputData = resource.data?.outputData
                        val normalizedLabels =
                            outputData?.getStringArray(BroadcastUsernameVotesWorker.KEY_NORMALIZED_LABELS)?.toList()
                        val usernames =
                            outputData?.getStringArray(BroadcastUsernameVotesWorker.KEY_LABELS)?.toList()
                        val votes =
                            outputData?.getStringArray(BroadcastUsernameVotesWorker.KEY_VOTE_CHOICES)?.toList()
                        val isQuickVoting = outputData?.getBoolean(BroadcastUsernameVotesWorker.KEY_QUICK_VOTING, false) ?: false
                        when (resource.status) {
                            Status.LOADING -> {
                                log.info("  loading: {}", outputData)
                            }

                            Status.SUCCESS -> {
                                log.info("  success: {}", outputData)
                                showVoteIndicator(votes!!, usernames!!, resource.status, isQuickVoting)
                                if (!isQuickVoting) {
                                    normalizedLabels?.firstOrNull()?.let {
                                        viewModel.updateUsernameRequestWithVotes(it)
                                    }
                                } else {
                                    viewModel.updateUsernameRequestsWithVotes()
                                }
                            }

                            Status.ERROR -> {
                                log.info("  error: {}", outputData)
                                showVoteIndicator(votes!!, usernames!!, resource.status, isQuickVoting)
                            }

                            Status.CANCELED -> {
                                log.info("  error: {}", outputData)
                            }
                        }
                    } catch (e: Exception) {
                        log.error("error processing vote information", e)
                    }
                    if (resource.status == Status.SUCCESS) {
                        // remove all observer
                        liveData.removeObservers(viewLifecycleOwner)
                    }
                }
            }
        }
    }

    private fun performVote(request: UsernameRequest) {
        lifecycleScope.launch {
            // handle voting
            val blockVote = request.requestId == ""
            val usernameVotes = viewModel.getVotes(request.username)
            when {
                (usernameVotes.size == UsernameVote.MAX_VOTES - 1) -> {
                    if (AdaptiveDialog.create(
                            icon = null,
                            getString(R.string.username_vote_one_left),
                            getString(R.string.username_vote_one_left_message, UsernameVote.MAX_VOTES - 1),
                            getString(R.string.cancel),
                            getString(R.string.button_ok)
                        ).showAsync(requireActivity()) == true
                    ) {
                        if (blockVote) {
                            doBlockVote(request)
                        } else {
                            doVote(request)
                        }
                    }
                }

                usernameVotes.size == UsernameVote.MAX_VOTES -> {
                    AdaptiveDialog.create(
                        icon = null,
                        getString(R.string.username_vote_none_left),
                        getString(R.string.username_vote_none_left_message, UsernameVote.MAX_VOTES),
                        getString(R.string.button_ok)
                    ).show(requireActivity()) {
                        // do nothing
                    }
                }

                else -> {
                    if (blockVote) {
                        doBlockVote(request)
                    } else {
                        doVote(request)
                    }
                }
            }
        }
    }

    private suspend fun doBlockVote(request: UsernameRequest) {
        // perform block vote
        val vote = when (viewModel.getVotes(request.normalizedLabel).lastOrNull()?.type) {
            UsernameVote.LOCK -> UsernameVote.ABSTAIN
            else -> UsernameVote.LOCK
        }
        if (viewModel.shouldMaybeAskForMoreKeys()) {
            if (viewModel.keysAmount > 0) {
                safeNavigate(
                    UsernameRequestsFragmentDirections.requestsToAddVotingKeysFragment(
                        request.normalizedLabel,
                        vote
                    )
                )
            } else {
                safeNavigate(
                    UsernameRequestsFragmentDirections.requestsToVotingKeyInputFragment(
                        request.requestId,
                        vote
                    )
                )
            }
        } else {
            viewModel.submitVote(request.requestId, vote)
        }
    }

    private suspend fun doVote(request: UsernameRequest) {
        val lastVote = viewModel.getVotes(request.normalizedLabel).lastOrNull()
        val voteType = when {
            lastVote == null -> UsernameVote.APPROVE
            lastVote.type == UsernameVote.APPROVE && lastVote.identity == request.identity -> UsernameVote.ABSTAIN
            else -> UsernameVote.APPROVE
        }
        if (viewModel.shouldMaybeAskForMoreKeys()) {
            if (viewModel.keysAmount > 0) {
                safeNavigate(
                    UsernameRequestsFragmentDirections.requestsToAddVotingKeysFragment(
                        request.requestId,
                        voteType
                    )
                )
            } else {
                safeNavigate(
                    UsernameRequestsFragmentDirections.requestsToVotingKeyInputFragment(
                        request.requestId,
                        voteType
                    )
                )
            }
        } else {
            viewModel.submitVote(request.requestId, voteType)
        }
    }
    private fun showFirstTimeInfo() {
        if (!isShowingFirstTimeDialog) {
            isShowingFirstTimeDialog = true
            lifecycleScope.launch {
                delay(200)
                AdaptiveDialog.create(
                    R.drawable.ic_user_list,
                    getString(R.string.voting_duplicates_only_title),
                    getString(R.string.voting_duplicates_only_message),
                    getString(R.string.button_ok)
                ).showAsync(requireActivity())
                isShowingFirstTimeDialog = false
                viewModel.setFirstTimeInfoShown()
            }
        }
    }

    private fun setState(requests: List<UsernameRequestGroupView>) {
        binding.filterSubtitle.text = getString(R.string.n_usernames, requests.size)
        binding.filterSubtitle.isVisible = requests.isNotEmpty()
        binding.searchPanel.isVisible = requests.isNotEmpty()
        binding.quickVoteButton.isVisible = requests.isNotEmpty() && viewModel.keysAmount > 0
        binding.noItemsTxt.isVisible = requests.isEmpty()
    }

    private fun setList(adapter: UsernameRequestGroupAdapter, requests: List<UsernameRequestGroupView>) {
        itemList = requests
        val list = filterByQuery(itemList, binding.search.text.toString())
        val layoutManager = binding.requestGroups.layoutManager as LinearLayoutManager
        val scrollPosition = layoutManager.findFirstVisibleItemPosition()
        val listForAdapter = if (list.isNotEmpty() && (viewModel.filterState.value.sortByOption == UsernameSortOption.VotingPeriodLatest ||
            viewModel.filterState.value.sortByOption == UsernameSortOption.VotingPeriodSoonest)) {
            list.groupBy {
                Instant.ofEpochMilli(it.votingEndDate).atZone(ZoneId.systemDefault()).toLocalDate()
            }.map {
                val outList = mutableListOf<UsernameRequestRowView>()
                outList.add(UsernameRequestRowView(it.key))
                outList.apply { addAll(it.value) }
            }.reduce { acc, list -> acc.apply { addAll(list) } }
        } else {
            requests
        }
        adapter.submitList(listForAdapter)
        binding.requestGroups.scrollToPosition(scrollPosition)
    }

    /**
     * Returns a list of [UsernameRequestGroupView] where the username of the group or the label or normalizedLabel
     * of any related request contains [query] text.
     */
    private fun filterByQuery(items: List<UsernameRequestGroupView>, query: String?): List<UsernameRequestGroupView> {
        if (query.isNullOrEmpty()) {
            return items
        }

        return items.filter {
            it.username.contains(query, true) || it.requests.any {
                request -> request.normalizedLabel.contains(query)
            }
        }
    }

    private fun populateAppliedFilters(state: FiltersUIState) {
        val sortByOptionNames = binding.root.resources.getStringArray(R.array.usernames_sort_by_options)
        val appliedFilterNames = mutableListOf<String>()

        if (state.sortByOption != UsernameSortOption.DateDescending) {
            appliedFilterNames.add(sortByOptionNames[state.sortByOption.ordinal])
        }

        if (state.onlyDuplicates) {
            appliedFilterNames.add(getString(R.string.only_duplicates))
        }

        if (state.onlyLinks) {
            appliedFilterNames.add(getString(R.string.only_links_short))
        }

        binding.filteredByTxt.text = appliedFilterNames.joinToString(", ")
    }

    /**
     * Display the toast regarding the last vote
     */
    private fun showVoteIndicator(
        votes: List<String>,
        usernames: List<String>,
        status: Status,
        isQuickVoting: Boolean
    ) {
        if (votes.isEmpty()) {
            return
        }
        val resourceVoteChoice = ResourceVoteChoice.from(votes.first())
        val isCancelled = resourceVoteChoice == AbstainVoteChoice()
        val username = if (usernames.size > 1) getString(R.string.quick_vote) else usernames.first()
        if (isQuickVoting) {
            // show dialog about 1 vote left if necessary
            showQuickVotingResults(usernames)
        }
        val toastText = when (status) {
            Status.SUCCESS -> when (resourceVoteChoice) {
                is AbstainVoteChoice -> getString(R.string.cancel_submitted, username)
                is LockVoteChoice -> getString(R.string.block_submitted, username)
                is TowardsIdentity -> {
                    if (isQuickVoting) {
                        getString(R.string.quick_vote_submitted)
                    } else {
                        getString(R.string.vote_submitted, username)
                    }
                }
                else -> error("invalid vote choice: $resourceVoteChoice")
            }
            Status.LOADING -> when (resourceVoteChoice) {
                is AbstainVoteChoice -> getString(R.string.cancel_submitting, username)
                is LockVoteChoice -> getString(R.string.block_submitting, username)
                is TowardsIdentity -> {
                    if (isQuickVoting) {
                        getString(R.string.vote_submitting, username)
                    } else {
                        getString(R.string.vote_submitting, username)
                    }
                }
                else -> error("invalid vote choice: $resourceVoteChoice")
            }
            Status.ERROR -> when (resourceVoteChoice) {
                is AbstainVoteChoice -> getString(R.string.cancel_submitted_error, username)
                is LockVoteChoice -> getString(R.string.block_submitted_error, username)
                is TowardsIdentity -> getString(R.string.vote_submitted_error, username)
                else -> error("invalid vote choice: $resourceVoteChoice")
            }
            Status.CANCELED -> return
        }
        binding.voteSubmittedTxt.text = toastText
        binding.voteSubmittedIcon.isVisible = !isCancelled
        binding.voteSubmittedIndicator.alpha = 0f
        binding.voteSubmittedIndicator.isVisible = true

        val animationDuration = 300L
        binding.voteSubmittedIndicator.animate()
            .alpha(1f)
            .setDuration(animationDuration)
            .setListener(null)
            .start()

        viewModel.voteHandled()
        binding.voteSubmittedIndicator.postDelayed({
            binding.voteSubmittedIndicator.animate()
                .alpha(0f)
                .setDuration(animationDuration)
                .withEndAction {
                    binding.voteSubmittedIndicator.isVisible = false
                }
                .start()
        }, 3000L)
    }

    /**
     * Display a dialog for any usernames that have only one vote remaining after quick voting
     */
    private fun showQuickVotingResults(usernames: List<String>) {
        lifecycleScope.launch {
            val usernamesWithOneVoteLeft = viewModel.getUsernamesByVotesLeft(usernames, 1)
            if (usernamesWithOneVoteLeft.isNotEmpty()) {
                OneVoteLeftDialogFragment.newInstance(usernamesWithOneVoteLeft).show(requireActivity())
            }
        }
    }
}
