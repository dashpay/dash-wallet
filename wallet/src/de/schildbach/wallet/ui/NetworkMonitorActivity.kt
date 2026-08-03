/*
 * Copyright 2013-2026 the original author or authors.
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
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.viewpager.widget.ViewPager
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import org.dash.wallet.common.util.observe
import java.text.NumberFormat

/**
 * Tools → Network Monitor.
 *
 * The top "Network status" section renders LIVE engine-neutral sync detail
 * from [NetworkMonitorViewModel] (stage, header/filter scan positions,
 * masternode-list and chainlock heights) — it works in every regime
 * because it consumes the [de.schildbach.wallet.service.L1SyncStatusService]
 * seam, never an engine directly.
 *
 * The legacy dashj peer/block pager below it reads the dashj peergroup,
 * which is HELD post-cutover (empty peers, frozen blocks) — so it is only
 * shown while the dashj-sync DIAGNOSTIC toggle (Tools) has un-held that
 * engine, exactly the situation where comparing engines is wanted. A hint
 * replaces it otherwise.
 *
 * @author Andreas Schildbach
 */
@AndroidEntryPoint
class NetworkMonitorActivity : AbstractBindServiceActivity() {

    private val viewModel: NetworkMonitorViewModel by viewModels()

    private lateinit var pager: ViewPager
    private lateinit var peersCheckBox: CheckBox
    private lateinit var blocksCheckBox: CheckBox
    private lateinit var dashjPanels: View
    private lateinit var dashjHint: TextView

    private lateinit var stageView: TextView
    private lateinit var progressView: TextView
    private lateinit var connectionView: TextView
    private lateinit var headersView: TextView
    private lateinit var filtersView: TextView
    private lateinit var mnListView: TextView
    private lateinit var chainLockView: TextView

    private val numberFormat = NumberFormat.getIntegerInstance()

    private val onCheckedChangeListener =
        CompoundButton.OnCheckedChangeListener { buttonView, _ ->
            pager.setCurrentItem(if (buttonView === peersCheckBox) 0 else 1, true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.network_monitor_content)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.network_monitor_activity_title)
        toolbar.setNavigationOnClickListener { finish() }

        stageView = findViewById(R.id.network_status_stage)
        progressView = findViewById(R.id.network_status_progress)
        connectionView = findViewById(R.id.network_status_connection)
        headersView = findViewById(R.id.network_status_headers)
        filtersView = findViewById(R.id.network_status_filters)
        mnListView = findViewById(R.id.network_status_mnlist)
        chainLockView = findViewById(R.id.network_status_chainlock)
        dashjPanels = findViewById(R.id.network_monitor_dashj_panels)
        dashjHint = findViewById(R.id.network_monitor_dashj_hint)

        pager = findViewById(R.id.network_monitor_pager)
        peersCheckBox = findViewById(R.id.peers_checkbox)
        blocksCheckBox = findViewById(R.id.blocks_checkbox)

        peersCheckBox.setOnCheckedChangeListener(onCheckedChangeListener)
        blocksCheckBox.setOnCheckedChangeListener(onCheckedChangeListener)

        pager.adapter = PagerAdapter(
            getString(R.string.network_monitor_peer_list_title),
            getString(R.string.network_monitor_block_list_title)
        )
        pager.pageMargin = 2
        pager.setPageMarginDrawable(R.color.background_primary)
        pager.addOnPageChangeListener(onPageChangeListener)

        viewModel.uiState.observe(this) { state -> render(state) }
    }

    private fun render(state: NetworkMonitorUIState) {
        stageView.setText(state.stageRes)
        progressView.text = getString(R.string.network_monitor_progress, state.percentage)
        progressView.visibility = if (state.isSynced) View.GONE else View.VISIBLE
        connectionView.setText(state.connectionRes)
        headersView.text = formatHeights(state.headerHeight, state.headerTarget)
        filtersView.text = formatHeights(state.filterHeight, state.filterTarget)
        mnListView.text = formatHeight(state.mnListHeight)
        chainLockView.text = formatHeight(state.chainLockHeight)

        dashjPanels.visibility = if (state.showDashjPanels) View.VISIBLE else View.GONE
        dashjHint.visibility = if (state.showDashjPanels) View.GONE else View.VISIBLE
    }

    /** "height / target"; height alone when the target is unknown or reached. */
    private fun formatHeights(height: Long, target: Long): String = when {
        height <= 0 && target <= 0 -> getString(R.string.network_monitor_value_unknown)
        target <= 0 || target <= height -> numberFormat.format(height)
        else -> getString(
            R.string.network_monitor_height_of_target,
            numberFormat.format(height),
            numberFormat.format(target)
        )
    }

    private fun formatHeight(height: Long): String =
        if (height <= 0) getString(R.string.network_monitor_value_unknown) else numberFormat.format(height)

    private val onPageChangeListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            peersCheckBox.setOnCheckedChangeListener(null)
            blocksCheckBox.setOnCheckedChangeListener(null)

            peersCheckBox.isChecked = position == 0
            blocksCheckBox.isChecked = position == 1

            peersCheckBox.setOnCheckedChangeListener(onCheckedChangeListener)
            blocksCheckBox.setOnCheckedChangeListener(onCheckedChangeListener)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_left)
    }

    private inner class PagerAdapter(
        private val peersTitle: String,
        private val blocksTitle: String
    ) : FragmentStatePagerAdapter(supportFragmentManager) {

        override fun getCount(): Int = 2

        override fun getItem(position: Int): Fragment =
            if (position == 0) PeerListFragment() else BlockListFragment()

        override fun getPageTitle(position: Int): CharSequence =
            if (position == 0) peersTitle else blocksTitle
    }
}
