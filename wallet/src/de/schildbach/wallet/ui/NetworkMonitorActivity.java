/*
 * Copyright 2013-2015 the original author or authors.
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

package de.schildbach.wallet.ui;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.CheckBox;
import android.widget.CompoundButton;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.ViewPager;

import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
public final class NetworkMonitorActivity extends AbstractBindServiceActivity {

    private PeerListFragment peerListFragment;
    private BlockListFragment blockListFragment;
    private ViewPager pager;
    private CheckBox peersCheckBox;
    private CheckBox blocksCheckBox;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.network_monitor_content);

        pager = findViewById(R.id.network_monitor_pager);
        final FragmentManager fm = getSupportFragmentManager();

        peersCheckBox = findViewById(R.id.peers_checkbox);
        blocksCheckBox = findViewById(R.id.blocks_checkbox);

        if (pager != null) {
            peersCheckBox.setOnCheckedChangeListener(onCheckedChangeListener);
            blocksCheckBox.setOnCheckedChangeListener(onCheckedChangeListener);

            String peersTitle = getString(R.string.network_monitor_peer_list_title);
            String blocksTitle = getString(R.string.network_monitor_block_list_title);
            final PagerAdapter pagerAdapter = new PagerAdapter(fm, peersTitle, blocksTitle);

            pager.setAdapter(pagerAdapter);
            pager.setPageMargin(2);
            pager.setPageMarginDrawable(R.color.bg_less_bright);
            pager.addOnPageChangeListener(onPageChangeListener);

            peerListFragment = new PeerListFragment();
            blockListFragment = new BlockListFragment();
        } else {
            peerListFragment = (PeerListFragment) fm.findFragmentById(R.id.peer_list_fragment);
            blockListFragment = (BlockListFragment) fm.findFragmentById(R.id.block_list_fragment);
        }
    }

    private ViewPager.OnPageChangeListener onPageChangeListener = new ViewPager.OnPageChangeListener() {
        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

        }

        @Override
        public void onPageSelected(int position) {
            peersCheckBox.setOnCheckedChangeListener(null);
            blocksCheckBox.setOnCheckedChangeListener(null);

            switch (position) {
                case 0:
                    peersCheckBox.setChecked(true);
                    blocksCheckBox.setChecked(false);
                    break;
                case 1:
                    peersCheckBox.setChecked(false);
                    blocksCheckBox.setChecked(true);
                    break;
            }

            peersCheckBox.setOnCheckedChangeListener(onCheckedChangeListener);
            blocksCheckBox.setOnCheckedChangeListener(onCheckedChangeListener);
        }

        @Override
        public void onPageScrollStateChanged(int state) {

        }
    };

    private CheckBox.OnCheckedChangeListener onCheckedChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (buttonView == peersCheckBox) {
                pager.setCurrentItem(0, true);
            } else {
                pager.setCurrentItem(1, true);
            }
        }
    };

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
        case android.R.id.home:
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_left);
    }

    private class PagerAdapter extends FragmentStatePagerAdapter {

        private final String peersTitle;
        private final String blocksTitle;

        public PagerAdapter(final FragmentManager fm, String peersTitle, String blocksTitle) {
            super(fm);
            this.peersTitle = peersTitle;
            this.blocksTitle = blocksTitle;
        }

        @Override
        public int getCount() {
            return 2;
        }

        @Override
        public Fragment getItem(final int position) {
            if (position == 0)
                return peerListFragment;
            else
                return blockListFragment;
        }

        @Nullable
        @Override
        public CharSequence getPageTitle(int position) {
            switch (position) {
                case 0: return peersTitle;
                case 1: return blocksTitle;
            }
            return super.getPageTitle(position);
        }
    }
}
