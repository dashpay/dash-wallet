package de.schildbach.wallet.wallofcoins.buying_wizard;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;


import org.bitcoinj.core.Coin;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.ui.CurrencyTextView;
import de.schildbach.wallet.wallofcoins.BuyDashPref;
import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.buying_wizard.email_phone.BuyingWizardEmailPhoneFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.location.BuyingWizardLocationFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.offer_amount.BuyingWizardOfferAmountFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.order_history.BuyingWizardOrderHistoryFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.BuyingWizardFragmentUtils;
import de.schildbach.wallet.wallofcoins.buying_wizard.verification_otp.BuyingWizardVerifycationOtpFragment;
import de.schildbach.wallet.wallofcoins.response.CreateHoldResp;
import de.schildbach.wallet_test.R;


/**
 * Created on 6/3/18.
 */

public class BuyingWizardBaseActivity extends AppCompatActivity implements
        SharedPreferences.OnSharedPreferenceChangeListener, View.OnClickListener {

    private FragmentManager fragmentManager;
    public BuyDashPref buyDashPref;
    private ImageView imgViewToolbarBack;
    private TextView appBarMessageView;
    private LinearLayout viewBalance;
    private CurrencyTextView viewBalanceBtc, viewBalanceLocal;
    private ImageView viewBalanceTooMuch;
    private static final Coin TOO_MUCH_BALANCE_THRESHOLD = Coin.COIN.multiply(30);
    @javax.annotation.Nullable
    private Coin balance = null;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_buying_base);
        init();
        setListners();
        if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
            if (!TextUtils.isEmpty(buyDashPref.getHoldId())) {
                CreateHoldResp createHoldResp = buyDashPref.getCreateHoldResp();
                Bundle bundle = new Bundle();
                bundle.putString(WOCConstants.ARG_VERIFICATION_OTP, createHoldResp.__PURCHASE_CODE);
                BuyingWizardVerifycationOtpFragment otpFragment = new BuyingWizardVerifycationOtpFragment();
                otpFragment.setArguments(bundle);
                replaceFragment(otpFragment, true, false);
            } else {
                replaceFragment(new BuyingWizardLocationFragment(), true, true);
            }
        } else
            replaceFragment(new BuyingWizardLocationFragment(), false, true);
    }

    private void init() {
        this.buyDashPref = new BuyDashPref(PreferenceManager.getDefaultSharedPreferences(this));
        buyDashPref.registerOnSharedPreferenceChangeListener(this);
        fragmentManager = getSupportFragmentManager();
        imgViewToolbarBack = (ImageView) findViewById(R.id.imgViewToolbarBack);
        appBarMessageView = (TextView) findViewById(R.id.toolbar_message);
        viewBalance = (LinearLayout) findViewById(R.id.wallet_balance);
        viewBalanceBtc = (CurrencyTextView) findViewById(R.id.wallet_balance_btc);
        viewBalanceLocal = (CurrencyTextView) findViewById(R.id.wallet_balance_local);
        viewBalanceTooMuch = (ImageView) findViewById(R.id.wallet_balance_too_much_warning);

        viewBalanceBtc.setPrefixScaleX(0.9f);
        viewBalanceLocal.setInsignificantRelativeSize(1);
        viewBalanceLocal.setStrikeThru(Constants.TEST);
    }

    private void setListners() {
        imgViewToolbarBack.setOnClickListener(this);
        viewBalance.setOnClickListener(this);
    }

    public void replaceFragment(Fragment fragment, boolean withAnimation, boolean withBackStack) {
        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        Log.e("Fragment name", fragment.getClass().getName());
        if (withAnimation)
            fragmentTransaction.setCustomAnimations(R.anim.activity_in, R.anim.activity_out, R.anim.activity_backin,
                    R.anim.activity_back_out);
        if (withBackStack)
            fragmentTransaction.replace(R.id.frame_container_buy_base, fragment).addToBackStack(fragment.getClass().getName());
        else
            fragmentTransaction.replace(R.id.frame_container_buy_base, fragment);
        fragmentTransaction.commitAllowingStateLoss();
    }

    public void popbackFragment() {

        Log.e("CurrentFragment", fragmentManager.findFragmentById(R.id.frame_container_buy_base).toString());
        if (fragmentManager.getBackStackEntryCount() > 0) {
            Fragment fragment = fragmentManager.findFragmentById(R.id.frame_container_buy_base);
            if (fragment instanceof BuyingWizardEmailPhoneFragment)
                ((BuyingWizardEmailPhoneFragment) fragment).changeView();

            else if (fragment instanceof BuyingWizardOfferAmountFragment)
                ((BuyingWizardOfferAmountFragment) fragment).changeView();
            else if (fragment instanceof BuyingWizardLocationFragment)
                this.finish();
            else if (fragment instanceof BuyingWizardOrderHistoryFragment)
                ((BuyingWizardOrderHistoryFragment) fragment).changeView();
            else
                fragmentManager.popBackStack();
        } else
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
        popbackFragment();
        //super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        buyDashPref.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {

    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {

            case R.id.imgViewToolbarBack:
                popbackFragment();
                break;

            case R.id.wallet_balance:
                showWarningIfBalanceTooMuch();
                //showExchangeRatesActivity();
                break;
        }


    }

    @Override
    public void onResume() {
        super.onResume();
    }

    private void showWarningIfBalanceTooMuch() {
        if (balance != null && balance.isGreaterThan(TOO_MUCH_BALANCE_THRESHOLD)) {
            Toast.makeText(this, getString(R.string.wallet_balance_fragment_too_much),
                    Toast.LENGTH_LONG).show();
        }
    }


    private void showAppBarMessage(CharSequence message) {
        if (message != null) {
            appBarMessageView.setVisibility(View.VISIBLE);
            appBarMessageView.setText(message);
        } else {
            appBarMessageView.setVisibility(View.GONE);
        }
    }

    private void updateBalanceTooMuchWarning() {
        if (balance == null)
            return;

        boolean tooMuch = balance.isGreaterThan(TOO_MUCH_BALANCE_THRESHOLD);
        viewBalanceTooMuch.setVisibility(tooMuch ? View.VISIBLE : View.GONE);
    }

    public void popBackAllFragmentsExcept(String tag) {
        fragmentManager.popBackStack(tag, FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    public void removeAllFragmentFromStack() {

        if (fragmentManager.getBackStackEntryCount() > 0) {
            BuyingWizardFragmentUtils.sDisableFragmentAnimations = true;
            fragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            BuyingWizardFragmentUtils.sDisableFragmentAnimations = false;
        }
    }
}
