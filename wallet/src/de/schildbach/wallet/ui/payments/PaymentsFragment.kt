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
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.transition.Fade
import androidx.transition.Slide
import androidx.transition.TransitionSet
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentPaymentsBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.observeOnDestroy
import org.dash.wallet.common.ui.segmented_picker.SegmentedOption
import org.dash.wallet.common.ui.viewBinding
import androidx.core.content.edit
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.segmented_picker.SegmentedPicker
import javax.inject.Inject

@AndroidEntryPoint
class PaymentsFragment : Fragment(R.layout.fragment_payments) {

    companion object {
        private const val PREFS_RECENT_TAB = "recent_tab"
        const val ARG_ACTIVE_TAB = "active_tab"
        const val ARG_SOURCE = "source"

        // Logical tab ids — stable regardless of whether the flag-gated
        // "Internal" tab is inserted between Receive and Send.
        const val ACTIVE_TAB_RECEIVE = 0
        const val ACTIVE_TAB_PAY = 1
        const val ACTIVE_TAB_INTERNAL = 2

        /**
         * Tab ids in display order. With [showInternalTab] off this is exactly
         * the pre-shielded two-tab layout (Receive | Send); with it on, the
         * "Internal" tab sits between them (Figma 1693:15911).
         */
        fun tabIdsFor(showInternalTab: Boolean): List<Int> = if (showInternalTab) {
            listOf(ACTIVE_TAB_RECEIVE, ACTIVE_TAB_INTERNAL, ACTIVE_TAB_PAY)
        } else {
            listOf(ACTIVE_TAB_RECEIVE, ACTIVE_TAB_PAY)
        }
    }

    @Inject lateinit var dashPayConfig: DashPayConfig

    private val binding by viewBinding(FragmentPaymentsBinding::bind)
    private var selectedTab by mutableIntStateOf(0)

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

        // The shielded "Internal" tab is flag-gated; the flag is read once at
        // screen build. Flags off ⇒ exactly the two pre-existing tabs.
        viewLifecycleOwner.lifecycleScope.launch {
            val showInternalTab = Constants.SUPPORTS_PLATFORM &&
                dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true
            setupTabs(tabIdsFor(showInternalTab))
        }
    }

    private fun setupTabs(tabIds: List<Int>) {
        val options = tabIds.map { tabId ->
            when (tabId) {
                ACTIVE_TAB_RECEIVE -> SegmentedOption(
                    getString(R.string.payments_tab_receive_label),
                    R.drawable.ic_arrow_down
                )
                ACTIVE_TAB_INTERNAL -> SegmentedOption(
                    getString(R.string.shielded_tab_internal),
                    R.drawable.ic_arrows_internal
                )
                else -> SegmentedOption(
                    getString(R.string.payments_tab_pay_label),
                    R.drawable.ic_arrow_up
                )
            }
        }

        val binding = this.binding
        binding.tabs.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.tabs.setContent {
            DashWalletTheme {
                SegmentedPicker(
                    options,
                    modifier = Modifier.height(32.dp),
                    selectedIndex = selectedTab
                ) { option, index ->
                    selectedTab = index
                    binding.pager.currentItem = index
                }
            }
        }

        val adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = tabIds.size

            override fun createFragment(position: Int): Fragment {
                val fragment = when (tabIds[position]) {
                    ACTIVE_TAB_RECEIVE -> PaymentsReceiveFragment.newInstance()
                    ACTIVE_TAB_INTERNAL -> PaymentsInternalFragment.newInstance()
                    else -> PaymentsPayFragment.newInstance(source = arguments?.getString(ARG_SOURCE) ?: "")
                }
                return fragment
            }
        }

        binding.pager.adapter = adapter
        viewLifecycleOwner.observeOnDestroy { binding.pager.adapter = null }
        binding.pager.registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                selectedTab = position

                if (arguments?.containsKey(ARG_ACTIVE_TAB) != true) {
                    val preferences = requireActivity().getPreferences(Context.MODE_PRIVATE)
                    // store the logical tab id — stays valid if the flag flips
                    preferences.edit { putInt(PREFS_RECENT_TAB, tabIds[position]) }
                }
            }
        })

        activateTab(tabIds)
    }

    private fun activateTab(tabIds: List<Int>) {
        val activeTabId = if (arguments?.containsKey(ARG_ACTIVE_TAB) == true) {
            requireArguments().getInt(ARG_ACTIVE_TAB, ACTIVE_TAB_RECEIVE)
        } else {
            val preferences = requireActivity().getPreferences(Context.MODE_PRIVATE)
            preferences.getInt(PREFS_RECENT_TAB, ACTIVE_TAB_RECEIVE)
        }

        val position = tabIds.indexOf(activeTabId).takeIf { it >= 0 } ?: 0
        selectedTab = position
        binding.pager.setCurrentItem(position, false)
    }
}
