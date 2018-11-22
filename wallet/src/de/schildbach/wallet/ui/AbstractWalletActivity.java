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

package de.schildbach.wallet.ui;

import android.support.annotation.LayoutRes;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.Toolbar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.WalletLock;
import de.schildbach.wallet.ui.preference.PinRetryController;
import de.schildbach.wallet_test.R;

import android.support.v7.app.AppCompatActivity;
import android.app.ActivityManager.TaskDescription;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

/**
 * @author Andreas Schildbach
 */
public abstract class AbstractWalletActivity extends AppCompatActivity implements WalletLock.OnLockChangeListener {
    private WalletApplication application;

    protected static final Logger log = LoggerFactory.getLogger(AbstractWalletActivity.class);

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        application = (WalletApplication) getApplication();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            setTaskDescription(new TaskDescription(null, null, getResources().getColor(R.color.bg_action_bar)));

        PinRetryController.handleLockedForever(this);

        WalletLock.getInstance().addListener(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        WalletLock.getInstance().removeListener(this);
        super.onDestroy();
    }

    @Override
    public void setContentView(@LayoutRes int layoutResID) {
        super.setContentView(layoutResID);

        initToolbar();
    }

    private void initToolbar() {
        Toolbar toolbarView = (Toolbar) findViewById(R.id.toolbar);
        if (toolbarView != null) {
            setSupportActionBar(toolbarView);
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(true);
                actionBar.setDisplayShowHomeEnabled(true);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.wallet_options_lock:
                unlockWallet();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        MenuItem walletLockMenuItem = menu.findItem(R.id.wallet_options_lock);
        if (walletLockMenuItem != null) {
            walletLockMenuItem.setVisible(WalletLock.getInstance()
                    .isWalletLocked(getWalletApplication().getWallet()));
        }
        return super.onCreateOptionsMenu(menu);
    }

    private void unlockWallet() {
        if(!PinRetryController.handleLockedForever(this))
            UnlockWalletDialogFragment.show(getSupportFragmentManager());
    }

    @Override
    public void onLockChanged(boolean locked) {
        invalidateOptionsMenu();
    }

    protected WalletApplication getWalletApplication() {
        return application;
    }
}
