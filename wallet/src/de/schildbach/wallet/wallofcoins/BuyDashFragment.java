package de.schildbach.wallet.wallofcoins;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.provider.Settings.Secure;
import android.support.annotation.NonNull;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import org.bitcoinj.core.Address;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
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
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.ExchangeRate;
import de.schildbach.wallet.data.ExchangeRatesProvider;
import de.schildbach.wallet.ui.AbstractBindServiceActivity;
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
import de.schildbach.wallet.wallofcoins.response.GetHoldsResp;
import de.schildbach.wallet.wallofcoins.response.GetOffersResp;
import de.schildbach.wallet.wallofcoins.response.GetReceivingOptionsResp;
import de.schildbach.wallet.wallofcoins.response.OrderListResp;
import de.schildbach.wallet_test.R;
import de.schildbach.wallet_test.databinding.BuyDashFragmentBinding;
import de.schildbach.wallet_test.databinding.ItemOrderListBinding;
import okhttp3.Interceptor;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import static org.bitcoinj.wallet.KeyChain.KeyPurpose.RECEIVE_FUNDS;

public final class BuyDashFragment extends Fragment implements OnSharedPreferenceChangeListener {

    private final String TAG = BuyDashFragment.class.getSimpleName();
    private final int ID_BALANCE_LOADER = 0;
    private final int ID_RATE_LOADER = 1;
    private final int ID_BLOCKCHAIN_STATE_LOADER = 2;
    private final int ID_ADDRESS_LOADER = 4;
    private final int PERMISSIONS_REQUEST_LOCATION = 8989;
    private AbstractBindServiceActivity activity;
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private LoaderManager loaderManager;
    private CurrencyCalculatorLink amountCalculatorLink;
    private BuyDashPref buyDashPref;
    private AddressAndLabel currentAddressQrAddress = null;
    private String keyAddress;
    private CreateHoldResp createHoldResp;
    private CreateDeviceResp createDeviceResp;
    private String offerId;
    private List<GetReceivingOptionsResp> receivingOptionsResps;
    private String email = "";
    private String dashAmount = "";
    private String bankId = "";
    private String zipCode;
    private double latitude;
    private double longitude;
    private String password = "";
    private String phone_no = "";
    private String country_code = "";
    // DataBinder for BuyDashFragment
    @Nullable
    private BuyDashFragmentBinding binding;
    private CountryData countryData;
    private int countdownInterval = 1000;

    /**
     * Callback Manager for Load Exchange rate from Exchange rate Provider
     */
    private final LoaderManager.LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            return new ExchangeRateLoader(activity, config);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> loader, final Cursor data) {
            if (data != null && data.getCount() > 0) {
                data.moveToFirst();
                final ExchangeRate exchangeRate = ExchangeRatesProvider.getExchangeRate(data);
                amountCalculatorLink.setExchangeRate(exchangeRate.rate);
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> loader) {
        }
    };

    /**
     * Call Manager for load crypto address
     */
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

    /**
     * AsyncTask Loader for CurrentAddress
     */
    public class CurrentAddressLoader extends AsyncTaskLoader<Address> {
        private LocalBroadcastManager broadcastManager;
        private Wallet wallet;
        private Configuration config;

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
            broadcastManager = null;
            wallet.removeChangeEventListener(walletChangeListener);
            wallet.removeCoinsSentEventListener(walletChangeListener);
            wallet.removeCoinsReceivedEventListener(walletChangeListener);
            walletChangeListener.removeCallbacks();
            walletChangeListener = null;

            super.onStopLoading();
        }

        @Override
        protected void onReset() {
            config.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
            broadcastManager.unregisterReceiver(walletChangeReceiver);
            broadcastManager = null;
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

        private  ThrottlingWalletChangeListener walletChangeListener = new ThrottlingWalletChangeListener() {
            @Override
            public void onThrottledWalletChanged() {
                safeForceLoad();
            }
        };

        private  BroadcastReceiver walletChangeReceiver = new BroadcastReceiver() {
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
                x.printStackTrace();
            }
        }
    }

    /**
     *  API Header parameter interceptor
     */
    private Interceptor interceptor = new Interceptor() {
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            Request original = chain.request();
            // Request customization: add request headers
            Request.Builder requestBuilder = original.newBuilder();
            if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                requestBuilder.addHeader(WOCConstants.KEY_HEADER_AUTH_TOKEN, buyDashPref.getAuthToken());
            }
            requestBuilder.addHeader(WOCConstants.KEY_HEADER_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
            requestBuilder.addHeader(WOCConstants.KEY_HEADER_CONTENT_TYPE,WOCConstants.KEY_HEADER_CONTENT_TYPE_VALUE);
            Request request = requestBuilder.build();
            return chain.proceed(request);
        }
    };

   @Override
    public void onAttach(final Context context) {
        super.onAttach(context);

        this.activity = (AbstractBindServiceActivity) context;
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

    @SuppressLint("HardwareIds")
    private String getDeviceCode(Context context) {

        String deviceUID = buyDashPref.getDeviceCode();
        if (TextUtils.isEmpty(deviceUID)) {
            String deviceID;
            deviceID = Secure.getString(context.getContentResolver(), Secure.ANDROID_ID);
            byte[] data = (deviceID + deviceID + deviceID).getBytes(Charsets.UTF_8);
            deviceUID = Base64.encodeToString(data, Base64.DEFAULT).substring(0, 39);
            buyDashPref.setDeviceCode(deviceUID);
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
                        Toast.makeText(activity, R.string.alert_location_permission_denied, Toast.LENGTH_LONG).show();
                        //hideViewExcept(binding.layoutZip);

                        return;
                    }else if(grantResult ==  PackageManager.PERMISSION_GRANTED){
                        requestLocation();
                        return;
                    }
                }

            }
        }
    }

    /**
     * Get Last known best accurate location from available providers
     * @return location object
     */
    private Location getLastKnownLocation() {
        boolean gps_enabled = false;
        boolean network_enabled = false;
        LocationManager lm = (LocationManager) activity
                .getSystemService(Context.LOCATION_SERVICE);
        gps_enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        network_enabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        Location net_loc = null, gps_loc = null, finalLoc = null;
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (gps_enabled)
                gps_loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (network_enabled)
                net_loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

        } else {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    PERMISSIONS_REQUEST_LOCATION);
        }

        if (gps_loc != null && net_loc != null) {
            if (gps_loc.getAccuracy() > net_loc.getAccuracy())
                finalLoc = net_loc;
            else
                finalLoc = gps_loc;
        } else {

            if (gps_loc != null) {
                finalLoc = gps_loc;
            } else if (net_loc != null) {
                finalLoc = net_loc;
            }
        }
        return finalLoc;
    }

    /**
     *  Request location for find near by location
     */
    private void requestLocation() {

        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            getZip();
        } else {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION
                            , Manifest.permission.ACCESS_COARSE_LOCATION},
                    PERMISSIONS_REQUEST_LOCATION);
        }
    }

    /**
     *  API call for get all receiving options by country code
     */
    private void getReceivingOptions() {
        String locale;
        locale = getResources().getConfiguration().locale.getCountry();
        assert binding != null;
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

    /**
     *  Set Payment option name for Payment options
     * @param receivingOptionsResps
     */
    private void setPaymentOptNames(final List<GetReceivingOptionsResp> receivingOptionsResps) {
        final ArrayList<String> names = new ArrayList<String>();
        GetReceivingOptionsResp optionsRespDefaultName = new GetReceivingOptionsResp();
        optionsRespDefaultName.name = getString(R.string.label_select_payment_center);
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

    /**
     *  Validate Email id
     * @param target Email
     * @return boolean for email valid or not
     */
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

        //Method call for Request Location for get zipCode on Find Location button click
        binding.buttonBuyDashGetLocation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestLocation();
            }
        });

        //Method call for view zipcode layout on "No Thanks" button click
        binding.buttonBuyDashGetLocationNo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideViewExcept(binding.layoutZip);
            }
        });

        //Method call for find Payment receiving center
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

        binding.btnOrderHistoryWoc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
             getOrderList(false);
            }
        });

        // Method call for redirect on web portal for already capture hold
        binding.btnSignIn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goToUrl("https://wallofcoins.com/signin/"+country_code.replace("+","")+"-"+phone_no+"/");
            }
        });

        // Method call for Sing Out form WOC login
        binding.btnSignOutWoc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteAuthCall(false);
            }
        });

        //Method call for Buy Dash Bank button
        binding.buttonBuyDashBankNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (binding.spBanks.getSelectedItemPosition() == 0) {
                    Toast.makeText(activity, R.string.alert_select_any_payment_center, Toast.LENGTH_LONG).show();
                } else {
                    zipCode = null;
                    callDiscoveryInputs();
                }
            }
        });

        //Method call for view phone number
        binding.btnNextEmail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!binding.editBuyDashEmail.getText().toString().isEmpty() && isValidEmail(binding.editBuyDashEmail.getText().toString())) {
                    email = binding.editBuyDashEmail.getText().toString();
                    hideViewExcept(binding.linearPhone);
                } else {
                    Toast.makeText(activity, R.string.alert_enter_valid_email, Toast.LENGTH_LONG).show();
                }
            }
        });


        binding.tvSkipEmail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideViewExcept(binding.linearPhone);
            }
        });

        //Method call for CheckAuth
        binding.btnNextPhone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                country_code = countryData.countries.get(binding.spCountry.getSelectedItemPosition()).code;
                phone_no = binding.editBuyDashPhone.getText().toString().trim();
                String phone = country_code + binding.editBuyDashPhone.getText().toString().trim();
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

        if (config.getFormat().code().equals(WOCConstants.KEY_DASH)) {
            binding.btnBuyMore.setText(R.string.label_buy_more_dash);
        } else {
            binding.btnBuyMore.setText(R.string.label_buy_more_bitcoin);
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

        // Method call for discoveryInputs API call
        binding.buttonBuyDashGetOffers.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyBoard();
                if (Float.valueOf(binding.requestCoinsAmountLocal.getTextView().getHint().toString()) > 0f
                        || !TextUtils.isEmpty(binding.requestCoinsAmountLocal.getTextView().getText())) {
                    if (!TextUtils.isEmpty(binding.requestCoinsAmountLocal.getTextView().getText().toString())
                            && Float.valueOf(binding.requestCoinsAmountLocal.getTextView().getText().toString()) >= 5f
                            || !TextUtils.isEmpty(binding.requestCoinsAmountLocal.getTextView().getHint().toString())
                            && Float.valueOf(binding.requestCoinsAmountLocal.getTextView().getHint().toString()) >= 5f) {
                        callDiscoveryInputs();
                    } else {
                        Toast.makeText(activity, R.string.alert_puchase_amout, Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(getContext(), R.string.alert_amount, Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Add on click listener on edit text otp for call verifyOTP
        binding.buttonVerifyOtp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                verifyOTP();
            }
        });

        // Add on text change listener on edit text otp for call verifyOTP
        binding.etOtp.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == 5) {
                    verifyOTP();
                }
            }
        });

        return binding.getRoot();
    }

    /**
     * API call for call for Capture Hold @POST("api/v1/holds/{id}/capture/")
     */
    private void verifyOTP(){

        hideKeyBoard();
        HashMap<String, String> captureHoldReq = new HashMap<String, String>();
        String otp = binding.etOtp.getText().toString().trim();

        if (TextUtils.isEmpty(otp)) {
            Toast.makeText(getContext(), R.string.alert_purchase_code, Toast.LENGTH_LONG).show();
            return;
        }

        captureHoldReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
        captureHoldReq.put(WOCConstants.KEY_VERIFICATION_CODE, otp);
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
                                getOrderList(true);
                            } else {
                                getOrderList(true);
                            }

                            hideViewExcept(binding.scrollCompletionDetail);

                        } else if (null != response && null != response.errorBody()) {
                            binding.linearProgress.setVisibility(View.GONE);

                            if (response.code() == 404) {
                                AlertDialog.Builder builder;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    builder = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert);
                                } else {
                                    builder = new AlertDialog.Builder(activity);
                                }
                                builder.setTitle(getString(R.string.alert_title_purchase_code))
                                        .setMessage(getString(R.string.alert_description_purchase_code))
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

    /**
     * Count down timer for Hold Expire status
     * @param dueDateTime
     * @param textDepositeDue
     */
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

    /**
     * Get ZipCode for device current location
     */
    private void getZip() {

        Location myLocation = getLastKnownLocation();
        if (myLocation != null) {

            Geocoder geocoder;
            List<android.location.Address> addresses;
            geocoder = new Geocoder(activity, Locale.getDefault());
            if (geocoder != null) {
                try {
                    addresses = geocoder.getFromLocation(myLocation.getLatitude(), myLocation.getLongitude(), 1); // Here 1 represent max location result to returned, by documents it recommended 1 to 5
                    latitude = myLocation.getLatitude();
                    longitude = myLocation.getLongitude();
                    zipCode = addresses.get(0).getPostalCode();
                    hideViewExcept(binding.layoutCreateHold);
                    showKeyBoard();

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } else {
            LocationManager mlocManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
            boolean enabled = mlocManager.isProviderEnabled(LocationManager.GPS_PROVIDER);

            if (!enabled) {
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
        builder.setTitle(getString(R.string.enable_gps));
        builder.setMessage(getString(R.string.enable_gps_location));
        builder.setPositiveButton(getString(R.string.enable_label), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                startActivity(
                        new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            }
        });
        builder.setNegativeButton(getString(R.string.ignore_label), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        AlertDialog alert = builder.create();
        alert.show();
    }

    /**
     * Method call for Cancel order with status code "WD"
     * @param orderId
     */
     private void cancelOrder(String orderId) {
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, activity).cancelOrder(orderId, getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                binding.linearProgress.setVisibility(View.GONE);
                if (response.code() == 204) {
                    Toast.makeText(getContext(), R.string.alert_cancel_order, Toast.LENGTH_SHORT).show();
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

    /**
     * Method call for confirm order deposit amount
     * @param response
     */
    private void confirmDeposit(CaptureHoldResp response) {
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).confirmDeposit("" + response.id, "", getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<ConfirmDepositResp>() {
            @Override
            public void onResponse(Call<ConfirmDepositResp> call, Response<ConfirmDepositResp> response) {
                binding.linearProgress.setVisibility(View.GONE);

                if (null != response && null != response.body()) {
                    binding.scrollCompletionDetail.setVisibility(View.GONE);
                    Toast.makeText(activity, R.string.alert_payment_done, Toast.LENGTH_LONG).show();
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
            }

            @Override
            public void focusChanged(final boolean hasFocus) {
            }
        });
        loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);
    }

    @Override
    public void onPause() {
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
        }
    }

    /**
     * Method for call discovery inputs & offers for discovery input
     */
    private void callDiscoveryInputs() {
        HashMap<String, String> discoveryInputsReq = new HashMap<String, String>();
        discoveryInputsReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
        keyAddress = wallet.freshAddress(RECEIVE_FUNDS).toBase58();
        discoveryInputsReq.put(WOCConstants.KEY_CRYPTO_ADDRESS, keyAddress);
        String offerAmount = "0";
        try {
            if (Float.valueOf(binding.requestCoinsAmountLocal.getTextView().getHint().toString()) > 0f) {
                discoveryInputsReq.put(WOCConstants.KEY_USD_AMOUNT, "" + binding.requestCoinsAmountLocal.getTextView().getHint());
                offerAmount = "" + binding.requestCoinsAmountLocal.getTextView().getHint();
            } else {
                discoveryInputsReq.put(WOCConstants.KEY_USD_AMOUNT, "" + binding.requestCoinsAmountLocal.getTextView().getText());
                offerAmount = "" + binding.requestCoinsAmountLocal.getTextView().getText();
            }
            Log.d(TAG, "callDiscoveryInputs: usdAmount==>>" + binding.requestCoinsAmountLocal.getTextView().getHint());
        } catch (Exception e) {
            discoveryInputsReq.put(WOCConstants.KEY_USD_AMOUNT, "0");
            e.printStackTrace();
        }
        discoveryInputsReq.put(WOCConstants.KEY_COUNTRY,getCountryCode().toLowerCase());
        discoveryInputsReq.put(WOCConstants.KEY_CRYPTO, config.getFormat().code());
        discoveryInputsReq.put(WOCConstants.KEY_BANK, bankId);
        discoveryInputsReq.put(WOCConstants.KEY_ZIP_CODE, zipCode);

        JsonObject jObj = new JsonObject();
        jObj.addProperty(WOCConstants.KEY_LATITUDE,latitude+"");
        jObj.addProperty(WOCConstants.KEY_LONGITUDE,longitude+"");

        discoveryInputsReq.put(WOCConstants.KEY_BROWSE_LOCATION,jObj.toString());
        discoveryInputsReq.put(WOCConstants.KEY_CRYPTO_AMOUNT,"0");
        binding.linearProgress.setVisibility(View.VISIBLE);

        final String finalOfferAmount = offerAmount;
        WallofCoins.createService(interceptor, getActivity())
                .discoveryInputs(discoveryInputsReq)
                .enqueue(new Callback<DiscoveryInputsResp>() {
            @Override
            public void onResponse(Call<DiscoveryInputsResp> call, Response<DiscoveryInputsResp> response) {

                if (null != response && null != response.body()) {
                    if (null != response.body().id) {
                        updateAddressBookValue(keyAddress,WOCConstants.WOC_ADDRESS);// Update Address Book for Order

                        WallofCoins.createService(null, getActivity()).getOffers(response.body().id, getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<GetOffersResp>() {
                            @Override
                            public void onResponse(Call<GetOffersResp> call, final Response<GetOffersResp> response) {

                                if (null != response && null != response.body()) {

                                    binding.linearProgress.setVisibility(View.GONE);

                                    if (null != response.body().singleDeposit && !response.body().singleDeposit.isEmpty()) {
                                        hideViewExcept(binding.rvOffers);
                                        binding.spBanks.setAdapter(null);

                                        BuyDashOffersAdapter buyDashOffersAdapter = new BuyDashOffersAdapter(activity, response.body(), finalOfferAmount, new AdapterView.OnItemSelectedListener() {
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
                                                    createHold();
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
                                        Toast.makeText(getContext(), R.string.alert_no_offers, Toast.LENGTH_LONG).show();
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

    /**
     * get country code form current latitude & longitude
     * @return Country Code
     */
    private String getCountryCode(){
        String countryCode = "";
        try {
            Geocoder geo = new Geocoder(activity.getApplicationContext(), Locale.getDefault());
            List<android.location.Address> addresses = geo.getFromLocation(latitude, longitude, 1);
            android.location.Address obj = addresses.get(0);
            countryCode = obj.getCountryCode();
        }
        catch(Exception e){
            e.printStackTrace();
        }

        if(countryCode.equals("")){
            return  "us";
        }else {
            return countryCode;
        }
    }

    /**
     * Authorized user using password or device code
     * @param password
     */
    private void getAuthTokenCall(final String password) {
        String phone = buyDashPref.getPhone();

        if (!TextUtils.isEmpty(phone)) {

            HashMap<String, String> getAuthTokenReq = new HashMap<String, String>();
            if (!TextUtils.isEmpty(password)) {
                getAuthTokenReq.put(WOCConstants.KEY_PASSWORD, password);
            } else {
                getAuthTokenReq.put(WOCConstants.KEY_DEVICECODE, getDeviceCode(activity));
            }

            if(!TextUtils.isEmpty(buyDashPref.getDeviceId())){
                getAuthTokenReq.put(WOCConstants.KEY_DEVICEID, buyDashPref.getDeviceId());
            }

            getAuthTokenReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));

            binding.linearProgress.setVisibility(View.VISIBLE);
            WallofCoins.createService(interceptor, getActivity()).getAuthToken(phone, getAuthTokenReq).enqueue(new Callback<GetAuthTokenResp>() {
                @Override
                public void onResponse(Call<GetAuthTokenResp> call, Response<GetAuthTokenResp> response) {
                    binding.linearProgress.setVisibility(View.GONE);
                    int code = response.code();

                    if (code >= 400 && response.body() == null) {
                        try {
                                BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                              if(!TextUtils.isEmpty(password)) {
                                  showAlertPasswordDialog();
                              }else{
                                  createDevice();
                              }
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
                    if (!TextUtils.isEmpty(password) && TextUtils.isEmpty(buyDashPref.getDeviceId())) {
                        getDevice();
                    } else {
                        createHold();
                    }
                }

                @Override
                public void onFailure(Call<GetAuthTokenResp> call, Throwable t) {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                    binding.linearProgress.setVisibility(View.GONE);
                }
            });

        } else {
            Toast.makeText(getContext(), R.string.alert_phone_password_required, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Show alert dialog  wrong username or password
     */
    private void  showAlertPasswordDialog(){
        AlertDialog.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert);
        } else {
            builder = new AlertDialog.Builder(activity);
        }
        builder.setTitle("")
                .setMessage(getString(R.string.user_pass_wrong))
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .show();
    }

    /**
     * User authentication custom dialog for authenticate user using password
     */
    private void showUserPasswordAuthenticationDialog() {

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(activity);
        LayoutInflater inflater = activity.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.authenticate_password_wallet_dialog, null);
        dialogBuilder.setView(dialogView);

        final EditText edtPassword = (EditText) dialogView.findViewById(R.id.edt_woc_authenticaion_password);
        TextView txtTitle = (TextView) dialogView.findViewById(R.id.txt_existing_user_dialog_message);
        Button btnLogin = (Button)dialogView.findViewById(R.id.btnLogin);
        Button btnForgotPassword = (Button)dialogView.findViewById(R.id.btnForgotPassword);

        txtTitle.setMovementMethod(LinkMovementMethod.getInstance());

        final AlertDialog alertDialog = dialogBuilder.create();
        alertDialog.show();

        ImageView imgClose = (ImageView) dialogView.findViewById(R.id.imgClose);

        imgClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                alertDialog.dismiss();
            }
        });

        btnForgotPassword.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goToUrl(WOCConstants.KEY_FORGOT_PASSWORD_URL);
            }
        });

        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                password = edtPassword.getText().toString().trim();
                if(password.length()>0){
                    getAuthTokenCall(password);
                    alertDialog.dismiss();
                }else{
                    Toast.makeText(getContext(), R.string.password_alert, Toast.LENGTH_SHORT).show();
                }
            }
        });

    }

    /**
     * Method for Open web url link in external browser app
     * @param url
     */
    private void goToUrl (String url) {
        Uri uriUrl = Uri.parse(url);
        Intent launchBrowser = new Intent(Intent.ACTION_VIEW, uriUrl);
        startActivity(launchBrowser);
    }

    /**
     *  Get Devices for Register user with password
     */
    private void getDevice(){

        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).getDevice().enqueue(new Callback<List<CreateDeviceResp>>() {
            @Override
            public void onResponse(Call<List<CreateDeviceResp>> call, Response<List<CreateDeviceResp>> response) {
                if (response.code() == 200 && response.body() != null) {
                    List<CreateDeviceResp> deviceList = response.body();
                    if (deviceList.size() > 0){
                        buyDashPref.setDeviceId(deviceList.get(deviceList.size()-1).getId()+"");
                        getAuthTokenCall("");
                    } else {
                        createDevice();
                    }
                }
            }
            @Override
            public void onFailure(Call<List<CreateDeviceResp>> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Log.e(TAG, "onFailure: ", t);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });

    }

    /**
     * Method for register new device
     */
    private void createDevice() {
        final HashMap<String, String> createDeviceReq = new HashMap<String, String>();
        createDeviceReq.put(WOCConstants.KEY_DEVICE_NAME, "Dash Wallet (Android)");
        createDeviceReq.put(WOCConstants.KEY_DEVICE_CODE, getDeviceCode(getContext()));
        createDeviceReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).createDevice(createDeviceReq).enqueue(new Callback<CreateDeviceResp>() {
            @Override
            public void onResponse(Call<CreateDeviceResp> call, Response<CreateDeviceResp> response) {
                if (null != response.body() && response.code() < 299) {
                    createDeviceResp = response.body();
                    buyDashPref.setDeviceId(createDeviceResp.getId()+"");
                    getAuthTokenCall("");
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

    /**
     * Method for Update Address book of Order Transaction
     * @param KEY_ADDRESS
     * @param newLabel
     */
    public void updateAddressBookValue(String KEY_ADDRESS, String newLabel){
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

    /**
     * Method for create new hold
     */
    public void createHold() {
        String phone = buyDashPref.getPhone();

        final HashMap<String, String> createHoldPassReq = new HashMap<String, String>();

        if(TextUtils.isEmpty(buyDashPref.getAuthToken())) {
            createHoldPassReq.put(WOCConstants.KEY_PHONE, phone);
            createHoldPassReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
            createHoldPassReq.put(WOCConstants.KEY_EMAIL, email);
            createHoldPassReq.put(WOCConstants.KEY_deviceName, WOCConstants.KEY_DEVICE_NAME_VALUE);
            createHoldPassReq.put(WOCConstants.KEY_DEVICECODE, getDeviceCode(getContext()));
        }
        createHoldPassReq.put(WOCConstants.KEY_OFFER, offerId);

        binding.linearProgress.setVisibility(View.VISIBLE);

        WallofCoins.createService(interceptor, getActivity()).createHold(createHoldPassReq).enqueue(new Callback<CreateHoldResp>() {
            @Override
            public void onResponse(Call<CreateHoldResp> call, Response<CreateHoldResp> response) {
                binding.linearProgress.setVisibility(View.GONE);

                if (null != response.body() && response.code() < 299) {

                    createHoldResp = response.body();
                    buyDashPref.setHoldId(createHoldResp.id);
                    buyDashPref.setCreateHoldResp(createHoldResp);
                    if (TextUtils.isEmpty(buyDashPref.getDeviceId())
                            && !TextUtils.isEmpty(createHoldResp.deviceId)) {
                        buyDashPref.setDeviceId(createHoldResp.deviceId);
                    }
                    if (!TextUtils.isEmpty(response.body().token)) {
                        buyDashPref.setAuthToken(createHoldResp.token);
                    }
                    hideViewExcept(binding.layoutVerifyOtp);
                    clearForm((ViewGroup) binding.getRoot());
                    binding.etOtp.setText(createHoldResp.__PURCHASE_CODE);

                } else if (null != response.errorBody()) {
                    if (response.code() == 403 && TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                        hideViewExcept(binding.layoutHold);
                        clearForm((ViewGroup) binding.getRoot());
                    } else if (response.code() == 403 && !TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                        getHolds();
                    } else if (response.code() == 400) {
                        if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                            getOrderList(false);
                        } else {
                            hideViewExcept(binding.layoutHold);
                            clearForm((ViewGroup) binding.getRoot());
                        }
                    } else {
                        try {
                                if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                                    getOrderList(false);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                    }
                } else {
                    clearForm((ViewGroup) binding.getRoot());
                }
            }

            @Override
            public void onFailure(Call<CreateHoldResp> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
            }
        });

    }

    /**
     * Get all holds for delete active hold
     */
    private void getHolds() {
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).getHolds().enqueue(new Callback<List<GetHoldsResp>>() {
            @Override
            public void onResponse(Call<List<GetHoldsResp>> call, Response<List<GetHoldsResp>> response) {
                if (response.code() == 200 && response.body() != null) {
                    List<GetHoldsResp> holdsList = response.body();
                    int holdCount = 0;
                    if (holdsList.size()>0) {
                        for (int i = 0; i < holdsList.size(); i++) {
                            if (null!= holdsList.get(i).status && holdsList.get(i).status.equals("AC")) {
                                deleteHold(holdsList.get(i).id);
                                holdCount++;
                            }
                        }
                        if (holdCount == 0) {
                            getOrderList(false);
                        }
                    } else {
                        getOrderList(false);
                    }
                }
            }
            @Override
            public void onFailure(Call<List<GetHoldsResp>> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Log.e(TAG, "onFailure: ", t);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     *
     *
     * Method call for delete for provide holdId
     * @param holdId
     */
    private void deleteHold(String holdId) {
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).deleteHold(holdId).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                createHold();
             }
            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Log.e(TAG, "onFailure: ", t);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Method for singout user
     * @param isPendingHold
     */
    public void deleteAuthCall(final boolean isPendingHold) {
        final String phone = buyDashPref.getPhone();
            if (!TextUtils.isEmpty(phone)) {
            binding.linearProgress.setVisibility(View.VISIBLE);
            password = "";
            WallofCoins.createService(interceptor, activity)
                    .deleteAuth(phone, getString(R.string.WALLOFCOINS_PUBLISHER_ID))
                    .enqueue(new Callback<CheckAuthResp>() {
                @Override
                public void onResponse(Call<CheckAuthResp> call, Response<CheckAuthResp> response) {
                    Log.d(TAG, "onResponse: response code==>>" + response.code());
                    binding.linearProgress.setVisibility(View.GONE);
                    if (response.code() < 299) {
                        buyDashPref.setAuthToken("");
                        password="";
                        buyDashPref.clearAllPrefrance();
                        if (isPendingHold) {
                            binding.editBuyDashPhone.setText(null);
                            checkAuth();
                        } else {
                            Toast.makeText(activity, R.string.alert_sign_out, Toast.LENGTH_LONG).show();
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
            Toast.makeText(getContext(), R.string.alert_phone, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Get order list using auth token
     * @param isFromCreateHold
     */
    private void getOrderList(final boolean isFromCreateHold) {

        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, activity)
                .getOrders(getString(R.string.WALLOFCOINS_PUBLISHER_ID))
                .enqueue(new Callback<List<OrderListResp>>() {
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
                            if (orderList.size()>0) {
                                manageOrderList(orderList, isFromCreateHold);
                            } else {
                                manageOrderList(response.body(), isFromCreateHold);
                            }
                        } else {
                            manageOrderList(response.body(),false);
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

    /**
     * Method for manage order list
     * @param orderList
     * @param isFromCreateHold
     */
    private void manageOrderList(final List<OrderListResp> orderList,boolean isFromCreateHold) {

        if (orderList.size() > 0) {
            hideViewExcept(binding.layoutOrderList);
            for (OrderListResp orderListResp : orderList) {
                if (orderListResp.status.equals("WD")) {
                    binding.btnBuyMore.setVisibility(View.GONE);
                    break;
                } else {
                    binding.btnBuyMore.setVisibility(View.VISIBLE);
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
                if (orderList.get(i).status.equals("WD")) {
                    lastWDV = i;
                }
            }

            OrderListResp orderListResp1 = new OrderListResp();
            orderListResp1.id = -1;
            orderList.add(lastWDV + 1, orderListResp1);

            OrderListResp orderListResp = new OrderListResp();
            orderListResp.id = -2;
            orderList.add(lastWDV+ 2, orderListResp);

            if (orderList.size()-2 == 1 && orderList.get(0).status != null && orderList.get(0).status.equals("WD")) {
                isFromCreateHold = true;
            }

            if (!isFromCreateHold ) {
                OrderListResp orderListResp2 = new OrderListResp();
                orderListResp2.id = -3;
                orderList.add(lastWDV + 3, orderListResp2);
            }


            if (orderList.size()== 1 && orderList.get(0).status.equals("WD")) {
                binding.textEmailReceipt.setVisibility(View.GONE);
            } else {

                binding.textEmailReceipt.setVisibility(View.VISIBLE);
                binding.textEmailReceipt.setText(Html.fromHtml(getString(R.string.text_send_email_receipt)));

                binding.textEmailReceipt.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent emailIntent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts(
                                "mailto", WOCConstants.SUPPORT_EMAIL, null));
                        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{WOCConstants.SUPPORT_EMAIL});
                        if(orderList.size()>0)
                            emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Order #{" + orderList.get(0).id + "} - {" + buyDashPref.getPhone() + "}.");
                        emailIntent.putExtra(Intent.EXTRA_TEXT, "");
                        startActivity(Intent.createChooser(emailIntent, WOCConstants.SEND_EMAIL));
                    }
                });
            }

            LinearLayoutManager linearLayoutManager = new LinearLayoutManager(activity);
            binding.rvOrderList.setLayoutManager(linearLayoutManager);
            binding.rvOrderList.setAdapter(new OrderListAdapter(activity, orderList));
        } else {
            hideViewExcept(binding.layoutLocation);
        }
    }

    /**
     * Method for check authentication type
     */
    private void checkAuth() {
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
                            }else if ((response.body().getAvailableAuthSources().size() >= 2
                                    && response.body().getAvailableAuthSources().get(1).equals("device"))
                                    || (response.body().getAvailableAuthSources().get(0).equals("device"))) {
                                hideKeyBoard();
                                createHold();
                            }
                        }
                    } else if (response.code() == 404) {
                        hideKeyBoard();
                        createHold();
                    }
                }

                @Override
                public void onFailure(Call<CheckAuthResp> call, Throwable t) {
                    binding.linearProgress.setVisibility(View.GONE);
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Toast.makeText(getContext(), R.string.alert_phone, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Method for hide keyboard
     */
    private void hideKeyBoard() {
        View view = activity.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    /**
     * Method for show keyboard
     */
    private void showKeyBoard() {
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
        public VHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater layoutInflater = LayoutInflater.from(activity);
            ItemOrderListBinding itemBinding = DataBindingUtil.inflate(layoutInflater, R.layout.item_order_list, parent, false);
            return new VHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(VHolder holder, int position) {
            final OrderListResp orderListResp = orderList.get(position);
            if (orderListResp.id != -1 && orderListResp.id != -2 && orderListResp.id != -3) {

                holder.itemBinding.layLogout.setVisibility(View.GONE);
                holder.itemBinding.layHelpInstruction.setVisibility(View.GONE);
                holder.itemBinding.layOrderHistory.setVisibility(View.GONE);
                holder.itemBinding.layoutCompletionDetail.setVisibility(View.VISIBLE);
                holder.itemBinding.setItem(orderListResp);


                Type listType = new TypeToken<ArrayList<AccountJson>>() {
                }.getType();

                holder.itemBinding.linearAccountDetail.removeAllViews();
                try {
                    ArrayList<AccountJson> accountList = new Gson().fromJson(orderListResp.account, listType);

                    if(accountList!=null && orderListResp.status.equals("WD")) {
                        for (int i = 0; i < accountList.size(); i++) {
                            TextView textView = new TextView(getActivity());
                            textView.setTextSize(16);
                            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                            layoutParams.topMargin = 0;
                            textView.setLayoutParams(layoutParams);
                            textView.setText(accountList.get(i).getLabel() + ": " + accountList.get(i).getValue());
                            holder.itemBinding.linearAccountDetail.addView(textView);
                        }
                        holder.itemBinding.textAccountNo.setVisibility(View.GONE);
                        holder.itemBinding.textNameAccount.setVisibility(View.GONE);

                    }else{
                        holder.itemBinding.linearAccountDetail.setVisibility(View.GONE);
                    }
                } catch (JsonSyntaxException e) {
                    e.printStackTrace();
                }

                if(orderListResp.account == null
                        || orderListResp.account.equals("") ){
                    holder.itemBinding.textAccountNo.setVisibility(View.GONE);
                }
                if(orderListResp.nameOnAccount == null
                        || orderListResp.nameOnAccount.equals("") ){
                    holder.itemBinding.textNameAccount.setVisibility(View.GONE);
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


                if (orderListResp.nearestBranch != null) {
                    if ((orderListResp.nearestBranch.address == null
                            || orderListResp.nearestBranch.address.equals(""))
                            && orderListResp.status.equals("WD")
                            ) {
                        holder.itemBinding.buttonBuyDashItemLocation.setVisibility(View.VISIBLE);
                        holder.itemBinding.tvItrmOffer4.setVisibility(View.VISIBLE);
                    } else {
                        holder.itemBinding.buttonBuyDashItemLocation.setVisibility(View.GONE);
                        holder.itemBinding.tvItrmOffer4.setVisibility(View.GONE);
                    }
                }

                holder.itemBinding.buttonBuyDashItemLocation.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        goToUrl(orderListResp.bankUrl);
                    }
                });

//              you must deposit cash
                double dots = Double.parseDouble(orderListResp.total)* 1000000;
                DecimalFormat formatter = new DecimalFormat("#,###,###.##");
                String yourFormattedDots = formatter.format(dots);

                if (orderListResp.bankLogo != null
                        && !orderListResp.bankLogo.equals("")) {
                    Glide.with(activity)
                            .load(orderListResp.bankLogo)
                            .placeholder(R.drawable.ic_account_balance_black_24dp)
                            .error(R.drawable.ic_account_balance_black_24dp)
                            .into(holder.itemBinding.imageBank);
                } else {
                    holder.itemBinding.imageBank.setImageResource(R.drawable.ic_account_balance_black_24dp);
                }
                Log.e(TAG, "onBindViewHolder: " + orderListResp.status);

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
                            goToUrl(WOCConstants.KEY_WEB_URL);
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
                    holder.itemBinding.textAccountNo.setVisibility(View.GONE);
                    holder.itemBinding.textNameAccount.setVisibility(View.GONE);

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
                    holder.itemBinding.textTransactionStatus.setText("Status: Reserved for Deposit");
                }else  if(orderListResp.status.equals("RMIT")){
                    holder.itemBinding.textTransactionStatus.setText("Status: Remit Address Missing");
                }else  if(orderListResp.status.equals("UCRV")){
                    holder.itemBinding.textTransactionStatus.setText("Status: Under Review");
                }else  if(orderListResp.status.equals("PAYP")){
                    holder.itemBinding.textTransactionStatus.setText("Status: Done - Pending Delivery");
                }else  if(orderListResp.status.equals("SENT")){
                    holder.itemBinding.textTransactionStatus.setText("Status: Done - Units Delivered");
                }

            }
            else if (orderListResp.id == -3){

                holder.itemBinding.layoutCompletionDetail.setVisibility(View.GONE);
                holder.itemBinding.layHelpInstruction.setVisibility(View.GONE);
                holder.itemBinding.layLogout.setVisibility(View.GONE);
                holder.itemBinding.layOrderHistory.setVisibility(View.VISIBLE);

            }
            else if (orderListResp.id == -2){

                holder.itemBinding.layoutCompletionDetail.setVisibility(View.GONE);
                holder.itemBinding.layHelpInstruction.setVisibility(View.GONE);
                holder.itemBinding.layLogout.setVisibility(View.VISIBLE);
                holder.itemBinding.layOrderHistory.setVisibility(View.GONE);

                holder.itemBinding.textMessage.setText("Your wallet is signed into Wall of Coins using your mobile number " + buyDashPref.getPhone());

                holder.itemBinding.btnSignout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        deleteAuthCall(false);
                    }
                });


            } else if (orderListResp.id == -1){

                holder.itemBinding.layoutCompletionDetail.setVisibility(View.GONE);
                holder.itemBinding.layHelpInstruction.setVisibility(View.VISIBLE);
                holder.itemBinding.layLogout.setVisibility(View.GONE);
                holder.itemBinding.layOrderHistory.setVisibility(View.GONE);

                holder.itemBinding.textHelpMessage.setText(" Call (866) 841-2646 for help. \n Help is also available on the website.");
                holder.itemBinding.btnWebLink.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        goToUrl(WOCConstants.KEY_WEB_URL);
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

    /**
     * Method for clear all child for ViewGroup
     * @param root
     */
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

    /**
     * Method for manage BuyDashFragment sub view visibility
     * @param v
     */
    private void hideViewExcept(View v) {
        assert binding != null;
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
        binding.layoutHold.setVisibility(View.GONE);
        if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
            binding.layoutSignOut.setVisibility(View.VISIBLE);
            binding.textMessageSignOut.setText("Your wallet is signed into Wall of Coins using your mobile number " + buyDashPref.getPhone());
        } else {
            binding.layoutSignOut.setVisibility(View.GONE);
        }

        if (v != null) v.setVisibility(View.VISIBLE);
    }

    /**
     * Method for manage back button navigation for fragment
     * @return
     */
    public boolean hideViewManageBack() {

        hideKeyBoard();

        if (binding.layoutOrderList.getVisibility() == View.VISIBLE) {
            return true;
        } else if (binding.layoutLocation.getVisibility() == View.VISIBLE) {
            return true;
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
