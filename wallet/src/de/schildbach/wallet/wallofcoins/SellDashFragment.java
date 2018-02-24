package de.schildbach.wallet.wallofcoins;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.databinding.DataBindingUtil;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
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
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.wallofcoins.request.CreateAuthReq;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.ui.AbstractWalletActivity;
import de.schildbach.wallet.ui.WalletBalanceLoader;
import de.schildbach.wallet.ui.send.SendCoinsActivity;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.response.AdsListActivityResp;
import de.schildbach.wallet.wallofcoins.response.BuyDashErrorResp;
import de.schildbach.wallet.wallofcoins.response.CheckAuthResp;
import de.schildbach.wallet.wallofcoins.response.CountryData;
import de.schildbach.wallet.wallofcoins.response.CreateAdResp;
import de.schildbach.wallet.wallofcoins.response.CreateAuthErrorResp;
import de.schildbach.wallet.wallofcoins.response.CreateAuthResp;
import de.schildbach.wallet.wallofcoins.response.GetAuthTokenResp;
import de.schildbach.wallet.wallofcoins.response.GetCurrencyResp;
import de.schildbach.wallet.wallofcoins.response.GetPricingOptionsResp;
import de.schildbach.wallet.wallofcoins.response.GetReceivingOptionsResp;
import de.schildbach.wallet.wallofcoins.response.SendVerificationResp;
import de.schildbach.wallet.wallofcoins.response.VerifyAdResp;
import de.schildbach.wallet_test.R;
import de.schildbach.wallet_test.databinding.ItemAdsListingBinding;
import de.schildbach.wallet_test.databinding.SellDashFragmentBinding;
import okhttp3.Interceptor;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;


public final class SellDashFragment extends Fragment implements OnSharedPreferenceChangeListener {
    private static final String TAG = SellDashFragment.class.getSimpleName();
    private static final int ID_BALANCE_LOADER = 0;
    private static final int ID_RATE_LOADER = 1;
    private static final int ID_BLOCKCHAIN_STATE_LOADER = 2;
    private AbstractWalletActivity activity;
    private WalletApplication application;
    private Configuration config;
    private SellDashPref sellDashPref;
    private BuyDashPref buyDashPref;
    private Wallet wallet;
    private LoaderManager loaderManager;
    private Coin balance = null;
    private final LoaderManager.LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>() {
        @Override
        public Loader<Coin> onCreateLoader(final int id, final Bundle args) {
            new WalletBalanceLoader(activity, wallet);
            return null;
        }

        @Override
        public void onLoadFinished(final Loader<Coin> loader, final Coin balance) {
            SellDashFragment.this.balance = balance;
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
            return null;//new BlockchainStateLoader(activity);
        }

        @Override
        public void onLoadFinished(final Loader<BlockchainState> loader, final BlockchainState blockchainState) {
            SellDashFragment.this.blockchainState = blockchainState;

            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<BlockchainState> loader) {
        }
    };

    @Nullable
    private String defaultCurrency = null;
    private SellDashFragmentBinding binding;
    private List<GetReceivingOptionsResp> receivingOptionsResps;
    private GetReceivingOptionsResp receivingOptionsResp;
    private List<GetPricingOptionsResp> optionsRespList;
    private GetAuthTokenResp getAuthTokenResp;
    private SendVerificationResp sendVerificationResp;

    private Interceptor interceptor = new Interceptor() {
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            Request original = chain.request();

            // Request customization: add request headers
            Request.Builder requestBuilder = original.newBuilder()
                    .addHeader("X-Coins-Api-Token", sellDashPref.getAuthToken())
                    .addHeader("Content-Type", "multipart/form-data");

            Request request = requestBuilder.build();
            return chain.proceed(request);
        }
    };

    private CreateAdResp createAdResp;
    private VerifyAdResp verifyAdResp;
    private CreateAuthReq createAuthReq;
    private CountryData countryData;
    private List<AdsListActivityResp> adsListingResps;

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

        this.sellDashPref = new SellDashPref(PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext()));
        this.buyDashPref = new BuyDashPref(PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext()));

        setRetainInstance(true);
        setHasOptionsMenu(true);


        defaultCurrency = config.getExchangeCurrencyCode();
        config.registerOnSharedPreferenceChangeListener(this);
        sellDashPref.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {

        binding = DataBindingUtil.inflate(inflater, R.layout.sell_dash_fragment, container, false);

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
            binding.spCountry.setAdapter(countryAdapter);
        }

        if (null != sellDashPref.getAuthToken() && !TextUtils.isEmpty(sellDashPref.getAuthToken())) {

            callAdList();
            //getReceivingOptions();
            getCurrency();
        } else {
            binding.linearPhone.setVisibility(View.VISIBLE);
        }

        binding.btnNextPhone.setOnClickListener(new OnClickListener()

        {
            @Override
            public void onClick(View v) {
                hideKeyBoard();
                checkAuth();
            }
        });

//        binding.buttonSellDashCreateAuth.setOnClickListener(new OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                sendCreateAuth();
//            }
//        });


        binding.buttonSellDashCreateAdd.setOnClickListener(new

                                                                   OnClickListener() {
                                                                       @Override
                                                                       public void onClick(View v) {
                                                                           sendAdData();
                                                                       }
                                                                   });

        binding.buttonVerifyAd.setOnClickListener(new

                                                          OnClickListener() {
                                                              @Override
                                                              public void onClick(View v) {
                                                                  verifyAd();
                                                              }
                                                          });

        binding.buttonResendOtp.setOnClickListener(new

                                                           OnClickListener() {
                                                               @Override
                                                               public void onClick(View v) {
                                                                   setSendVerificationCall();
                                                               }
                                                           });

        binding.cbIsDynamicPricing.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener()

        {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    getDynamicPricingOpt();
                    binding.layStaticPrice.setVisibility(View.GONE);
                    binding.llSelectPricingOpt.setVisibility(View.VISIBLE);
                } else {
                    binding.llSelectPricingOpt.setVisibility(View.GONE);
                    binding.layStaticPrice.setVisibility(View.VISIBLE);
                }
            }
        });

        return binding.getRoot();
    }

    public void hideKeyBoard() {
        View view = activity.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }


    public void checkAuth() {
        String countryCode = countryData.countries.get(binding.spCountry.getSelectedItemPosition()).code;
        String phone = countryCode + binding.etPhone.getText().toString().trim();
        if (!TextUtils.isEmpty(phone)) {
            binding.sellDashProgress.setVisibility(View.VISIBLE);

            WallofCoins.createService(activity).checkAuth(phone, getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<CheckAuthResp>() {
                @Override
                public void onResponse(Call<CheckAuthResp> call, Response<CheckAuthResp> response) {
                    binding.sellDashProgress.setVisibility(View.GONE);
                    if (response.code() == 200) {
                        if (response.body() != null && response.body().getAvailableAuthSources() != null && response.body().getAvailableAuthSources().size() > 0) {
                            if (response.body().getAvailableAuthSources().get(0).equals("device")) {
                                hideKeyBoard();
                                getAuthTokenCall(false);
                                Log.d(TAG, "onResponse: device");
                            } else if (response.body().getAvailableAuthSources().get(0).equals("password")) {
                                binding.layoutCreateBankOpts.setVisibility(View.GONE);
                                binding.linearPhone.setVisibility(View.GONE);
                                binding.layoutVerifyOtp.setVisibility(View.GONE);
//                                binding.etEmail.setVisibility(View.GONE);
                                binding.linearPassword.setVisibility(View.VISIBLE);
                                binding.textPassAbove.setText("Existing Account Login");
                                binding.etPassword.setHint("Password");

                                binding.btnNextPassword.setOnClickListener(new OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        hideKeyBoard();
                                        getAuthTokenCall(false);
                                    }
                                });
                            }
                        }
                    } else if (response.code() == 404) {
                        binding.layoutCreateBankOpts.setVisibility(View.GONE);
                        binding.linearPhone.setVisibility(View.GONE);
                        binding.linearPassword.setVisibility(View.VISIBLE);
                        binding.layoutVerifyOtp.setVisibility(View.GONE);

                        binding.textPassAbove.setText("Register New Account");
                        binding.etPassword.setHint("Password");
                        binding.btnNextPassword.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                hideKeyBoard();
                                sendCreateAuth();
                            }
                        });
                    }
                }

                @Override
                public void onFailure(Call<CheckAuthResp> call, Throwable t) {
                    binding.sellDashProgress.setVisibility(View.GONE);
                    Toast.makeText(activity, R.string.try_again, Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Toast.makeText(activity, R.string.alert_phone, Toast.LENGTH_SHORT).show();
        }
    }


    private void verifyAd() {

        if (sendVerificationResp.__CASH_CODE.equals(binding.etOtp.getText().toString())) {

            HashMap<String, String> verifyAdReq = new HashMap<String, String>();

            verifyAdReq.put("ad_id", "" + createAdResp.id);
            verifyAdReq.put("code", sendVerificationResp.__CASH_CODE);
            verifyAdReq.put("phone", createAuthReq.phone);


            WallofCoins.createService(interceptor, getActivity()).verifyAd(verifyAdReq).enqueue(new Callback<VerifyAdResp>() {
                @Override
                public void onResponse(Call<VerifyAdResp> call, Response<VerifyAdResp> response) {
                    if (null != response && null != response.body()) {
                        verifyAdResp = response.body();

                        if (null != verifyAdResp.fundingAddress) {

                            ContentValues mNewValues = new ContentValues();
                            mNewValues.put(AddressBookProvider.KEY_ADDRESS, verifyAdResp.fundingAddress);
                            mNewValues.put(AddressBookProvider.KEY_LABEL, receivingOptionsResp.name);


                            final Address address = Address.fromBase58(Constants.NETWORK_PARAMETERS, verifyAdResp.fundingAddress);
                            final Uri uri = AddressBookProvider.contentUri(activity.getPackageName()).buildUpon().appendPath(address.toBase58()).build();

                            activity.getContentResolver().insert(
                                    uri,
                                    mNewValues
                            );

                            SendCoinsActivity.start(activity, PaymentIntent.fromAddress(Address.fromBase58(Constants.NETWORK_PARAMETERS, verifyAdResp.fundingAddress), receivingOptionsResp.name));

                            activity.finish();

                        } else {
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                        }
                    }
                }

                @Override
                public void onFailure(Call<VerifyAdResp> call, Throwable t) {
                    Log.e(TAG, "onFailure: ", t);
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                }
            });
        } else {
            Toast.makeText(getContext(), "OTP not match, Try again!", Toast.LENGTH_LONG).show();
        }
    }

    private void getDynamicPricingOpt() {
        WallofCoins.createService(getActivity()).getPricingOptions(CoinDefinition.cryptsyMarketCurrency, defaultCurrency).enqueue(new Callback<List<GetPricingOptionsResp>>() {
            @Override
            public void onResponse(Call<List<GetPricingOptionsResp>> call, Response<List<GetPricingOptionsResp>> response) {
                optionsRespList = response.body();
                List<String> primaryList = new ArrayList<String>();
                List<String> secondaryList = new ArrayList<String>();
                primaryList.add(0, "Select Primary market");
                secondaryList.add(0, "Select Secondary market");
                for (GetPricingOptionsResp getPricingOptionsResp : optionsRespList) {
                    primaryList.add(getPricingOptionsResp.label);
                    secondaryList.add(getPricingOptionsResp.label);
                }

                ArrayAdapter<String> primaryAdapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, primaryList);
                primaryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                binding.spPrimaryMarket.setAdapter(primaryAdapter);


                ArrayAdapter<String> secondaryAdapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, secondaryList);
                secondaryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                binding.spSecondaryMarket.setAdapter(secondaryAdapter);

            }

            @Override
            public void onFailure(Call<List<GetPricingOptionsResp>> call, Throwable t) {
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void sendAdData() {

        if (null == receivingOptionsResp) {
            Toast.makeText(activity, R.string.receivnig_option_empty, Toast.LENGTH_LONG).show();
            return;
        }


        int selCountry = binding.spCountry.getSelectedItemPosition();

        final HashMap<String, Object> createAdReq = new HashMap<String, Object>();
        createAdReq.put("phone", getAuthTokenResp.phone);
        createAdReq.put("email", getAuthTokenResp.email);
        createAdReq.put("phoneCode", countryData.countries.get(selCountry).code.replace("+", ""));
        createAdReq.put("bankBusiness", "" + receivingOptionsResp.id);
        createAdReq.put("sellCrypto", "DASH");
        createAdReq.put("userEnabled", "true");
        createAdReq.put("dynamicPrice", "" + binding.cbIsDynamicPricing.isChecked());
        createAdReq.put("usePayFields", "" + (receivingOptionsResp.payFields.payFieldsB == null));


        if (binding.cbIsDynamicPricing.isChecked()) {

            int selPrimary = binding.spPrimaryMarket.getSelectedItemPosition();
            int selSecondary = binding.spSecondaryMarket.getSelectedItemPosition();

            if (selPrimary == 0) {
                Toast.makeText(activity, R.string.primary_empty, Toast.LENGTH_SHORT).show();
                return;
            } else {
                createAdReq.put("primaryMarket", "" + optionsRespList.get(selPrimary - 1).id);
            }
            if (selSecondary == 0) {
                Toast.makeText(activity, R.string.secondary_empty, Toast.LENGTH_SHORT).show();
                return;
            } else {
                createAdReq.put("secondaryMarket", "" + optionsRespList.get(selSecondary - 1).id);
            }


            if (selPrimary == selSecondary) {
                Toast.makeText(activity, R.string.error_primary_secondry_same, Toast.LENGTH_SHORT).show();
                return;
            }


            createAdReq.put("minPayment", binding.etMinPayment.getText().toString());
            createAdReq.put("maxPayment", binding.etMaxPayment.getText().toString());
            createAdReq.put("sellerFee", binding.etSellerFee.getText().toString());
        } else if (binding.etStaticPrice.getText().toString().trim().isEmpty()) {
            Toast.makeText(activity, R.string.all_field_required, Toast.LENGTH_SHORT).show();
            return;
        }

        createAdReq.put("currentPrice", binding.etStaticPrice.getText().toString());


        if (receivingOptionsResp.payFields.payFieldsB == null) {
            int count = binding.llReceivingPricingOptions.getChildCount() - 1;
            Log.e(TAG, "sendAdData: " + count);
            for (int i = count; i >= 0; i--) {
                View view = binding.llReceivingPricingOptions.getChildAt(i);
                Object tag = view.getTag();
                if (tag != null && !tag.toString().contains("*")) {
                    EditText editText = (EditText) view;

                    if (editText.getText().toString().trim().isEmpty()) {
                        Toast.makeText(activity, R.string.all_field_required, Toast.LENGTH_LONG).show();
                        return;
                    }

                    EditText editTextC = (EditText) binding.llReceivingPricingOptions.findViewWithTag(tag + "*");
                    if (null != editTextC) {
                        if (!editText.getText().toString().equals(editTextC.getText().toString())) {
                            Toast.makeText(getContext(), "Confirm " + tag + " not match", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }

                    createAdReq.put("payfield_" + tag, editText.getText().toString());
                }
            }
        } else {

            String name = ((EditText) binding.llReceivingPricingOptions.findViewById(R.id.et_name_acct)).getText().toString();
            String number = ((EditText) binding.llReceivingPricingOptions.findViewById(R.id.et_acct)).getText().toString();
            String number2 = ((EditText) binding.llReceivingPricingOptions.findViewById(R.id.et_acct_confirm)).getText().toString();


            if (TextUtils.isEmpty(name.trim()) || TextUtils.isEmpty(number.trim()) || TextUtils.isEmpty(number2.trim())) {
                Toast.makeText(activity, R.string.all_field_required, Toast.LENGTH_LONG).show();
                return;
            }

            createAdReq.put("name", name);
            createAdReq.put("number", ((EditText) binding.llReceivingPricingOptions.findViewById(R.id.et_acct)).getText().toString());
            createAdReq.put("number2", ((EditText) binding.llReceivingPricingOptions.findViewById(R.id.et_acct_confirm)).getText().toString());
        }

//        HashMap<String, Object> map = new Gson().fromJson(asJsonObject, new TypeToken<HashMap<String, Object>>() {
//        }.getType());

        WallofCoins.createService(interceptor, getActivity()).createAd(createAdReq).enqueue(new Callback<CreateAdResp>() {
            @Override
            public void onResponse(Call<CreateAdResp> call, Response<CreateAdResp> response) {

                if (null != response && null != response.body()) {
                    createAdResp = response.body();

                    setSendVerificationCall();
                }

            }

            @Override
            public void onFailure(Call<CreateAdResp> call, Throwable t) {
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setSendVerificationCall() {
        HashMap<String, Object> reqMap = new HashMap<String, Object>();
        reqMap.put("ad_id", createAdResp.id);
        reqMap.put("phone", getAuthTokenResp.phone);

        binding.sellDashProgress.setVisibility(View.VISIBLE);

        WallofCoins.createService(interceptor, getActivity()).sendVerification(reqMap).enqueue(new Callback<SendVerificationResp>() {
            @Override
            public void onResponse(Call<SendVerificationResp> call, Response<SendVerificationResp> response) {

                binding.sellDashProgress.setVisibility(View.GONE);

                if (null != response && null != response.body() && null != response.body().__CASH_CODE) {
                    binding.layoutCreateBankOpts.setVisibility(View.GONE);
                    binding.llReceivingPricingOptions.setVisibility(View.GONE);
                    binding.layoutVerifyOtp.setVisibility(View.VISIBLE);


                    sendVerificationResp = response.body();

                    binding.etOtp.setText(sendVerificationResp.__CASH_CODE);
                }
            }

            @Override
            public void onFailure(Call<SendVerificationResp> call, Throwable t) {
                binding.sellDashProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                Log.e(TAG, "onFailure: ", t);
            }
        });
    }

    //send first request to get payment options
    private void sendCreateAuth() {

        if (
//                TextUtils.isEmpty(binding.etEmail.getText()) ||
                TextUtils.isEmpty(binding.etPhone.getText()) ||
                        TextUtils.isEmpty(binding.etPassword.getText())) {
            Toast.makeText(getContext(), "All Fields are required!", Toast.LENGTH_LONG).show();
            return;
        }

        int selCountry = binding.spCountry.getSelectedItemPosition();

        createAuthReq = new CreateAuthReq();
//        createAuthReq.email = binding.etEmail.getText().toString();
        createAuthReq.password = binding.etPassword.getText().toString();
        createAuthReq.phone = countryData.countries.get(selCountry).code + binding.etPhone.getText().toString();

        binding.sellDashProgress.setVisibility(View.VISIBLE);

        WallofCoins.createService(getActivity()).createAuth(createAuthReq).enqueue(new Callback<CreateAuthResp>() {
            @Override
            public void onResponse(Call<CreateAuthResp> call, Response<CreateAuthResp> response) {

                try {
                    if (response.code() >= 400) {

                        String errorMessage = response.errorBody().string();

                        if (errorMessage != null) {

                            CreateAuthErrorResp errorResp = new Gson().fromJson(errorMessage, CreateAuthErrorResp.class);
                            if (errorResp.phone.get(0).equals("This phone is already registered. Try to authorize it first.")) {
                                sellDashPref.setCreateAuthReq(createAuthReq);
                                getAuthTokenCall(false);
                                return;
                            }
                        } else {
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                        }

                    } else if (response.code() >= 200 && null != response.body()) {
                        sellDashPref.setCreateAuthReq(createAuthReq);
                        getAuthTokenCall(false);
                    } else {
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }


                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                }
                binding.sellDashProgress.setVisibility(View.GONE);

            }

            @Override
            public void onFailure(Call<CreateAuthResp> call, Throwable t) {
                binding.sellDashProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void getAuthTokenCall(boolean isPass) {
        String countryCode = countryData.countries.get(binding.spCountry.getSelectedItemPosition()).code;
        String phone = countryCode + binding.etPhone.getText().toString().trim();
        String password = binding.etPassword.getText().toString().trim();

        if (!TextUtils.isEmpty(phone) && !TextUtils.isEmpty(password)) {
            HashMap<String, String> getAuthTokenReq = new HashMap<String, String>();
            getAuthTokenReq.put(isPass ? "password" : "deviceCode", password);
            binding.sellDashProgress.setVisibility(View.VISIBLE);
            WallofCoins.createService(getActivity()).getAuthToken(phone, getAuthTokenReq).enqueue(new Callback<GetAuthTokenResp>() {
                @Override
                public void onResponse(Call<GetAuthTokenResp> call, Response<GetAuthTokenResp> response) {
                    try {
                        binding.sellDashProgress.setVisibility(View.GONE);
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

                        getAuthTokenResp = response.body();
                        sellDashPref.setAuthToken(getAuthTokenResp.token);

//                    getReceivingOptions();
                        callAdList();
                        getCurrency();
                    } catch (Resources.NotFoundException e) {
                        e.printStackTrace();
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void onFailure(Call<GetAuthTokenResp> call, Throwable t) {
                    Toast.makeText(activity, R.string.try_again, Toast.LENGTH_SHORT).show();
                    binding.sellDashProgress.setVisibility(View.GONE);
                    binding.linearPhone.setVisibility(View.GONE);
                    binding.linearPassword.setVisibility(View.GONE);
                }
            });

        } else {
            Toast.makeText(activity, "Phone and Password is required", Toast.LENGTH_SHORT).show();
        }
    }


//    private void getAuthTokenCall() {
//        final GetAuthTokenReq getAuthTokenReq = new GetAuthTokenReq();
//        getAuthTokenReq.deviceCode = sellDashPref.getCreateAuthReq().deviceCode;
//        binding.sellDashProgress.setVisibility(View.VISIBLE);
//        WallofCoins.createService(getActivity()).getAuthToken(sellDashPref.getCreateAuthReq().phone, getAuthTokenReq).enqueue(new Callback<GetAuthTokenResp>() {
//            @Override
//            public void onResponse(Call<GetAuthTokenResp> call, Response<GetAuthTokenResp> response) {
//                getAuthTokenResp = response.body();
//                sellDashPref.setAuthToken(getAuthTokenResp.token);
//                getReceivingOptions();
//                getCurrency();
//           }
//
//            @Override
//            public void onFailure(Call<GetAuthTokenResp> call, Throwable t) {
//                binding.sellDashProgress.setVisibility(View.GONE);
//            }
//        });
//    }

    private void getCurrency() {
        binding.sellDashProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(getActivity()).getCurrency().enqueue(new Callback<List<GetCurrencyResp>>() {
            @Override
            public void onResponse(Call<List<GetCurrencyResp>> call, Response<List<GetCurrencyResp>> response) {
                binding.sellDashProgress.setVisibility(View.GONE);
                final List<GetCurrencyResp> getCurrencyResps = response.body();

                for (GetCurrencyResp getCurrencyResp : getCurrencyResps) {
                    getCurrencyResp.toString();
                }

                ArrayAdapter<GetCurrencyResp> adapter = new ArrayAdapter<GetCurrencyResp>(activity, android.R.layout.simple_spinner_dropdown_item, getCurrencyResps) {
                    @NonNull
                    @Override
                    public View getView(int position, @android.support.annotation.Nullable View convertView, @NonNull ViewGroup parent) {

                        View v;

                        if (null == convertView) {
                            LayoutInflater vi = (LayoutInflater) parent.getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                            v = vi.inflate(android.R.layout.simple_spinner_dropdown_item, null);
                        } else {
                            v = convertView;
                        }

                        final TextView t = (TextView) v.findViewById(android.R.id.text1);
                        t.setText(getCurrencyResps.get(position).symbol);
                        return v;
                    }

                    @Override
                    public View getDropDownView(int position, @android.support.annotation.Nullable View convertView, @NonNull ViewGroup parent) {

                        View v;

                        if (null == convertView) {
                            LayoutInflater vi = (LayoutInflater) parent.getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                            v = vi.inflate(android.R.layout.simple_spinner_dropdown_item, null);
                        } else {
                            v = convertView;
                        }

                        final TextView t = (TextView) v.findViewById(android.R.id.text1);
                        t.setText(getCurrencyResps.get(position).name + " (" + getCurrencyResps.get(position).symbol + ")");
                        return v;
                    }
                };

                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                binding.spStaticPrice.setAdapter(adapter);
                binding.spMinCurrency.setAdapter(adapter);
                binding.spMaxCurrency.setAdapter(adapter);

                AdapterView.OnItemSelectedListener currencySelectedListener = new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        if (binding.spStaticPrice.getSelectedItemPosition() != position) {
                            binding.spStaticPrice.setSelection(position);
                        }
                        if (binding.spMinCurrency.getSelectedItemPosition() != position) {
                            binding.spMinCurrency.setSelection(position);
                        }
                        if (binding.spMaxCurrency.getSelectedItemPosition() != position) {
                            binding.spMaxCurrency.setSelection(position);
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {

                    }
                };

                binding.spStaticPrice.setOnItemSelectedListener(currencySelectedListener);
                binding.spMinCurrency.setOnItemSelectedListener(currencySelectedListener);
                binding.spMaxCurrency.setOnItemSelectedListener(currencySelectedListener);
            }

            @Override
            public void onFailure(Call<List<GetCurrencyResp>> call, Throwable t) {
                binding.sellDashProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void callAdList() {
        //here show the listing of ads first and if empty then redirect back to create Ads screen @getReceivingOptions
        binding.sellDashProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).getAdsListing().enqueue(new Callback<List<AdsListActivityResp>>() {
            @Override
            public void onResponse(Call<List<AdsListActivityResp>> call, Response<List<AdsListActivityResp>> response) {

                if (response.code() == 200) {
                    binding.sellDashProgress.setVisibility(View.GONE);

                    if (response.body() != null && response.body().size() > 0) {
                        //here show the list
                        adsListingResps = response.body();
                        binding.linearPhone.setVisibility(View.GONE);
                        binding.linearPassword.setVisibility(View.GONE);
                        binding.layoutVerifyOtp.setVisibility(View.GONE);
                        binding.layoutCreateBankOpts.setVisibility(View.GONE);
                        binding.llFragSellDashAdListing.setVisibility(View.VISIBLE);
                        binding.rvFragSellDashAdsList.setLayoutManager(new LinearLayoutManager(getActivity()));
                        binding.rvFragSellDashAdsList.setAdapter(new AdListingAdapter(response.body()));

                        binding.btFragSellDashCreateAd.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                getReceivingOptions();
                            }
                        });
                    } else {
                        binding.llFragSellDashAdListing.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "No Ads created", Toast.LENGTH_SHORT).show();
                        getReceivingOptions();
                    }
                } else {
                    getReceivingOptions();
                }

            }

            @Override
            public void onFailure(Call<List<AdsListActivityResp>> call, Throwable t) {
                binding.sellDashProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                t.printStackTrace();
            }
        });

    }

    private void getReceivingOptions() {
        String locale;
        locale = getResources().getConfiguration().locale.getCountry();
        binding.sellDashProgress.setVisibility(View.VISIBLE);

        WallofCoins.createService(getActivity()).getReceivingOptions(locale.toLowerCase(), "").enqueue(new Callback<List<GetReceivingOptionsResp>>() {
            @Override
            public void onResponse(Call<List<GetReceivingOptionsResp>> call, Response<List<GetReceivingOptionsResp>> response) {
                Log.e(TAG, "onResponse: " + response.body().size());
                binding.sellDashProgress.setVisibility(View.GONE);
                receivingOptionsResps = response.body();
                binding.linearPhone.setVisibility(View.GONE);
                binding.linearPassword.setVisibility(View.GONE);
                binding.layoutVerifyOtp.setVisibility(View.GONE);
                binding.llFragSellDashAdListing.setVisibility(View.GONE);
                binding.layoutCreateBankOpts.setVisibility(View.VISIBLE);

                //set data in drop down list
                setPaymentOptNames(receivingOptionsResps);
            }

            @Override
            public void onFailure(Call<List<GetReceivingOptionsResp>> call, Throwable t) {
                binding.sellDashProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                t.printStackTrace();
            }
        });
    }

    private void setPaymentOptNames(List<GetReceivingOptionsResp> receivingOptionsResps) {
        ArrayList<String> names = new ArrayList<String>();
        GetReceivingOptionsResp optionsRespDefaultName = new GetReceivingOptionsResp();
        optionsRespDefaultName.name = "Select Your Cash Account";
        receivingOptionsResps.add(0, optionsRespDefaultName);
        for (GetReceivingOptionsResp receivingOptionsResp : receivingOptionsResps) {
            names.add((receivingOptionsResp.name));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spPaymentOptions.setAdapter(adapter);

        binding.spPaymentOptions.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) {
                    updateInputs(position);
                } else {
                    binding.llReceivingPricingOptions.removeAllViews();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

    }

    private void updateInputs(int position) {
        receivingOptionsResp = receivingOptionsResps.get(position);

        Log.e(TAG, "updateInputs: " + new Gson().toJson(receivingOptionsResp));

        if (receivingOptionsResp.payFields.payFieldsB != null) {
            binding.llReceivingPricingOptions.removeAllViews();
            View defaultFieldView = LayoutInflater.from(activity).inflate(R.layout.default_fields_receiving_opt, binding
                    .llReceivingPricingOptions, false);
            binding.llReceivingPricingOptions.addView(defaultFieldView);
        } else {
            binding.llReceivingPricingOptions.removeAllViews();
            // dynamic editText
            List<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> payFields = receivingOptionsResp.payFields.payFieldsX;
            String trigger = receivingOptionsResp.payFields.trigger;
            CheckBox cbTrigger = null;
            // setup trigger
            if (trigger != null) {
                cbTrigger = new CheckBox(activity);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams
                        .WRAP_CONTENT);
                cbTrigger.setLayoutParams(params);
                cbTrigger.setText(trigger);
                binding.llReceivingPricingOptions.addView(cbTrigger);
            }
            // setup static field
            if (payFields != null && payFields.size() > 0) {
                // display sort comparator
                Comparator<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> payFieldsDisplayComparator = new Comparator<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean>() {
                    @Override
                    public int compare(GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean o1, GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean o2) {
                        return o1.displaySort < o2.displaySort ? -1 : (o1.displaySort == o2.displaySort ? 0 : 1);
                    }
                };
                Collections.sort(payFields, Collections.reverseOrder(payFieldsDisplayComparator));

                int sizeDynamicList = payFields.size();
                List<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> confirmFields = receivingOptionsResp.payFields.confirmFields;
                for (int i = 0; i < sizeDynamicList; i++) {
                    for (int j = 0; j < confirmFields.size(); j++) {
                        if (confirmFields.size() > 0 && payFields.get(i).idX == confirmFields.get(j).idX) {
                            payFields.add(i + 1, confirmFields.get(j));
                            confirmFields.remove(j);
                        }
                    }
                }
                List<String> ids = new ArrayList<>();
                for (GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean fieldsBean : payFields) {
                    EditText etField = new EditText(activity);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams
                            .WRAP_CONTENT);
                    etField.setLayoutParams(params);
                    etField.setLines(1);
                    if (ids.contains(String.valueOf(fieldsBean.idX))) {
                        etField.setTag("" + fieldsBean.nameX + "*");
                    } else {
                        etField.setTag("" + fieldsBean.nameX);
                    }
                    ids.add(String.valueOf(fieldsBean.idX));
                    etField.setHint(fieldsBean.label);
                    etField.setLines(1);
                    binding.llReceivingPricingOptions.addView(etField);
                }
            }

            if (cbTrigger != null) {
                cbTrigger.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        int count = binding.llReceivingPricingOptions.getChildCount() - 1;
                        for (int i = count; i > 0; i--) {
                            binding.llReceivingPricingOptions.removeViewAt(i);
                        }
                        if (isChecked) {
                            // add dynamic fields
                            List<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> fieldsBeanList = new ArrayList<GetReceivingOptionsResp
                                    .PayFieldsBeanX.PayFieldsBean>();
                            fieldsBeanList.addAll(receivingOptionsResp.payFields.dynamicFields);
                            List<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> confirmFields = new ArrayList<GetReceivingOptionsResp
                                    .PayFieldsBeanX.PayFieldsBean>();
                            confirmFields.addAll(receivingOptionsResp.payFields.confirmFields);
                            if (fieldsBeanList.size() > 0) {
                                Comparator<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> payFieldsDisplayComparator = new Comparator<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean>() {
                                    @Override
                                    public int compare(GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean o1, GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean o2) {
                                        return o1.displaySort < o2.displaySort ? -1 : (o1.displaySort == o2.displaySort ? 0 : 1);
                                    }
                                };
                                Collections.sort(fieldsBeanList, Collections.reverseOrder(payFieldsDisplayComparator));

                                int sizeDynamicList = fieldsBeanList.size();
                                for (int i = 0; i < sizeDynamicList; i++) {
                                    for (int j = 0; j < confirmFields.size(); j++) {
                                        if (confirmFields.size() > 0 && fieldsBeanList.get(i).idX == confirmFields.get(j).idX) {
                                            fieldsBeanList.add(i + 1, confirmFields.get(j));
                                            confirmFields.remove(j);
                                        }
                                    }
                                }

                                List<String> ids = new ArrayList<>();
                                for (GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean fieldsBean : fieldsBeanList) {
                                    EditText etField = new EditText(activity);
                                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams
                                            .WRAP_CONTENT);
                                    etField.setLayoutParams(params);
                                    etField.setLines(1);
                                    if (ids.contains(String.valueOf(fieldsBean.idX))) {
                                        etField.setTag("" + fieldsBean.nameX + "*");
                                    } else {
                                        etField.setTag("" + fieldsBean.nameX);
                                    }
                                    ids.add(String.valueOf(fieldsBean.idX));
                                    etField.setHint(fieldsBean.label);
                                    etField.setLines(1);
                                    binding.llReceivingPricingOptions.addView(etField);
                                }
                            }
                        } else {
                            List<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> payFields = receivingOptionsResp.payFields.payFieldsX;
                            List<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> confirmFields = receivingOptionsResp.payFields.confirmFields;
                            // setup static field
                            if (payFields != null && payFields.size() > 0) {
                                // display sort comparator
                                Comparator<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> payFieldsDisplayComparator = new Comparator<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean>() {
                                    @Override
                                    public int compare(GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean o1, GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean o2) {
                                        return o1.displaySort < o2.displaySort ? -1 : (o1.displaySort == o2.displaySort ? 0 : 1);
                                    }
                                };
                                Collections.sort(payFields, payFieldsDisplayComparator);

                                for (int i = 0; i < payFields.size(); i++) {
                                    for (int j = 0; j < confirmFields.size(); j++) {
                                        if (payFields.get(i).idX == confirmFields.get(j).idX) {
                                            payFields.add(i + 1, confirmFields.get(j));
                                        }
                                    }
                                }
                                List<String> ids = new ArrayList<>();
                                for (GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean fieldsBean : payFields) {
                                    EditText etField = new EditText(activity);
                                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams
                                            .WRAP_CONTENT);
                                    etField.setLayoutParams(params);
                                    etField.setLines(1);
                                    if (ids.contains(String.valueOf(fieldsBean.idX))) {
                                        etField.setTag("" + fieldsBean.nameX + "*");
                                    } else {
                                        etField.setTag("" + fieldsBean.nameX);
                                    }
                                    ids.add(String.valueOf(fieldsBean.idX));
                                    etField.setHint(fieldsBean.label);
                                    etField.setLines(1);
                                    binding.llReceivingPricingOptions.addView(etField);
                                }
                            }
                        }
                    }
                });
            }
        }
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
        sellDashPref.unregisterOnSharedPreferenceChangeListener(this);
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


    class AdListingAdapter extends RecyclerView.Adapter<AdListingAdapter.VHListing> {


        private List<AdsListActivityResp> body;
        LayoutInflater inflater;
        ItemAdsListingBinding itemAdsListingBinding;

        public AdListingAdapter(List<AdsListActivityResp> body) {
            this.body = body;
        }

        @Override
        public VHListing onCreateViewHolder(ViewGroup parent, int viewType) {
            inflater = LayoutInflater.from(parent.getContext());
            itemAdsListingBinding = DataBindingUtil.inflate(inflater, R.layout.item_ads_listing, parent, false);
            return new VHListing(itemAdsListingBinding.getRoot());
        }

        @Override
        public void onBindViewHolder(VHListing holder, int position) {
            itemAdsListingBinding.tvItemAdListCurrPrice.setText(body.get(position).getCurrentPrice());
        }

        @Override
        public int getItemCount() {
            return body.size();
        }

        class VHListing extends RecyclerView.ViewHolder {
            VHListing(View itemView) {
                super(itemView);
            }
        }
    }
}
