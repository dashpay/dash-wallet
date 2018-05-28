package de.schildbach.wallet.wallofcoins.buying_wizard.offer_amount;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.LoaderManager;
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
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.buying_wizard.BuyingWizardBaseActivity;
import de.schildbach.wallet.wallofcoins.buying_wizard.BuyingWizardBaseFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.adapters.BuyingWizardOffersAdapter;
import de.schildbach.wallet.wallofcoins.buying_wizard.email_phone.BuyingWizardEmailPhoneFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.order_history.BuyingWizardOrderHistoryFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.BuyingWizardAddressPref;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.BuyingWizardFragmentUtils;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.NetworkUtil;
import de.schildbach.wallet.wallofcoins.buying_wizard.verification_otp.BuyingWizardVerifycationOtpFragment;
import de.schildbach.wallet.wallofcoins.response.CreateHoldResp;
import de.schildbach.wallet.wallofcoins.response.DiscoveryInputsResp;
import de.schildbach.wallet.wallofcoins.response.ErrorResp;
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

public class BuyingWizardOfferAmountFragment extends BuyingWizardBaseFragment implements View.OnClickListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private View rootView;
    private String zipCode;
    private double latitude, longitude;
    private Button button_get_offers;
    private EditText edtViewDollar;
    private LinearLayout linearProgress, layout_create_hold;
    private final String TAG = "OfferAmountFragment";
    private String keyAddress, offerId,coinAmount = "", bankId;
    private RecyclerView rv_offers;
    private LoaderManager loaderManager;
    private final int ID_RATE_LOADER = 1;
    private CreateHoldResp createHoldResp;
    private BuyingWizardAddressPref wizardAddressPref;
    private Wallet wallet;
    private Configuration config;
    private WalletApplication application;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_buying_offer_amount, container, false);
            init();
            setListeners();
            readBundle(getArguments());
            return rootView;
        } else
            return rootView;
    }


    private void init() {
        this.application = (WalletApplication) getActivity().getApplication();
        this.config = application.getConfiguration();
        this.wallet = application.getWallet();
        this.loaderManager = getLoaderManager();
        wizardAddressPref = new BuyingWizardAddressPref(PreferenceManager.getDefaultSharedPreferences(mContext));

        button_get_offers = (Button) rootView.findViewById(R.id.button_get_offers);
        linearProgress = (LinearLayout) rootView.findViewById(R.id.linear_progress);
        layout_create_hold = (LinearLayout) rootView.findViewById(R.id.layout_create_hold);
        edtViewDollar = (EditText) rootView.findViewById(R.id.edtViewDollar);

        rv_offers = (RecyclerView) rootView.findViewById(R.id.rv_offers);
        rv_offers.setLayoutManager(new LinearLayoutManager(mContext));
    }

    private void setListeners() {
        button_get_offers.setOnClickListener(this);
    }

    /**
     * handle the arguments according to user come from previos screen
     */
    private void readBundle(Bundle bundle) {
        if (bundle != null) {
            if (bundle.containsKey(WOCConstants.ARG_LATITUDE)) { //user come from my location
                latitude = bundle.getDouble(WOCConstants.ARG_LATITUDE);
                longitude = bundle.getDouble(WOCConstants.ARG_LONGITUDE);
            }
            if (bundle.containsKey(WOCConstants.ARG_ZIP)) { // user come with only zip
                zipCode = bundle.getString(WOCConstants.ARG_ZIP);
            }
            if (bundle.containsKey(WOCConstants.ARG_BANK_ID)) {// user come from bank list
                bankId = bundle.getString(WOCConstants.ARG_BANK_ID);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();

    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onDestroy() {
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
            ((BuyingWizardBaseActivity) mContext).popBackDirect();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.button_get_offers:
                hideKeyBoard();
                if (isValidAmount())
                    callDiscoveryInputs();
                break;


        }
    }

    private boolean isValidAmount() {
        String amt = edtViewDollar.getText().toString().trim();

        if (edtViewDollar.getText().toString().trim().isEmpty()) {
            showToast(mContext.getString(R.string.alert_amount));
            return false;
        } else if (amt.matches("^\\.$")) {
            showToast(mContext.getString(R.string.enter_valid_amt));
            return false;
        } else if (Double.parseDouble(edtViewDollar.getText().toString().trim()) < 5) {
            showToast(mContext.getString(R.string.alert_puchase_amout));
            return false;
        } else if (Double.parseDouble(edtViewDollar.getText().toString().trim()) > 1000000) {
            showToast(mContext.getString(R.string.amount_less_than_1000000));
            return false;
        }
        return true;
    }


    private void callDiscoveryInputs() {
        if (NetworkUtil.isOnline(mContext)) {
            HashMap<String, String> discoveryInputsReq = new HashMap<>();
            discoveryInputsReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
            keyAddress = wallet.freshAddress(RECEIVE_FUNDS).toBase58();
            wizardAddressPref.setBuyCoinAddress(keyAddress);

            discoveryInputsReq.put(WOCConstants.KEY_CRYPTO_ADDRESS, keyAddress);
            String offerAmount = "0";

            discoveryInputsReq.put(WOCConstants.KEY_USD_AMOUNT, "" + edtViewDollar.getText().toString());
            offerAmount = "" + edtViewDollar.getText().toString();

            if (latitude > 0.0)
                discoveryInputsReq.put(WOCConstants.KEY_COUNTRY, getCountryCode(latitude, longitude).toLowerCase());

            discoveryInputsReq.put(WOCConstants.KEY_CRYPTO, WOCConstants.CRYPTO);
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
                                                    layout_create_hold.setVisibility(View.GONE);

                                                    BuyingWizardOffersAdapter wizardOffersAdapter = new BuyingWizardOffersAdapter(mContext, response.body(), finalOfferAmount, new AdapterView.OnItemSelectedListener() {
                                                        @Override
                                                        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                                            hideKeyBoard();
                                                            if (position < response.body().singleDeposit.size() + 1) {
                                                                offerId = response.body().singleDeposit.get(position - 1).id;
                                                                coinAmount = response.body().singleDeposit.get(position - 1).amount.DASH;
                                                            } else {
                                                                offerId = response.body().doubleDeposit.get(position - response.body().singleDeposit.size() - 2).id;
                                                                coinAmount = response.body().doubleDeposit.get(position - response.body().singleDeposit.size() - 2).totalAmount.DASH;
                                                            }
                                                            if (!TextUtils.isEmpty(((BuyingWizardBaseActivity) mContext).buyDashPref.getAuthToken())) {
                                                                createHold();
                                                            } else {
                                                                Bundle bundle = new Bundle();
                                                                bundle.putString(WOCConstants.ARG_OFFER_ID, offerId);
                                                                BuyingWizardEmailPhoneFragment fragment = new BuyingWizardEmailPhoneFragment();
                                                                fragment.setArguments(bundle);

                                                                ((BuyingWizardBaseActivity) mContext).replaceFragment(fragment, true,
                                                                        true);
                                                            }
                                                        }

                                                        @Override
                                                        public void onNothingSelected(AdapterView<?> parent) {
                                                            linearProgress.setVisibility(View.GONE);
                                                        }
                                                    });
                                                    rv_offers.setAdapter(wizardOffersAdapter);
                                                } else {
                                                    showToast(mContext.getString(R.string.alert_no_offers));
                                                }
                                            } else if (null != response && null != response.errorBody()) {
                                                linearProgress.setVisibility(View.GONE);
                                                try {
                                                    ErrorResp errorResp = new Gson().fromJson(response.errorBody().string(), ErrorResp.class);
                                                    Toast.makeText(getContext(), errorResp.detail, Toast.LENGTH_LONG).show();
                                                } catch (Exception e) {
                                                    e.printStackTrace();
                                                    showToast(mContext.getString(R.string.try_again));
                                                }

                                            } else {
                                                linearProgress.setVisibility(View.GONE);
                                                showToast(mContext.getString(R.string.try_again));
                                            }
                                        }

                                        @Override
                                        public void onFailure(Call<GetOffersResp> call, Throwable t) {
                                            linearProgress.setVisibility(View.GONE);
                                            showToast(mContext.getString(R.string.try_again));
                                        }
                                    });
                                } else {
                                    linearProgress.setVisibility(View.GONE);
                                    showToast(mContext.getString(R.string.try_again));
                                }
                            } else if (null != response && null != response.errorBody()) {

                                linearProgress.setVisibility(View.GONE);

                                try {
                                    ErrorResp errorResp = new Gson().fromJson(response.errorBody().string(), ErrorResp.class);
                                    if (errorResp.detail != null && !TextUtils.isEmpty(errorResp.detail)) {
                                        Toast.makeText(getContext(), errorResp.detail, Toast.LENGTH_LONG).show();
                                    } else {
                                        showToast(mContext.getString(R.string.try_again));
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    showToast(mContext.getString(R.string.try_again));
                                }

                            } else {
                                linearProgress.setVisibility(View.GONE);
                                showToast(mContext.getString(R.string.try_again));
                            }
                        }

                        @Override
                        public void onFailure(Call<DiscoveryInputsResp> call, Throwable t) {
                            linearProgress.setVisibility(View.GONE);
                            String message = t.getMessage();
                            Log.d("failure", message);
                            showToast(mContext.getString(R.string.try_again));
                        }
                    });
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));

    }

    /**
     * Method for create new hold
     */
    public void createHold() {
        if (NetworkUtil.isOnline(mContext)) {
            String phone = ((BuyingWizardBaseActivity) mContext).buyDashPref.getPhone();

            final HashMap<String, String> createHoldPassReq = new HashMap<>();

            if (TextUtils.isEmpty(((BuyingWizardBaseActivity) mContext).buyDashPref.getAuthToken())) {
                createHoldPassReq.put(WOCConstants.KEY_PHONE, phone);
                createHoldPassReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
                createHoldPassReq.put(WOCConstants.KEY_deviceName, WOCConstants.KEY_DEVICE_NAME_VALUE);
                createHoldPassReq.put(WOCConstants.KEY_DEVICECODE, getDeviceCode(mContext,
                        ((BuyingWizardBaseActivity) mContext).buyDashPref));
            }
            createHoldPassReq.put(WOCConstants.KEY_OFFER, offerId);

            linearProgress.setVisibility(View.VISIBLE);

            WallofCoins.createService(interceptor, getActivity()).createHold(createHoldPassReq).enqueue(new Callback<CreateHoldResp>() {
                @Override
                public void onResponse(Call<CreateHoldResp> call, Response<CreateHoldResp> response) {
                    linearProgress.setVisibility(View.GONE);

                    if (null != response.body() && response.code() < 299) {

                        createHoldResp = response.body();
                        ((BuyingWizardBaseActivity) mContext).buyDashPref.setHoldId(createHoldResp.id);
                        ((BuyingWizardBaseActivity) mContext).buyDashPref.setCreateHoldResp(createHoldResp);
                        if (TextUtils.isEmpty(((BuyingWizardBaseActivity) mContext).buyDashPref.getDeviceId())
                                && !TextUtils.isEmpty(createHoldResp.deviceId)) {
                            ((BuyingWizardBaseActivity) mContext).buyDashPref.setDeviceId(createHoldResp.deviceId);
                        }
                        if (!TextUtils.isEmpty(response.body().token)) {
                            ((BuyingWizardBaseActivity) mContext).buyDashPref.setAuthToken(createHoldResp.token);
                        }
                        navigateToVerifyOtp(createHoldResp.__PURCHASE_CODE);

                    } else if (null != response.errorBody()) {
                        if (response.code() == 403 && TextUtils.isEmpty(((BuyingWizardBaseActivity) mContext)
                                .buyDashPref.getAuthToken())) {
                        } else if (response.code() == 403 && !TextUtils.isEmpty(((BuyingWizardBaseActivity) mContext)
                                .buyDashPref.getAuthToken())) {
                            getHolds();
                        } else if (response.code() == 400) {
                            if (!TextUtils.isEmpty(((BuyingWizardBaseActivity) mContext).buyDashPref.getAuthToken())) {
                                navigateToOrderList(false);
                            }
                        } else {
                            try {
                                if (!TextUtils.isEmpty(((BuyingWizardBaseActivity) mContext).buyDashPref.getAuthToken())) {
                                    navigateToOrderList(false);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }

                @Override
                public void onFailure(Call<CreateHoldResp> call, Throwable t) {
                    linearProgress.setVisibility(View.GONE);
                    showToast(mContext.getString(R.string.try_again));
                }
            });
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));


    }

    /**
     * Get all holds for delete active hold
     */
    private void getHolds() {
        if (NetworkUtil.isOnline(mContext)) {
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
                            }
                        } else {
                            navigateToOrderList(false);
                        }
                    }
                }

                @Override
                public void onFailure(Call<List<GetHoldsResp>> call, Throwable t) {
                    linearProgress.setVisibility(View.GONE);
                    Log.e(TAG, "onFailure: ", t);
                    showToast(mContext.getString(R.string.try_again));
                }
            });
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));

    }

    /**
     * Method call for delete for provide holdId
     *
     * @param holdId
     */
    private void deleteHold(String holdId) {
        if (NetworkUtil.isOnline(mContext)) {
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
                    showToast(mContext.getString(R.string.try_again));
                }
            });
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));

    }

    private void navigateToOrderList(boolean isFromCreateHold) {
        BuyingWizardOrderHistoryFragment historyFragment = new BuyingWizardOrderHistoryFragment();
        Bundle bundle = new Bundle();
        bundle.putBoolean("isFromCreateHold", isFromCreateHold);
        historyFragment.setArguments(bundle);
        ((BuyingWizardBaseActivity) mContext).replaceFragment(historyFragment, true, true);
    }

    private void navigateToVerifyOtp(String otp) {
        Bundle bundle = new Bundle();
        bundle.putString(WOCConstants.ARG_VERIFICATION_OTP, otp);
        BuyingWizardVerifycationOtpFragment otpFragment = new BuyingWizardVerifycationOtpFragment();
        otpFragment.setArguments(bundle);

        ((BuyingWizardBaseActivity) mContext).replaceFragment(otpFragment, true, true);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {

    }

    //this method remove animation when user want to clear back stack
    @Override
    public Animation onCreateAnimation(int transit, boolean enter, int nextAnim) {
        if (BuyingWizardFragmentUtils.sDisableFragmentAnimations) {
            Animation a = new Animation() {
            };
            a.setDuration(0);
            return a;
        }
        return super.onCreateAnimation(transit, enter, nextAnim);
    }
}
