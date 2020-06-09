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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.ChangeBounds
import androidx.transition.Transition
import androidx.transition.TransitionManager
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.GlobalFooterActivity
import de.schildbach.wallet.ui.SearchUserActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_contacts_1.*


class ContactsActivity : GlobalFooterActivity(), TextWatcher, ContactSearchResultsAdapter.OnSortOrderChangedListener, ContactSearchResultsAdapter.OnItemClickListener {



    private lateinit var dashPayViewModel: DashPayViewModel
    private lateinit var walletApplication: WalletApplication
    private var handler: Handler = Handler()
    private lateinit var searchDashPayProfileRunnable: Runnable
    private val contactsAdapter: ContactSearchResultsAdapter = ContactSearchResultsAdapter(this)
    private var query = ""
    private var blockchainIdentityId: String? = null
    private var direction = UsernameSortOrderBy.DISPLAY_NAME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            with(window) {
                requestFeature(Window.FEATURE_CONTENT_TRANSITIONS)
            }
        }

        setContentViewWithFooter(R.layout.activity_contacts_root)
        walletApplication = application as WalletApplication

        activateContactsButton()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        setTitle(R.string.contacts_title)

        contacts_rv.layoutManager = LinearLayoutManager(this)
        contacts_rv.adapter = this.contactsAdapter
        this.contactsAdapter.itemClickListener = this
        contacts_rv.itemAnimator = null


        initViewModel()

        var setChanged = false
        val constraintSet1 = ConstraintSet()
        constraintSet1.clone(root)
        val constraintSet2 = ConstraintSet()
        constraintSet2.clone(this, R.layout.activity_contacts_2)

        search.addTextChangedListener(this)
        search.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !setChanged && search.text!!.isNotEmpty()) {
                val transition: Transition = ChangeBounds()
                transition.addListener(object : Transition.TransitionListener {
                    override fun onTransitionEnd(transition: Transition) {
                        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        search.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        val searchPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                                60f, resources.displayMetrics).toInt()
                        search.setPadding(searchPadding, 0, 0, 0)
                        search.typeface = ResourcesCompat.getFont(this@ContactsActivity,
                                R.font.montserrat_semibold)
                    }

                    override fun onTransitionResume(transition: Transition) {
                    }

                    override fun onTransitionPause(transition: Transition) {
                    }

                    override fun onTransitionCancel(transition: Transition) {
                    }

                    override fun onTransitionStart(transition: Transition) {
                    }
                })
                TransitionManager.beginDelayedTransition(root, transition)
                //constraintSet2.applyTo(root)
                setChanged = true
            }
        }
        /*val adapter = ArrayAdapter.createFromResource(sort_filter.context, R.array.contacts_sort, R.layout.custom_spinner_item)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sort_filter.adapter = adapter
        sort_filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
                when (position) {
                    0 -> direction = Direction.DISPLAY_NAME
                    1 -> direction = Direction.USERNAME
                    2 -> direction = Direction.DATE_ADDED
                    3 -> direction = Direction.LAST_ACTIVITY
                }
                searchContacts()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }*/
        searchContacts()
    }

    private fun initViewModel() {
        dashPayViewModel = ViewModelProvider(this).get(DashPayViewModel::class.java)
        dashPayViewModel.searchContactsLiveData.observe(this, Observer {
            if (Status.LOADING == it.status) {
                startLoading()
            } else {
                stopLoading()
                if (it.data != null) {

                    val requests = it.data.filter { r -> r.isPendingRequest }.toMutableList()
                    val requestCount = requests.size
                    while (requests.size > 3) {
                        requests.remove(requests[requests.size - 1])
                    }

                    val results = ArrayList<ContactSearchResultsAdapter.ViewItem>()
                    if (requests.isNotEmpty())
                        results.add(ContactSearchResultsAdapter.ViewItem(null, ContactSearchResultsAdapter.CONTACT_REQUEST_HEADER, requestCount =  requestCount))
                    requests.forEach { r -> results.add(ContactSearchResultsAdapter.ViewItem(r, ContactSearchResultsAdapter.CONTACT_REQUEST)) }

                    val contacts = it.data.filter { r -> r.haveWeRequested }
                    if (contacts.isNotEmpty())
                        results.add(ContactSearchResultsAdapter.ViewItem(null, ContactSearchResultsAdapter.CONTACT_HEADER))
                    contacts.forEach { r -> results.add(ContactSearchResultsAdapter.ViewItem(r, ContactSearchResultsAdapter.CONTACT)) }


                    contactsAdapter.results = results
                    if (it.data.isEmpty()) {
                        showEmptyResult()
                    } else {
                        hideEmptyResult()
                    }
                } else {
                    showEmptyResult()
                }
            }
        })
        AppDatabase.getAppDatabase().blockchainIdentityDataDao().load().observe(this, Observer {
            if (it != null) {
                //TODO: we don't have an easy way of getting the identity id (userId)
                val tx = walletApplication.wallet.getTransaction(it.creditFundingTxId)
                val cftx = walletApplication.wallet.getCreditFundingTransaction(tx)
                blockchainIdentityId = cftx.creditBurnIdentityIdentifier.toStringBase58()
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.contacts_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    private fun startLoading() {
        val query = search.text.toString()
        hideEmptyResult()
        //search_loading.visibility = View.VISIBLE
        var loadingText = getString(R.string.contacts_loading)
        loadingText = loadingText.replace("%", "\"<b>$query</b>\"")
       // search_loading_label.text = HtmlCompat.fromHtml(loadingText,
       //         HtmlCompat.FROM_HTML_MODE_COMPACT)
        //(search_loading_icon.drawable as AnimationDrawable).start()
    }

    private fun stopLoading() {
        //search_loading.visibility = View.GONE
        //(search_loading_icon.drawable as AnimationDrawable).start()
    }

    private fun showEmptyResult() {
        //search_user_empty_result.visibility = View.VISIBLE
        var emptyResultText = getString(R.string.contacts_no_results)
        emptyResultText += " \"<b>$query</b>\""
        //no_results_label.text = HtmlCompat.fromHtml(emptyResultText, HtmlCompat.FROM_HTML_MODE_COMPACT)
    }

    private fun hideEmptyResult() {
        //search_user_empty_result.visibility = View.GONE
    }

    override fun onSortOrderChangedListener(direction: UsernameSortOrderBy) {
        this.direction = direction
        searchContacts()
    }

    private fun searchContacts() {
        contactsAdapter.results = listOf()
        hideEmptyResult()
        if (this::searchDashPayProfileRunnable.isInitialized) {
            handler.removeCallbacks(searchDashPayProfileRunnable)
        }

        searchDashPayProfileRunnable = Runnable {
            dashPayViewModel.searchContacts(query, direction)
        }
        handler.postDelayed(searchDashPayProfileRunnable, 500)
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
            R.id.contacts_add_contact -> {
                startActivity(Intent(this, SearchUserActivity::class.java))
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
