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

package de.schildbach.wallet.ui.payments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityPaymentsBinding

@AndroidEntryPoint
class PaymentsActivity : LockScreenActivity() {

    companion object {
        private const val PREFS_RECENT_TAB = "recent_tab"
        private const val EXTRA_ACTIVE_TAB = "extra_active_tab"

        const val ACTIVE_TAB_RECEIVE = 0
        const val ACTIVE_TAB_PAY = 1

        @JvmStatic
        fun createIntent(context: Context, activeTab: Int? = null): Intent {
            val intent = Intent(context, PaymentsActivity::class.java)

            if (activeTab != null) {
                intent.putExtra(EXTRA_ACTIVE_TAB, activeTab)
            }

            return intent
        }
    }

    private lateinit var binding: ActivityPaymentsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPaymentsBinding.inflate(layoutInflater)
        binding.closeButton.setOnClickListener { finish() }

        binding.tabs.provideOptions(listOf(
            getString(R.string.payments_tab_receive_label),
            getString(R.string.payments_tab_pay_label)
        ))

        binding.tabs.setOnOptionPickedListener { _, index ->
            binding.pager.currentItem = index
        }

        val adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2

            override fun createFragment(position: Int): Fragment {
                val fragment = when (position) {
                    0 -> PaymentsReceiveFragment.newInstance()
                    else -> PaymentsPayFragment.newInstance()
                }
                return fragment
            }
        }

        binding.pager.adapter = adapter
        binding.pager.registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                binding.tabs.setSelectedIndex(position, true)

                if (!intent.hasExtra(EXTRA_ACTIVE_TAB)) {
                    val preferences = getPreferences(Context.MODE_PRIVATE)
                    preferences.edit().putInt(PREFS_RECENT_TAB, position).apply()
                }
            }
        })

        activateTab()
        setContentView(binding.root)
    }

    private fun activateTab() {
        val activeTab = if (intent.hasExtra(EXTRA_ACTIVE_TAB)) {
            intent.getIntExtra(EXTRA_ACTIVE_TAB, 0)
        } else {
            val preferences = getPreferences(Context.MODE_PRIVATE)
            preferences.getInt(PREFS_RECENT_TAB, 0)
        }

        binding.tabs.setSelectedIndex(activeTab, false)
        binding.pager.setCurrentItem(activeTab, false)
    }
}

