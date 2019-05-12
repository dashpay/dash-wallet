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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.annotation.LayoutRes;
import android.support.v4.content.LocalBroadcastManager;
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
import android.view.MenuItem;

/**
 * @author Andreas Schildbach
 */
public abstract class AbstractWalletActivity extends AppCompatActivity {
    private WalletApplication application;

    protected static final Logger log = LoggerFactory.getLogger(AbstractWalletActivity.class);

    private static final String FINISH_ALL_ACTIVITIES_ACTION = "dash.wallet.action.CLOSE_ALL_ACTIVITIES_ACTION";

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        application = (WalletApplication) getApplication();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            setTaskDescription(new TaskDescription(null, null, getResources().getColor(R.color.bg_action_bar)));
        PinRetryController.handleLockedForever(this);
        WalletLock.getInstance().setConfiguration(application.getConfiguration());

        registerFinishAllReceiver();
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        unregisterFinishAllReceiver();
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

    private void unlockWallet() {
        if(!PinRetryController.handleLockedForever(this))
            UnlockWalletDialogFragment.show(getSupportFragmentManager());
    }

    protected WalletApplication getWalletApplication() {
        return application;
    }

    private BroadcastReceiver finishAllReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            LocalBroadcastManager.getInstance(application).unregisterReceiver(this);
            AbstractWalletActivity.this.finish();
        }
    };

    private void registerFinishAllReceiver() {
        IntentFilter finishAllFilter = new IntentFilter(FINISH_ALL_ACTIVITIES_ACTION);
        LocalBroadcastManager.getInstance(application).registerReceiver(finishAllReceiver, finishAllFilter);
    }

    private void unregisterFinishAllReceiver() {
        LocalBroadcastManager.getInstance(application).unregisterReceiver(finishAllReceiver);
    }

    public static void finishAll(Context context) {
        Intent localIntent = new Intent(FINISH_ALL_ACTIVITIES_ACTION);
        LocalBroadcastManager.getInstance(context).sendBroadcast(localIntent);
    }
}
