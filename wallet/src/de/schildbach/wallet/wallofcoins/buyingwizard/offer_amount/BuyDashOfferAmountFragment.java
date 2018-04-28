package de.schildbach.wallet.wallofcoins.buyingwizard.offer_amount;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.bitcoinj.wallet.Wallet;

import java.util.HashMap;
import java.util.List;

import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.ExchangeRate;
import de.schildbach.wallet.data.ExchangeRatesProvider;
import de.schildbach.wallet.ui.CurrencyAmountView;
import de.schildbach.wallet.ui.CurrencyCalculatorLink;
import de.schildbach.wallet.ui.ExchangeRateLoader;
import de.schildbach.wallet.wallofcoins.BuyDashOffersAdapter;
import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseActivity;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.email_phone.EmailAndPhoneFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.order_history.OrderHistoryFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.utils.BuyDashAddressPref;
import de.schildbach.wallet.wallofcoins.buyingwizard.utils.FragmentUtils;
import de.schildbach.wallet.wallofcoins.buyingwizard.verification_otp.VerifycationOtpFragment;
import de.schildbach.wallet.wallofcoins.response.BuyDashErrorResp;
import de.schildbach.wallet.wallofcoins.response.CreateHoldResp;
import de.schildbach.wallet.wallofcoins.response.DiscoveryInputsResp;
import de.schildbach.wallet.wallofcoins.response.GetHoldsResp;
import de.schildbach.wallet.wallofcoins.response.GetOffersResp;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import static org.bitcoinj.wallet.KeyChain.KeyPurpose.RECEIVE_FUNDS;

/**
 * Created on 07-Mar-18.
 */

public class BuyDashOfferAmountFragment extends BuyDashBaseFragment implements View.OnClickListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private View rootView;
    private String zipCode;
    private double latitude, longitude;
    private Button button_buy_dash_get_offers;
    private EditText request_coins_amount_btc_edittext, request_coins_amount_local_edittext;
    private LinearLayout linearProgress, layout_create_hold;
    private final String TAG = "BuyDashOfferFragment";
    private Wallet wallet;
    private String keyAddress, offerId, dashAmount = "", bankId;
    private Configuration config;
    private WalletApplication application;
    private RecyclerView rv_offers;
    private CurrencyAmountView request_coins_amount_local, request_coins_amount_btc;
    private CurrencyCalculatorLink amountCalculatorLink;
    private LoaderManager loaderManager;
    private final int ID_RATE_LOADER = 1;
    private CreateHoldResp createHoldResp;
    private BuyDashAddressPref dashAddressPref;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_offer_amount, container, false);
            init();
            setListeners();
            handleArgs();
            return rootView;
        } else
            return rootView;
    }


    private void init() {
        this.application = (WalletApplication) getActivity().getApplication();
        this.config = application.getConfiguration();
        this.wallet = application.getWallet();
        this.loaderManager = getLoaderManager();
        dashAddressPref = new BuyDashAddressPref(PreferenceManager.getDefaultSharedPreferences(mContext));

        button_buy_dash_get_offers = (Button) rootView.findViewById(R.id.button_buy_dash_get_offers);
        request_coins_amount_btc_edittext = (EditText) rootView.findViewById(R.id.request_coins_amount_btc_edittext);
        request_coins_amount_local_edittext = (EditText) rootView.findViewById(R.id.request_coins_amount_local_edittext);
        linearProgress = (LinearLayout) rootView.findViewById(R.id.linear_progress);
        layout_create_hold = (LinearLayout) rootView.findViewById(R.id.layout_create_hold);
        request_coins_amount_local = (CurrencyAmountView) rootView.findViewById(R.id.request_coins_amount_local);
        request_coins_amount_btc = (CurrencyAmountView) rootView.findViewById(R.id.request_coins_amount_btc);

        request_coins_amount_btc.setCurrencySymbol(config.getFormat().code());
        request_coins_amount_btc.setInputFormat(config.getMaxPrecisionFormat());
        request_coins_amount_btc.setHintFormat(config.getFormat());

        request_coins_amount_local.setInputFormat(Constants.LOCAL_FORMAT);
        request_coins_amount_local.setHintFormat(Constants.LOCAL_FORMAT);

        amountCalculatorLink = new CurrencyCalculatorLink(request_coins_amount_btc, request_coins_amount_local);

        rv_offers = (RecyclerView) rootView.findViewById(R.id.rv_offers);
        rv_offers.setLayoutManager(new LinearLayoutManager(mContext));
    }

    private void setListeners() {
        button_buy_dash_get_offers.setOnClickListener(this);
    }

    /**
     * handle the arguments according to user come from previos screen
     */
    private void handleArgs() {
        if (getArguments() != null) {
            if (getArguments().containsKey(WOCConstants.LATITUDE)) { //user come from my location
                latitude = getArguments().getDouble(WOCConstants.LATITUDE);
                longitude = getArguments().getDouble(WOCConstants.LONGITUDE);
            }
            if (getArguments().containsKey(WOCConstants.ZIP)) { // user come with only zip
                zipCode = getArguments().getString(WOCConstants.ZIP);
            }
            if (getArguments().containsKey(WOCConstants.BANK_ID)) {// user come from bank list
                bankId = getArguments().getString(WOCConstants.BANK_ID);
            }
        }
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

    /**
     * hide show view
     */
    public void changeView() {
        if (layout_create_hold.getVisibility() == View.GONE) {
            layout_create_hold.setVisibility(View.VISIBLE);
            rv_offers.setVisibility(View.GONE);
        } else
            ((BuyDashBaseActivity) mContext).popBackDirect();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.button_buy_dash_get_offers:
                hideKeyBoard();
                if (Float.valueOf(request_coins_amount_local_edittext.getHint().toString()) > 0f
                        || !TextUtils.isEmpty(request_coins_amount_local_edittext.getText())) {

                    if (!TextUtils.isEmpty(request_coins_amount_local_edittext.getText().toString())
                            && Float.valueOf(request_coins_amount_local_edittext.getText().toString()) >= 5f

                            || !TextUtils.isEmpty(request_coins_amount_local_edittext.getHint().toString())
                            && Float.valueOf(request_coins_amount_local_edittext.getHint().toString()) >= 5f) {

                        if (isValidAmount())
                            callDiscoveryInputs();
                    } else {
                        Toast.makeText(mContext, R.string.alert_puchase_amout, Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(getContext(), R.string.alert_amount, Toast.LENGTH_SHORT).show();
                }
                break;


        }
    }

    private boolean isValidAmount() {
        if (!TextUtils.isEmpty(request_coins_amount_local_edittext.getText().toString())
                && Float.valueOf(request_coins_amount_local_edittext.getText().toString()) > 1000000f

                || !TextUtils.isEmpty(request_coins_amount_local_edittext.getHint().toString())
                && Float.valueOf(request_coins_amount_local_edittext.getHint().toString()) > 1000000f) {
            showToast("Amount should be less than 1000000.");
            return false;
        }
        return true;
    }

    /**
     * Callback Manager for Load Exchange rate from Exchange rate Provider
     */
    private final LoaderManager.LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            return new ExchangeRateLoader(mContext, config);
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
     * Method for call discovery inputs & offers for discovery input
     */

    private void callDiscoveryInputs() {
        HashMap<String, String> discoveryInputsReq = new HashMap<String, String>();
        discoveryInputsReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
        keyAddress = wallet.freshAddress(RECEIVE_FUNDS).toBase58();
        dashAddressPref.setBuyDashAddress(keyAddress);

        // Log.e("------------------",dashAddressPref.getBuyDashAddress());
        discoveryInputsReq.put(WOCConstants.KEY_CRYPTO_ADDRESS, keyAddress);
        String offerAmount = "0";
        try {
            if (Float.valueOf(request_coins_amount_local.getTextView().getHint().toString()) > 0f) {
                discoveryInputsReq.put(WOCConstants.KEY_USD_AMOUNT, "" + request_coins_amount_local.getTextView().getHint());
                offerAmount = "" + request_coins_amount_local_edittext.getHint();
            } else {
                discoveryInputsReq.put(WOCConstants.KEY_USD_AMOUNT, "" + request_coins_amount_local.getTextView().getText());
                offerAmount = "" + request_coins_amount_local_edittext.getText();
            }
            Log.d(TAG, "callDiscoveryInputs: usdAmount==>>" + request_coins_amount_local.getTextView().getHint());
        } catch (Exception e) {
            discoveryInputsReq.put(WOCConstants.KEY_USD_AMOUNT, "0");
            e.printStackTrace();
        }

        if (latitude > 0.0)
            discoveryInputsReq.put(WOCConstants.KEY_COUNTRY, getCountryCode(latitude, longitude).toLowerCase());
        discoveryInputsReq.put(WOCConstants.KEY_CRYPTO, config.getFormat().code());

        if (bankId != null)
            discoveryInputsReq.put(WOCConstants.KEY_BANK, bankId);

        if (zipCode != null)
            discoveryInputsReq.put(WOCConstants.KEY_ZIP_CODE, zipCode);

        if (latitude > 0.0) {
            JsonObject jObj = new JsonObject();
            jObj.addProperty(WOCConstants.KEY_LATITUDE, latitude + "");
            jObj.addProperty(WOCConstants.KEY_LONGITUDE, longitude + "");

            discoveryInputsReq.put(WOCConstants.KEY_BROWSE_LOCATION, jObj.toString());
        }
        discoveryInputsReq.put(WOCConstants.KEY_CRYPTO_AMOUNT, "0");
        linearProgress.setVisibility(View.VISIBLE);

        final String finalOfferAmount = offerAmount;
        WallofCoins.createService(interceptor, getActivity())
                .discoveryInputs(discoveryInputsReq)
                .enqueue(new Callback<DiscoveryInputsResp>() {
                    @Override
                    public void onResponse(Call<DiscoveryInputsResp> call, Response<DiscoveryInputsResp> response) {

                        if (null != response && null != response.body()) {
                            if (null != response.body().id) {
                                updateAddressBookValue(keyAddress, WOCConstants.WOC_ADDRESS);// Update Address Book for Order

                                WallofCoins.createService(null, getActivity()).getOffers(response.body().id, getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<GetOffersResp>() {
                                    @Override
                                    public void onResponse(Call<GetOffersResp> call, final Response<GetOffersResp> response) {

                                        if (null != response && null != response.body()) {

                                            linearProgress.setVisibility(View.GONE);

                                            if (null != response.body().singleDeposit && !response.body().singleDeposit.isEmpty()) {
                                                rv_offers.setVisibility(View.VISIBLE);
                                                GetOffersResp getOffersResp = response.body();
                                                layout_create_hold.setVisibility(View.GONE);
                                                //binding.spBanks.setAdapter(null);

                                                BuyDashOffersAdapter buyDashOffersAdapter = new BuyDashOffersAdapter(mContext, response.body(), finalOfferAmount, new AdapterView.OnItemSelectedListener() {
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
                                                        if (!TextUtils.isEmpty(((BuyDashBaseActivity) mContext).buyDashPref.getAuthToken())) {
                                                            createHold();
                                                        } else {
                                                            Bundle bundle = new Bundle();
                                                            bundle.putString(WOCConstants.OFFER_ID, offerId);
                                                            EmailAndPhoneFragment fragment = new EmailAndPhoneFragment();
                                                            fragment.setArguments(bundle);

                                                            ((BuyDashBaseActivity) mContext).replaceFragment(fragment, true,
                                                                    true);
                                                            // hideViewExcept(binding.linearEmail);
                                                            //clearForm((ViewGroup) binding.getRoot());
                                                        }
                                                    }

                                                    @Override
                                                    public void onNothingSelected(AdapterView<?> parent) {
                                                        linearProgress.setVisibility(View.GONE);
                                                    }
                                                });
                                                rv_offers.setAdapter(buyDashOffersAdapter);
                                            } else {
                                                Toast.makeText(getContext(), R.string.alert_no_offers, Toast.LENGTH_LONG).show();
                                            }
                                        } else if (null != response && null != response.errorBody()) {
                                            linearProgress.setVisibility(View.GONE);
                                            try {
                                                BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                                                Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                            }

                                        } else {
                                            linearProgress.setVisibility(View.GONE);
                                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                        }
                                    }

                                    @Override
                                    public void onFailure(Call<GetOffersResp> call, Throwable t) {
                                        linearProgress.setVisibility(View.GONE);
                                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                    }
                                });
                            } else {
                                linearProgress.setVisibility(View.GONE);
                                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                            }
                        } else if (null != response && null != response.errorBody()) {

                            linearProgress.setVisibility(View.GONE);

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
                            linearProgress.setVisibility(View.GONE);
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<DiscoveryInputsResp> call, Throwable t) {
                        linearProgress.setVisibility(View.GONE);
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }
                });
    }

    /**
     * Method for create new hold
     */
    public void createHold() {
        String phone = ((BuyDashBaseActivity) mContext).buyDashPref.getPhone();

        final HashMap<String, String> createHoldPassReq = new HashMap<String, String>();

        if (TextUtils.isEmpty(((BuyDashBaseActivity) mContext).buyDashPref.getAuthToken())) {
            createHoldPassReq.put(WOCConstants.KEY_PHONE, phone);
            createHoldPassReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
            //createHoldPassReq.put(WOCConstants.KEY_EMAIL, email);
            createHoldPassReq.put(WOCConstants.KEY_deviceName, WOCConstants.KEY_DEVICE_NAME_VALUE);
            createHoldPassReq.put(WOCConstants.KEY_DEVICECODE, getDeviceCode(mContext, ((BuyDashBaseActivity) mContext).buyDashPref));
        }
        createHoldPassReq.put(WOCConstants.KEY_OFFER, offerId);

        linearProgress.setVisibility(View.VISIBLE);

        WallofCoins.createService(interceptor, getActivity()).createHold(createHoldPassReq).enqueue(new Callback<CreateHoldResp>() {
            @Override
            public void onResponse(Call<CreateHoldResp> call, Response<CreateHoldResp> response) {
                linearProgress.setVisibility(View.GONE);

                if (null != response.body() && response.code() < 299) {

                    createHoldResp = response.body();
                    ((BuyDashBaseActivity) mContext).buyDashPref.setHoldId(createHoldResp.id);
                    ((BuyDashBaseActivity) mContext).buyDashPref.setCreateHoldResp(createHoldResp);
                    if (TextUtils.isEmpty(((BuyDashBaseActivity) mContext).buyDashPref.getDeviceId())
                            && !TextUtils.isEmpty(createHoldResp.deviceId)) {
                        ((BuyDashBaseActivity) mContext).buyDashPref.setDeviceId(createHoldResp.deviceId);
                    }
                    if (!TextUtils.isEmpty(response.body().token)) {
                        ((BuyDashBaseActivity) mContext).buyDashPref.setAuthToken(createHoldResp.token);
                    }
                    //hideViewExcept(binding.layoutVerifyOtp);
                    //clearForm((ViewGroup) binding.getRoot());
                    //binding.etOtp.setText(createHoldResp.__PURCHASE_CODE);
                    navigateToVerifyOtp(createHoldResp.__PURCHASE_CODE);

                } else if (null != response.errorBody()) {
                    if (response.code() == 403 && TextUtils.isEmpty(((BuyDashBaseActivity) mContext).buyDashPref.getAuthToken())) {
                        //hideViewExcept(binding.layoutHold);
                        //clearForm((ViewGroup) binding.getRoot());
                    } else if (response.code() == 403 && !TextUtils.isEmpty(((BuyDashBaseActivity) mContext).buyDashPref.getAuthToken())) {
                        getHolds();
                    } else if (response.code() == 400) {
                        if (!TextUtils.isEmpty(((BuyDashBaseActivity) mContext).buyDashPref.getAuthToken())) {
                            navigateToOrderList(false);
                            //getOrderList(false);
                        } else {
                            //hideViewExcept(binding.layoutHold);
                            //clearForm((ViewGroup) binding.getRoot());
                        }
                    } else {
                        try {
                            if (!TextUtils.isEmpty(((BuyDashBaseActivity) mContext).buyDashPref.getAuthToken())) {
                                //getOrderList(false);
                                navigateToOrderList(false);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                } else {
                    //clearForm((ViewGroup) binding.getRoot());
                }
            }

            @Override
            public void onFailure(Call<CreateHoldResp> call, Throwable t) {
                linearProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
            }
        });

    }

    /**
     * Get all holds for delete active hold
     */
    private void getHolds() {
        linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).getHolds().enqueue(new Callback<List<GetHoldsResp>>() {
            @Override
            public void onResponse(Call<List<GetHoldsResp>> call, Response<List<GetHoldsResp>> response) {
                linearProgress.setVisibility(View.GONE);
                if (response.code() == 200 && response.body() != null) {
                    List<GetHoldsResp> holdsList = response.body();
                    int holdCount = 0;
                    if (holdsList.size() > 0) {
                        for (int i = 0; i < holdsList.size(); i++) {
                            if (null != holdsList.get(i).status && holdsList.get(i).status.equals("AC")) {
                                deleteHold(holdsList.get(i).id);
                                holdCount++;
                            }
                        }
                        if (holdCount == 0) {
                            navigateToOrderList(false);
                            //getOrderList(false);
                        }
                    } else {
                        //getOrderList(false);
                        navigateToOrderList(false);
                    }
                }
            }

            @Override
            public void onFailure(Call<List<GetHoldsResp>> call, Throwable t) {
                linearProgress.setVisibility(View.GONE);
                Log.e(TAG, "onFailure: ", t);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Method call for delete for provide holdId
     *
     * @param holdId
     */
    private void deleteHold(String holdId) {
        linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).deleteHold(holdId).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                createHold();
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                linearProgress.setVisibility(View.GONE);
                Log.e(TAG, "onFailure: ", t);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void navigateToOrderList(boolean isFromCreateHold) {
        OrderHistoryFragment historyFragment = new OrderHistoryFragment();
        Bundle bundle = new Bundle();
        bundle.putBoolean("isFromCreateHold", isFromCreateHold);
        historyFragment.setArguments(bundle);
        ((BuyDashBaseActivity) mContext).replaceFragment(historyFragment, true, true);
    }

    private void navigateToVerifyOtp(String otp) {
        Bundle bundle = new Bundle();
        bundle.putString(WOCConstants.VERIFICATION_OTP, otp);
        VerifycationOtpFragment otpFragment = new VerifycationOtpFragment();
        otpFragment.setArguments(bundle);

        ((BuyDashBaseActivity) mContext).replaceFragment(otpFragment, true, true);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {

    }

    //this method remove animation when user want to clear back stack
    @Override
    public Animation onCreateAnimation(int transit, boolean enter, int nextAnim) {
        if (FragmentUtils.sDisableFragmentAnimations) {
            Animation a = new Animation() {
            };
            a.setDuration(0);
            return a;
        }
        return super.onCreateAnimation(transit, enter, nextAnim);
    }
}
