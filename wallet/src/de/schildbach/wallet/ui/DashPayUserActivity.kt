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
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.*
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.dashpay.NotificationsAdapter
import de.schildbach.wallet.ui.dashpay.notification.ContactViewHolder
import de.schildbach.wallet.ui.dashpay.widget.ContactRequestPane
import de.schildbach.wallet.ui.send.SendCoinsInternalActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_dashpay_user.*
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.common.InteractionAwareActivity

class DashPayUserActivity : InteractionAwareActivity(),
        NotificationsAdapter.OnItemClickListener,
        ContactViewHolder.OnContactActionClickListener {

    private var initialDataLoaded = false

    private lateinit var viewModel: DashPayUserActivityViewModel
    private lateinit var dashPayViewModel: DashPayViewModel
    private val showContactHistoryDisclaimer by lazy { intent.getBooleanExtra(EXTRA_SHOW_CONTACT_HISTORY_DISCLAIMER, false) }
    private val notificationsAdapter: NotificationsAdapter by lazy {
        NotificationsAdapter(this,
                WalletApplication.getInstance().wallet, false, this,
                this, true, showContactHistoryDisclaimer)
    }

    companion object {
        private const val EXTRA_INIT_USER_DATA = "extra_init_user_data"
        private const val EXTRA_SHOW_CONTACT_HISTORY_DISCLAIMER = "extra_show_contact_history_disclaimer"
        private const val EXTRA_AUTO_ACCEPT = "extra_auto_accept"

        const val REQUEST_CODE_DEFAULT = 0
        const val RESULT_CODE_OK = 1
        const val RESULT_CODE_CHANGED = 2

        @JvmStatic
        fun createIntent(context: Context, usernameSearchResult: UsernameSearchResult, showContactHistoryDisclaimer: Boolean = false, autoAccept: Boolean = false): Intent {
            val intent = Intent(context, DashPayUserActivity::class.java)
            intent.putExtra(EXTRA_INIT_USER_DATA, usernameSearchResult)
            intent.putExtra(EXTRA_SHOW_CONTACT_HISTORY_DISCLAIMER, showContactHistoryDisclaimer)
            intent.putExtra(EXTRA_AUTO_ACCEPT, autoAccept)
            return intent
        }
    }

    private val autoAccept: Boolean by lazy {
        intent.extras!!.getBoolean(EXTRA_AUTO_ACCEPT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashpay_user)

        close.setOnClickListener { finish() }

        viewModel = ViewModelProvider(this).get(DashPayUserActivityViewModel::class.java)
        dashPayViewModel = ViewModelProvider(this).get(DashPayViewModel::class.java)

        viewModel.userData = intent.getParcelableExtra(EXTRA_INIT_USER_DATA)
        viewModel.userLiveData.observe(this, Observer {
            if (it != null) {
                updateContactRelationUi(it)
            } else {
                Toast.makeText(this@DashPayUserActivity, "error loading user data", Toast.LENGTH_LONG).show()
            }
            if (initialDataLoaded) {
                setResult(RESULT_CODE_CHANGED)
            }
            initialDataLoaded = true
        })

        val username = viewModel.userData.username
        val profile = viewModel.userData.dashPayProfile
        val displayName = profile.displayName

        val defaultAvatar = UserAvatarPlaceholderDrawable.getDrawable(this, username[0])
        if (profile.avatarUrl.isNotEmpty()) {
            Glide.with(this).load(profile.avatarUrl).circleCrop()
                    .placeholder(defaultAvatar).into(avatar)
        } else {
            avatar.background = defaultAvatar
        }
        if (displayName.isNotEmpty()) {
            displayNameTxt.text = displayName
            usernameTxt.text = username
        } else {
            displayNameTxt.text = username
        }

        contact_request_pane.setOnUserActionListener(object : ContactRequestPane.OnUserActionListener {

            override fun onSendContactRequestClick() {
                viewModel.sendContactRequest(true)
            }

            override fun onAcceptClick() {
                viewModel.sendContactRequest(true)
            }

            override fun onIgnoreClick() {
                Toast.makeText(this@DashPayUserActivity, "Not yet implemented", Toast.LENGTH_LONG).show()
            }

            override fun onPayClick() {
                startPayActivity()
            }
        })

        activity_rv.layoutManager = LinearLayoutManager(this)
        activity_rv.adapter = this.notificationsAdapter

        updateContactRelationUi(viewModel.userData)

        if (autoAccept && !viewModel.userData.requestSent) {
            contact_request_pane.performAcceptClick()
        }
    }

    private fun updateContactRelationUi(userData: UsernameSearchResult) {
        when (userData.type) {
            UsernameSearchResult.Type.NO_RELATIONSHIP -> {
                if (showContactHistoryDisclaimer) {
                    contact_request_pane.applyDisclaimerState(userData.username)
                } else {
                    contact_request_pane.applySendState()
                }
                activity_rv.visibility = View.GONE
            }
            UsernameSearchResult.Type.CONTACT_ESTABLISHED -> {
                contact_request_pane.applyFriendsState()
                activity_rv.visibility = View.VISIBLE
            }
            UsernameSearchResult.Type.REQUEST_SENT -> {
                if (showContactHistoryDisclaimer) {
                    contact_request_pane.applySentStateWithDisclaimer(userData.username)
                } else {
                    contact_request_pane.applySentState()
                }
                activity_rv.visibility = View.VISIBLE
            }
            UsernameSearchResult.Type.REQUEST_RECEIVED -> {
                contact_request_pane.applyReceivedState(userData.username)
                activity_rv.visibility = View.VISIBLE
            }
        }
        if (userData.type != UsernameSearchResult.Type.NO_RELATIONSHIP) {
            viewModel.notificationsForUser.observe(this, Observer {
                if (Status.SUCCESS == it.status) {
                    if (it.data != null) {
                        processResults(it.data)
                    }
                }
            })
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_bottom)
    }

    private fun startPayActivity() {
        handleString(viewModel.userData.dashPayProfile.userId, true, R.string.scan_to_pay_username_dialog_message)
        finish()
    }

    private fun handleString(input: String, fireAction: Boolean, errorDialogTitleResId: Int) {
        object : InputParser.StringInputParser(input, true) {

            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                if (fireAction) {
                    SendCoinsInternalActivity.start(this@DashPayUserActivity, paymentIntent, true)
                }
            }

            override fun error(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                if (fireAction) {
                    dialog(this@DashPayUserActivity, null, errorDialogTitleResId, messageResId, *messageArgs)
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

    override fun onItemClicked(view: View, notificationItem: NotificationItem) {
        when (notificationItem) {
            is NotificationItemContact -> {

            }
            is NotificationItemPayment -> {
                val transactionDetailsDialogFragment = TransactionDetailsDialogFragment.newInstance(notificationItem.tx!!.txId)
                transactionDetailsDialogFragment.show(supportFragmentManager, null)
            }
        }
    }

    override fun onAcceptRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
        // do nothing
    }

    override fun onIgnoreRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
        //do nothing if an item is clicked for now
    }

    private fun processResults(data: List<NotificationItem>) {

        val results = ArrayList<NotificationsAdapter.NotificationViewItem>()

        results.add(NotificationsAdapter.HeaderViewItem(1, R.string.notifications_profile_activity))
        data.forEach { results.add(NotificationsAdapter.NotificationViewItem(it, false)) }

        notificationsAdapter.results = results
    }
}