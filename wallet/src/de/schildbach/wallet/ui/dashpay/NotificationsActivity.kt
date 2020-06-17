package de.schildbach.wallet.ui.dashpay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.Window
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.DashPayUserActivity
import de.schildbach.wallet.ui.GlobalFooterActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_contacts.*

class NotificationsActivity : GlobalFooterActivity(), TextWatcher,
        NotificationsAdapter.OnItemClickListener {

    companion object {
        private const val EXTRA_MODE = "extra_mode"

        const val MODE_NOTIFICATIONS = 10
        const val MODE_NOTIFICATONS_GLOBAL_FOOTER = 11

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
    protected val notificationsAdapter: NotificationsAdapter = NotificationsAdapter()
    private var query = ""
    private var blockchainIdentityId: String? = null
    private var direction = UsernameSortOrderBy.DATE_ADDED
    protected var mode = MODE_NOTIFICATIONS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        walletApplication = application as WalletApplication

        if (intent.extras != null && intent.extras!!.containsKey(EXTRA_MODE)) {
            mode = intent.extras.getInt(EXTRA_MODE)
        }

        if(mode == MODE_NOTIFICATONS_GLOBAL_FOOTER) {
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

        search.addTextChangedListener(this)
        search.visibility = View.VISIBLE
        icon.visibility = View.VISIBLE
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
    }

    fun processResults(data: List<UsernameSearchResult>) {

        val results = ArrayList<NotificationsAdapter.ViewItem>()
        // process the requests
        val requests = //if (mode != MODE_SELECT_CONTACT)
            data.filter { r -> r.isPendingRequest }.toMutableList()
        //else ArrayList()

        results.add(NotificationsAdapter.ViewItem(null, NotificationsAdapter.NOTIFICATION_NEW_HEADER))
        results.add(NotificationsAdapter.ViewItem(null, NotificationsAdapter.NOTIFICATION_NEW_EMPTY))
        results.add(NotificationsAdapter.ViewItem(null, NotificationsAdapter.NOTIFICATION_EARLIER_HEADER))
        //if (requests.isNotEmpty() && mode != MODE_VIEW_REQUESTS)
        //    results.add(ContactSearchResultsAdapter.ViewItem(null, ContactSearchResultsAdapter.CONTACT_REQUEST_HEADER, requestCount = requestCount))
        requests.forEach { r -> results.add(NotificationsAdapter.ViewItem(r, NotificationsAdapter.NOTIFICATION_CONTACT_REQUEST_RECEIVED)) }

        // process contacts
        val contacts = //if (mode != MODE_VIEW_REQUESTS)
            data.filter { r -> r.requestSent && r.requestReceived }
        //else ArrayList()

        //if (contacts.isNotEmpty())
        //    results.add(NotificationsAdapter.ViewItem(null, NotificationsAdapter.CONTACT_HEADER))
        contacts.forEach { r -> results.add(NotificationsAdapter.ViewItem(r, NotificationsAdapter.NOTIFICATION_CONTACT_ADDED)) }

        notificationsAdapter.results = results
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
        when {
            usernameSearchResult.isPendingRequest -> {
                // TODO: show screen for the user has sent us a request
                startActivity(DashPayUserActivity.createIntent(this,
                        usernameSearchResult.username, usernameSearchResult.dashPayProfile, contactRequestSent = false,
                        contactRequestReceived = true))
            }
            !usernameSearchResult.isPendingRequest -> {
                // TODO: show screen for the user that is our contact
                // How do we handle if this activity was started from the Payments Screen?
                startActivity(DashPayUserActivity.createIntent(this,
                        usernameSearchResult.username, usernameSearchResult.dashPayProfile, contactRequestSent = usernameSearchResult.requestSent,
                        contactRequestReceived = usernameSearchResult.requestReceived))
            }
        }
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

}