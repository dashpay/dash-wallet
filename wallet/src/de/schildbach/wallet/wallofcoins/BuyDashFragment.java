package de.schildbach.wallet.wallofcoins;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.databinding.DataBindingUtil;
import android.graphics.Color;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.Loader;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.Html;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.Nullable;

import de.schildbach.wallet.AddressBookProvider;
import de.schildbach.wallet.BuyDashPref;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.ExchangeRatesProvider;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.service.BlockchainStateLoader;
import de.schildbach.wallet.ui.AbstractWalletActivity;
import de.schildbach.wallet.ui.AddressAndLabel;
import de.schildbach.wallet.ui.CurrencyAmountView;
import de.schildbach.wallet.ui.CurrencyCalculatorLink;
import de.schildbach.wallet.ui.ExchangeRateLoader;
import de.schildbach.wallet.ui.WalletBalanceLoader;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.response.BuyDashErrorResp;
import de.schildbach.wallet.wallofcoins.response.CaptureHoldResp;
import de.schildbach.wallet.wallofcoins.response.CheckAuthResp;
import de.schildbach.wallet.wallofcoins.response.ConfirmDepositResp;
import de.schildbach.wallet.wallofcoins.response.CountryData;
import de.schildbach.wallet.wallofcoins.response.CreateDeviceResp;
import de.schildbach.wallet.wallofcoins.response.CreateHoldResp;
import de.schildbach.wallet.wallofcoins.response.DiscoveryInputsResp;
import de.schildbach.wallet.wallofcoins.response.GetAuthTokenResp;
import de.schildbach.wallet.wallofcoins.response.GetOffersResp;
import de.schildbach.wallet.wallofcoins.response.GetReceivingOptionsResp;
import de.schildbach.wallet.wallofcoins.response.OrderListResp;
import hashengineering.darkcoin.wallet.R;
import hashengineering.darkcoin.wallet.databinding.BuyDashFragmentBinding;
import hashengineering.darkcoin.wallet.databinding.ItemOrderListBinding;
import okhttp3.Interceptor;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import static org.bitcoinj.wallet.KeyChain.KeyPurpose.RECEIVE_FUNDS;


public final class BuyDashFragment extends Fragment implements OnSharedPreferenceChangeListener {
    private static final String TAG = BuyDashFragment.class.getSimpleName();
    private static final int ID_BALANCE_LOADER = 0;
    private static final int ID_RATE_LOADER = 1;
    private static final int ID_BLOCKCHAIN_STATE_LOADER = 2;
    private static final int ID_ADDRESS_LOADER = 4;
    private static final int PERMISSIONS_REQUEST_LOCATION = 8989;
    private AbstractWalletActivity activity;
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private LoaderManager loaderManager;
    private Coin balance = null;
    private CurrencyCalculatorLink amountCalculatorLink;
    private BuyDashPref buyDashPref;
    private AddressAndLabel currentAddressQrAddress = null;
    private String keyAddress;
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

    private final LoaderManager.LoaderCallbacks<BlockchainState> blockchainStateLoaderCallbacks = new LoaderManager.LoaderCallbacks<BlockchainState>() {
        @Override
        public Loader<BlockchainState> onCreateLoader(final int id, final Bundle args) {
            return new BlockchainStateLoader(activity);
        }

        @Override
        public void onLoadFinished(final Loader<BlockchainState> loader, final BlockchainState blockchainState) {
            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<BlockchainState> loader) {
        }
    };

    private final LoaderManager.LoaderCallbacks<Address> addressLoaderCallbacks = new LoaderManager.LoaderCallbacks<Address>() {
        @Override
        public Loader<Address> onCreateLoader(final int id, final Bundle args) {
            return new CurrentAddressLoader(activity, application.getWallet(), config);
        }

        @Override
        public void onLoadFinished(Loader<Address> loader, Address currentAddress) {
            if (!currentAddress.equals(currentAddressQrAddress)) {

                currentAddressQrAddress = new AddressAndLabel(currentAddress, config.getOwnName());
            }
        }

        @Override
        public void onLoaderReset(Loader<Address> loader) {

        }
    };

    private CreateHoldResp createHoldResp;
    private String offerId;
    private boolean isBuyMoreVisible;
    private List<GetReceivingOptionsResp> receivingOptionsResps;
    private String email = "";
    private String dashAmount = "";

    public static class CurrentAddressLoader extends AsyncTaskLoader<Address> {
        private LocalBroadcastManager broadcastManager;
        private final Wallet wallet;
        private Configuration config;

        private static final Logger log = LoggerFactory.getLogger(WalletBalanceLoader.class);

        public CurrentAddressLoader(final Context context, final Wallet wallet, final Configuration config) {
            super(context);

            this.broadcastManager = LocalBroadcastManager.getInstance(context.getApplicationContext());
            this.wallet = wallet;
            this.config = config;
        }

        @Override
        protected void onStartLoading() {
            super.onStartLoading();

            wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletChangeListener);
            wallet.addCoinsSentEventListener(Threading.SAME_THREAD, walletChangeListener);
            wallet.addChangeEventListener(Threading.SAME_THREAD, walletChangeListener);
            broadcastManager.registerReceiver(walletChangeReceiver, new IntentFilter(WalletApplication.ACTION_WALLET_REFERENCE_CHANGED));
            config.registerOnSharedPreferenceChangeListener(preferenceChangeListener);

            safeForceLoad();
        }

        @Override
        protected void onStopLoading() {
            config.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
            broadcastManager.unregisterReceiver(walletChangeReceiver);
            wallet.removeChangeEventListener(walletChangeListener);
            wallet.removeCoinsSentEventListener(walletChangeListener);
            wallet.removeCoinsReceivedEventListener(walletChangeListener);
            walletChangeListener.removeCallbacks();

            super.onStopLoading();
        }

        @Override
        protected void onReset() {
            config.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
            broadcastManager.unregisterReceiver(walletChangeReceiver);
            wallet.removeChangeEventListener(walletChangeListener);
            wallet.removeCoinsSentEventListener(walletChangeListener);
            wallet.removeCoinsReceivedEventListener(walletChangeListener);
            walletChangeListener.removeCallbacks();

            super.onReset();
        }

        @Override
        public Address loadInBackground() {
            org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

            return wallet.currentReceiveAddress();
        }

        private final ThrottlingWalletChangeListener walletChangeListener = new ThrottlingWalletChangeListener() {
            @Override
            public void onThrottledWalletChanged() {
                safeForceLoad();
            }
        };

        private final BroadcastReceiver walletChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                safeForceLoad();
            }
        };

        private final OnSharedPreferenceChangeListener preferenceChangeListener = new OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
                if (Configuration.PREFS_KEY_OWN_NAME.equals(key))
                    safeForceLoad();
            }
        };

        private void safeForceLoad() {
            try {
                forceLoad();
            } catch (final RejectedExecutionException x) {
                log.info("rejected execution: " + BuyDashFragment.CurrentAddressLoader.this.toString());
            }
        }
    }

    @Nullable
//    private String defaultCurrency = null;
    private BuyDashFragmentBinding binding;

    private String token;
    private Interceptor interceptor = new Interceptor() {
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            Request original = chain.request();
            // Request customization: add request headers
            Request.Builder requestBuilder = original.newBuilder();
            if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                requestBuilder.addHeader("X-Coins-Api-Token", buyDashPref.getAuthToken());
            }
            requestBuilder.addHeader("publisher-id", getString(R.string.WALLOFCOINS_PUBLISHER_ID));
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

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.buyDashPref = new BuyDashPref(PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext()));

        setRetainInstance(true);
        setHasOptionsMenu(true);

//        defaultCurrency = config.getExchangeCurrencyCode();
        config.registerOnSharedPreferenceChangeListener(this);
        buyDashPref.registerOnSharedPreferenceChangeListener(this);
    }

    String getDeviceId(Context context) {
        String deviceUID = buyDashPref.getDeviceId();
        if (TextUtils.isEmpty(deviceUID)) {
            String deviceID = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            byte[] data = (deviceID + deviceID + deviceID).getBytes(Charsets.UTF_8);
            deviceUID = Base64.encodeToString(data, Base64.DEFAULT).substring(0, 39);
            buyDashPref.setDeviceId(deviceUID);
        }
        return deviceUID;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_LOCATION: {
                for (int grantResult : grantResults) {
                    if (grantResult != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(activity, "Location permission denied, \nYou can enable it from Settings", Toast.LENGTH_LONG).show();
                        hideViewExcept(binding.layoutZip);
                        return;
                    }
                }
                getZip();
            }
        }
    }

   // LocationManager mLocationManager;

    private Location getLastKnownLocation() {
        boolean gps_enabled = false;
        boolean network_enabled = false;

        LocationManager lm = (LocationManager) activity
                .getSystemService(Context.LOCATION_SERVICE);

        gps_enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        network_enabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        Location net_loc = null, gps_loc = null, finalLoc = null;

        if (gps_enabled)
            gps_loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (network_enabled)
            net_loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

        if (gps_loc != null && net_loc != null) {

            //smaller the number more accurate result will
            if (gps_loc.getAccuracy() > net_loc.getAccuracy())
                finalLoc = net_loc;
            else
                finalLoc = gps_loc;

            // I used this just to get an idea (if both avail, its upto you which you want to take
            // as I've taken location with more accuracy)

        } else {

            if (gps_loc != null) {
                finalLoc = gps_loc;
            } else if (net_loc != null) {
                finalLoc = net_loc;
            }
        }
        return finalLoc;
    }

    private void requestLocation() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            getZip();
        } else {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    PERMISSIONS_REQUEST_LOCATION);
        }
    }

    private void getReceivingOptions() {
        String locale;
        locale = getResources().getConfiguration().locale.getCountry();
        binding.linearProgress.setVisibility(View.VISIBLE);

        WallofCoins.createService(interceptor, getActivity()).getReceivingOptions(locale.toLowerCase(), getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<List<GetReceivingOptionsResp>>() {
            @Override
            public void onResponse(Call<List<GetReceivingOptionsResp>> call, Response<List<GetReceivingOptionsResp>> response) {
                Log.e(TAG, "onResponse: " + response.body().size());
                binding.linearProgress.setVisibility(View.GONE);
                receivingOptionsResps = response.body();
                hideViewExcept(binding.layoutBanks);

                //set data in drop down list
                setPaymentOptNames(receivingOptionsResps);
            }

            @Override
            public void onFailure(Call<List<GetReceivingOptionsResp>> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                t.printStackTrace();
            }
        });
    }

    private void setPaymentOptNames(final List<GetReceivingOptionsResp> receivingOptionsResps) {
        final ArrayList<String> names = new ArrayList<String>();
        GetReceivingOptionsResp optionsRespDefaultName = new GetReceivingOptionsResp();
        optionsRespDefaultName.name = "Select Payment Center";
        receivingOptionsResps.add(0, optionsRespDefaultName);
        for (GetReceivingOptionsResp receivingOptionsResp : receivingOptionsResps) {
            names.add((receivingOptionsResp.name));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.buyDashZip.setText(null);
        binding.spBanks.setAdapter(adapter);

        binding.spBanks.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) return;
                bankId = "" + receivingOptionsResps.get(position - 1).id;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

    }

    public boolean isValidEmail(CharSequence target) {
        if (target == null) {
            return false;
        } else {
            return android.util.Patterns.EMAIL_ADDRESS.matcher(target).matches();
        }
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {

        binding = DataBindingUtil.inflate(inflater, R.layout.buy_dash_fragment, container, false);

        binding.buttonBuyDashGetLocation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestLocation();
            }
        });

        binding.buttonBuyDashGetLocationNo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideViewExcept(binding.layoutZip);
            }
        });

        binding.buttonBuyDashZipNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                zipCode = binding.buyDashZip.getText().toString();
                if (TextUtils.isEmpty(zipCode)) {
                    getReceivingOptions();
                } else {
                    hideViewExcept(binding.layoutCreateHold);
                    showKeyBoard();
                }
            }
        });

        binding.buttonBuyDashBankNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (binding.spBanks.getSelectedItemPosition() == 0) {
                    Toast.makeText(activity, "Select Any Payment Center", Toast.LENGTH_LONG).show();
                } else {
                    zipCode = null;
                    callDiscoveryInputs();
                }
            }
        });

        binding.btnNextEmail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!binding.editBuyDashEmail.getText().toString().isEmpty() && isValidEmail(binding.editBuyDashEmail.getText().toString())) {
                    email = binding.editBuyDashEmail.getText().toString();
                    hideViewExcept(binding.linearPhone);
                } else {
                    Toast.makeText(activity, "Enter Valid Email or click [Do Not send me email] to skip", Toast.LENGTH_LONG).show();
                }
            }
        });

        binding.tvSkipEmail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideViewExcept(binding.linearPhone);
            }
        });

        binding.btnNextPhone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String countryCode = countryData.countries.get(binding.spCountry.getSelectedItemPosition()).code;
                String phone = countryCode + binding.editBuyDashPhone.getText().toString().trim();
                buyDashPref.setPhone(phone);
                hideKeyBoard();
                checkAuth();

            }
        });

        String json;
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

        countryData = new Gson().fromJson(json, CountryData.class);

        List<String> stringList = new ArrayList<>();

        for (CountryData.CountriesBean bean : countryData.countries) {
            stringList.add(bean.name + " (" + bean.code + ")");
        }

        CustomAdapter customAdapter = new CustomAdapter(activity, R.layout.spinner_row_country, countryData.countries);
        customAdapter.setDropDownViewResource(R.layout.spinner_row_country);
        binding.spCountry.setAdapter(customAdapter);

        if (config.getFormat().code().equals("DASH")) {
            binding.btnBuyMore.setText("Buy More Dash!");
        } else {
            binding.btnBuyMore.setText("Buy More Bitcoin!");
        }
        binding.requestCoinsAmountBtc.setCurrencySymbol(config.getFormat().code());
        binding.requestCoinsAmountBtc.setInputFormat(config.getMaxPrecisionFormat());
        binding.requestCoinsAmountBtc.setHintFormat(config.getFormat());

        binding.requestCoinsAmountLocal.setInputFormat(Constants.LOCAL_FORMAT);
        binding.requestCoinsAmountLocal.setHintFormat(Constants.LOCAL_FORMAT);
        amountCalculatorLink = new CurrencyCalculatorLink(binding.requestCoinsAmountBtc, binding.requestCoinsAmountLocal);

        binding.rvOffers.setLayoutManager(new LinearLayoutManager(activity));

        if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
            if (!TextUtils.isEmpty(buyDashPref.getHoldId())) {
                CreateHoldResp createHoldResp = buyDashPref.getCreateHoldResp();
                binding.etOtp.setText(createHoldResp.__PURCHASE_CODE);
                hideViewExcept(binding.layoutVerifyOtp);
            } else {
                hideViewExcept(binding.rvOrderList);
                getOrderList(false);
            }
        }


        binding.buttonBuyDashGetOffers.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyBoard();
                if (Float.valueOf(binding.requestCoinsAmountLocal.getTextView().getHint().toString()) > 0f || !TextUtils.isEmpty(binding.requestCoinsAmountLocal.getTextView().getText())) {
                    if (!TextUtils.isEmpty(binding.requestCoinsAmountLocal.getTextView().getText().toString()) && Float.valueOf(binding.requestCoinsAmountLocal.getTextView().getText().toString()) >= 5f
                            || !TextUtils.isEmpty(binding.requestCoinsAmountLocal.getTextView().getHint().toString()) && Float.valueOf(binding.requestCoinsAmountLocal.getTextView().getHint().toString()) >= 5f) {
                        callDiscoveryInputs();
                    } else {
                        Toast.makeText(activity, "Purchase amount must be at least $5", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(getContext(), "Enter amount", Toast.LENGTH_SHORT).show();
                }
            }
        });

        binding.buttonVerifyOtp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                verifyOTP();
            }
        });
        binding.etOtp.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if(s.length() ==5)
                    verifyOTP();
            }
        });

        return binding.getRoot();
    }

    private void verifyOTP(){
        hideKeyBoard();
        HashMap<String, String> captureHoldReq = new HashMap<String, String>();
        String otp = binding.etOtp.getText().toString().trim();
        if (TextUtils.isEmpty(otp)) {
            Toast.makeText(getContext(), "Please Enter Purchase Code!", Toast.LENGTH_LONG).show();
            return;
        }

        captureHoldReq.put("publisherId", getString(R.string.WALLOFCOINS_PUBLISHER_ID));
        captureHoldReq.put("verificationCode", otp);
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).captureHold(buyDashPref.getHoldId(), captureHoldReq)
                .enqueue(new Callback<List<CaptureHoldResp>>() {
                    @Override
                    public void onResponse(Call<List<CaptureHoldResp>> call, final Response<List<CaptureHoldResp>> response) {
                        binding.linearProgress.setVisibility(View.GONE);
                        buyDashPref.setHoldId("");
                        buyDashPref.setCreateHoldResp(null);
                        Log.e(TAG, "onResponse: " + buyDashPref.getHoldId() + " here");
                        if (null != response && null != response.body() && !response.body().isEmpty()) {
                            if (response.body().get(0).account != null && !TextUtils.isEmpty(response.body().get(0).account)) {
                                updateAddressBookValue(keyAddress,"WallofCoins.com - Order " + response.body().get(0).id);

                                if (isJSONValid(response.body().get(0).account)) {
                                    ItemOrderListBinding itemBankBinding = DataBindingUtil.inflate(LayoutInflater.from(activity), R.layout.item_order_list, null, false);

                                    itemBankBinding.layLogout.setVisibility(View.GONE);
                                    itemBankBinding.layHelpInstruction.setVisibility(View.GONE);
                                    itemBankBinding.layoutCompletionDetail.setVisibility(View.VISIBLE);

                                    Glide.with(activity).load(response.body().get(0).bankLogo).into(itemBankBinding.imageBank);
                                    if (!TextUtils.isEmpty(response.body().get(0).bankName)) {
                                        itemBankBinding.textBankName.setText(response.body().get(0).bankName);
                                    } else {
                                        itemBankBinding.textBankName.setVisibility(View.GONE);
                                    }
                                    if (!TextUtils.isEmpty(response.body().get(0).nearestBranch.phone)) {
                                        itemBankBinding.textPhone.setText("Phone: " + response.body().get(0).nearestBranch.phone);
                                    } else {
                                        itemBankBinding.textPhone.setVisibility(View.GONE);
                                    }

                                    if (!TextUtils.isEmpty(response.body().get(0).nameOnAccount)) {
                                        itemBankBinding.textNameAccount.setText("Name on Account: " + response.body().get(0).nameOnAccount);
                                    } else {
                                        itemBankBinding.textNameAccount.setVisibility(View.GONE);
                                    }

                                    if (!TextUtils.isEmpty(response.body().get(0).payment)) {
                                        itemBankBinding.textCashToDeposite.setText(getString(R.string.cash_to_deposit, Float.valueOf(response.body().get(0).payment)));
                                    } else {
                                        itemBankBinding.textCashToDeposite.setVisibility(View.GONE);
                                    }

                                    itemBankBinding.orderDash.setText("You are ordering: " + response.body().get(0).total + " Dash.\n"
                                            + "You must deposit cash at the above Payment Center. Additional fees may apply. Paying in another method other than cash may delay your order.");

                                    itemBankBinding.textContactInstruction.setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            String url = "https://wallofcoins.com/";
                                            Intent i = new Intent(Intent.ACTION_VIEW);
                                            i.setData(Uri.parse(url));
                                            startActivity(i);
                                        }
                                    });

                                    countDownStart(response.body().get(0).paymentDue, itemBankBinding.textPaymentDueDate);

//                                            if (response.body().get(0).paymentDue != null && !TextUtils.isEmpty(response.body().get(0).paymentDue)) {
//                                                itemBankBinding.textDepositeDue.setText("Deposit Due: " + response.body().get(0).paymentDue.substring(0, 16).replace("T", " "));
//                                            } else {
//                                                itemBankBinding.textDepositeDue.setVisibility(View.GONE);
//                                            }
                                    Type listType = new TypeToken<ArrayList<AccountJson>>() {
                                    }.getType();
                                    ArrayList<AccountJson> accountList = new Gson().fromJson(response.body().get(0).account, listType);
                                    for (int i = 0; i < accountList.size(); i++) {
                                        TextView textView = new TextView(getActivity());
                                        textView.setTextSize(16);
                                        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                                        layoutParams.topMargin = 8;
                                        textView.setLayoutParams(layoutParams);
                                        textView.setText(accountList.get(i).getLabel() + ": " + accountList.get(i).getValue());
                                        if (response.body().get(0).status.equals("WD") && !accountList.get(i).getLabel().contains("Name on Account"))
                                            itemBankBinding.linearAccountDetail.addView(textView);
                                    }
                                    binding.layoutCompletionDetail.removeAllViews();
                                    binding.layoutCompletionDetail.addView(itemBankBinding.getRoot());

                                    if (response.body().get(0).status.equals("WD")) {
                                        itemBankBinding.btnCancelOrder.setVisibility(View.VISIBLE);
                                        itemBankBinding.btnDepositFinished.setVisibility(View.VISIBLE);
                                        itemBankBinding.textAccountNo.setVisibility(View.VISIBLE);
                                    } else {
                                        itemBankBinding.btnCancelOrder.setVisibility(View.GONE);
                                        itemBankBinding.btnDepositFinished.setVisibility(View.GONE);
                                        itemBankBinding.textAccountNo.setVisibility(View.GONE);
                                    }

                                    itemBankBinding.btnDepositFinished.setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            hideKeyBoard();
                                            AlertDialog.Builder builder;
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                                builder = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert);
                                            } else {
                                                builder = new AlertDialog.Builder(activity);
                                            }
                                            builder.setTitle(getString(R.string.deposit_finish_confirmation_title))
                                                    .setMessage(getString(R.string.deposit_finish_confirmation_message))
                                                    .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                                        public void onClick(DialogInterface dialog, int which) {
                                                            confirmDeposit(response.body().get(0));
                                                        }
                                                    })
                                                    .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                                        @Override
                                                        public void onClick(DialogInterface dialog, int which) {
                                                            dialog.dismiss();
                                                        }
                                                    })
                                                    .show();
                                        }
                                    });

                                    itemBankBinding.btnCancelOrder.setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            hideKeyBoard();
                                            // call cancel order
                                            AlertDialog.Builder builder;
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                                builder = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert);
                                            } else {
                                                builder = new AlertDialog.Builder(activity);
                                            }
                                            builder.setTitle(getString(R.string.deposit_cancel_confirmation_title))
                                                    .setMessage(getString(R.string.deposit_cancel_confirmation_message))
                                                    .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                                        @Override
                                                        public void onClick(DialogInterface dialog, int which) {
                                                            cancelOrder("" + response.body().get(0).id);
                                                        }
                                                    })
                                                    .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                                        @Override
                                                        public void onClick(DialogInterface dialog, int which) {
                                                            dialog.dismiss();
                                                        }
                                                    })
                                                    .show();
                                        }
                                    });
                                } else {
                                    binding.setConfiremedData(response.body().get(0));
                                }
                            } else {
                                binding.setConfiremedData(response.body().get(0));
                            }

                            hideViewExcept(binding.scrollCompletionDetail);

                            binding.orderDash.setText("You are ordering: " + response.body().get(0).total + " Dash.\n"
                                    + "You must deposit cash at the above Payment Center. Additional fees may apply. Paying in another method other than cash may delay your order.");

                            binding.textContactInstruction.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    String url = "https://wallofcoins.com/";
                                    Intent i = new Intent(Intent.ACTION_VIEW);
                                    i.setData(Uri.parse(url));
                                    startActivity(i);
                                }
                            });

                            countDownStart(response.body().get(0).paymentDue, binding.textPaymentDueDate);

                            binding.textNearByCenter.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    hideKeyBoard();
                                    String yourAddress = response.body().get(0).nearestBranch.name
                                            + ", " + response.body().get(0).nearestBranch.address
                                            + ", " + response.body().get(0).nearestBranch.city
                                            + ", " + response.body().get(0).nearestBranch.state;
                                    String uri = "http://maps.google.co.in/maps?q=" + yourAddress;
                                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                                    activity.startActivity(intent);
                                }
                            });
                            binding.btnDepositFinished.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    hideKeyBoard();

                                    AlertDialog.Builder builder;
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        builder = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert);
                                    } else {
                                        builder = new AlertDialog.Builder(activity);
                                    }
                                    builder.setTitle(getString(R.string.deposit_finish_confirmation_title))
                                            .setMessage(getString(R.string.deposit_finish_confirmation_message))
                                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                                public void onClick(DialogInterface dialog, int which) {
                                                    confirmDeposit(response.body().get(0));
                                                }
                                            })
                                            .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface dialog, int which) {
                                                    dialog.dismiss();
                                                }
                                            })
                                            .show();
                                }
                            });

                            binding.btnCancelOrder.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    hideKeyBoard();
                                    // call cancel order
                                    AlertDialog.Builder builder;
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        builder = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert);
                                    } else {
                                        builder = new AlertDialog.Builder(activity);
                                    }
                                    builder.setTitle(getString(R.string.deposit_cancel_confirmation_title))
                                            .setMessage(getString(R.string.deposit_cancel_confirmation_message))
                                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface dialog, int which) {
                                                    cancelOrder("" + response.body().get(0).id);
                                                }
                                            })
                                            .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface dialog, int which) {
                                                    dialog.dismiss();
                                                }
                                            })
                                            .show();
                                }
                            });

                        } else if (null != response && null != response.errorBody()) {
                            binding.linearProgress.setVisibility(View.GONE);

                            if (response.code() == 404) {
                                AlertDialog.Builder builder;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    builder = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert);
                                } else {
                                    builder = new AlertDialog.Builder(activity);
                                }
                                builder.setTitle("Whoops!")
                                        .setMessage("The Purchase Code has expired. " +
                                                "After you receive the Purchase Code, you will need to type quickly. " +
                                                "You will need to create another order to receive another Purchase Code.")
                                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface dialog, int which) {
                                                hideViewExcept(binding.layoutCreateHold);
                                            }
                                        })
                                        .show();
                            } else {
                                try {
                                    BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                                    Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                }
                            }
                        } else {
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                            binding.linearProgress.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onFailure
                            (Call<List<CaptureHoldResp>> call, Throwable t) {
                        binding.linearProgress.setVisibility(View.GONE);
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                        Log.e(TAG, "onFailure: ", t);
                    }
                });
    }

    int countdownInterval = 1000;

    public void countDownStart(final String dueDateTime, final TextView textDepositeDue) {

        Log.e(TAG, "countDownStart: " + dueDateTime);
        countdownInterval = 1000;
        final Handler handler = new Handler();
        final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                handler.postDelayed(this, countdownInterval);
                try {
                    SimpleDateFormat dateFormat = new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss");
                    dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                    // Here Set your Event Date
                    Date eventDate = dateFormat.parse(dueDateTime.replace("T", " ").substring(0, 19));
                    Date currentDate = new Date();
                    if (!currentDate.after(eventDate)) {
                        long diff = eventDate.getTime()
                                - currentDate.getTime();
//                        long days = diff / (24 * 60 * 60 * 1000);
//                        diff -= days * (24 * 60 * 60 * 1000);
                        long hours = diff / (60 * 60 * 1000);
                        diff -= hours * (60 * 60 * 1000);
                        long minutes = diff / (60 * 1000);
                        diff -= minutes * (60 * 1000);
                        long seconds = diff / 1000;

                        if (hours > 0) {
                            textDepositeDue.setText("Deposit Due: " + hours + " hours " + minutes + " minutes");
                            countdownInterval = 60 * 1000;
                        } else {
                            if (minutes < 10) {
                                textDepositeDue.setTextColor(Color.parseColor("#DD0000"));
                            } else {
                                textDepositeDue.setTextColor(Color.parseColor("#000000"));
                            }
                            textDepositeDue.setText("Deposit Due: " + minutes + " minutes " + seconds + " seconds");
                            countdownInterval = 1000;
                        }
                    } else {

                        textDepositeDue.setText("Deposit Due: 0 minutes 0 seconds");
                        handler.removeMessages(0);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
        handler.postDelayed(runnable, 0);
    }

    private void getZip() {

        Location myLocation = getLastKnownLocation();
        if(myLocation != null) {

            Geocoder geocoder;
            List<android.location.Address> addresses;
            geocoder = new Geocoder(activity, Locale.getDefault());
            if (geocoder != null) {
                try {
                    addresses = geocoder.getFromLocation(myLocation.getLatitude(), myLocation.getLongitude(), 1); // Here 1 represent max location result to returned, by documents it recommended 1 to 5
                    zipCode = addresses.get(0).getPostalCode();

                    hideViewExcept(binding.layoutCreateHold);
                    showKeyBoard();

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }else{
            LocationManager mlocManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
            boolean enabled = mlocManager.isProviderEnabled(LocationManager.GPS_PROVIDER);

            if(!enabled) {
                showDialogGPS();
            }
        }
    }

    /**
     * Show a dialog to the user requesting that GPS be enabled
     */
    private void showDialogGPS() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setCancelable(false);
        builder.setTitle("Enable GPS");
        builder.setMessage("Please enable GPS for Find My Location");
        builder.setInverseBackgroundForced(true);
        builder.setPositiveButton("Enable", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                startActivity(
                        new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            }
        });
        builder.setNegativeButton("Ignore", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        AlertDialog alert = builder.create();
        alert.show();
    }

    public boolean isJSONValid(String test) {
        try {
            new JSONObject(test);
        } catch (JSONException ex) {
            try {
                new JSONArray(test);
            } catch (JSONException ex1) {
                return false;
            }
        }
        return true;
    }

    private void cancelOrder(String orderId) {
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, activity).cancelOrder(orderId, getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                binding.linearProgress.setVisibility(View.GONE);
                if (response.code() == 204) {
                    Toast.makeText(getContext(), "Your Order cancelled successfully", Toast.LENGTH_SHORT).show();
                    binding.requestCoinsAmountBtcEdittext.setText("");
                    binding.requestCoinsAmountLocalEdittext.setText("");
                    binding.buyDashZip.setText("");
                    hideViewExcept(binding.layoutLocation);
                } else {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "onFailure: ", t);
                binding.linearProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmDeposit(CaptureHoldResp response) {
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).confirmDeposit("" + response.id, "", getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<ConfirmDepositResp>() {
            @Override
            public void onResponse(Call<ConfirmDepositResp> call, Response<ConfirmDepositResp> response) {
                binding.linearProgress.setVisibility(View.GONE);

                if (null != response && null != response.body()) {
                    binding.scrollCompletionDetail.setVisibility(View.GONE);
                    Toast.makeText(activity, "Thank you for making the payment! Once we verify your payment, we will send the Dash to your wallet!", Toast.LENGTH_LONG).show();
                    getOrderList(false);
                } else if (null != response && null != response.errorBody()) {
                    try {
                        BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                        Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }

                } else {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<ConfirmDepositResp> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Log.e(TAG, "onFailure: ", t);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        amountCalculatorLink.setExchangeDirection(config.getLastExchangeDirection());
        amountCalculatorLink.requestFocus();
    }

    @Override
    public void onResume() {
        super.onResume();

        amountCalculatorLink.setListener(new CurrencyAmountView.Listener() {
            @Override
            public void changed() {
                updateView();
            }

            @Override
            public void focusChanged(final boolean hasFocus) {
            }
        });
        loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);

        loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);
        loaderManager.initLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);
        loaderManager.initLoader(ID_ADDRESS_LOADER, null, addressLoaderCallbacks);

        updateView();
    }

    @Override
    public void onPause() {

        loaderManager.destroyLoader(ID_BALANCE_LOADER);
        loaderManager.destroyLoader(ID_BLOCKCHAIN_STATE_LOADER);
        loaderManager.destroyLoader(ID_ADDRESS_LOADER);

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
//            defaultCurrency = config.getExchangeCurrencyCode();

            updateView();
        }
    }

    private void updateView() {
        balance = application.getWallet().getBalance(BalanceType.ESTIMATED);
    }

    private String bankId = "";
    String zipCode;

    private void callDiscoveryInputs() {

        HashMap<String, String> discoveryInputsReq = new HashMap<String, String>();

        discoveryInputsReq.put("publisherId", getString(R.string.WALLOFCOINS_PUBLISHER_ID));
        keyAddress = wallet.freshAddress(RECEIVE_FUNDS).toBase58();
        discoveryInputsReq.put("cryptoAddress", keyAddress);


        try {
            if (Float.valueOf(binding.requestCoinsAmountLocal.getTextView().getHint().toString()) > 0f) {
                discoveryInputsReq.put("usdAmount", "" + binding.requestCoinsAmountLocal.getTextView().getHint());
            } else {
                discoveryInputsReq.put("usdAmount", "" + binding.requestCoinsAmountLocal.getTextView().getText());
            }
            Log.d(TAG, "callDiscoveryInputs: usdAmount==>>" + binding.requestCoinsAmountLocal.getTextView().getHint());
        } catch (Exception e) {
            discoveryInputsReq.put("usdAmount", "0");
            e.printStackTrace();
        }
        discoveryInputsReq.put("crypto", config.getFormat().code());
        discoveryInputsReq.put("bank", bankId);
        discoveryInputsReq.put("zipCode", zipCode);

        binding.linearProgress.setVisibility(View.VISIBLE);

        WallofCoins.createService(interceptor, getActivity()).discoveryInputs(discoveryInputsReq).enqueue(new Callback<DiscoveryInputsResp>() {
            @Override
            public void onResponse(Call<DiscoveryInputsResp> call, Response<DiscoveryInputsResp> response) {

                if (null != response && null != response.body()) {
                    if (null != response.body().id) {
                        updateAddressBookValue(keyAddress,"WallofCoins.com");

                        WallofCoins.createService(null, getActivity()).getOffers(response.body().id, getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<GetOffersResp>() {
                            @Override
                            public void onResponse(Call<GetOffersResp> call, final Response<GetOffersResp> response) {

                                if (null != response && null != response.body()) {

                                    binding.linearProgress.setVisibility(View.GONE);

                                    if (null != response.body().singleDeposit && !response.body().singleDeposit.isEmpty()) {
                                        hideViewExcept(binding.rvOffers);
                                        binding.spBanks.setAdapter(null);
                                        BuyDashOffersAdapter buyDashOffersAdapter = new BuyDashOffersAdapter(activity, response.body(), new AdapterView.OnItemSelectedListener() {
                                            @Override
                                            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                                hideKeyBoard();
                                                if (position < response.body().singleDeposit.size() + 1) {
                                                    offerId = response.body().singleDeposit.get(position - 1).id;
                                                    dashAmount = response.body().singleDeposit.get(position - 1).amount.DASH;
                                                } else {
                                                    offerId = response.body().doubleDeposit.get(position - response.body().singleDeposit.size() - 2).id;
                                                    dashAmount = response.body().doubleDeposit.get(position - response.body().singleDeposit.size() - 2).totalAmount.DASH;
                                                }
                                                if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                                                    createHold(true);
                                                } else {
                                                    hideViewExcept(binding.linearEmail);
                                                    clearForm((ViewGroup) binding.getRoot());
                                                }
                                            }

                                            @Override
                                            public void onNothingSelected(AdapterView<?> parent) {
                                                binding.linearProgress.setVisibility(View.GONE);
                                            }
                                        });
                                        binding.rvOffers.setAdapter(buyDashOffersAdapter);
                                    } else {
                                        Toast.makeText(getContext(), "No offers available", Toast.LENGTH_LONG).show();
                                    }
                                } else if (null != response && null != response.errorBody()) {
                                    binding.linearProgress.setVisibility(View.GONE);
                                    try {
                                        BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                                        Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                    }

                                } else {
                                    binding.linearProgress.setVisibility(View.GONE);
                                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                }
                            }

                            @Override
                            public void onFailure(Call<GetOffersResp> call, Throwable t) {
                                binding.linearProgress.setVisibility(View.GONE);
                                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                            }
                        });
                    } else {
                        binding.linearProgress.setVisibility(View.GONE);
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }
                } else if (null != response && null != response.errorBody()) {

                    binding.linearProgress.setVisibility(View.GONE);

                    try {
                        BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                        if (buyDashErrorResp.detail != null && !TextUtils.isEmpty(buyDashErrorResp.detail)) {
                            Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }

                } else {
                    binding.linearProgress.setVisibility(View.GONE);
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<DiscoveryInputsResp> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void getAuthTokenCall(final String password) {
        String phone = buyDashPref.getPhone();

        if (!TextUtils.isEmpty(phone)) {

            HashMap<String, String> getAuthTokenReq = new HashMap<String, String>();
            if (!TextUtils.isEmpty(password)) {
                getAuthTokenReq.put("password", password);
            } else {
                getAuthTokenReq.put("deviceCode", getDeviceId(activity));
            }
            getAuthTokenReq.put("publisherId", getString(R.string.WALLOFCOINS_PUBLISHER_ID));

            binding.linearProgress.setVisibility(View.VISIBLE);
            WallofCoins.createService(null, getActivity()).getAuthToken(phone, getAuthTokenReq).enqueue(new Callback<GetAuthTokenResp>() {
                @Override
                public void onResponse(Call<GetAuthTokenResp> call, Response<GetAuthTokenResp> response) {
                    binding.linearProgress.setVisibility(View.GONE);
                    int code = response.code();

                    if (code >= 400 && response.body() == null) {
                        try {
                            BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                            Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();

                        } catch (Exception e) {
                            e.printStackTrace();
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                        }
                        return;
                    }

                    if (!TextUtils.isEmpty(response.body().token)) {
                        buyDashPref.setAuthToken(response.body().token);
                    }
                    hideViewExcept(null);
                    // call create hold
                    if (!TextUtils.isEmpty(password)) {
                        creteDevice();
                    } else {
                        createHold(true);
                    }
                }

                @Override
                public void onFailure(Call<GetAuthTokenResp> call, Throwable t) {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                    binding.linearProgress.setVisibility(View.GONE);
                }
            });

        } else {
            Toast.makeText(getContext(), "Phone and Password is required", Toast.LENGTH_SHORT).show();
        }
    }

    private void showUserPasswordAuthenticationDialog() {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(activity);
        LayoutInflater inflater = activity.getLayoutInflater();
        final View dialogView = inflater.inflate(R.layout.authenticate_password_wallet_dialog, null);
        dialogBuilder.setView(dialogView);

        final EditText edtPassword = (EditText) dialogView.findViewById(R.id.edt_woc_authenticaion_password);

        final  TextView txtTitle = (TextView) dialogView.findViewById(R.id.txt_existing_user_dialog_message);
        txtTitle.setMovementMethod(LinkMovementMethod.getInstance());

        Button btnLogin = (Button)dialogView.findViewById(R.id.btnLogin);
        Button btnForgotPassword = (Button)dialogView.findViewById(R.id.btnForgotPassword);
        final AlertDialog alertDialog = dialogBuilder.create();
        alertDialog.show();

        btnForgotPassword.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goToUrl("https://wallofcoins.com/forgotPassword/");
            }
        });
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String password = edtPassword.getText().toString().trim();
                if(password.length()>0){
                    getAuthTokenCall(password);
                    alertDialog.dismiss();
                }else{
                    Toast.makeText(getContext(), R.string.password_alert, Toast.LENGTH_SHORT).show();
                }
            }
        });

    }

    private void goToUrl (String url) {
        Uri uriUrl = Uri.parse(url);
        Intent launchBrowser = new Intent(Intent.ACTION_VIEW, uriUrl);
        startActivity(launchBrowser);
    }

    private void creteDevice() {
        final HashMap<String, String> createDeviceReq = new HashMap<String, String>();
        createDeviceReq.put("name", "Dash Wallet (Android)");
        createDeviceReq.put("code", getDeviceId(getContext()));
        createDeviceReq.put("publisherId", getString(R.string.WALLOFCOINS_PUBLISHER_ID));
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).createDevice(createDeviceReq).enqueue(new Callback<CreateDeviceResp>() {
            @Override
            public void onResponse(Call<CreateDeviceResp> call, Response<CreateDeviceResp> response) {
                if (response.code() < 299) {
                    createHold(true);
                } else {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<CreateDeviceResp> call, Throwable t) {
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    public void updateAddressBookValue(String KEY_ADDRESS,String newLabel){
        if(KEY_ADDRESS!=null && newLabel!=null) {
            Address keyAddress = Address.fromBase58(Constants.NETWORK_PARAMETERS, KEY_ADDRESS);
            final Uri uri = AddressBookProvider.contentUri(activity.getPackageName()).buildUpon().appendPath(keyAddress.toBase58()).build();
            final String addressLabel = AddressBookProvider.resolveLabel(activity, keyAddress.toBase58());

            ContentResolver contentResolver;
            contentResolver = activity.getContentResolver();

            final ContentValues values = new ContentValues();

            values.put(AddressBookProvider.KEY_LABEL, newLabel);

            if (addressLabel == null) {
                contentResolver.insert(uri, values);
            } else {
                contentResolver.update(uri, values, null, null);
            }
        }
    }

    public void createHold(boolean isUserExist) {
        String phone = buyDashPref.getPhone();

        final HashMap<String, String> createHoldPassReq = new HashMap<String, String>();
        if (!isUserExist)
            createHoldPassReq.put("phone", phone);
        createHoldPassReq.put("offer", offerId);
        createHoldPassReq.put("publisherId", getString(R.string.WALLOFCOINS_PUBLISHER_ID));
        createHoldPassReq.put("email", email);
        createHoldPassReq.put("deviceName", "Dash Wallet (Android)");
        createHoldPassReq.put("deviceCode", getDeviceId(getContext()));
        Log.e(TAG, "createHold: " + getDeviceId(activity));
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).createHold(createHoldPassReq).enqueue(new Callback<CreateHoldResp>() {
            @Override
            public void onResponse(Call<CreateHoldResp> call, Response<CreateHoldResp> response) {
                binding.linearProgress.setVisibility(View.GONE);

                if (null != response.body() && response.code() < 299) {

                    createHoldResp = response.body();
                    buyDashPref.setHoldId(createHoldResp.id);
                    buyDashPref.setCreateHoldResp(createHoldResp);
                    if (!TextUtils.isEmpty(response.body().token)) {
                        buyDashPref.setAuthToken(createHoldResp.token);
                    }

                    hideViewExcept(binding.layoutVerifyOtp);

//                        Log.d(TAG, "onResponse: purchase code==>>" + createHoldResp.__PURCHASE_CODE);
                    clearForm((ViewGroup) binding.getRoot());
                    binding.etOtp.setText(createHoldResp.__PURCHASE_CODE);
                } else if (null != response.errorBody()) {
                    if (response.code() == 403 && !TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                        deleteAuthCall(true);
                        creteDevice();
                    }else if (response.code() == 403 && TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                        getAuthTokenCall(null);
                    }  else if (response.code() == 404) {
                        createHold(false);
                    } else if (response.code() == 400) {
                        if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                            getOrderList(true);
                        } else {
                            getAuthTokenCall(
                                    null);
                        }
                    } else {
                        try {
                            BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                            Log.d(TAG, "onResponse: message==>> " + buyDashErrorResp.detail);
                            getOrderList(false);
                            Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            e.printStackTrace();
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                        }
                    }
                } else {
                    clearForm((ViewGroup) binding.getRoot());
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<CreateHoldResp> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
            }
        });

    }

    public void deleteAuthCall(final boolean isPendingHold) {
        String phone = buyDashPref.getPhone();
        if (!TextUtils.isEmpty(phone)) {
            binding.linearProgress.setVisibility(View.VISIBLE);

            WallofCoins.createService(interceptor, activity).deleteAuth(phone, getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<CheckAuthResp>() {
                @Override
                public void onResponse(Call<CheckAuthResp> call, Response<CheckAuthResp> response) {
                    Log.d(TAG, "onResponse: response code==>>" + response.code());
                    binding.linearProgress.setVisibility(View.GONE);
                    if (response.code() < 299) {
                        buyDashPref.setAuthToken("");
                        if (isPendingHold) {
                            binding.editBuyDashPhone.setText(null);
                            checkAuth();
                        } else {
                            Toast.makeText(activity, "Signed Out Successfully", Toast.LENGTH_LONG).show();
                            hideViewExcept(binding.layoutLocation);

                        }
                    } else {
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onFailure(Call<CheckAuthResp> call, Throwable t) {
                    binding.linearProgress.setVisibility(View.GONE);
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Toast.makeText(getContext(), "Enter phone number", Toast.LENGTH_SHORT).show();
        }
    }

    public void getOrderList(final boolean isFromCreateHold) {
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, activity).getOrders(getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<List<OrderListResp>>() {
            @Override
            public void onResponse(Call<List<OrderListResp>> call, Response<List<OrderListResp>> response) {
                binding.linearProgress.setVisibility(View.GONE);
                if (response.code() == 200 && response.body() != null) {
                    Log.d(TAG, "onResponse: boolean==>" + isFromCreateHold);
                    if (response.body() != null && response.body().size() > 0) {
                        if (isFromCreateHold) {
                            List<OrderListResp> orderList = new ArrayList<>();

                            for (OrderListResp orderListResp : response.body()) {
                                if (orderListResp.status.equals("WD")) {
                                    Log.d(TAG, "onResponse: status==>" + orderListResp.status);
                                    orderList.add(orderListResp);
                                    break;
                                }
                            }
                            manageOrderList(orderList);
                        } else {
                            manageOrderList(response.body());
                        }
                    } else {
                        hideViewExcept(binding.layoutLocation);
                    }
                } else if (response.code() == 403) {
                    hideViewExcept(binding.layoutLocation);
                }
            }

            @Override
            public void onFailure(Call<List<OrderListResp>> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Log.e(TAG, "onFailure: ", t);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void manageOrderList(final List<OrderListResp> orderList) {

        if (orderList.size() > 0) {
            hideViewExcept(binding.layoutOrderList);
            for (OrderListResp orderListResp : orderList) {
                if (orderListResp.status.equals("WD")) {
                    binding.btnBuyMore.setVisibility(View.GONE);
                    isBuyMoreVisible = false;
                    break;
                } else {
                    binding.btnBuyMore.setVisibility(View.VISIBLE);
                    isBuyMoreVisible = true;
                }
            }
            binding.btnBuyMore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    binding.requestCoinsAmountBtcEdittext.setText("");
                    binding.requestCoinsAmountLocalEdittext.setText("");
                    binding.buyDashZip.setText("");
                    hideViewExcept(binding.layoutLocation);
                }
            });

            int lastWDV = -1;
            for (int i = 0; i < orderList.size(); i++) {
                if (orderList.get(i).status.equals("WDV")) {
                    lastWDV = i;
                }
            }

            if (lastWDV != -1) {
                OrderListResp orderListResp = new OrderListResp();
                orderListResp.id = -2;
                orderList.add(lastWDV+ 1, orderListResp);

                OrderListResp orderListResp1 = new OrderListResp();
                orderListResp1.id = -1;

                orderList.add(lastWDV + 2, orderListResp1);


                binding.textEmailReceipt.setVisibility(View.VISIBLE);
                binding.textEmailReceipt.setText(Html.fromHtml(getString(R.string.text_send_email_receipt)));
                final int finalLastWDV = lastWDV;
                binding.textEmailReceipt.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent emailIntent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts(
                                "mailto", "support@wallofcoins.com", null));
                        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{"support@wallofcoins.com"});
                        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Order #{" + orderList.get(finalLastWDV).id + "} - {" + buyDashPref.getPhone() + "}.");
                        emailIntent.putExtra(Intent.EXTRA_TEXT, "");
                        startActivity(Intent.createChooser(emailIntent, "Send email..."));
                    }
                });
            } else {
                binding.textEmailReceipt.setVisibility(View.GONE);
            }

            LinearLayoutManager linearLayoutManager = new LinearLayoutManager(activity);
            binding.rvOrderList.setLayoutManager(linearLayoutManager);
            binding.rvOrderList.setAdapter(new OrderListAdapter(activity, orderList));
        } else {
            hideViewExcept(binding.layoutLocation);
        }
    }

    public void checkAuth() {
        String phone = buyDashPref.getPhone();
        if (!TextUtils.isEmpty(phone)) {
            binding.linearProgress.setVisibility(View.VISIBLE);

            WallofCoins.createService(interceptor, activity).checkAuth(phone, getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<CheckAuthResp>() {
                @Override
                public void onResponse(Call<CheckAuthResp> call, Response<CheckAuthResp> response) {
                    Log.d(TAG, "onResponse: response code==>>" + response.code());
                    binding.linearProgress.setVisibility(View.GONE);
                    if (response.code() == 200) {
                        if (response.body() != null
                                && response.body().getAvailableAuthSources() != null
                                && response.body().getAvailableAuthSources().size() > 0) {
                            if (response.body().getAvailableAuthSources().get(0).equals("password")) {

                                showUserPasswordAuthenticationDialog();

                                return;
                            }else if (response.body().getAvailableAuthSources().size() >= 2
                                    && response.body().getAvailableAuthSources().get(1).equals("device")) {
                                hideKeyBoard();
                                createHold(true);
                            }else if (response.body().getAvailableAuthSources().get(0).equals("device")) {
                                hideKeyBoard();
                                createHold(true);
                                Log.d(TAG, "onResponse: device");
                            }
                        }
                    } else if (response.code() == 404) {
                        hideKeyBoard();
                        createHold(false);
                    }
                }

                @Override
                public void onFailure(Call<CheckAuthResp> call, Throwable t) {
                    binding.linearProgress.setVisibility(View.GONE);
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Toast.makeText(getContext(), "Enter phone number", Toast.LENGTH_SHORT).show();
        }
    }

    public void hideKeyBoard() {
        View view = activity.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    public void showKeyBoard() {
        binding.requestCoinsAmountBtcEdittext.requestFocus();
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
    }

    class OrderListAdapter extends RecyclerView.Adapter<OrderListAdapter.VHolder> {

        private final Activity activity;
        private final List<OrderListResp> orderList;

        public OrderListAdapter(Activity activity, List<OrderListResp> orderList) {
            this.activity = activity;
            this.orderList = orderList;
        }

        @Override
        public OrderListAdapter.VHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater layoutInflater = LayoutInflater.from(activity);
            ItemOrderListBinding itemBinding = DataBindingUtil.inflate(layoutInflater, R.layout.item_order_list, parent, false);
            return new VHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(OrderListAdapter.VHolder holder, int position) {
            final OrderListResp orderListResp = orderList.get(position);
            if (orderListResp.id != -1 & orderListResp.id != -2) {
                holder.itemBinding.layLogout.setVisibility(View.GONE);
                holder.itemBinding.layHelpInstruction.setVisibility(View.GONE);
                holder.itemBinding.layoutCompletionDetail.setVisibility(View.VISIBLE);
                holder.itemBinding.setItem(orderListResp);

                Type listType = new TypeToken<ArrayList<AccountJson>>() {
                }.getType();

                holder.itemBinding.linearAccountDetail.removeAllViews();
                try {
                    ArrayList<AccountJson> accountList = new Gson().fromJson(orderListResp.account, listType);

                    if(accountList!=null) {
                        for (int i = 0; i < accountList.size(); i++) {
                            TextView textView = new TextView(getActivity());
                            textView.setTextSize(16);
                            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                            layoutParams.topMargin = 0;
                            textView.setLayoutParams(layoutParams);
                            textView.setText(accountList.get(i).getLabel() + ": " + accountList.get(i).getValue());
                            holder.itemBinding.linearAccountDetail.addView(textView);
                        }
                    }else{
                        holder.itemBinding.linearAccountDetail.setVisibility(View.GONE);
                    }
                } catch (JsonSyntaxException e) {
                    e.printStackTrace();
                }


                holder.itemBinding.btnDepositFinished.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        hideKeyBoard();
                        AlertDialog.Builder builder;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            builder = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert);
                        } else {
                            builder = new AlertDialog.Builder(activity);
                        }
                        builder.setTitle(getString(R.string.deposit_finish_confirmation_title))
                                .setMessage(getString(R.string.deposit_finish_confirmation_message))
                                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        CaptureHoldResp response = new CaptureHoldResp();
                                        response.id = orderListResp.id;
                                        response.total = orderListResp.total;
                                        response.payment = orderListResp.payment;
                                        response.paymentDue = orderListResp.paymentDue;
                                        response.bankName = orderListResp.bankName;
                                        response.nameOnAccount = orderListResp.nameOnAccount;
                                        response.account = orderListResp.account;
                                        response.status = orderListResp.status;
                                        CaptureHoldResp.NearestBranchBean nearestBranchBean = new CaptureHoldResp.NearestBranchBean();
                                        nearestBranchBean.name = orderListResp.nearestBranch.name;
                                        nearestBranchBean.city = orderListResp.nearestBranch.city;
                                        nearestBranchBean.state = orderListResp.nearestBranch.state;
                                        nearestBranchBean.phone = orderListResp.nearestBranch.phone;
                                        nearestBranchBean.address = orderListResp.nearestBranch.address;
                                        response.nearestBranch = nearestBranchBean;
                                        response.bankUrl = orderListResp.account;
                                        response.bankLogo = orderListResp.account;
                                        response.bankIcon = orderListResp.account;
                                        response.bankIconHq = orderListResp.account;
                                        response.privateId = orderListResp.account;

                                        confirmDeposit(response);
                                    }
                                })
                                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        dialog.dismiss();
                                    }
                                })
                                .show();
                    }
                });

                holder.itemBinding.btnCancelOrder.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        hideKeyBoard();
                        // call cancel order
                        AlertDialog.Builder builder;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            builder = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert);
                        } else {
                            builder = new AlertDialog.Builder(activity);
                        }
                        builder.setTitle(getString(R.string.deposit_cancel_confirmation_title))
                                .setMessage(getString(R.string.deposit_cancel_confirmation_message))
                                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        cancelOrder("" + orderListResp.id);
                                    }
                                })
                                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        dialog.dismiss();
                                    }
                                })
                                .show();
                    }
                });

//              you must deposit cash
                double dots =    Double.parseDouble(orderListResp.total)* 1000000;
                DecimalFormat formatter = new DecimalFormat("#,###,###.##");
                String yourFormattedDots = formatter.format(dots);

                if (orderListResp.status.equals("WD")) {
                    holder.itemBinding.orderDash.setText("Total Dash: " + orderListResp.total + " (" + yourFormattedDots + " dots)\n"
                            + "You must deposit cash at the above Payment Center. Additional fees may apply. Paying in another method other than cash may delay your order.");
                    holder.itemBinding.orderDashInstruction.setVisibility(View.VISIBLE);
                    holder.itemBinding.btnCancelOrder.setVisibility(View.VISIBLE);
                    holder.itemBinding.btnDepositFinished.setVisibility(View.VISIBLE);
                    holder.itemBinding.layoutDueDate.setVisibility(View.VISIBLE);

                    holder.itemBinding.textPaymentDueDate.setVisibility(View.VISIBLE);
                    countDownStart(orderListResp.paymentDue, holder.itemBinding.textPaymentDueDate);
                    holder.itemBinding.textContactInstruction.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            goToUrl("https://wallofcoins.com");
                        }
                    });
                } else {
                    holder.itemBinding.orderDash.setText("Total Dash: " + orderListResp.total + " (" + yourFormattedDots +" dots)");
                    holder.itemBinding.layoutDueDate.setVisibility(View.GONE);
                    holder.itemBinding.textPaymentDueDate.setVisibility(View.GONE);
                    holder.itemBinding.orderDashInstruction.setVisibility(View.GONE);
                    holder.itemBinding.btnCancelOrder.setVisibility(View.GONE);
                    holder.itemBinding.btnDepositFinished.setVisibility(View.GONE);
                    holder.itemBinding.textContactInstruction.setVisibility(View.GONE);
                }

                if(orderListResp.status.equals("WD")){
                    holder.itemBinding.textTransactionStatus.setText("Status: Waiting Deposit");
                }else  if(orderListResp.status.equals("WDV")){
                    holder.itemBinding.textTransactionStatus.setText("Status: Waiting Deposit Verification");
                }else  if(orderListResp.status.equals("RERR")){
                    holder.itemBinding.textTransactionStatus.setText("Status: Issue with Receipt");
                }else  if(orderListResp.status.equals("DERR")){
                    holder.itemBinding.textTransactionStatus.setText("Status: Issue with Deposit");
                }else  if(orderListResp.status.equals("RSD")){
                    holder.itemBinding.textTransactionStatus.setText("Status: Reserved for Deposit'");
                }else  if(orderListResp.status.equals("RMIT")){
                    holder.itemBinding.textTransactionStatus.setText("Status: Remit Address Missing");
                }else  if(orderListResp.status.equals("UCRV")){
                    holder.itemBinding.textTransactionStatus.setText("Status: Under Review");
                }else  if(orderListResp.status.equals("PAYP")){
                    holder.itemBinding.textTransactionStatus.setText("Status: Done - Pending Delivery");
                }else  if(orderListResp.status.equals("SENT")){
                    holder.itemBinding.textTransactionStatus.setText("Status: Waiting Done - Units Delivered");
                }


                Log.e(TAG, "onBindViewHolder: " + orderListResp.status);
                if (orderListResp.status.equals("WDV")) {
                    holder.itemBinding.textNameAccount.setVisibility(View.GONE);
                    holder.itemBinding.textPhone.setVisibility(View.GONE);
                } else {
                    holder.itemBinding.textNameAccount.setVisibility(View.VISIBLE);
                    holder.itemBinding.textPhone.setVisibility(View.VISIBLE);
                }

            } else if (orderListResp.id == -1){
                holder.itemBinding.layoutCompletionDetail.setVisibility(View.GONE);
                holder.itemBinding.layHelpInstruction.setVisibility(View.GONE);
                holder.itemBinding.layLogout.setVisibility(View.VISIBLE);
                holder.itemBinding.textMessage.setText("Your wallet is signed into Wall of Coins using your mobile number " + buyDashPref.getPhone());

                holder.itemBinding.btnSignout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        deleteAuthCall(false);
                    }
                });


            } else if (orderListResp.id == -2){
                holder.itemBinding.layoutCompletionDetail.setVisibility(View.GONE);
                holder.itemBinding.layHelpInstruction.setVisibility(View.VISIBLE);
                holder.itemBinding.layLogout.setVisibility(View.GONE);
                holder.itemBinding.textHelpMessage.setText(" Call (866) 841-2646 for help. \n Help is also available on the website.");
                holder.itemBinding.btnWebLink.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        goToUrl("https://wallofcoins.com");
                    }
                });
            }

        }

        @Override
        public int getItemCount() {
            return orderList.size();
        }

        public class VHolder extends RecyclerView.ViewHolder {
            private ItemOrderListBinding itemBinding;

            public VHolder(ItemOrderListBinding itemBinding) {
                super(itemBinding.getRoot());
                this.itemBinding = itemBinding;
            }
        }
    }

    class AccountJson {

        /**
         * displaySort : 0
         * name : fullName
         * value : Ian Marshall
         * label : Full Name
         */

        private String displaySort;
        private String name;
        private String value;
        private String label;

        public String getDisplaySort() {
            return displaySort;
        }

        public void setDisplaySort(String displaySort) {
            this.displaySort = displaySort;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    private void clearForm(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            final View child = root.getChildAt(i);
            if (child instanceof ViewGroup) {
                clearForm((ViewGroup) child);
                // DO SOMETHING WITH VIEWGROUP, AFTER CHILDREN HAS BEEN LOOPED
            } else {
                if (child != null && child instanceof EditText) {
                    ((EditText) child).setText("");
                }
            }
        }
    }

    private void hideViewExcept(View v) {
        binding.layoutOrderList.setVisibility(View.GONE);
        binding.layoutLocation.setVisibility(View.GONE);
        binding.layoutCreateHold.setVisibility(View.GONE);
        binding.rvOffers.setVisibility(View.GONE);
        binding.linearEmail.setVisibility(View.GONE);
        binding.linearPhone.setVisibility(View.GONE);
        binding.linearPassword.setVisibility(View.GONE);
        binding.layoutVerifyOtp.setVisibility(View.GONE);
        binding.scrollCompletionDetail.setVisibility(View.GONE);
        binding.layoutBanks.setVisibility(View.GONE);
        binding.layoutZip.setVisibility(View.GONE);

        if (v != null) v.setVisibility(View.VISIBLE);
    }

    public boolean hideViewManageBack() {

        hideKeyBoard();

        if (binding.layoutOrderList.getVisibility() == View.VISIBLE) {
            return true;
        } else if (binding.layoutLocation.getVisibility() == View.VISIBLE) {
            if (binding.rvOrderList.getAdapter() != null) {
                hideViewExcept(binding.layoutOrderList);
                return false;
            } else {
                return true;
            }
        } else if (binding.layoutZip.getVisibility() == View.VISIBLE) {
            hideViewExcept(binding.layoutLocation);
            return false;
        } else if (binding.layoutBanks.getVisibility() == View.VISIBLE) {
            hideViewExcept(binding.layoutZip);
            return false;
        } else if (binding.layoutCreateHold.getVisibility() == View.VISIBLE) {
            if (TextUtils.isEmpty(binding.buyDashZip.getText().toString())) {
                if (binding.spBanks.getAdapter() != null) {
                    hideViewExcept(binding.layoutBanks);
                } else {
                    hideViewExcept(binding.layoutLocation);
                }
            } else {
                hideViewExcept(binding.layoutZip);
            }
            return false;
        } else if (binding.rvOffers.getVisibility() == View.VISIBLE) {
            hideViewExcept(binding.layoutCreateHold);
            return false;
        } else if (binding.linearEmail.getVisibility() == View.VISIBLE) {
            hideViewExcept(binding.rvOffers);
            return false;
        } else if (binding.linearPhone.getVisibility() == View.VISIBLE) {
            hideViewExcept(binding.linearEmail);
            return false;
        } else if (binding.linearPassword.getVisibility() == View.VISIBLE) {
            if (TextUtils.isEmpty(binding.editBuyDashPhone.getText().toString())) {
                return true;
            } else {
                hideViewExcept(binding.linearPhone);
                return false;
            }
        } else if (binding.layoutVerifyOtp.getVisibility() == View.VISIBLE) {
            return true;
        } else if (binding.scrollCompletionDetail.getVisibility() == View.VISIBLE) {
            return true;
        } else {
            return true;
        }
    }
}
