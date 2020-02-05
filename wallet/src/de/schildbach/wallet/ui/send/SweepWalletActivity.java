/*
 * Copyright 2014-2015 the original author or authors.
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

package de.schildbach.wallet.ui.send;

import org.bitcoinj.core.PrefixedChecksummedBytes;

import de.schildbach.wallet.ui.AbstractBindServiceActivity;
import de.schildbach.wallet_test.R;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

/**
 * @author Andreas Schildbach
 */
public final class SweepWalletActivity extends AbstractBindServiceActivity {

    public static final String INTENT_EXTRA_KEY = "sweep_key";
    public static final String INTENT_EXTRA_USER_AUTHORIZED = "user_authorized";

    public static void start(final Context context, boolean userAuthorized) {
        final Intent intent = new Intent(context, SweepWalletActivity.class);
        intent.putExtra(INTENT_EXTRA_USER_AUTHORIZED, userAuthorized);
        context.startActivity(intent);
    }

    public static void start(final Context context, final PrefixedChecksummedBytes key, boolean userAuthorized) {
        final Intent intent = new Intent(context, SweepWalletActivity.class);
        intent.putExtra(INTENT_EXTRA_KEY, key);
        intent.putExtra(INTENT_EXTRA_USER_AUTHORIZED, userAuthorized);
        context.startActivity(intent);
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.sweep_wallet_content);

        getWalletApplication().startBlockchainService(false);
    }

    public boolean isUserAuthorized() {
        return getIntent().getBooleanExtra(INTENT_EXTRA_USER_AUTHORIZED, false);
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
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_left);
    }

}
