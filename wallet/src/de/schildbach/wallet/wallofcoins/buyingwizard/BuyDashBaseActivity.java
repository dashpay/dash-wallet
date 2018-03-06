package de.schildbach.wallet.wallofcoins.buyingwizard;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;

import de.schildbach.wallet.wallofcoins.buyingwizard.buy_dash_location.BuyDashLocationFragment;
import de.schildbach.wallet_test.R;

/**
 * Created on 6/3/18.
 */

public class BuyDashBaseActivity extends AppCompatActivity {



    private FragmentManager fragmentManager;
    private FragmentTransaction fragmentTransaction;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.layout_buy_dash_activity);
        init();
        replaceFragment(new BuyDashLocationFragment(), true, false, "BuyDashLocationFragment");
    }

    private void init() {

        fragmentManager = getSupportFragmentManager();
    }

    public void replaceFragment(Fragment fragment, boolean withAnimation, boolean withBackStack, String tag) {
        fragmentTransaction = fragmentManager.beginTransaction();

        if (withAnimation)
            //fragmentTransaction.setCustomAnimations(R.anim.activity_in, R.anim.activity_out, R.anim.activity_backin, R.anim.activity_back_out);
            if (withBackStack)
                fragmentTransaction.replace(R.id.containerBuyDashBase, fragment).addToBackStack(tag);
            else
                fragmentTransaction.replace(R.id.containerBuyDashBase, fragment);
        fragmentTransaction.commitAllowingStateLoss();
    }




}
