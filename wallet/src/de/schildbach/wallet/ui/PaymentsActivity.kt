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
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.Toolbar
import com.google.android.material.tabs.TabLayout
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_payments.*

class PaymentsActivity : GlobalFooterActivity() {

    companion object {
        private const val PREFS_RECENT_TAB = "recent_tab"

        private const val EXTRA_ACTIVE_TAB = "extra_active_tab"

        const val ACTIVE_TAB_RECENT = -1
        const val ACTIVE_TAB_PAY = 0
        const val ACTIVE_TAB_RECEIVE = 1

        @JvmStatic
        fun createIntent(context: Context, activeTab: Int): Intent {
            val intent = Intent(context, PaymentsActivity::class.java)
            intent.putExtra(EXTRA_ACTIVE_TAB, activeTab)
            return intent
        }
    }

    private var saveRecentTab = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithFooter(R.layout.activity_payments)
        activateGotoButton()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        setTitle(R.string.payments_title)

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
                supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, fragment)
                        .commitNow()

                if (saveRecentTab) {
                    val preferences = getPreferences(Context.MODE_PRIVATE)
                    preferences.edit().putInt(PREFS_RECENT_TAB, tab.position).apply()
                }
            }

        })
        activateTab()
    }

    private fun activateTab() {
        val activeTab = intent.getIntExtra(EXTRA_ACTIVE_TAB, ACTIVE_TAB_RECENT)
        if (activeTab < 0) {
            val preferences = getPreferences(Context.MODE_PRIVATE)
            val recentTab = preferences.getInt(PREFS_RECENT_TAB, 0)
            tabs.getTabAt(recentTab)!!.select()
            saveRecentTab = true
        } else {
            tabs.getTabAt(activeTab)!!.select()
        }
    }

    override fun onGotoClick() {
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.payment_options, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.option_close -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
