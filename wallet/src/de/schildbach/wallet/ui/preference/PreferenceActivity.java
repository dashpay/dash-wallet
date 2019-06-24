/*
 * Copyright 2011-2015 the original author or authors.
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

package de.schildbach.wallet.ui.preference;

import java.util.List;

import de.schildbach.wallet_test.R;

import android.os.Bundle;
import com.google.android.material.appbar.AppBarLayout;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.app.ActionBar;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.widget.LinearLayout;

/**
 * @author Andreas Schildbach
 */
public final class PreferenceActivity extends AppCompatPreferenceActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        initToolbar();
    }

    private void initToolbar()
    {
        LinearLayout root = (LinearLayout)findViewById(android.R.id.list).getParent().getParent().getParent();
        AppBarLayout appBar = (AppBarLayout) LayoutInflater.from(this).inflate(R.layout.app_bar_general, root, false);
        root.addView(appBar, 0); // insert at top

        Toolbar toolbarView = (Toolbar) appBar.findViewById(R.id.toolbar);
        if (toolbarView != null)
        {
            setSupportActionBar(toolbarView);
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null)
            {
                actionBar.setDisplayHomeAsUpEnabled(true);
                actionBar.setDisplayShowHomeEnabled(true);
            }
        }
    }
    @Override
    public void onBuildHeaders(final List<Header> target) {
        loadHeadersFromResource(R.xml.preference_headers, target);
    }

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
    protected boolean isValidFragment(final String fragmentName) {
        return SettingsFragment.class.getName().equals(fragmentName)
                || DiagnosticsFragment.class.getName().equals(fragmentName)
                || AboutFragment.class.getName().equals(fragmentName);
    }
}
