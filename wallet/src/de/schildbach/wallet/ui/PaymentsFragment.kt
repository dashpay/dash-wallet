/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import de.schildbach.wallet.ui.dashpay.BottomNavFragment
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_payments.*

class PaymentsFragment : BottomNavFragment(R.layout.activity_payments) {

    companion object {
        private const val PREFS_RECENT_TAB = "recent_tab"
        private const val ARGS_ACTIVE_TAB = "extra_active_tab"

        const val ACTIVE_TAB_RECENT = -1
        const val ACTIVE_TAB_PAY = 0
        const val ACTIVE_TAB_RECEIVE = 1

        @JvmStatic
        fun newInstance(activeTab: Int = ACTIVE_TAB_RECENT): PaymentsFragment {
            val args = Bundle()
            args.putInt(ARGS_ACTIVE_TAB, activeTab)

            val instance = PaymentsFragment()
            instance.arguments = args
            return instance
        }
    }

    override val navigationItemId = R.id.payments

    private var saveRecentTab = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar.title = ""
        val appCompatActivity = requireActivity() as AppCompatActivity
        appCompatActivity.setSupportActionBar(toolbar)

        //TODO: Implement FragmentViewPager
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {

            var initialReselection: Boolean = true

            override fun onTabReselected(tab: TabLayout.Tab) {
                if (initialReselection) {
                    onTabSelected(tab)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {

            }

            override fun onTabSelected(tab: TabLayout.Tab) {
                initialReselection = false
                val fragment = when (tab.position) {
                    0 -> PaymentsPayFragment.newInstance()
                    else -> PaymentsReceiveFragment.newInstance()
                }

                childFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, fragment)
                        .commitNow()

                if (saveRecentTab) {
                    val preferences = requireActivity().getPreferences(Context.MODE_PRIVATE)
                    preferences.edit().putInt(PREFS_RECENT_TAB, tab.position).apply()
                }
            }

        })
        activateTab()
    }

    private fun activateTab() {
        val activeTab = requireArguments().getInt(ARGS_ACTIVE_TAB, ACTIVE_TAB_RECENT)
        if (activeTab < 0) {
            val preferences = requireActivity().getPreferences(Context.MODE_PRIVATE)
            val recentTab = preferences.getInt(PREFS_RECENT_TAB, 0)
            tabs.getTabAt(recentTab)!!.select()
            saveRecentTab = true
        } else {
            tabs.getTabAt(activeTab)!!.select()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.close_button_options, menu)
        super.onCreateOptionsMenu(menu, menuInflater)
    }
}
