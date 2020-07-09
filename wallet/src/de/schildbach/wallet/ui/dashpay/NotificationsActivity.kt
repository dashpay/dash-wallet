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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayContactRequest
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.DashPayUserActivity
import de.schildbach.wallet.ui.GlobalFooterActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_contacts.*
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.max

class NotificationsActivity : GlobalFooterActivity(), TextWatcher,
        NotificationsAdapter.OnItemClickListener,
        NotificationsAdapter.OnContactRequestButtonClickListener {

    companion object {
        private val log = LoggerFactory.getLogger(NotificationsAdapter::class.java)

        private const val EXTRA_MODE = "extra_mode"

        const val MODE_NOTIFICATIONS = 0x02
        const val MODE_NOTIFICATIONS_GLOBAL_FOOTER = 0x04
        const val MODE_NOTIFICATIONS_SEARCH = 0x01

        @JvmStatic
        fun createIntent(context: Context, mode: Int = MODE_NOTIFICATIONS): Intent {
            val intent = Intent(context, NotificationsActivity::class.java)
            intent.putExtra(EXTRA_MODE, mode)
            return intent
        }
    }

    private lateinit var dashPayViewModel: DashPayViewModel
    private lateinit var walletApplication: WalletApplication
    private var handler: Handler = Handler()
    private lateinit var searchContactsRunnable: Runnable
    protected val notificationsAdapter: NotificationsAdapter = NotificationsAdapter(this)
    private var query = ""
    private var blockchainIdentityId: String? = null
    private var direction = UsernameSortOrderBy.DATE_ADDED
    private var mode = MODE_NOTIFICATIONS
    private var lastSeenNotificationTime = 0L
    private val contactRequestCode = 1
    private var currentPosition = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        walletApplication = application as WalletApplication
        lastSeenNotificationTime = walletApplication.configuration.lastSeenNotificationTime

        if (intent.extras != null && intent.extras!!.containsKey(EXTRA_MODE)) {
            mode = intent.extras.getInt(EXTRA_MODE)
        }

        if (mode and MODE_NOTIFICATIONS_GLOBAL_FOOTER != 0) {
            setContentViewWithFooter(R.layout.activity_notifications)
            activateContactsButton()
        } else {
            setContentView(R.layout.activity_notifications)
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        contacts_rv.layoutManager = LinearLayoutManager(this)
        contacts_rv.adapter = this.notificationsAdapter
        this.notificationsAdapter.itemClickListener = this

        initViewModel()

        if (mode and MODE_NOTIFICATIONS_SEARCH != 0) {
            search.addTextChangedListener(this)
            search.visibility = View.VISIBLE
            icon.visibility = View.VISIBLE
        } else {
            search.visibility = View.GONE
            icon.visibility = View.GONE
        }
        setTitle(R.string.notifications_title)

        searchContacts()
    }

    private fun initViewModel() {
        dashPayViewModel = ViewModelProvider(this).get(DashPayViewModel::class.java)
        dashPayViewModel.searchContactsLiveData.observe(this, Observer {
            if (Status.SUCCESS == it.status) {
                if (it.data != null) {
                    processResults(it.data)
                }
            }
        })
        //This is not used
        AppDatabase.getAppDatabase().blockchainIdentityDataDao().load().observe(this, Observer {
            if (it != null) {
                //TODO: we don't have an easy way of getting the identity id (userId)
                val tx = walletApplication.wallet.getTransaction(it.creditFundingTxId)
                val cftx = walletApplication.wallet.getCreditFundingTransaction(tx)
                blockchainIdentityId = cftx.creditBurnIdentityIdentifier.toStringBase58()
            }
        })

        dashPayViewModel.getContactRequestLiveData.observe(this, object : Observer<Resource<DashPayContactRequest>> {
            override fun onChanged(it: Resource<DashPayContactRequest>?) {
                if (it != null && currentPosition != -1) {
                    when (it.status) {
                        Status.LOADING -> {

                        }
                        Status.ERROR -> {
                            var msg = it.message
                            if (msg == null) {
                                msg = "!!Error!!  ${it.exception!!.message}"
                            }
                            Toast.makeText(this@NotificationsActivity, msg, Toast.LENGTH_LONG).show()
                        }
                        Status.SUCCESS -> {
                            // update the data
                            notificationsAdapter.results[currentPosition].usernameSearchResult!!.toContactRequest = it.data!!
                            notificationsAdapter.notifyItemChanged(currentPosition)
                            currentPosition = -1
                            lastSeenNotificationTime = it.data.timestamp.toLong() * 1000
                        }
                    }
                }
            }
        })
    }

    private fun getViewType(usernameSearchResult: UsernameSearchResult): Int {
        return when (usernameSearchResult.requestSent to usernameSearchResult.requestReceived) {
            true to true -> {
                NotificationsAdapter.NOTIFICATION_CONTACT_ADDED
            }
            false to true -> {
                NotificationsAdapter.NOTIFICATION_CONTACT_REQUEST_RECEIVED
            }
            else -> throw IllegalArgumentException("View not supported")
        }
    }

    private fun processResults(data: List<UsernameSearchResult>) {

        val results = ArrayList<NotificationsAdapter.ViewItem>()

        // get the last seen date from the configuration
        val newDate = walletApplication.configuration.lastSeenNotificationTime

        // find the most recent notification timestamp
        var lastNotificationTime = 0L
        data.forEach { lastNotificationTime = max(lastNotificationTime, it.date) }

        val newItems = data.filter { r -> r.date >= newDate }.toMutableList()
        log.info("New contacts at ${Date(newDate)} = ${newItems.size} - NotificationActivity")

        results.add(NotificationsAdapter.ViewItem(null, NotificationsAdapter.NOTIFICATION_NEW_HEADER))
        if (newItems.isEmpty()) {
            results.add(NotificationsAdapter.ViewItem(null, NotificationsAdapter.NOTIFICATION_NEW_EMPTY))
        } else {
            newItems.forEach { r -> results.add(NotificationsAdapter.ViewItem(r, getViewType(r), true)) }
        }

        supportActionBar!!.title = getString(R.string.notifications_title_with_count, newItems.size)
        results.add(NotificationsAdapter.ViewItem(null, NotificationsAdapter.NOTIFICATION_EARLIER_HEADER))

        // process contacts
        val earlierItems = data.filter { r -> r.date < newDate }

        earlierItems.forEach { r -> results.add(NotificationsAdapter.ViewItem(r, getViewType(r))) }

        notificationsAdapter.results = results
        lastSeenNotificationTime = lastNotificationTime
    }

    private fun searchContacts() {
        if (this::searchContactsRunnable.isInitialized) {
            handler.removeCallbacks(searchContactsRunnable)
        }

        searchContactsRunnable = Runnable {
            dashPayViewModel.searchContacts(query, direction)
        }
        handler.postDelayed(searchContactsRunnable, 500)
    }

    override fun afterTextChanged(s: Editable?) {
        s?.let {
            query = it.toString()
            searchContacts()
        }
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
    }

    override fun onItemClicked(view: View, usernameSearchResult: UsernameSearchResult) {

        startActivityForResult(DashPayUserActivity.createIntent(this,
                usernameSearchResult.username, usernameSearchResult.dashPayProfile, contactRequestSent = usernameSearchResult.requestSent,
                contactRequestReceived = usernameSearchResult.requestReceived), DashPayUserActivity.REQUEST_CODE_DEFAULT)

    }


    override fun onGotoClick() {
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        walletApplication.configuration.setPrefsLastSeenNotificationTime(max(lastSeenNotificationTime, walletApplication.configuration.lastSeenNotificationTime) + DateUtils.SECOND_IN_MILLIS)
        super.onDestroy()
    }

    override fun onAcceptRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
        if (currentPosition == -1) {
            currentPosition = position
            dashPayViewModel.sendContactRequest(usernameSearchResult.fromContactRequest!!.userId)
        }
    }

    override fun onIgnoreRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DashPayUserActivity.REQUEST_CODE_DEFAULT && resultCode == DashPayUserActivity.RESULT_CODE_CHANGED) {
            searchContacts()
        }
    }

}