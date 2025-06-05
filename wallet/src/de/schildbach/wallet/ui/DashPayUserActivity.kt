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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.*
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.dashpay.NotificationsAdapter
import de.schildbach.wallet.ui.dashpay.utils.display
import de.schildbach.wallet.ui.dashpay.widget.ContactRequestPane
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet.ui.transactions.TransactionDetailsDialogFragment
import de.schildbach.wallet.ui.util.InputParser
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityDashpayUserBinding
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.observe
import javax.inject.Inject

@AndroidEntryPoint
class DashPayUserActivity : LockScreenActivity() {

    private val viewModel: DashPayUserActivityViewModel by viewModels()
    private val dashPayViewModel: DashPayViewModel by viewModels()
    private lateinit var binding: ActivityDashpayUserBinding
    @Inject lateinit var walletDataProvider: WalletDataProvider

    private val showContactHistoryDisclaimer by lazy {
        intent.getBooleanExtra(EXTRA_SHOW_CONTACT_HISTORY_DISCLAIMER, false)
    }
    private val notificationsAdapter: NotificationsAdapter by lazy {
        NotificationsAdapter(this,
            walletDataProvider.transactionBag,
            false,
            { searchResult, position -> onAcceptRequest(searchResult, position) },
            { searchResult, position -> onIgnoreRequest(searchResult, position) },
            { onUserAlertDismiss(it) },
            { onItemClicked(it) },
            viewModel.getChainLockBlockHeight(),
            true,
            showContactHistoryDisclaimer
        )
    }

    companion object {
        private const val EXTRA_INIT_USER_DATA = "extra_init_user_data"
        private const val EXTRA_INIT_PROFILE_DATA = "extra_init_profile_data"
        private const val EXTRA_SHOW_CONTACT_HISTORY_DISCLAIMER = "extra_show_contact_history_disclaimer"

        const val REQUEST_CODE_DEFAULT = 0
        const val RESULT_CODE_OK = 1
        const val RESULT_CODE_CHANGED = 2

        @JvmStatic
        fun createIntent(context: Context, dashPayProfile: DashPayProfile): Intent {
            val intent = Intent(context, DashPayUserActivity::class.java)
            intent.putExtra(EXTRA_INIT_PROFILE_DATA, dashPayProfile)
            intent.putExtra(EXTRA_SHOW_CONTACT_HISTORY_DISCLAIMER, false)
            return intent
        }

        @JvmStatic
        fun createIntent(context: Context, usernameSearchResult: UsernameSearchResult, showContactHistoryDisclaimer: Boolean = false): Intent {
            val intent = Intent(context, DashPayUserActivity::class.java)
            intent.putExtra(EXTRA_INIT_USER_DATA, usernameSearchResult)
            intent.putExtra(EXTRA_SHOW_CONTACT_HISTORY_DISCLAIMER, showContactHistoryDisclaimer)
            return intent
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDashpayUserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.close.setOnClickListener { finish() }

        binding.contactRequestPane.setOnUserActionListener(object : ContactRequestPane.OnUserActionListener {
            override fun onSendContactRequestClick() {
                dashPayViewModel.logEvent(AnalyticsConstants.UsersContacts.SEND_REQUEST)
                // check credit balance
                sendContactRequest()
                setResult(RESULT_CODE_CHANGED)
            }

            override fun onAcceptClick() {
                // check credit balance
                sendContactRequest()
                setResult(RESULT_CODE_CHANGED)
            }

            override fun onIgnoreClick() {
                Toast.makeText(this@DashPayUserActivity, "Not yet implemented", Toast.LENGTH_LONG).show()
            }

            override fun onPayClick() {
                startPayActivity()
            }
        })

        binding.activityRv.layoutManager = LinearLayoutManager(this)
        binding.activityRv.adapter = this.notificationsAdapter

        viewModel.userData.filterNotNull().observe(this) { userData ->
            updateContactRelationUi()

            val username = userData.username
            val profile = userData.dashPayProfile
            val displayName = profile.displayName

            ProfilePictureDisplay.display(binding.avatar, profile)

            if (displayName.isNotEmpty()) {
                binding.displayNameTxt.text = displayName
                binding.usernameTxt.text = username
            } else {
                binding.displayNameTxt.text = username
            }
        }

        val userData = if (intent.hasExtra(EXTRA_INIT_USER_DATA)) {
            intent.getParcelableExtra<UsernameSearchResult>(EXTRA_INIT_USER_DATA)
        } else {
            intent.getParcelableExtra<DashPayProfile>(EXTRA_INIT_PROFILE_DATA)?.let { profile ->
                UsernameSearchResult(profile.username, profile, null, null)
            }
        }

        if (userData != null) {
            viewModel.initUserData(userData)
        }

        viewModel.sendContactRequestState.observe(this) { state ->
            imitateUserInteraction()
            updateContactRelationUi()
        }

        viewModel.notifications.observe(this) { notifications ->
            if (notifications.isNotEmpty()) {
                binding.activityRv.isVisible = true
                binding.activityRvTopLine.isVisible = true
                processResults(notifications)
            } else {
                binding.activityRv.isVisible = false
                binding.activityRvTopLine.isVisible = false
            }
        }

        dashPayViewModel.blockchainStateData.observe(this) {
            it?.apply {
                val networkError = impediments.contains(BlockchainState.Impediment.NETWORK)
                binding.contactRequestPane.applyNetworkErrorState(networkError)
            }
        }
    }

    fun sendContactRequest() {
        lifecycleScope.launch {
            val enough = viewModel.hasEnoughCredits()
            val shouldWarn = enough.isBalanceWarning()
            val isEmpty = enough.isBalanceWarning()

            if (shouldWarn || isEmpty) {
                val answer = AdaptiveDialog.create(
                    R.drawable.ic_warning_yellow_circle,
                    if (isEmpty) getString(R.string.credit_balance_empty_warning_title) else getString(R.string.credit_balance_low_warning_title),
                    if (isEmpty) getString(R.string.credit_balance_empty_warning_message) else getString(R.string.credit_balance_low_warning_message),
                    getString(R.string.credit_balance_button_maybe_later),
                    getString(R.string.credit_balance_button_buy)
                ).showAsync(this@DashPayUserActivity)

                if (answer == true) {
                    SendCoinsActivity.startBuyCredits(this@DashPayUserActivity)
                } else {
                    if (shouldWarn)
                        viewModel.sendContactRequest()
                }
            } else {
                viewModel.sendContactRequest()
            }
        }
    }

    private fun updateContactRelationUi() {
        val userData = viewModel.userData.value ?: return
        val state = viewModel.sendContactRequestState.value
        ContactRelation.process(userData.type, state, object : ContactRelation.RelationshipCallback {
            override fun none() {
                binding.contactRequestPane.applySendStateWithDisclaimer(userData.username)
            }

            override fun inviting() {
                binding.contactRequestPane.applySendingStateWithDisclaimer(userData.username)
            }

            override fun invited() {
                binding.contactRequestPane.applySentStateWithDisclaimer(userData.username)
            }

            override fun inviteReceived() {
                binding.contactRequestPane.applyReceivedState(userData.username)
            }

            override fun acceptingInvite() {
                binding.contactRequestPane.applyAcceptingState()
            }

            override fun friends() {
                binding.contactRequestPane.applyFriendsState()
            }
        })
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_bottom)
    }

    private fun startPayActivity() {
        val userData = viewModel.userData.value ?: return
        handleString(userData.dashPayProfile.userId, true, R.string.scan_to_pay_username_dialog_message)
        finish()
    }

    private fun handleString(input: String, fireAction: Boolean, errorDialogTitleResId: Int) {
        object : InputParser.StringInputParser(input, true) {

            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                if (fireAction) {
                    SendCoinsActivity.start(this@DashPayUserActivity, null, paymentIntent, true)
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
                    dialog.show(this@DashPayUserActivity)
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

    fun onItemClicked(notificationItem: NotificationItem) {
        when (notificationItem) {
            is NotificationItemContact -> {

            }
            is NotificationItemPayment -> {
                val transactionDetailsDialogFragment = TransactionDetailsDialogFragment.newInstance(notificationItem.tx!!.txId)
                transactionDetailsDialogFragment.show(supportFragmentManager, null)
            }
        }
    }

    fun onAcceptRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
        // do nothing
    }

    fun onIgnoreRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
        //do nothing if an item is clicked for now
    }

    private fun processResults(data: List<NotificationItem>) {
        val results = ArrayList<NotificationsAdapter.NotificationViewItem>()

        results.add(NotificationsAdapter.HeaderViewItem(1, R.string.notifications_profile_activity))
        data.forEach { results.add(NotificationsAdapter.NotificationViewItem(it, false)) }

        notificationsAdapter.results = results
    }

    fun onUserAlertDismiss(alertId: Int) {
        Toast.makeText(this, "Dismiss", Toast.LENGTH_SHORT).show()
    }
}