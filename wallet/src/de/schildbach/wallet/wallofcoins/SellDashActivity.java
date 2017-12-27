package de.schildbach.wallet.wallofcoins;

import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.view.MenuItem;

import de.schildbach.wallet.ui.AbstractWalletActivity;
import hashengineering.darkcoin.wallet.R;
import hashengineering.darkcoin.wallet.databinding.SellDashContentBinding;

public final class SellDashActivity extends AbstractWalletActivity {

    private SellDashContentBinding binding;
    private FragmentManager manager;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.sell_dash_content);
        manager = getSupportFragmentManager();
        replaceFragment(new SellDashFragment(), false);
    }

    protected void replaceFragment(Fragment fragment, boolean isAddToBackStack) {
        FragmentTransaction fragmentTransaction = manager.beginTransaction();
        fragmentTransaction.replace(binding.flSellDash.getId(), fragment);
        if (isAddToBackStack) {
            fragmentTransaction.add(fragment, null);
            fragmentTransaction.commit();
            return;
        }
        fragmentTransaction.commit();
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
