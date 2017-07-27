package de.schildbach.wallet.ui;

import android.os.Bundle;
import android.view.MenuItem;

import hashengineering.darkcoin.wallet.R;

public final class SellDashActivity extends AbstractWalletActivity {
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.sell_dash_content);
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
}
