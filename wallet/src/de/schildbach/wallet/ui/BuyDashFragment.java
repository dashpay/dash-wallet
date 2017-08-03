package de.schildbach.wallet.ui;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.databinding.DataBindingUtil;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.gson.Gson;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.CoinDefinition;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import javax.annotation.Nullable;

import de.schildbach.wallet.AddressBookProvider;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.ExchangeRatesProvider;
import de.schildbach.wallet.SellDashPref;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.request.CreateAuthReq;
import de.schildbach.wallet.request.GetAuthTokenReq;
import de.schildbach.wallet.response.CountryData;
import de.schildbach.wallet.response.CreateAdResp;
import de.schildbach.wallet.response.CreateAuthErrorResp;
import de.schildbach.wallet.response.CreateAuthResp;
import de.schildbach.wallet.response.GetAuthTokenResp;
import de.schildbach.wallet.response.GetPricingOptionsResp;
import de.schildbach.wallet.response.GetReceivingOptionsResp;
import de.schildbach.wallet.response.SendVerificationResp;
import de.schildbach.wallet.response.VerifyAdResp;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.service.BlockchainStateLoader;
import de.schildbach.wallet.service.ServiceGenerator;
import de.schildbach.wallet.ui.send.SendCoinsActivity;
import hashengineering.darkcoin.wallet.BuildConfig;
import hashengineering.darkcoin.wallet.R;
import hashengineering.darkcoin.wallet.databinding.BuyDashFragmentBinding;
import hashengineering.darkcoin.wallet.databinding.SellDashFragmentBinding;
import okhttp3.Interceptor;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;


public final class BuyDashFragment extends Fragment implements OnSharedPreferenceChangeListener {
    private static final String TAG = BuyDashFragment.class.getSimpleName();
    private static final int ID_BALANCE_LOADER = 0;
    private static final int ID_RATE_LOADER = 1;
    private static final int ID_BLOCKCHAIN_STATE_LOADER = 2;
    private AbstractWalletActivity activity;
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private LoaderManager loaderManager;
    private Coin balance = null;
    private CurrencyCalculatorLink amountCalculatorLink;


    private final LoaderManager.LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            return new ExchangeRateLoader(activity, config);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> loader, final Cursor data) {
            if (data != null && data.getCount() > 0) {
                data.moveToFirst();
                final ExchangeRatesProvider.ExchangeRate exchangeRate = ExchangeRatesProvider.getExchangeRate(data);

                amountCalculatorLink.setExchangeRate(exchangeRate.rate);
                updateView();
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> loader) {
        }
    };

    private final LoaderManager.LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>() {
        @Override
        public Loader<Coin> onCreateLoader(final int id, final Bundle args) {
            return new WalletBalanceLoader(activity, wallet);
        }

        @Override
        public void onLoadFinished(final Loader<Coin> loader, final Coin balance) {
            BuyDashFragment.this.balance = balance;
            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<Coin> loader) {
        }
    };
    @Nullable
    private BlockchainState blockchainState = null;


    private final LoaderManager.LoaderCallbacks<BlockchainState> blockchainStateLoaderCallbacks = new LoaderManager.LoaderCallbacks<BlockchainState>() {
        @Override
        public Loader<BlockchainState> onCreateLoader(final int id, final Bundle args) {
            return new BlockchainStateLoader(activity);
        }

        @Override
        public void onLoadFinished(final Loader<BlockchainState> loader, final BlockchainState blockchainState) {
            BuyDashFragment.this.blockchainState = blockchainState;

            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<BlockchainState> loader) {
        }
    };

    @Nullable
    private String defaultCurrency = null;
    private BuyDashFragmentBinding binding;

    private String token;
    private Interceptor interceptor = new Interceptor() {
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            Request original = chain.request();

            // Request customization: add request headers
            Request.Builder requestBuilder = original.newBuilder()
                    .addHeader("X-Coins-Api-Token", token)
                    .addHeader("Content-Type", "multipart/form-data");

            Request request = requestBuilder.build();
            return chain.proceed(request);
        }
    };

    private CountryData countryData;

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

        binding = DataBindingUtil.inflate(inflater, R.layout.buy_dash_fragment, container, false);

        String json = null;
        try {
            InputStream is = activity.getAssets().open("countries.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            json = new String(buffer, "UTF-8");
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }

        if (null != json) {

            countryData = new Gson().fromJson(json, CountryData.class);

            List<String> stringList = new ArrayList<>();

            for (CountryData.CountriesBean bean : countryData.countries) {
                stringList.add(bean.name + " (" + bean.code + ")");
            }

            ArrayAdapter<String> countryAdapter = new ArrayAdapter<String>(activity,
                    android.R.layout.simple_spinner_dropdown_item, stringList);
            countryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
//            binding.spCountry.setAdapter(countryAdapter);
        }

        binding.requestCoinsAmountBtc.setCurrencySymbol(config.getFormat().code());
        binding.requestCoinsAmountBtc.setInputFormat(config.getMaxPrecisionFormat());
        binding.requestCoinsAmountBtc.setHintFormat(config.getFormat());

        binding.requestCoinsAmountLocal.setInputFormat(Constants.LOCAL_FORMAT);
        binding.requestCoinsAmountLocal.setHintFormat(Constants.LOCAL_FORMAT);
        amountCalculatorLink = new CurrencyCalculatorLink(binding.requestCoinsAmountBtc, binding.requestCoinsAmountLocal);


        return binding.getRoot();
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState)
    {
        super.onViewCreated(view, savedInstanceState);

        amountCalculatorLink.setExchangeDirection(config.getLastExchangeDirection());
        amountCalculatorLink.requestFocus();
    }

    @Override
    public void onResume() {
        super.onResume();

        amountCalculatorLink.setListener(new CurrencyAmountView.Listener()
        {
            @Override
            public void changed()
            {
                updateView();
            }

            @Override
            public void focusChanged(final boolean hasFocus)
            {
            }
        });
        loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);

        loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);
        loaderManager.initLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);

        updateView();
    }

    @Override
    public void onPause() {
        loaderManager.destroyLoader(ID_BALANCE_LOADER);
        loaderManager.destroyLoader(ID_BLOCKCHAIN_STATE_LOADER);

        amountCalculatorLink.setListener(null);

        super.onPause();
    }

    @Override
    public void onDestroy() {
        config.unregisterOnSharedPreferenceChangeListener(this);

        config.setLastExchangeDirection(amountCalculatorLink.getExchangeDirection());
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
}
