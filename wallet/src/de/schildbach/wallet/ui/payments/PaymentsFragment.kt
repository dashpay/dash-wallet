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
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentPaymentsBinding
import org.dash.wallet.common.ui.segmented_picker.SegmentedOption
import org.dash.wallet.common.ui.viewBinding

@AndroidEntryPoint
class PaymentsFragment : Fragment(R.layout.fragment_payments) {

    companion object {
        private const val PREFS_RECENT_TAB = "recent_tab"
        private const val EXTRA_ACTIVE_TAB = "active_tab"

        const val ACTIVE_TAB_RECEIVE = 0 // TODO: should be used
        const val ACTIVE_TAB_PAY = 1

        @JvmStatic
        fun newInstance(activeTab: Int? = null): PaymentsFragment {
            val instance = PaymentsFragment()

            if (activeTab != null) {
                instance.arguments = bundleOf(EXTRA_ACTIVE_TAB to activeTab)
            }

            return instance
        }
    }

    private val binding by viewBinding(FragmentPaymentsBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = MaterialFadeThrough()

        binding.tabs.provideOptions(listOf(
            SegmentedOption(getString(R.string.payments_tab_receive_label), R.drawable.ic_arrow_down),
            SegmentedOption(getString(R.string.payments_tab_pay_label), R.drawable.ic_arrow_up)
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

                if (arguments?.containsKey(EXTRA_ACTIVE_TAB) != true) {
                    val preferences = requireActivity().getPreferences(Context.MODE_PRIVATE)
                    preferences.edit().putInt(PREFS_RECENT_TAB, position).apply()
                }
            }
        })

        activateTab()
    }

    private fun activateTab() {
        val activeTab = if (arguments?.containsKey(EXTRA_ACTIVE_TAB) == true) {
            requireArguments().getInt(EXTRA_ACTIVE_TAB, 0)
        } else {
            val preferences = requireActivity().getPreferences(Context.MODE_PRIVATE)
            preferences.getInt(PREFS_RECENT_TAB, 0)
        }

        binding.tabs.setSelectedIndex(activeTab, false)
        binding.pager.setCurrentItem(activeTab, false)
    }
}