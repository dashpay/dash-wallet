package de.schildbach.wallet.wallofcoins.buyingwizard;

import android.app.LoaderManager;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Fiat;
import org.bitcoinj.wallet.Wallet;

import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.ExchangeRate;
import de.schildbach.wallet.data.ExchangeRatesLoader;
import de.schildbach.wallet.data.ExchangeRatesProvider;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.service.BlockchainStateLoader;
import de.schildbach.wallet.ui.AbstractBindServiceActivity;
import de.schildbach.wallet.ui.CurrencyTextView;
import de.schildbach.wallet.ui.ExchangeRatesActivity;
import de.schildbach.wallet.ui.WalletBalanceLoader;
import de.schildbach.wallet.wallofcoins.BuyDashPref;
import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.buyingwizard.buy_dash_location.BuyDashLocationFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.email_phone.EmailAndPhoneFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.offer_amount.BuyDashOfferAmountFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.order_history.OrderHistoryFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.utils.FragmentUtils;
import de.schildbach.wallet.wallofcoins.buyingwizard.verification_otp.VerifycationOtpFragment;
import de.schildbach.wallet.wallofcoins.response.CreateHoldResp;
import de.schildbach.wallet_test.R;

/**
 * Created on 6/3/18.
 */

public class BuyDashBaseActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener, View.OnClickListener {


    private FragmentManager fragmentManager;
    private FragmentTransaction fragmentTransaction;
    public BuyDashPref buyDashPref;
    private ImageView imgViewToolbarBack;
    private Wallet wallet;
    private WalletApplication application;
    private TextView appBarMessageView;
    private ProgressBar progressView;
    private LinearLayout viewBalance;
    private CurrencyTextView viewBalanceBtc, viewBalanceLocal;
    private ImageView viewBalanceTooMuch;
    private static final Coin TOO_MUCH_BALANCE_THRESHOLD = Coin.COIN.multiply(30);
    private static final int ID_BALANCE_LOADER = 0;
    private static final int ID_RATE_LOADER = 1;
    private static final int ID_BLOCKCHAIN_STATE_LOADER = 2;
    private static final int ID_MASTERNODE_SYNC_LOADER = 3;

    private static final long BLOCKCHAIN_UPTODATE_THRESHOLD_MS = DateUtils.HOUR_IN_MILLIS;
    @javax.annotation.Nullable
    private Coin balance = null;
    private LoaderManager loaderManager;
    private boolean showLocalBalance;
    private boolean initComplete = false;
    @javax.annotation.Nullable
    private BlockchainState blockchainState = null;
    private String progressMessage;
    private Configuration config;
    @javax.annotation.Nullable
    private ExchangeRate exchangeRate = null;
    private AbstractBindServiceActivity activity;
    private Context mContext;
    private boolean isPopBack;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_base_buy_dash);
        init();
        setListners();
        if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
            if (!TextUtils.isEmpty(buyDashPref.getHoldId())) {
                CreateHoldResp createHoldResp = buyDashPref.getCreateHoldResp();
                //binding.etOtp.setText(createHoldResp.__PURCHASE_CODE);
                // hideViewExcept(binding.layoutVerifyOtp);
                Bundle bundle = new Bundle();
                bundle.putString(WOCConstants.VERIFICATION_OTP, createHoldResp.__PURCHASE_CODE);
                VerifycationOtpFragment otpFragment = new VerifycationOtpFragment();
                otpFragment.setArguments(bundle);
                replaceFragment(otpFragment, true, false);
                //navigateToVerifyOtp(createHoldResp.__PURCHASE_CODE);
            } else {
                //hideViewExcept(binding.rvOrderList);
                //getOrderList(false);
                //navigateToOrderList(false);
                OrderHistoryFragment historyFragment = new OrderHistoryFragment();
                Bundle bundle = new Bundle();
                bundle.putBoolean("isFromCreateHold", false);
                historyFragment.setArguments(bundle);
                replaceFragment(historyFragment, true, false);
            }
        } else
            replaceFragment(new BuyDashLocationFragment(), true, true);
    }

    private void init() {
        mContext = this;
        this.loaderManager = getLoaderManager();
        this.application = (WalletApplication) this.getApplication();
        this.wallet = application.getWallet();
        this.config = application.getConfiguration();
        this.buyDashPref = new BuyDashPref(PreferenceManager.getDefaultSharedPreferences(this));
        buyDashPref.registerOnSharedPreferenceChangeListener(this);
        fragmentManager = getSupportFragmentManager();
        imgViewToolbarBack = (ImageView) findViewById(R.id.imgViewToolbarBack);
        appBarMessageView = (TextView) findViewById(R.id.toolbar_message);
        progressView = (ProgressBar) findViewById(R.id.progress);
        viewBalance = (LinearLayout) findViewById(R.id.wallet_balance);
        viewBalanceBtc = (CurrencyTextView) findViewById(R.id.wallet_balance_btc);
        viewBalanceLocal = (CurrencyTextView) findViewById(R.id.wallet_balance_local);
        viewBalanceTooMuch = (ImageView) findViewById(R.id.wallet_balance_too_much_warning);


        viewBalanceBtc.setPrefixScaleX(0.9f);
        viewBalanceLocal.setInsignificantRelativeSize(1);
        viewBalanceLocal.setStrikeThru(Constants.TEST);

        loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);

        loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);

   /*     if (!initComplete) {
            loaderManager.initLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);
            initComplete = true;
        } else
            loaderManager.restartLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);*/

        updateView();

    }

    private void setListners() {
        imgViewToolbarBack.setOnClickListener(this);
        viewBalance.setOnClickListener(this);
    }

    public void replaceFragment(Fragment fragment, boolean withAnimation, boolean withBackStack) {
        fragmentTransaction = fragmentManager.beginTransaction();
        Log.e("Fragment name", fragment.getClass().getName());
        if (withAnimation)
            fragmentTransaction.setCustomAnimations(R.anim.activity_in, R.anim.activity_out, R.anim.activity_backin, R.anim.activity_back_out);
        if (withBackStack)
            fragmentTransaction.replace(R.id.containerBuyDashBase, fragment).addToBackStack(fragment.getClass().getName());
        else
            fragmentTransaction.replace(R.id.containerBuyDashBase, fragment);
        fragmentTransaction.commitAllowingStateLoss();
    }

    public void finishBaseActivity() {
        this.finish();
    }

    public void popbackFragment() {

        Log.e("CurrentFragment", fragmentManager.findFragmentById(R.id.containerBuyDashBase).toString());
        if (fragmentManager.getBackStackEntryCount() > 0) {
            Fragment fragment = fragmentManager.findFragmentById(R.id.containerBuyDashBase);
            if (fragment instanceof EmailAndPhoneFragment)
                ((EmailAndPhoneFragment) fragment).changeView();

            else if (fragment instanceof BuyDashOfferAmountFragment)
                ((BuyDashOfferAmountFragment) fragment).changeView();
            else if (fragment instanceof BuyDashLocationFragment)
                finish();
            else
                fragmentManager.popBackStack();
        } else
            finish();
    }

    public void popBackDirect() {
        if (fragmentManager.getBackStackEntryCount() > 0)
            fragmentManager.popBackStack();
        else
            finish();

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
                showExchangeRatesActivity();
                break;
        }


    }

    @Override
    public void onResume() {
        super.onResume();

      /*  loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);

        if (rateLoaderCallbacks == null)
            Log.e("nullllll","nullllll");
        loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);
        if (!initComplete) {
            loaderManager.initLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);
            initComplete = true;
        } else
            loaderManager.restartLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);

        updateView();*/
    }

    private void showExchangeRatesActivity() {
        Intent intent = new Intent(this, ExchangeRatesActivity.class);
        startActivity(intent);
    }

    private void showWarningIfBalanceTooMuch() {
        if (balance != null && balance.isGreaterThan(TOO_MUCH_BALANCE_THRESHOLD)) {
            Toast.makeText(application, getString(R.string.wallet_balance_fragment_too_much),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void updateView() {

        final boolean showProgress;

        if (blockchainState != null && blockchainState.bestChainDate != null) {
            final long blockchainLag = System.currentTimeMillis() - blockchainState.bestChainDate.getTime();
            final boolean blockchainUptodate = blockchainLag < BLOCKCHAIN_UPTODATE_THRESHOLD_MS;
            final boolean noImpediments = blockchainState.impediments.isEmpty();

            showProgress = !(blockchainUptodate || !blockchainState.replaying);

            final String downloading = getString(noImpediments ? R.string.blockchain_state_progress_downloading
                    : R.string.blockchain_state_progress_stalled);

            if (blockchainLag < 2 * DateUtils.DAY_IN_MILLIS) {
                final long hours = blockchainLag / DateUtils.HOUR_IN_MILLIS;
                progressMessage = getString(R.string.blockchain_state_progress_hours, downloading, hours);
            } else if (blockchainLag < 2 * DateUtils.WEEK_IN_MILLIS) {
                final long days = blockchainLag / DateUtils.DAY_IN_MILLIS;
                progressMessage = getString(R.string.blockchain_state_progress_days, downloading, days);
            } else if (blockchainLag < 90 * DateUtils.DAY_IN_MILLIS) {
                final long weeks = blockchainLag / DateUtils.WEEK_IN_MILLIS;
                progressMessage = getString(R.string.blockchain_state_progress_weeks, downloading, weeks);
            } else {
                final long months = blockchainLag / (30 * DateUtils.DAY_IN_MILLIS);
                progressMessage = getString(R.string.blockchain_state_progress_months, downloading, months);
            }
        } else {
            showProgress = false;
        }

        if (!showProgress) {
            viewBalance.setVisibility(View.VISIBLE);

            if (!showLocalBalance)
                //viewBalanceLocal.setVisibility(View.GONE);
                viewBalanceLocal.setVisibility(View.INVISIBLE);

            if (balance != null) {
                viewBalanceBtc.setVisibility(View.VISIBLE);
                viewBalanceBtc.setFormat(config.getFormat().noCode());
                viewBalanceBtc.setAmount(balance);

                updateBalanceTooMuchWarning();

                if (showLocalBalance) {
                    if (exchangeRate != null) {
                        final Fiat localValue = exchangeRate.rate.coinToFiat(balance);
                        viewBalanceLocal.setVisibility(View.VISIBLE);
                        viewBalanceLocal.setFormat(Constants.LOCAL_FORMAT.code(0, Constants.PREFIX_ALMOST_EQUAL_TO + exchangeRate.getCurrencyCode()));
                        viewBalanceLocal.setAmount(localValue);
                    } else {
                        viewBalanceLocal.setVisibility(View.INVISIBLE);
                    }
                }
            } else {
                viewBalanceBtc.setVisibility(View.INVISIBLE);
            }

            //if(masternodeSyncStatus != MasternodeSync.MASTERNODE_SYNC_FINISHED)
            //{
//                progressView.setVisibility(View.VISIBLE);
            viewBalance.setVisibility(View.VISIBLE);
            //            String syncStatus = wallet.getContext().masternodeSync.getSyncStatus();
            //          showAppBarMessage(syncStatus);
            //    } else {
            //Show sync status of Masternodes
            //int masternodesLoaded = wallet.getContext().masternodeSync.mapSeenSyncMNB.size();
            //int totalMasternodes = wallet.getContext().masternodeSync.masterNodeCountFromNetwork();

            //if(totalMasternodes == 0 || totalMasternodes < masternodesLoaded + 100) {
            progressView.setVisibility(View.GONE);
            showAppBarMessage(null);
            //}
            //else
            //{
            //showAppBarMessage("Masternodes Loaded: " + masternodesLoaded *100 /totalMasternodes +"%");
            //	showAppBarMessage("Masternodes Loaded: " + masternodesLoaded +" of "+ totalMasternodes);
            //}
            //}
        } else {
            showAppBarMessage(progressMessage);
            progressView.setVisibility(View.VISIBLE);
            progressView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Toast.makeText(application, progressMessage, Toast.LENGTH_LONG).show();
                }
            });
            viewBalance.setVisibility(View.INVISIBLE);
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

    private final LoaderManager.LoaderCallbacks<BlockchainState> blockchainStateLoaderCallbacks = new LoaderManager.LoaderCallbacks<BlockchainState>() {
        @Override
        public Loader<BlockchainState> onCreateLoader(final int id, final Bundle args) {
            return new BlockchainStateLoader((AbstractBindServiceActivity) getApplicationContext());
        }

        @Override
        public void onLoadFinished(final Loader<BlockchainState> loader, final BlockchainState blockchainState1) {
            blockchainState = blockchainState1;

            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<BlockchainState> loader) {
        }
    };

    private final LoaderManager.LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>() {
        @Override
        public Loader<Coin> onCreateLoader(final int id, final Bundle args) {
            return new WalletBalanceLoader(getApplicationContext(), wallet);
        }

        @Override
        public void onLoadFinished(final Loader<Coin> loader, final Coin balance1) {
            balance = balance1;

            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<Coin> loader) {
        }
    };

    private final LoaderManager.LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            return new ExchangeRatesLoader(getApplicationContext(), config);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> loader, final Cursor data) {
            if (data != null && data.getCount() > 0) {
                data.moveToFirst();
                exchangeRate = ExchangeRatesProvider.getExchangeRate(data);
                updateView();
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> loader) {
        }
    };

    public void popBackAllFragmentsExcept(String tag) {
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
