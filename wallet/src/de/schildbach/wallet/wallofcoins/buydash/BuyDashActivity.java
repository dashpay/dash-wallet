package de.schildbach.wallet.wallofcoins.buydash;

import android.os.Bundle;
import android.view.MenuItem;

import de.schildbach.wallet.ui.AbstractWalletActivity;
import de.schildbach.wallet.wallofcoins.BuyDashFragment;
import hashengineering.darkcoin.wallet.R;

public final class BuyDashActivity extends AbstractWalletActivity {
    private static final String TAG = BuyDashActivity.class.getSimpleName();

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.buy_dash_content);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        BuyDashFragment fr = (BuyDashFragment) getSupportFragmentManager().findFragmentById(R.id.buy_dash_fragment);

        if (fr.hideViewManageBack()) {
            super.onBackPressed();
        }
    }
}
