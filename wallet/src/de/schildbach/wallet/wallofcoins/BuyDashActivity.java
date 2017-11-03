package de.schildbach.wallet.wallofcoins;

import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;

import de.schildbach.wallet.ui.AbstractWalletActivity;
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
        Log.d(TAG, "onBackPressed: list==" + fr.backManageViews.toString());
        Log.d(TAG, "onBackPressed: " + fr.backManageViews.size());
        if (fr.backManageViews.size() >= 2) {
            if (fr.backManageViews.get(fr.backManageViews.size() - 2) == fr.LAYOUT_VERIFY_OTP) {
                fr.backManageViews.clear();
                fr.getOrderList(false);
            } else {
                if (fr.backManageViews.get(fr.backManageViews.size() - 1) == fr.LAYOUT_VERIFY_OTP && fr.backManageViews.get(fr.backManageViews.size() - 2) == fr.LAYOUT_PASSWORD) {
                    fr.hideViewManageBack(fr.backManageViews.get(fr.backManageViews.size() - 4));
                    fr.backManageViews.remove(fr.backManageViews.size() - 1);
                    fr.backManageViews.remove(fr.backManageViews.size() - 1);
                    fr.backManageViews.remove(fr.backManageViews.size() - 1);
                } else if (fr.backManageViews.get(fr.backManageViews.size() - 1) == fr.LAYOUT_PASSWORD) {
                    fr.hideViewManageBack(fr.backManageViews.get(fr.backManageViews.size() - 3));
                    fr.backManageViews.remove(fr.backManageViews.size() - 1);
                    fr.backManageViews.remove(fr.backManageViews.size() - 1);
                } else {
                    fr.hideViewManageBack(fr.backManageViews.get(fr.backManageViews.size() - 2));
                    fr.backManageViews.remove(fr.backManageViews.size() - 1);
                }
            }
        } else {
            super.onBackPressed();
        }
    }
}
