package de.schildbach.wallet.wallofcoins.buyingwizard.offer_amount;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.bitcoinj.wallet.Wallet;

import java.io.IOException;
import java.util.HashMap;

import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.CurrencyAmountView;
import de.schildbach.wallet.wallofcoins.BuyDashOffersAdapter;
import de.schildbach.wallet.wallofcoins.BuyDashPref;
import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseActivity;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseFragment;
import de.schildbach.wallet.wallofcoins.response.BuyDashErrorResp;
import de.schildbach.wallet.wallofcoins.response.DiscoveryInputsResp;
import de.schildbach.wallet.wallofcoins.response.GetOffersResp;
import de.schildbach.wallet_test.R;
import okhttp3.Interceptor;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import static org.bitcoinj.wallet.KeyChain.KeyPurpose.RECEIVE_FUNDS;

/**
 * Created by Bypt on 07-Mar-18.
 */

public class BuyDashOfferAmountFragment extends BuyDashBaseFragment implements View.OnClickListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private View rootView;
    private ImageView imgViewToolbarBack;
    private String zipCode;
    private double latitude, longitude;
    private Button button_buy_dash_get_offers;
    private EditText request_coins_amount_btc_edittext, request_coins_amount_local_edittext;
    private BuyDashPref buyDashPref;
    private LinearLayout linearProgress, layout_create_hold;
    private final String TAG = "BuyDashOfferFragment";
    private Wallet wallet;
    private String keyAddress, offerId, dashAmount = "", bankId;
    private Configuration config;
    private WalletApplication application;
    private RecyclerView rv_offers;
    private CurrencyAmountView request_coins_amount_local;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.layout_offer_amount, container, false);
            init();
            setListeners();
            handleArgs();
            return rootView;
        } else
            return rootView;
    }


    private void init() {
        this.application = (WalletApplication) getActivity().getApplication();
        this.buyDashPref = new BuyDashPref(PreferenceManager.getDefaultSharedPreferences(mContext));
        this.config = application.getConfiguration();
        this.wallet = application.getWallet();
        imgViewToolbarBack = (ImageView) rootView.findViewById(R.id.imgViewToolbarBack);
        button_buy_dash_get_offers = (Button) rootView.findViewById(R.id.button_buy_dash_get_offers);
        request_coins_amount_btc_edittext = (EditText) rootView.findViewById(R.id.request_coins_amount_btc_edittext);
        request_coins_amount_local_edittext = (EditText) rootView.findViewById(R.id.request_coins_amount_local_edittext);
        linearProgress = (LinearLayout) rootView.findViewById(R.id.linear_progress);
        layout_create_hold = (LinearLayout) rootView.findViewById(R.id.layout_create_hold);
        request_coins_amount_local= (CurrencyAmountView) rootView.findViewById(R.id.request_coins_amount_local);

        request_coins_amount_local.setInputFormat(Constants.LOCAL_FORMAT);
        request_coins_amount_local.setHintFormat(Constants.LOCAL_FORMAT);

        rv_offers = (RecyclerView) rootView.findViewById(R.id.rv_offers);
        rv_offers.setLayoutManager(new LinearLayoutManager(mContext));
    }

    private void setListeners() {
        imgViewToolbarBack.setOnClickListener(this);
        button_buy_dash_get_offers.setOnClickListener(this);
        buyDashPref.registerOnSharedPreferenceChangeListener(this);
    }

    /**
     * handle the arguments comes from previos screen
     */
    private void handleArgs() {
        if (getArguments() != null) {
            if (getArguments().containsKey(WOCConstants.LATITUDE)) {
                latitude = getArguments().getDouble(WOCConstants.LATITUDE);
                longitude = getArguments().getDouble(WOCConstants.LONGITUDE);
            }
            if (getArguments().containsKey(WOCConstants.ZIP)) {
                zipCode = getArguments().getString(WOCConstants.ZIP);
            }
            if (getArguments().containsKey(WOCConstants.BANK_ID)) {
                bankId = getArguments().getString(WOCConstants.BANK_ID);
            }
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.imgViewToolbarBack:
                ((BuyDashBaseActivity) mContext).popbackFragment();
                break;
            case R.id.button_buy_dash_get_offers:
                hideKeyBoard();
                if (Float.valueOf(request_coins_amount_local_edittext.getHint().toString()) > 0f
                        || !TextUtils.isEmpty(request_coins_amount_local_edittext.getText())) {
                    if (!TextUtils.isEmpty(request_coins_amount_local_edittext.getText().toString())
                            && Float.valueOf(request_coins_amount_local_edittext.getText().toString()) >= 5f
                            || !TextUtils.isEmpty(request_coins_amount_local_edittext.getHint().toString())
                            && Float.valueOf(request_coins_amount_local_edittext.getHint().toString()) >= 5f) {
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

    /**
     * API Header parameter interceptor
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
            requestBuilder.addHeader(WOCConstants.KEY_HEADER_CONTENT_TYPE, WOCConstants.KEY_HEADER_CONTENT_TYPE_VALUE);
            Request request = requestBuilder.build();
            return chain.proceed(request);
        }
    };

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
        discoveryInputsReq.put(WOCConstants.KEY_BANK, bankId);
        discoveryInputsReq.put(WOCConstants.KEY_ZIP_CODE, zipCode);

        if (latitude > 0.0)
        {
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
                                                        if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                                                            // createHold();
                                                        } else {
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

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {

    }
}
