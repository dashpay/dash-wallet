package de.schildbach.wallet.wallofcoins;

import android.os.Bundle;
import android.view.MenuItem;

import de.schildbach.wallet.ui.AbstractWalletActivity;
import hashengineering.darkcoin.wallet.R;

public final class BuyDashActivity extends AbstractWalletActivity {
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.buy_dash_content);
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
