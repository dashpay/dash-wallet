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
import android.view.animation.AccelerateInterpolator
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.transition.Fade
import androidx.transition.Slide
import androidx.transition.TransitionSet
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentPaymentsBinding
import org.dash.wallet.common.ui.observeOnDestroy
import org.dash.wallet.common.ui.segmented_picker.SegmentedOption
import org.dash.wallet.common.ui.viewBinding

@AndroidEntryPoint
class PaymentsFragment : Fragment(R.layout.fragment_payments) {

    companion object {
        private const val PREFS_RECENT_TAB = "recent_tab"
        const val ARG_ACTIVE_TAB = "active_tab"

        const val ACTIVE_TAB_RECEIVE = 0
        const val ACTIVE_TAB_PAY = 1
    }

    private val binding by viewBinding(FragmentPaymentsBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = TransitionSet().apply {
            duration = 200
            addTransition(Slide())
            addTransition(Fade().apply { interpolator = AccelerateInterpolator() })
        }
        returnTransition = MaterialFadeThrough()

        binding.closeButton.setOnClickListener {
            binding.closeButton.isVisible = false
            findNavController().popBackStack()
        }

        binding.tabs.provideOptions(
            listOf(
                SegmentedOption(getString(R.string.payments_tab_receive_label), R.drawable.ic_arrow_down),
                SegmentedOption(getString(R.string.payments_tab_pay_label), R.drawable.ic_arrow_up)
            )
        )

        binding.tabs.setOnOptionPickedListener { _, index ->
            binding.pager.currentItem = index
        }

        val adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2

            override fun createFragment(position: Int): Fragment {
                val fragment = when (position) {
                    ACTIVE_TAB_RECEIVE -> PaymentsReceiveFragment.newInstance()
                    else -> PaymentsPayFragment.newInstance()
                }
                return fragment
            }
        }

        binding.pager.adapter = adapter
        viewLifecycleOwner.observeOnDestroy { binding.pager.adapter = null }
        binding.pager.registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                binding.tabs.setSelectedIndex(position, true)

                if (arguments?.containsKey(ARG_ACTIVE_TAB) != true) {
                    val preferences = requireActivity().getPreferences(Context.MODE_PRIVATE)
                    preferences.edit().putInt(PREFS_RECENT_TAB, position).apply()
                }
            }
        })

        activateTab()
    }

    private fun activateTab() {
        val activeTab = if (arguments?.containsKey(ARG_ACTIVE_TAB) == true) {
            requireArguments().getInt(ARG_ACTIVE_TAB, ACTIVE_TAB_RECEIVE)
        } else {
            val preferences = requireActivity().getPreferences(Context.MODE_PRIVATE)
            preferences.getInt(PREFS_RECENT_TAB, ACTIVE_TAB_RECEIVE)
        }

        binding.tabs.setSelectedIndex(activeTab, false)
        binding.pager.setCurrentItem(activeTab, false)
    }
}
