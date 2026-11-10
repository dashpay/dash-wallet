/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.ui.payments

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet.ui.main.MainActivityExt.setBottomNavigationVisible
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ACTIVE_TAB_INTERNAL
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ACTIVE_TAB_RECEIVE
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ARG_ACTIVE_TAB
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ARG_SOURCE
import de.schildbach.wallet.ui.payments.internal.InternalTransferFragment
import de.schildbach.wallet.ui.payments.send.AdvancedSendFragment
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentAdvancedPaymentsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.observeOnDestroy
import org.dash.wallet.common.ui.segmented_picker.SegmentedOption
import org.dash.wallet.common.ui.segmented_picker.SegmentedPicker
import org.dash.wallet.common.ui.segmented_picker.SegmentedPickerStyle
import org.dash.wallet.common.ui.viewBinding

/**
 * The Advanced mode payments screen (MO-1123, Figma 6860:47472 / 7062:49303): a
 * Receive / Internal / Send picker right under the status bar, over the payments pages.
 * MainActivity's bottom navigation shows on the Receive tab only ([showsBottomNavigation]):
 * the destination has `ShowNavBar`, and this screen hides the bar on the other tabs.
 *
 * Receive is [AdvancedReceiveFragment] (the Compose [ReceivePage]), Send is
 * [AdvancedSendFragment] (MO-1121) and Internal is [InternalTransferFragment] (MO-1125), shown
 * whatever the shielded flag (see [advancedPaymentsTabIds]). The enter/exit transitions are on
 * the NavOptions (see [paymentsRoute]). There is no close control: like the other
 * bottom-nav screens it is left through the bottom navigation or Back.
 *
 * ViewPager2 keeps an offscreen page at STARTED and lifts only the selected one to
 * RESUMED, which is when [AdvancedReceiveFragment] (re)reads its address.
 */
@AndroidEntryPoint
class AdvancedPaymentsFragment : Fragment(R.layout.fragment_advanced_payments) {

    companion object {
        // Separate from PaymentsFragment's recent tab, which is private to it.
        private const val PREFS_RECENT_TAB = "advanced_payments_recent_tab"
        private const val STATE_SELECTED_TAB = "selected_tab"

        /** The tabs on show, in display order: fixed for the build ([advancedPaymentsTabIds]). */
        private val TAB_IDS: List<Int> = advancedPaymentsTabIds(Constants.SUPPORTS_PLATFORM)

        /** The pager position of [tabId] in [tabIds]; the first page if it is not shown. */
        @VisibleForTesting
        fun positionOf(tabIds: List<Int>, tabId: Int): Int =
            tabIds.indexOf(tabId).takeIf { it >= 0 } ?: 0

        /** MainActivity's bottom navigation shows on the Receive tab only. */
        @VisibleForTesting
        fun showsBottomNavigation(tabId: Int): Boolean = tabId == ACTIVE_TAB_RECEIVE
    }

    private val binding by viewBinding(FragmentAdvancedPaymentsBinding::bind)

    /**
     * The logical tab the user is on, and the only selection state: the picker and the pager
     * positions follow from it ([positionOf]). Set in [onCreate]; kept across view recreation.
     */
    private var selectedTabId by mutableStateOf(ACTIVE_TAB_RECEIVE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTabId = if (savedInstanceState?.containsKey(STATE_SELECTED_TAB) == true) {
            savedInstanceState.getInt(STATE_SELECTED_TAB)
        } else {
            initialTabId()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = this.binding

        // Own insets: under MainActivity's fitsSystemWindows container these arrive
        // already consumed (zero), so nothing is doubled; hosted anywhere else (a
        // sheet), the screen still clears the system bars and the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.header.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.header.setContent {
            DashWalletTheme {
                Header()
            }
        }

        val adapter = TabsAdapter(this, TAB_IDS, source = arguments?.getString(ARG_SOURCE) ?: "")
        // Only the top picker changes tabs: a sideways drag belongs to the page's own
        // content (the Receive balance switch scrolls sideways), never to the pager.
        binding.pager.isUserInputEnabled = false
        viewLifecycleOwner.observeOnDestroy { binding.pager.adapter = null }
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val tabId = TAB_IDS.getOrNull(position) ?: return
                selectedTabId = tabId
                applyBottomNavigation()

                if (arguments?.containsKey(ARG_ACTIVE_TAB) != true) {
                    requireActivity().getPreferences(Context.MODE_PRIVATE)
                        .edit { putInt(PREFS_RECENT_TAB, tabId) }
                }
            }
        })

        // Decide the tab before attaching: attaching can report a page selection of its own.
        val position = positionOf(TAB_IDS, selectedTabId)
        // Attached populated (the tab set is fixed), as PaymentsFragment does: ViewPager2 hands
        // the adapter its saved pages when it is attached, so after a rotation the retained
        // Receive child and its ViewModel are reused and the selected balance is kept.
        binding.pager.adapter = adapter
        selectedTabId = TAB_IDS[position]
        binding.pager.setCurrentItem(position, false)
        // setCurrentItem reports no selection when the position is unchanged.
        applyBottomNavigation()

        // MainActivity's destination listener sets the bottom navigation from `ShowNavBar` (true
        // here) whenever this destination becomes current: on a return from a pushed destination
        // or a dialog destination. It can be registered after this one (setupBottomNavigation runs
        // again once the wallet is set up), so the tab's visibility is applied after every
        // listener has run, from the main queue. Registering dispatches the current
        // destination, so a recreated view (back from a fragment destination) applies it too.
        val navController = findNavController()
        val destinationListener = NavController.OnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.advancedPaymentsFragment) {
                // Dispatchers.Main (not immediate) always posts; cancelled with the view.
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) { applyBottomNavigation() }
            }
        }
        navController.addOnDestinationChangedListener(destinationListener)
        viewLifecycleOwner.observeOnDestroy { navController.removeOnDestinationChangedListener(destinationListener) }
    }

    /**
     * Shows the bottom navigation for the selected tab, only while this destination is current:
     * once another destination is, its `ShowNavBar` (set by the destination listener) owns the bar,
     * and a page selection during this screen's exit must not override it.
     */
    private fun applyBottomNavigation() {
        val tabId = selectedTabId
        if (!isAdded) return
        val mainActivity = activity as? MainActivity ?: return
        if (findNavController().currentDestination?.id != R.id.advancedPaymentsFragment) return
        mainActivity.setBottomNavigationVisible(showsBottomNavigation(tabId))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_TAB, selectedTabId)
    }

    private fun initialTabId(): Int =
        if (arguments?.containsKey(ARG_ACTIVE_TAB) == true) {
            requireArguments().getInt(ARG_ACTIVE_TAB, ACTIVE_TAB_RECEIVE)
        } else {
            requireActivity().getPreferences(Context.MODE_PRIVATE)
                .getInt(PREFS_RECENT_TAB, ACTIVE_TAB_RECEIVE)
        }

    @Composable
    private fun Header() {
        val colors = LocalDashColors.current
        val ids = TAB_IDS
        val selected = selectedTabId

        val receiveLabel = stringResource(R.string.payments_tab_receive_label)
        val internalLabel = stringResource(R.string.shielded_tab_internal)
        val payLabel = stringResource(R.string.payments_tab_pay_label)
        val options = remember(ids, receiveLabel, internalLabel, payLabel) {
            ids.map { tabId ->
                when (tabId) {
                    ACTIVE_TAB_RECEIVE -> SegmentedOption(receiveLabel, R.drawable.ic_arrow_down)
                    ACTIVE_TAB_INTERNAL -> SegmentedOption(internalLabel, R.drawable.ic_arrows_internal)
                    else -> SegmentedOption(payLabel, R.drawable.ic_arrow_up)
                }
            }
        }
        // Figma 7062:49306 (20dp margins, 7062:49303): 70dp, 20dp corners, 4dp inset, 16dp thumb corners,
        // icon above the title, no dividers, gray300 @ 20% track.
        SegmentedPicker(
            options = options,
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth()
                .height(70.dp),
            selectedIndex = positionOf(ids, selected),
            style = SegmentedPickerStyle(
                backgroundColor = colors.gray300.copy(alpha = 0.2f),
                cornerRadius = 20f,
                containerPadding = 4f,
                thumbCornerRadius = 16f,
                showDividers = false,
                iconAboveTitle = true
            )
        ) { _, index ->
            val tabId = ids.getOrNull(index) ?: return@SegmentedPicker
            selectedTabId = tabId
            applyBottomNavigation()
            binding.pager.currentItem = positionOf(ids, tabId)
        }
    }

    /** One page per tab of the fixed [tabIds] (default positional ids: the set never changes). */
    private class TabsAdapter(
        fragment: Fragment,
        private val tabIds: List<Int>,
        private val source: String
    ) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = tabIds.size

        override fun createFragment(position: Int): Fragment = when (tabIds[position]) {
            ACTIVE_TAB_RECEIVE -> AdvancedReceiveFragment.newInstance()
            ACTIVE_TAB_INTERNAL -> InternalTransferFragment.newInstance()
            else -> AdvancedSendFragment.newInstance(source = source)
        }
    }
}

/**
 * The tab set for the Advanced payments screen (MO-1125): the "Internal" tab is always shown
 * where Platform is supported, whatever the shielded flag — with the flag off, the Internal
 * transfer shows Shielded disabled. Basic mode ([PaymentsFragment]) keeps its own gating.
 */
internal fun advancedPaymentsTabIds(supportsPlatform: Boolean): List<Int> = PaymentsFragment.tabIdsFor(supportsPlatform)
