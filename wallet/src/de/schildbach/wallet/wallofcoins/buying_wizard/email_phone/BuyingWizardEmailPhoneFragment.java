package de.schildbach.wallet.wallofcoins.buying_wizard.email_phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import de.schildbach.wallet.wallofcoins.CustomAdapter;
import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.buying_wizard.BuyingWizardBaseActivity;
import de.schildbach.wallet.wallofcoins.buying_wizard.BuyingWizardBaseFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.order_history.BuyingWizardOrderHistoryFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.BuyingWizardFragmentUtils;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.BuyingWizardPhoneListPref;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.NetworkUtil;
import de.schildbach.wallet.wallofcoins.buying_wizard.verification_otp.BuyingWizardVerifycationOtpFragment;
import de.schildbach.wallet.wallofcoins.response.CheckAuthResp;
import de.schildbach.wallet.wallofcoins.response.CountryData;
import de.schildbach.wallet.wallofcoins.response.CreateDeviceResp;
import de.schildbach.wallet.wallofcoins.response.CreateHoldResp;
import de.schildbach.wallet.wallofcoins.response.GetAuthTokenResp;
import de.schildbach.wallet.wallofcoins.response.GetHoldsResp;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created on 08-Mar-18.
 */

public class BuyingWizardEmailPhoneFragment extends BuyingWizardBaseFragment implements View.OnClickListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private final String TAG = "EmailPhoneFragment";
    private View rootView;
    private LinearLayout linearProgress, linear_email, linear_phone, layout_hold;
    private CountryData countryData;
    private Spinner sp_country;
    private Button btn_next_phone, btn_next_email, btn_sign_in;
    private String country_code = "", phone_no = "", email = "", password = "", offerId = "";
    private EditText edit_phone, edit_email;
    private TextView tv_skip_email;
    private CreateDeviceResp createDeviceResp;
    private CreateHoldResp createHoldResp;
    private BuyingWizardPhoneListPref credentilasPref;
    private String fromScreen = "";

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_buying_email_and_phone, container, false);
            init();
            addCountryCodeList();
            setListeners();
            readBundle(getArguments());
            return rootView;
        } else
            return rootView;
    }

    private void init() {
        credentilasPref = new BuyingWizardPhoneListPref(PreferenceManager.getDefaultSharedPreferences(mContext));

        linearProgress = (LinearLayout) rootView.findViewById(R.id.linear_progress);
        linear_email = (LinearLayout) rootView.findViewById(R.id.linear_email);
        sp_country = (Spinner) rootView.findViewById(R.id.sp_country);
        btn_next_phone = (Button) rootView.findViewById(R.id.btn_next_phone);
        edit_phone = (EditText) rootView.findViewById(R.id.edit_phone);
        btn_next_email = (Button) rootView.findViewById(R.id.btn_next_email);
        edit_email = (EditText) rootView.findViewById(R.id.edit_email);
        linear_phone = (LinearLayout) rootView.findViewById(R.id.linear_phone);
        layout_hold = (LinearLayout) rootView.findViewById(R.id.layout_hold);
        tv_skip_email = (TextView) rootView.findViewById(R.id.tv_skip_email);
        btn_sign_in = (Button) rootView.findViewById(R.id.btn_sign_in);
    }

    private void setListeners() {
        btn_next_phone.setOnClickListener(this);
        btn_next_email.setOnClickListener(this);
        tv_skip_email.setOnClickListener(this);
        btn_sign_in.setOnClickListener(this);
    }

    /**
     * handle the arguments according to user come from previos screen
     */
    private void readBundle(Bundle bundle) {
        if (bundle != null) {
            if (bundle.containsKey(WOCConstants.ARG_OFFER_ID)) {
                offerId = bundle.getString(WOCConstants.ARG_OFFER_ID);
            }
            if (bundle.containsKey(WOCConstants.ARG_SCREEN_TYPE)) {
                fromScreen = bundle.getString(WOCConstants.ARG_SCREEN_TYPE);
            }
        }
    }

    public void changeView() {
        if (linear_email.getVisibility() == View.VISIBLE)
            ((BuyingWizardBaseActivity) mContext).popBackDirect();
        else if (linear_phone.getVisibility() == View.GONE) {
            layout_hold.setVisibility(View.GONE);
            linear_phone.setVisibility(View.VISIBLE);
            linear_email.setVisibility(View.GONE);

        } else if (linear_email.getVisibility() == View.GONE) {
            linear_email.setVisibility(View.VISIBLE);
            linear_phone.setVisibility(View.GONE);
            layout_hold.setVisibility(View.GONE);
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.btn_next_phone:
                if (isValidPhone()) {
                    hideKeyBoard();
                    // if (!offerId.isEmpty()) {
                    country_code = countryData.countries.get(sp_country.getSelectedItemPosition()).code;
                    phone_no = edit_phone.getText().toString().trim();
                    String phone = country_code + edit_phone.getText().toString().trim();
                    ((BuyingWizardBaseActivity) mContext).buyDashPref.setPhone(phone);

                    checkAuth();
                    /*} else {
                        showToast(getString(R.string.offerid_not_available));
                        ((BuyingWizardBaseActivity) mContext).popBackDirect();
                    }*/
                }
                break;

            case R.id.btn_next_email:
                if (!edit_email.getText().toString().isEmpty() && isValidEmail(edit_email.getText().toString())) {
                    email = edit_email.getText().toString();
                    linear_phone.setVisibility(View.VISIBLE);
                    linear_email.setVisibility(View.GONE);
                } else {
                    showToast(mContext.getString(R.string.alert_enter_valid_email));
                }
                break;
            case R.id.tv_skip_email:
                linear_phone.setVisibility(View.VISIBLE);
                linear_email.setVisibility(View.GONE);
                break;
            case R.id.btn_sign_in:
                goToUrl("https://wallofcoins.com/signin/" + country_code.replace("+", "") + "-" + phone_no + "/");
                break;
        }
    }

    private boolean isValidPhone() {
        if (edit_phone.getText().toString().trim().isEmpty()) {
            edit_phone.requestFocus();
            showToast(mContext.getString(R.string.please_enter_phone_no));
            return false;
        } else if (edit_phone.getText().toString().trim().length() < 10) {
            edit_phone.requestFocus();
            showToast(mContext.getString(R.string.please_enter_10_digits_phone_no));
            return false;
        }
        return true;
    }

    //add country code list for phone
    private void addCountryCodeList() {
        String json;
        try {
            InputStream is = getActivity().getAssets().open("countries.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            json = new String(buffer, "UTF-8");
        } catch (IOException ex) {
            ex.printStackTrace();
            return;
        }

        countryData = new Gson().fromJson(json, CountryData.class);

        List<String> stringList = new ArrayList<>();

        for (CountryData.CountriesBean bean : countryData.countries) {
            stringList.add(bean.name + " (" + bean.code + ")");
        }
        CustomAdapter customAdapter = new CustomAdapter(getActivity(), R.layout.spinner_row_country, countryData.countries);
        customAdapter.setDropDownViewResource(R.layout.spinner_row_country);
        sp_country.setAdapter(customAdapter);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {

    }

    /**
     * Method for check authentication type
     */
    private void checkAuth() {
        if (NetworkUtil.isOnline(mContext)) {
            String phone = ((BuyingWizardBaseActivity) mContext).buyDashPref.getPhone();
            if (!TextUtils.isEmpty(phone)) {
                linearProgress.setVisibility(View.VISIBLE);

                WallofCoins.createService(interceptor, mContext).checkAuth(phone, getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<CheckAuthResp>() {
                    @Override
                    public void onResponse(Call<CheckAuthResp> call, Response<CheckAuthResp> response) {
                        Log.d(TAG, "onResponse: response code==>>" + response.code());
                        linearProgress.setVisibility(View.GONE);
                        if (response.code() == 200) {
                            if (response.body() != null
                                    && response.body().getAvailableAuthSources() != null
                                    && response.body().getAvailableAuthSources().size() > 0) {

                                if (response.body().getAvailableAuthSources().get(0).equals("password")) {//from wesite
                                    showUserPasswordAuthenticationDialog();
                                    return;
                                } else if ((response.body().getAvailableAuthSources().size() >= 2//from mobile
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
                        linearProgress.setVisibility(View.GONE);
                        showToast(mContext.getString(R.string.try_again));
                    }
                });
            } else {
                showToast(mContext.getString(R.string.alert_phone));
            }
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));
    }

    /**
     * User authentication custom dialog for authenticate user using password
     */
    private void showUserPasswordAuthenticationDialog() {

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(mContext);
        LayoutInflater inflater = getActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.layout_authenticate_password_wallet_dialog, null);
        dialogBuilder.setView(dialogView);

        final EditText edtPassword = (EditText) dialogView.findViewById(R.id.edt_woc_authenticaion_password);
        TextView txtTitle = (TextView) dialogView.findViewById(R.id.txt_existing_user_dialog_message);
        Button btnLogin = (Button) dialogView.findViewById(R.id.btnLogin);
        Button btnForgotPassword = (Button) dialogView.findViewById(R.id.btnForgotPassword);

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
                if (password.length() > 0) {
                    getAuthTokenCall(password);
                    alertDialog.dismiss();
                } else {
                    showToast(mContext.getString(R.string.password_alert));
                }
            }
        });

    }

    /**
     * Authorized user using password or device code
     *
     * @param password
     */
    private void getAuthTokenCall(final String password) {
        if (NetworkUtil.isOnline(mContext)) {
            String phone = ((BuyingWizardBaseActivity) mContext).buyDashPref.getPhone();

            if (!TextUtils.isEmpty(phone)) {

                HashMap<String, String> getAuthTokenReq = new HashMap<String, String>();
                if (!TextUtils.isEmpty(password)) {
                    getAuthTokenReq.put(WOCConstants.KEY_PASSWORD, password);
                } else {
                    getAuthTokenReq.put(WOCConstants.KEY_DEVICECODE, getDeviceCode(mContext,
                            ((BuyingWizardBaseActivity) mContext).buyDashPref));
                }

                if (!TextUtils.isEmpty(((BuyingWizardBaseActivity) mContext).buyDashPref.getDeviceId())) {
                    getAuthTokenReq.put(WOCConstants.KEY_DEVICEID, ((BuyingWizardBaseActivity) mContext)
                            .buyDashPref.getDeviceId());
                }

                getAuthTokenReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));

                linearProgress.setVisibility(View.VISIBLE);
                WallofCoins.createService(interceptor, getActivity()).getAuthToken(phone, getAuthTokenReq)
                        .enqueue(new Callback<GetAuthTokenResp>() {
                            @Override
                            public void onResponse(Call<GetAuthTokenResp> call, Response<GetAuthTokenResp> response) {
                                linearProgress.setVisibility(View.GONE);
                                int code = response.code();

                                if (code >= 400 && response.body() == null) {
                                    try {
                                        if (!TextUtils.isEmpty(password)) {
                                            showAlertPasswordDialog();
                                        } else {
                                            createDevice();
                                        }
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        showToast(mContext.getString(R.string.try_again));
                                    }
                                    return;
                                }

                                if (!TextUtils.isEmpty(response.body().token)) {
                                    ((BuyingWizardBaseActivity) mContext).buyDashPref.setAuthToken(response.body().token);
                                }
                                if (!TextUtils.isEmpty(password) && TextUtils.isEmpty(((BuyingWizardBaseActivity) mContext)
                                        .buyDashPref.getDeviceId())) {
                                    getDevice();
                                } else {
                                    credentilasPref.addPhone(country_code + edit_phone.getText().toString().trim(),
                                            ((BuyingWizardBaseActivity) mContext).buyDashPref.getDeviceId());
                                    if (fromScreen.equalsIgnoreCase("PhoneListFragment"))
                                        ((BuyingWizardBaseActivity) mContext)
                                                .popBackAllFragmentsExcept("de.schildbach.wallet.wallofcoins.buying_wizard.phone_list.PhoneListFragment");
                                    else
                                        createHold();
                                }
                            }

                            @Override
                            public void onFailure(Call<GetAuthTokenResp> call, Throwable t) {
                                showToast(mContext.getString(R.string.try_again));
                                linearProgress.setVisibility(View.GONE);
                            }
                        });

            } else {
                showToast(mContext.getString(R.string.alert_phone_password_required));
            }
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
                createHoldPassReq.put(WOCConstants.KEY_EMAIL, email);
                createHoldPassReq.put(WOCConstants.KEY_deviceName, WOCConstants.KEY_DEVICE_NAME_VALUE);
                createHoldPassReq.put(WOCConstants.KEY_DEVICECODE,
                        getDeviceCode(mContext, ((BuyingWizardBaseActivity) mContext).buyDashPref));
            }
            createHoldPassReq.put(WOCConstants.KEY_OFFER, offerId);

            linearProgress.setVisibility(View.VISIBLE);

            WallofCoins.createService(interceptor, getActivity()).createHold(createHoldPassReq)
                    .enqueue(new Callback<CreateHoldResp>() {
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

                                    //added
                                    String phone = country_code + edit_phone.getText().toString().trim();
                                    credentilasPref.addPhone(phone, ((BuyingWizardBaseActivity) mContext).buyDashPref.getDeviceId());
                                }
                                if (!TextUtils.isEmpty(response.body().token)) {
                                    ((BuyingWizardBaseActivity) mContext).buyDashPref.setAuthToken(createHoldResp.token);
                                }
                                navigateToVerifyOtp(createHoldResp.__PURCHASE_CODE);

                            } else if (null != response.errorBody()) {
                                if (response.code() == 403 && TextUtils.isEmpty(((BuyingWizardBaseActivity) mContext)
                                        .buyDashPref.getAuthToken())) {

                                    layout_hold.setVisibility(View.VISIBLE);
                                    linear_email.setVisibility(View.GONE);
                                    linear_phone.setVisibility(View.GONE);
                                } else if (response.code() == 403 && !TextUtils.isEmpty(((BuyingWizardBaseActivity) mContext)
                                        .buyDashPref.getAuthToken())) {
                                    getHolds();
                                } else if (response.code() == 400) {
                                    if (!TextUtils.isEmpty(((BuyingWizardBaseActivity) mContext).buyDashPref.getAuthToken())) {
                                        navigateToOrderList(false);
                                    } else {
                                        layout_hold.setVisibility(View.VISIBLE);
                                        linear_email.setVisibility(View.GONE);
                                        linear_phone.setVisibility(View.GONE);
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
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                        }
                    });
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));

    }

    /**
     * navigate to otp code screen with code
     *
     * @param otp
     */
    private void navigateToVerifyOtp(String otp) {
        Bundle bundle = new Bundle();
        bundle.putString(WOCConstants.ARG_VERIFICATION_OTP, otp);
        BuyingWizardVerifycationOtpFragment otpFragment = new BuyingWizardVerifycationOtpFragment();
        otpFragment.setArguments(bundle);

        ((BuyingWizardBaseActivity) mContext).replaceFragment(otpFragment, true, true);
    }

    /**
     * Get all holds for delete active hold
     */
    private void getHolds() {
        linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).getHolds().enqueue(new Callback<List<GetHoldsResp>>() {
            @Override
            public void onResponse(Call<List<GetHoldsResp>> call, Response<List<GetHoldsResp>> response) {
                if (response.code() == 200 && response.body() != null) {
                    linearProgress.setVisibility(View.GONE);
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
    }

    private void navigateToOrderList(boolean isFromCreateHold) {
        BuyingWizardOrderHistoryFragment historyFragment = new BuyingWizardOrderHistoryFragment();
        Bundle bundle = new Bundle();
        bundle.putBoolean("isFromCreateHold", isFromCreateHold);
        historyFragment.setArguments(bundle);
        ((BuyingWizardBaseActivity) mContext).replaceFragment(historyFragment, true, true);
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
                showToast(mContext.getString(R.string.try_again));
            }
        });
    }

    /**
     * Get Devices for Register user with password
     */
    private void getDevice() {

        linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).getDevice().enqueue(new Callback<List<CreateDeviceResp>>() {
            @Override
            public void onResponse(Call<List<CreateDeviceResp>> call, Response<List<CreateDeviceResp>> response) {
                if (response.code() == 200 && response.body() != null) {
                    linearProgress.setVisibility(View.GONE);
                    List<CreateDeviceResp> deviceList = response.body();
                    if (deviceList.size() > 0) {
                        ((BuyingWizardBaseActivity) mContext).buyDashPref.setDeviceId(
                                deviceList.get(deviceList.size() - 1).getId() + "");
                        getAuthTokenCall("");
                    } else {
                        createDevice();
                    }
                }
            }

            @Override
            public void onFailure(Call<List<CreateDeviceResp>> call, Throwable t) {
                linearProgress.setVisibility(View.GONE);
                Log.e(TAG, "onFailure: ", t);
                showToast(mContext.getString(R.string.try_again));
            }
        });

    }

    /**
     * Method for register new device
     */
    private void createDevice() {
        final HashMap<String, String> createDeviceReq = new HashMap<String, String>();
        createDeviceReq.put(WOCConstants.KEY_DEVICE_NAME, mContext.getString(R.string.wallet_name));
        createDeviceReq.put(WOCConstants.KEY_DEVICE_CODE, getDeviceCode(mContext, ((BuyingWizardBaseActivity) mContext)
                .buyDashPref));
        createDeviceReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
        linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).createDevice(createDeviceReq).enqueue(new Callback<CreateDeviceResp>() {
            @Override
            public void onResponse(Call<CreateDeviceResp> call, Response<CreateDeviceResp> response) {
                if (null != response.body() && response.code() < 299) {
                    createDeviceResp = response.body();
                    ((BuyingWizardBaseActivity) mContext).buyDashPref.setDeviceId(createDeviceResp.getId() + "");
                    getAuthTokenCall("");
                } else {
                    showToast(mContext.getString(R.string.try_again));
                }
            }

            @Override
            public void onFailure(Call<CreateDeviceResp> call, Throwable t) {
                showToast(mContext.getString(R.string.try_again));
            }
        });
    }

    //this method remove animation when user want to clear whole back stack
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
