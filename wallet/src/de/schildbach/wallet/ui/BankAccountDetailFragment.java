package de.schildbach.wallet.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;

import org.bitcoinj.core.Coin;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;

import javax.annotation.Nullable;

import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.service.BlockchainStateLoader;
import hashengineering.darkcoin.wallet.R;


public final class BankAccountDetailFragment extends Fragment implements OnSharedPreferenceChangeListener {
    private static final String TAG = BankAccountDetailFragment.class.getSimpleName();
    private AbstractWalletActivity activity;
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private LoaderManager loaderManager;


    private Coin balance = null;
    @Nullable
    private BlockchainState blockchainState = null;
    @Nullable
    private String defaultCurrency = null;

    private static final int ID_BALANCE_LOADER = 0;
    private static final int ID_RATE_LOADER = 1;
    private static final int ID_BLOCKCHAIN_STATE_LOADER = 2;

    ProgressBar progressBar;
    EditText email, phone, pass;
    Button createAuth;

    @Override
    public void onAttach(final Context context) {
        super.onAttach(context);

        this.activity = (AbstractWalletActivity) context;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        this.wallet = application.getWallet();
        this.loaderManager = getLoaderManager();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(true);
        setHasOptionsMenu(true);


        defaultCurrency = config.getExchangeCurrencyCode();
        config.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {

        final View view = inflater.inflate(R.layout.bank_account_detail_fragment, container, false);

        progressBar = (ProgressBar) view.findViewById(R.id.sell_dash_progress);


//        email = (EditText) view.findViewById(R.id.edit_sell_dash_email);
//        phone = (EditText) view.findViewById(R.id.edit_sell_dash_phone);
//        pass = (EditText) view.findViewById(R.id.edit_sell_dash_pass);
//        createAuth = (Button) view.findViewById(R.id.button_sell_dash_create_auth);

//        if (BuildConfig.DEBUG) {
//            // do something for a debug build
//            phone.setText("+19412335839");
//            email.setText("demo@geni.to");
//            pass.setText("asdf1234");
//        }

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);
        loaderManager.initLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);

        updateView();
    }

    @Override
    public void onPause() {
        loaderManager.destroyLoader(ID_BALANCE_LOADER);
        loaderManager.destroyLoader(ID_BLOCKCHAIN_STATE_LOADER);

        super.onPause();
    }

    @Override
    public void onDestroy() {
        config.unregisterOnSharedPreferenceChangeListener(this);

        loaderManager.destroyLoader(ID_RATE_LOADER);

        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (Configuration.PREFS_KEY_EXCHANGE_CURRENCY.equals(key) || Configuration.PREFS_KEY_BTC_PRECISION.equals(key)) {
            defaultCurrency = config.getExchangeCurrencyCode();

            updateView();
        }
    }

    private void updateView() {
        balance = application.getWallet().getBalance(BalanceType.ESTIMATED);
    }


    private final LoaderManager.LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>() {
        @Override
        public Loader<Coin> onCreateLoader(final int id, final Bundle args) {
            return new WalletBalanceLoader(activity, wallet);
        }

        @Override
        public void onLoadFinished(final Loader<Coin> loader, final Coin balance) {
            BankAccountDetailFragment.this.balance = balance;

            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<Coin> loader) {
        }
    };

    private final LoaderManager.LoaderCallbacks<BlockchainState> blockchainStateLoaderCallbacks = new LoaderManager.LoaderCallbacks<BlockchainState>() {
        @Override
        public Loader<BlockchainState> onCreateLoader(final int id, final Bundle args) {
            return new BlockchainStateLoader(activity);
        }

        @Override
        public void onLoadFinished(final Loader<BlockchainState> loader, final BlockchainState blockchainState) {
            BankAccountDetailFragment.this.blockchainState = blockchainState;

            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<BlockchainState> loader) {
        }
    };
}
