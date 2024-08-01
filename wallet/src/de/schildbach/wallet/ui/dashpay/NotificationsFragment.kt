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
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.*
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.DashPayUserActivity
import de.schildbach.wallet.ui.dashpay.notification.NotificationsViewModel
import de.schildbach.wallet.ui.invite.InviteFriendActivity
import de.schildbach.wallet.ui.invite.InvitesHistoryActivity
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentNotificationsBinding
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.slf4j.LoggerFactory
import java.util.*
import javax.inject.Inject
import kotlin.collections.ArrayList
import kotlin.math.max

@AndroidEntryPoint
class NotificationsFragment : Fragment(R.layout.fragment_notifications) {

    companion object {
        private val log = LoggerFactory.getLogger(NotificationsAdapter::class.java)

        private const val EXTRA_MODE = "extra_mode"

        const val MODE_NOTIFICATIONS = 0x02
        const val MODE_NOTIFICATIONS_GLOBAL_FOOTER = 0x04
        const val MODE_NOTIFICATIONS_SEARCH = 0x01
    }

    private val dashPayViewModel: DashPayViewModel by activityViewModels()
    private val viewModel: NotificationsViewModel by activityViewModels()
    private val binding by viewBinding(FragmentNotificationsBinding::bind)
    private var handler: Handler = Handler()
    private lateinit var searchContactsRunnable: Runnable
    private lateinit var notificationsAdapter: NotificationsAdapter
    private var query = ""
    private var direction = UsernameSortOrderBy.DATE_ADDED
    private var mode = MODE_NOTIFICATIONS
    private var lastSeenNotificationTime = 0L
    private var isBlockchainSynced = true
    private var userAlertItem: NotificationItemUserAlert? = null
    @Inject lateinit var walletDataProvider: WalletDataProvider

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lifecycleScope.launch {
            lastSeenNotificationTime = viewModel.getLastNotificationTime()
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        notificationsAdapter = NotificationsAdapter(
            requireContext(),
            walletDataProvider.wallet!!,
            true,
            { u, position -> onAcceptRequest(u, position) },
            { u, position -> onIgnoreRequest(u, position) },
            { onUserAlertDismiss(it) },
            { onItemClicked(it) }
        )

        if (arguments != null && requireArguments().containsKey(EXTRA_MODE)) {
            mode = requireArguments().getInt(EXTRA_MODE)
        }

        binding.apply {
            contactsRv.layoutManager = LinearLayoutManager(requireContext())
            contactsRv.adapter = notificationsAdapter

            initViewModel()

            if (mode and MODE_NOTIFICATIONS_SEARCH != 0) {
                search.doAfterTextChanged {
                    it?.let {
                        query = it.toString()
                        searchNotifications()
                    }
                }
                search.visibility = View.VISIBLE
                icon.visibility = View.VISIBLE
            } else {
                search.visibility = View.GONE
                icon.visibility = View.GONE
            }
        }

        searchNotifications()
        dashPayViewModel.updateDashPayState()

        if (mode == MODE_NOTIFICATIONS) {
            dashPayViewModel.logEvent(AnalyticsConstants.UsersContacts.NOTIFICATIONS_HOME_SCREEN)
        }
    }

    private fun initViewModel() {
        viewModel.notificationsLiveData.observe(viewLifecycleOwner) {
            if (Status.SUCCESS == it.status) {
                if (it.data != null) {
                    val results = arrayListOf<NotificationItem>()
                    results.addAll(it.data)
                    lifecycleScope.launch {
                        processResults(results)
                        showHideAlert()
                    }
                }
            }
        }
        dashPayViewModel.sendContactRequestState.observe(viewLifecycleOwner) {
            notificationsAdapter.sendContactRequestWorkStateMap = it
        }
        dashPayViewModel.recentlyModifiedContactsLiveData.observe(viewLifecycleOwner) {
            notificationsAdapter.recentlyModifiedContacts = it
        }
        dashPayViewModel.blockchainStateData.observe(viewLifecycleOwner) {
            if (it != null) {
                isBlockchainSynced = it.percentageSync == 100
                showHideAlert()
            }
        }
    }

    private suspend fun processResults(data: ArrayList<NotificationItem>) {
        val results = ArrayList<NotificationsAdapter.NotificationViewItem>()

        // get the last seen date from the configuration
        val newDate = viewModel.getLastNotificationTime()

        // find the most recent notification timestamp
        var lastNotificationTime = 0L
        data.forEach {
            lastNotificationTime = max(lastNotificationTime, it.getDate())
            if (it is NotificationItemUserAlert) {
                userAlertItem = it
            }
        }

        // remove User Alert from list to add it before the "New" header
        userAlertItem?.apply { data.remove(this) }
        userAlertItem?.apply {
            if (isBlockchainSynced) {
                results.add(NotificationsAdapter.NotificationViewItem(this))
            }
        }
        // process new notifications
        val newItems = data.filter { r -> r.getDate() >= newDate }
        log.info("New contacts at ${Date(newDate)} = ${newItems.size}")
        newItems.forEach { r -> results.add(NotificationsAdapter.NotificationViewItem(r, true)) }

        // process older notifications
        val earlierItems = data.filter { r -> r.getDate() < newDate }
        earlierItems.forEach { r -> results.add(NotificationsAdapter.NotificationViewItem(r)) }

        notificationsAdapter.results = results
        lastSeenNotificationTime = lastNotificationTime
    }

    private fun showHideAlert() {
        if (notificationsAdapter.results.isNotEmpty()) {
            val firstItem = notificationsAdapter.results[0]
            if (!isBlockchainSynced && userAlertItem != null && firstItem.notificationItem is NotificationItemUserAlert) {
                val newResults = notificationsAdapter.results.toMutableList()
                newResults.remove(notificationsAdapter.results[0])
                notificationsAdapter.results = newResults
            } else if (isBlockchainSynced && userAlertItem != null && firstItem.notificationItem !is NotificationItemUserAlert) {
                val newResults = arrayListOf<NotificationsAdapter.NotificationViewItem>()
                newResults.add(NotificationsAdapter.NotificationViewItem(userAlertItem!!))
                newResults.addAll(notificationsAdapter.results)
                notificationsAdapter.results = newResults
            }
        }
    }

    private fun searchNotifications() {
        if (this::searchContactsRunnable.isInitialized) {
            handler.removeCallbacks(searchContactsRunnable)
        }

        searchContactsRunnable = Runnable {
            viewModel.searchNotifications(query)
        }
        handler.postDelayed(searchContactsRunnable, 500)
    }

    fun onItemClicked(notificationItem: NotificationItem) {

        when (notificationItem) {
            is NotificationItemContact -> {
                dashPayViewModel.logEvent(AnalyticsConstants.UsersContacts.NOTIFICATIONS_CONTACT_DETAILS)
                val usernameSearchResult = notificationItem.usernameSearchResult
                startActivityForResult(DashPayUserActivity.createIntent(requireContext(), usernameSearchResult), DashPayUserActivity.REQUEST_CODE_DEFAULT)
            }
            is NotificationItemPayment -> {
                val tx = notificationItem.tx!!
                Toast.makeText(requireContext(), "payment $tx", Toast.LENGTH_LONG).show()
            }
            is NotificationItemUserAlert -> {
                lifecycleScope.launch {
                    val inviteHistory = dashPayViewModel.getInviteHistory()
                    userAlertItem = null
                    viewModel.dismissUserAlert(R.string.invitation_notification_text)

                    if (inviteHistory.isEmpty()) {
                        InviteFriendActivity.startOrError(requireActivity())
                    } else {
                        startActivity(InvitesHistoryActivity.createIntent(requireActivity()))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        viewModel.setLastNotificationTime(lastSeenNotificationTime)
        super.onDestroy()
    }

    fun onAcceptRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
        dashPayViewModel.logEvent(AnalyticsConstants.UsersContacts.NOTIFICATIONS_ACCEPT_REQUEST)
        sendContactRequest(usernameSearchResult)
    }

    fun onIgnoreRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
        // this Ignmore Request function is not currently implemented
    }

    fun sendContactRequest(usernameSearchResult: UsernameSearchResult) {
        lifecycleScope.launch {
            val enough = dashPayViewModel.hasEnoughCredits()
            // TODO: before merging remove this
            val shouldWarn = true // enough.isBalanceWarning()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DashPayUserActivity.REQUEST_CODE_DEFAULT && resultCode == DashPayUserActivity.RESULT_CODE_CHANGED) {
            searchNotifications()
        }
    }

    fun onUserAlertDismiss(alertId: Int) {
        userAlertItem = null
        viewModel.dismissUserAlert(alertId)
    }
}
