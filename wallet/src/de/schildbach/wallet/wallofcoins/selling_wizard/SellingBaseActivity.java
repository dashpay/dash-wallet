package de.schildbach.wallet.wallofcoins.selling_wizard;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import de.schildbach.wallet.wallofcoins.BuyDashPref;
import de.schildbach.wallet.wallofcoins.buyingwizard.utils.FragmentUtils;
import de.schildbach.wallet.wallofcoins.selling_wizard.selling_home.SellingHomeFragment;
import de.schildbach.wallet_test.R;

/**
 * Created by  on 03-Apr-18.
 */

public class SellingBaseActivity extends AppCompatActivity implements View.OnClickListener {


    private FragmentManager fragmentManager;
    private FragmentTransaction fragmentTransaction;
    public BuyDashPref buyDashPref;
    private ImageView image_toolbar_back;
    private TextView text_toolbar_title;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_base_selling_wizard);
        init();
        setListners();
        replaceFragment(new SellingHomeFragment(), true, false);

    }

    private void init() {
        fragmentManager = getSupportFragmentManager();
        image_toolbar_back = (ImageView) findViewById(R.id.image_toolbar_back);
        text_toolbar_title = (TextView) findViewById(R.id.text_toolbar_title);

    }

    private void setListners() {
        image_toolbar_back.setOnClickListener(this);
    }

    public void replaceFragment(Fragment fragment, boolean withAnimation, boolean withBackStack) {
        fragmentTransaction = fragmentManager.beginTransaction();
        Log.e("Fragment name", fragment.getClass().getName());
        if (withAnimation)
            fragmentTransaction.setCustomAnimations(R.anim.activity_in, R.anim.activity_out, R.anim.activity_backin, R.anim.activity_back_out);
        if (withBackStack)
            fragmentTransaction.replace(R.id.container_selling_base, fragment).addToBackStack(fragment.getClass().getName());
        else
            fragmentTransaction.replace(R.id.container_selling_base, fragment);
        fragmentTransaction.commitAllowingStateLoss();
    }

    public void setTopbarTitle(String msg) {
        text_toolbar_title.setText(msg);
    }

    public void finishBaseActivity() {
        this.finish();
    }

    public void popBackDirect() {
        if (fragmentManager.getBackStackEntryCount() > 0)
            fragmentManager.popBackStack();
        else
            this.finish();

    }

    @Override
    public void onBackPressed() {
        popBackDirect();
        //super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }


    @Override
    public void onClick(View view) {
        switch (view.getId()) {

            case R.id.image_toolbar_back:
                popBackDirect();
                break;

            case R.id.wallet_balance:
                break;
        }


    }

    @Override
    public void onResume() {
        super.onResume();
    }

    public void popBackInclusive(String tag) {
        fragmentManager.popBackStack(tag, FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    public void removeAllFragmentFromStack() {

        if (fragmentManager.getBackStackEntryCount() > 0) {
            FragmentUtils.sDisableFragmentAnimations = true;
            fragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            FragmentUtils.sDisableFragmentAnimations = false;
        }
    }
}

