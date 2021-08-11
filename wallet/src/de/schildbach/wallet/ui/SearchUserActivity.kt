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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.Window
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.ChangeBounds
import androidx.transition.Transition
import androidx.transition.TransitionManager
import de.schildbach.wallet.Constants.USERNAME_MIN_LENGTH
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.observeOnce
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.invite.InviteFriendActivity
import de.schildbach.wallet.ui.invite.InvitesHistoryActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_search_dashpay_profile_1.*
import kotlinx.android.synthetic.main.activity_search_dashpay_profile_root.*
import kotlinx.android.synthetic.main.invite_friend_hint_view.*
import kotlinx.android.synthetic.main.network_unavailable.*
import kotlinx.android.synthetic.main.user_search_empty_result.*
import kotlinx.android.synthetic.main.user_search_loading.*
import org.dash.wallet.common.InteractionAwareActivity

class SearchUserActivity : LockScreenActivity(), TextWatcher, ContactViewHolder.OnItemClickListener,
        ContactViewHolder.OnContactRequestButtonClickListener {

    companion object {

        private const val EXTRA_INIT_QUERY = "extra_init_query"

        fun createIntent(context: Context, initQuery: String?): Intent {
            return Intent(context, SearchUserActivity::class.java).apply {
                putExtra(EXTRA_INIT_QUERY, initQuery)
            }
        }
    }

    private lateinit var dashPayViewModel: DashPayViewModel
    private var handler: Handler = Handler()
    private lateinit var searchUserRunnable: Runnable
    private val adapter: UsernameSearchResultsAdapter = UsernameSearchResultsAdapter(this)
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            with(window) {
                requestFeature(Window.FEATURE_CONTENT_TRANSITIONS)
            }
        }

        setContentView(R.layout.activity_search_dashpay_profile_root)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        setTitle(R.string.add_new_contact)

        search_results_rv.layoutManager = LinearLayoutManager(this)
        search_results_rv.adapter = this.adapter
        this.adapter.itemClickListener = this

        initViewModel()

        var setChanged = false
        val constraintSet1 = ConstraintSet()
        constraintSet1.clone(root)
        val constraintSet2 = ConstraintSet()
        constraintSet2.clone(this, R.layout.activity_search_dashpay_profile_2)

        search.addTextChangedListener(this)
        search.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !setChanged) {
                val transition: Transition = ChangeBounds()
                transition.addListener(object : Transition.TransitionListener {
                    override fun onTransitionEnd(transition: Transition) {
                        finalizeViewsTransition()
                        search_results_rv.visibility = View.VISIBLE
                    }

                    override fun onTransitionResume(transition: Transition) {
                    }

                    override fun onTransitionPause(transition: Transition) {
                    }

                    override fun onTransitionCancel(transition: Transition) {
                    }

                    override fun onTransitionStart(transition: Transition) {
                        layout_title.visibility = View.GONE
                        find_a_user_label.visibility = View.GONE
                        invite_friend_hint_view_dashpay_profile_1.visibility = View.GONE
                    }

                })
                TransitionManager.beginDelayedTransition(root, transition)
                constraintSet2.applyTo(root)
                setChanged = true
            }
        }

        val initQuery = intent.getStringExtra(EXTRA_INIT_QUERY)
        if (!TextUtils.isEmpty(initQuery)) {
            constraintSet2.applyTo(root)
            setChanged = true
            layout_title.visibility = View.GONE
            find_a_user_label.visibility = View.GONE
            finalizeViewsTransition()
            search.setText(initQuery)
        }

        //Developer Mode Feature
        if (!walletApplication.configuration.developerMode) {
            invite_friend_hint_view_dashpay_profile_1.visibility = View.GONE
            invite_friend_hint_view_empty_result.visibility = View.GONE //this line doesn't hide the invite Layout item
        }

        invite_friend_hint_view_dashpay_profile_1.setOnClickListener {
            startInviteFlow()
        }
        invite_friend_hint_view_empty_result.setOnClickListener {
            startInviteFlow()
        }

        network_error_subtitle.setText(R.string.network_error_user_search)
    }

    private fun startInviteFlow() {
        dashPayViewModel.inviteHistory.observeOnce(this, Observer {
            if (it == null || it.isEmpty()) {
                InviteFriendActivity.startOrError(this)
            } else {
                startActivity(InvitesHistoryActivity.createIntent(this))
            }
        })
    }

    private fun finalizeViewsTransition() {
        search.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val searchPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60f, resources.displayMetrics).toInt()
            setPadding(searchPadding, 0, 0, 0)
            typeface = ResourcesCompat.getFont(this@SearchUserActivity, R.font.montserrat_semibold)
        }
    }

    private fun initViewModel() {
        dashPayViewModel = ViewModelProvider(this).get(DashPayViewModel::class.java)
        dashPayViewModel.searchUsernamesLiveData.observe(this, Observer {
            imitateUserInteraction()
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
                } else {
                    showEmptyResult()
                }
            }
        })
        dashPayViewModel.sendContactRequestState.observe(this, Observer {
            imitateUserInteraction()
            adapter.sendContactRequestWorkStateMap = it
        })
        dashPayViewModel.blockchainStateData.observe(this, {
            it?.apply {
                val networkError = impediments.contains(BlockchainState.Impediment.NETWORK)
                updateNetworkErrorVisibility(networkError)
            }
        })
    }

    private fun updateNetworkErrorVisibility(networkError: Boolean) {
        network_error.visibility = if (networkError) View.VISIBLE else View.GONE
        network_error_root.visibility = if (networkError) View.VISIBLE else View.GONE
    }

    private fun startLoading() {
        val query = search.text.toString()
        hideEmptyResult()
        search_loading.visibility = View.VISIBLE
        var loadingText = getString(R.string.search_user_loading)
        loadingText = loadingText.replace("%", "\"<b>$query</b>\"")
        search_loading_label.text = HtmlCompat.fromHtml(loadingText,
                HtmlCompat.FROM_HTML_MODE_COMPACT)
        (search_loading_icon.drawable as AnimationDrawable).start()
    }

    private fun stopLoading() {
        search_loading.visibility = View.GONE
        (search_loading_icon.drawable as AnimationDrawable).start()
    }

    private fun showEmptyResult() {
        search_user_empty_result.visibility = View.VISIBLE
        var emptyResultText = getString(R.string.search_user_no_results)
        emptyResultText += " \"<b>$query</b>\""
        no_results_label.text = HtmlCompat.fromHtml(emptyResultText, HtmlCompat.FROM_HTML_MODE_COMPACT)
    }

    private fun hideEmptyResult() {
        search_user_empty_result.visibility = View.GONE
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
            searchUserRunnable = Runnable {
                dashPayViewModel.searchUsernames(query)
            }
            handler.postDelayed(searchUserRunnable, 500)
        }
    }

    override fun afterTextChanged(s: Editable?) {
        s?.let {
            imitateUserInteraction()
            query = it.toString()
            searchUser()
        }
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
    }

    override fun onItemClicked(view: View, usernameSearchResult: UsernameSearchResult) {
        startActivityForResult(DashPayUserActivity.createIntent(this@SearchUserActivity, usernameSearchResult),
                DashPayUserActivity.REQUEST_CODE_DEFAULT)

        overridePendingTransition(R.anim.slide_in_bottom, R.anim.activity_stay)
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

    override fun onAcceptRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
        dashPayViewModel.sendContactRequest(usernameSearchResult.fromContactRequest!!.userId)
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
