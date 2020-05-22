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

import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.ChangeBounds
import androidx.transition.Transition
import androidx.transition.TransitionManager
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_search_dashpay_profile_1.*
import org.dash.wallet.common.InteractionAwareActivity


class SearchDashPayProfileActivity : InteractionAwareActivity(), TextWatcher {

    private lateinit var dashPayViewModel: DashPayViewModel
    private lateinit var walletApplication: WalletApplication
    private var handler: Handler = Handler()
    private lateinit var searchDashPayProfileRunnable: Runnable
    private val adapter: DashPayProfilesAdapter = DashPayProfilesAdapter()

    class UserSearchViewModel : ViewModel() {
        val name = MutableLiveData<String>()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                        search.typeface = ResourcesCompat.getFont(this@SearchDashPayProfileActivity,
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
            if (it.data != null) {
                adapter.profiles = it.data
            }
        })
    }

    private fun searchDashPayProfile(query: String) {
        if (this::searchDashPayProfileRunnable.isInitialized) {
            handler.removeCallbacks(searchDashPayProfileRunnable)
        }
        searchDashPayProfileRunnable = Runnable {
            dashPayViewModel.searchDashPayProfile(query)
        }
        handler.postDelayed(searchDashPayProfileRunnable, 333)
    }

    override fun afterTextChanged(s: Editable?) {
        val query = s?.toString()
        if (query != null) {
            Log.d("SearchDashPayProfile", query)
            searchDashPayProfile(query)
        }
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
    }
}
