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
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_search_dashpay_profile.*
import org.dash.wallet.common.InteractionAwareActivity
import org.w3c.dom.Document

class SearchDashPayProfileActivity : InteractionAwareActivity(), TextWatcher {

    private lateinit var dashPayViewModel: DashPayViewModel
    private lateinit var walletApplication: WalletApplication
    private var handler: Handler = Handler()
    private lateinit var searchDashPayProfileRunnable: Runnable
    private val adapter: DashPayProfilesAdapter = DashPayProfilesAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_search_dashpay_profile)
        walletApplication = application as WalletApplication

        search_results_rv.layoutManager = LinearLayoutManager(this)
        search_results_rv.adapter = this.adapter

        initViewModel()
        searchDashPayProfile("")
    }

    private fun initViewModel() {
        dashPayViewModel = ViewModelProvider(this).get(DashPayViewModel::class.java)
        dashPayViewModel.getSearchDashPayProfileLiveDatha.observe(this, Observer {
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
        handler.postDelayed(searchDashPayProfileRunnable, 600)
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
}