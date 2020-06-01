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

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.drawable.AnimationDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
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
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_search_dashpay_profile_1.*
import kotlinx.android.synthetic.main.user_search_empty_result.*
import kotlinx.android.synthetic.main.user_search_loading.*
import org.dash.wallet.common.InteractionAwareActivity
import org.dashevo.dpp.document.Document


class SearchUserActivity : InteractionAwareActivity(), TextWatcher, DashPayProfilesAdapter.OnItemClickListener {

    private lateinit var dashPayViewModel: DashPayViewModel
    private lateinit var walletApplication: WalletApplication
    private var handler: Handler = Handler()
    private lateinit var searchDashPayProfileRunnable: Runnable
    private val adapter: DashPayProfilesAdapter = DashPayProfilesAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            with(window) {
                requestFeature(Window.FEATURE_CONTENT_TRANSITIONS)
            }
        }

        setContentView(R.layout.activity_search_dashpay_profile_root)
        walletApplication = application as WalletApplication

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
                        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        search.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        val searchPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                                60f, resources.displayMetrics).toInt()
                        search.setPadding(searchPadding, 0, 0, 0)
                        search.typeface = ResourcesCompat.getFont(this@SearchUserActivity,
                                R.font.montserrat_semibold)
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
                    }

                })
                TransitionManager.beginDelayedTransition(root, transition)
                constraintSet2.applyTo(root)
                setChanged = true
            }
        }

    }

    private fun initViewModel() {
        dashPayViewModel = ViewModelProvider(this).get(DashPayViewModel::class.java)
        dashPayViewModel.getProfileSearchLiveData.observe(this, Observer {
            if (Status.LOADING == it.status) {
                startLoading()
            } else {
                stopLoading()
                if (it.data != null) {
                    adapter.profiles = it.data
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
    }

    private fun startLoading() {
        val query = search.text.toString()
        hideEmptyResult()
        search_loading.visibility = View.VISIBLE
        val loadingText = getString(R.string.search_user_loading).replace("%", "\"<b>$query</b>\"")
        search_loading_label.text = HtmlCompat.fromHtml(loadingText, HtmlCompat.FROM_HTML_MODE_COMPACT)
        (search_loading_icon.drawable as AnimationDrawable).start()
    }

    private fun stopLoading() {
        search_loading.visibility = View.GONE
        (search_loading_icon.drawable as AnimationDrawable).start()
    }

    private fun showEmptyResult() {
        search_user_empty_result.visibility = View.VISIBLE
        var emptyResultText = getString(R.string.search_user_no_results)
        emptyResultText += " \"<b>${search.text.toString()}</b>\""
        no_results_label.text = HtmlCompat.fromHtml(emptyResultText, HtmlCompat.FROM_HTML_MODE_COMPACT)
    }

    private fun hideEmptyResult() {
        search_user_empty_result.visibility = View.GONE
    }

    private fun searchDashPayProfile(query: String) {
        adapter.profiles = listOf()
        if (query.length < 3) {
            return
        }
        if (this::searchDashPayProfileRunnable.isInitialized) {
            handler.removeCallbacks(searchDashPayProfileRunnable)
        }
        searchDashPayProfileRunnable = Runnable {
            dashPayViewModel.searchDashPayProfile(query)
        }
        handler.postDelayed(searchDashPayProfileRunnable, 500)
    }

    override fun afterTextChanged(s: Editable?) {
        val query = s?.toString()
        if (query != null) {
            searchDashPayProfile(query)
        }
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
    }

    override fun onItemClicked(view: View, document: Document) {
        val intent = Intent(this, DashPayProfileActivity::class.java)
        intent.putExtra("avatarUrl", document.data["avatarUrl"].toString())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.transitionName = "avatar"
            val options = ActivityOptions.makeSceneTransitionAnimation(this, view,
                    "avatar")
            startActivity(intent, options.toBundle())
        } else {
            startActivity(intent)
        }
    }
}
