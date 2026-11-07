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

import android.annotation.SuppressLint
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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ACTIVE_TAB_INTERNAL
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ACTIVE_TAB_RECEIVE
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ARG_ACTIVE_TAB
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ARG_SOURCE
import de.schildbach.wallet.ui.payments.internal.InternalTransferFragment
import de.schildbach.wallet.ui.payments.send.AdvancedSendFragment
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentAdvancedPaymentsBinding
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
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
 * Receive / Internal / Send picker right under the status bar, above the bottom
 * navigation (`ShowNavBar` on the destination), over the payments pages.
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

        /** The pager position of [tabId] in [tabIds]; the first page if it is not shown. */
        @VisibleForTesting
        fun positionOf(tabIds: List<Int>, tabId: Int): Int =
            tabIds.indexOf(tabId).takeIf { it >= 0 } ?: 0
    }

    private val binding by viewBinding(FragmentAdvancedPaymentsBinding::bind)
    private val viewModel by viewModels<AdvancedPaymentsViewModel>()

    /**
     * The logical tab the user is on, and the only selection state: the picker and the pager
     * positions follow from it ([positionOf]). Kept across view recreation and tab-set changes.
     */
    private var selectedTabId by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey(STATE_SELECTED_TAB) == true) {
            selectedTabId = savedInstanceState.getInt(STATE_SELECTED_TAB)
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

        // Attached by showTabs once the first tab set is in, not here: see there.
        val adapter = TabsAdapter(this, source = arguments?.getString(ARG_SOURCE) ?: "")
        // Only the top picker changes tabs: a sideways drag belongs to the page's own
        // content (the Receive balance switch scrolls sideways), never to the pager.
        binding.pager.isUserInputEnabled = false
        viewLifecycleOwner.observeOnDestroy { binding.pager.adapter = null }
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val tabId = adapter.tabIds.getOrNull(position) ?: return
                selectedTabId = tabId

                if (arguments?.containsKey(ARG_ACTIVE_TAB) != true) {
                    requireActivity().getPreferences(Context.MODE_PRIVATE)
                        .edit { putInt(PREFS_RECENT_TAB, tabId) }
                }
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState
                    .map { it.tabIds }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collect { showTabs(adapter, it) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedTabId?.let { outState.putInt(STATE_SELECTED_TAB, it) }
    }

    private fun showTabs(adapter: TabsAdapter, newTabIds: List<Int>) {
        // Decide the tab before the adapter changes: a data-set change can report a
        // page selection of its own.
        val wanted = selectedTabId ?: initialTabId()
        val position = positionOf(newTabIds, wanted)
        adapter.submit(newTabIds)
        // Attach only once populated, as PaymentsFragment does. ViewPager2 hands the adapter
        // its saved pages when it is attached (or, if already attached, when the view state is
        // restored), and FragmentStateAdapter drops every restored page that containsItem()
        // rejects. Attached empty, that is every page: after a rotation the retained Receive
        // child and its ViewModel would be recreated and the selected balance reset to Core.
        if (binding.pager.adapter == null) {
            binding.pager.adapter = adapter
        }
        selectedTabId = newTabIds[position]
        binding.pager.setCurrentItem(position, false)
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
        val ids = viewModel.uiState.collectAsStateWithLifecycle().value.tabIds
        // Null until showTabs has picked a tab for this tab set.
        val selected = selectedTabId
        if (ids.isNullOrEmpty() || selected == null) return

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
            val pagerTabIds = (binding.pager.adapter as? TabsAdapter)?.tabIds.orEmpty()
            binding.pager.currentItem = positionOf(pagerTabIds, tabId)
        }
    }

    /** Pages keyed by logical tab id (stable ids), so a tab-set change keeps the pages that remain. */
    private class TabsAdapter(
        fragment: Fragment,
        private val source: String
    ) : FragmentStateAdapter(fragment) {
        var tabIds: List<Int> = emptyList()
            private set

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newTabIds: List<Int>) {
            if (newTabIds == tabIds) return
            tabIds = newTabIds
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = tabIds.size

        override fun getItemId(position: Int): Long = tabIds[position].toLong()

        override fun containsItem(itemId: Long): Boolean = tabIds.any { it.toLong() == itemId }

        override fun createFragment(position: Int): Fragment = when (tabIds[position]) {
            ACTIVE_TAB_RECEIVE -> AdvancedReceiveFragment.newInstance()
            ACTIVE_TAB_INTERNAL -> InternalTransferFragment.newInstance()
            else -> AdvancedSendFragment.newInstance(source = source)
        }
    }
}
