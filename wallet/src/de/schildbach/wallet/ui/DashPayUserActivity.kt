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
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.text.HtmlCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.*
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.dashpay.NotificationsAdapter
import de.schildbach.wallet.ui.dashpay.notification.ContactViewHolder
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

    private lateinit var dashPayViewModel: DashPayViewModel
    private val username by lazy { intent.getStringExtra(USERNAME) }
    private val profile: DashPayProfile by lazy { intent.getParcelableExtra(PROFILE) as DashPayProfile }
    private val displayName by lazy { profile.displayName }
    private val showContactHistoryDisclaimer by lazy { intent.getBooleanExtra(SHOW_CONTACT_HISTORY_DISCLAIMER, false) }
    private val notificationsAdapter: NotificationsAdapter by lazy {
        NotificationsAdapter(this,
                WalletApplication.getInstance().wallet, false, this,
                this, true, showContactHistoryDisclaimer)
    }
    private var contactRequestReceived: Boolean = false
    private var contactRequestSent: Boolean = false
    private var sendingRequest: Boolean = true

    companion object {
        private const val USERNAME = "username"
        private const val PROFILE = "profile"
        private const val CONTACT_REQUEST_SENT = "contact_request_sent"
        private const val CONTACT_REQUEST_RECEIVED = "contact_request_received"
        private const val SHOW_CONTACT_HISTORY_DISCLAIMER = "show_contact_history_disclaimer"

        const val REQUEST_CODE_DEFAULT = 0
        const val RESULT_CODE_OK = 1
        const val RESULT_CODE_CHANGED = 2

        @JvmStatic
        fun createIntent(context: Context, username: String, profile: DashPayProfile?,
                         contactRequestSent: Boolean, contactRequestReceived: Boolean,
                         showContactHistoryDisclaimer: Boolean = false): Intent {
            val intent = Intent(context, DashPayUserActivity::class.java)
            intent.putExtra(USERNAME, username)
            intent.putExtra(PROFILE, profile)
            intent.putExtra(CONTACT_REQUEST_SENT, contactRequestSent)
            intent.putExtra(CONTACT_REQUEST_RECEIVED, contactRequestReceived)
            intent.putExtra(SHOW_CONTACT_HISTORY_DISCLAIMER, showContactHistoryDisclaimer)
            return intent
        }

        @JvmStatic
        fun createIntent(context: TransactionResultActivity, usernameSearchResult: UsernameSearchResult): Intent {
            return createIntent(context, usernameSearchResult.username, usernameSearchResult.dashPayProfile,
                    usernameSearchResult.requestSent, usernameSearchResult.requestReceived, true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashpay_user)

        close.setOnClickListener { finish() }
        contactRequestSent = intent.getBooleanExtra(CONTACT_REQUEST_SENT, false)
        contactRequestReceived = intent.getBooleanExtra(CONTACT_REQUEST_RECEIVED, false)

        dashPayViewModel = ViewModelProvider(this).get(DashPayViewModel::class.java)

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

        sendContactRequestBtn.setOnClickListener {
            sendContactRequest(true)
        }
        accept.setOnClickListener {
            sendContactRequest(false)
        }
        payContactBtn.setOnClickListener { startPayActivity() }

        dashPayViewModel!!.sendContactRequestOperation.operationStatus.observe(this, Observer {
            println("sendContactRequestWorkInfo:\t$it")
        })
        dashPayViewModel.getContactRequestLiveData.observe(this, object : Observer<Resource<DashPayContactRequest>> {
        override fun onChanged(it: Resource<DashPayContactRequest>?) {
            if (it != null) {
                when (it.status) {
                    Status.ERROR -> {
                        var msg = it.message
                        if (msg == null) {
                            msg = "!!Error!!"
                        }
                        Toast.makeText(this@DashPayUserActivity, msg, Toast.LENGTH_LONG).show()
                    }
                    Status.SUCCESS -> {
                        setResult(RESULT_CODE_CHANGED)
                        if (sendingRequest) {
                            contactRequestSent = true
                        } else {
                            contactRequestReceived = true
                        }
                        updateContactRelationUi()
                        dashPayViewModel.getContactRequestLiveData.removeObserver(this)
                    }
                }
            }
        }
    })

    activity_rv.layoutManager = LinearLayoutManager(this)
    activity_rv.adapter = this.notificationsAdapter

    if (contactRequestReceived || contactRequestSent) {
        dashPayViewModel.notificationsForUserLiveData.observe(this, Observer {
            if (Status.SUCCESS == it.status) {
                if (it.data != null) {
                    processResults(it.data)
                }
            }
        })
    }

        if (showContactHistoryDisclaimer && !contactRequestReceived) {
            contact_history_disclaimer.visibility = View.VISIBLE
        } else {
            contact_history_disclaimer.visibility = View.GONE
        }
        sendContactRequestBtnStrangerQR.setOnClickListener {
            sendContactRequest(isSendingRequest = true, fromDisclaimer = true)
        }

        updateContactRelationUi()
    }

    private fun sendContactRequest(isSendingRequest: Boolean, fromDisclaimer: Boolean = false) {
        sendingRequest = isSendingRequest
        dashPayViewModel.sendContactRequestWork(profile.userId)
        startLoading(fromDisclaimer)
    }

    private fun startLoading(fromDisclaimer: Boolean = false) {
        if (fromDisclaimer) {
            sendContactRequestBtnStrangerQR.visibility = View.GONE
            sendingContactRequestDisclaimerBtn.visibility = View.VISIBLE
            (sendingContactRequestBtnDisclaimerImage.drawable as AnimationDrawable).start()
        } else {
            sendContactRequestBtn.visibility = View.GONE
            sendingContactRequestBtn.visibility = View.VISIBLE
            (sendingContactRequestBtnImage.drawable as AnimationDrawable).start()
        }
    }

    private fun updateContactRelationUi() {
        listOf<View>(sendContactRequestBtn, sendingContactRequestBtn, contactRequestSentBtn,
                contactRequestReceivedContainer, sendingContactRequestDisclaimerBtn,
                payContactBtn).forEach { it.visibility = View.GONE }

        when (contactRequestSent to contactRequestReceived) {
            //No Relationship
            false to false -> {
                sendContactRequestBtn.visibility = View.VISIBLE
                activity_rv.visibility = View.GONE
                if (contact_history_disclaimer.visibility == View.VISIBLE) {
                    sendContactRequestBtn.visibility = View.GONE
                    var disclaimerText = getString(R.string.contact_history_disclaimer)
                    disclaimerText = disclaimerText.replace("%", username)
                    contact_history_disclaimer_text.text = HtmlCompat.fromHtml(disclaimerText,
                            HtmlCompat.FROM_HTML_MODE_COMPACT)
                }
            }
            //Contact Established
            true to true -> {
                payContactBtn.visibility = View.VISIBLE
                activity_rv.visibility = View.VISIBLE
                dashPayViewModel.searchNotificationsForUser(profile.userId)
            }
            //Request Sent / Pending
            true to false -> {
                contactRequestSentBtn.visibility = View.VISIBLE
                activity_rv.visibility = View.VISIBLE
                dashPayViewModel.searchNotificationsForUser(profile.userId)
                if (contact_history_disclaimer.visibility == View.VISIBLE) {
                    sendContactRequestBtn.visibility = View.GONE
                    sendingContactRequestDisclaimerBtn.visibility = View.GONE
                    sendContactRequestBtnStrangerQR.visibility = View.GONE
                    if (contact_history_disclaimer.visibility == View.VISIBLE) {
                        var disclaimerText = getString(R.string.contact_history_disclaimer_pending)
                        disclaimerText = disclaimerText.replace("%", username)
                        contact_history_disclaimer_text.text = HtmlCompat.fromHtml(disclaimerText,
                                HtmlCompat.FROM_HTML_MODE_COMPACT)
                    }
                }
            }
            //Request Received
            false to true -> {
                payContactBtn.visibility = View.VISIBLE
                activity_rv.visibility = View.VISIBLE
                dashPayViewModel.searchNotificationsForUser(profile.userId)
                // manually set the activity_rv below the Accept button since it was too hard with the layout file
                val params = activity_rv.layoutParams as ConstraintLayout.LayoutParams
                params.topToBottom = R.id.contactRequestReceivedContainer
                activity_rv.requestLayout()
                dashPayViewModel.searchNotificationsForUser(profile.userId)
                contactRequestReceivedContainer.visibility = View.VISIBLE
                requestTitle.text = getString(R.string.contact_request_received_title, username)
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_bottom)
    }

    private fun startPayActivity() {
        handleString(profile.userId, true, R.string.scan_to_pay_username_dialog_message)
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