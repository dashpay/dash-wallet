package de.schildbach.wallet.wallofcoins.buyingwizard.email_phone;

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

import de.schildbach.wallet.wallofcoins.BuyDashPref;
import de.schildbach.wallet.wallofcoins.CustomAdapter;
import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseActivity;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.order_history.OrderHistoryFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.verification_otp.VerifycationOtpFragment;
import de.schildbach.wallet.wallofcoins.response.BuyDashErrorResp;
import de.schildbach.wallet.wallofcoins.response.CheckAuthResp;
import de.schildbach.wallet.wallofcoins.response.CountryData;
import de.schildbach.wallet.wallofcoins.response.CreateDeviceResp;
import de.schildbach.wallet.wallofcoins.response.CreateHoldResp;
import de.schildbach.wallet.wallofcoins.response.GetAuthTokenResp;
import de.schildbach.wallet.wallofcoins.response.GetHoldsResp;
import de.schildbach.wallet_test.R;
import okhttp3.Interceptor;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created on 08-Mar-18.
 */

public class EmailAndPhoneFragment extends BuyDashBaseFragment implements View.OnClickListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private final String TAG = "EmailAndPhoneFragment";
    private View rootView;
    private LinearLayout linearProgress, linear_email, linear_phone, layout_hold;
    private CountryData countryData;
    private Spinner sp_country;
    private Button btn_next_phone, btn_next_email, btn_sign_in;
    private ImageView imgViewToolbarBack;
    private String country_code = "", phone_no = "", email = "", password = "", offerId = "";
    private EditText edit_buy_dash_phone, edit_buy_dash_email;
    private BuyDashPref buyDashPref;
    private TextView tv_skip_email;
    private CreateDeviceResp createDeviceResp;
    private CreateHoldResp createHoldResp;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.layout_email_and_phone, container, false);
            init();
            addCountryCodeList();
            setListeners();
            handleArgs();
            return rootView;
        } else
            return rootView;
    }

    private void init() {

        this.buyDashPref = new BuyDashPref(PreferenceManager.getDefaultSharedPreferences(mContext));
        buyDashPref.registerOnSharedPreferenceChangeListener(this);

        imgViewToolbarBack = (ImageView) rootView.findViewById(R.id.imgViewToolbarBack);
        linearProgress = (LinearLayout) rootView.findViewById(R.id.linear_progress);
        linear_email = (LinearLayout) rootView.findViewById(R.id.linear_email);
        sp_country = (Spinner) rootView.findViewById(R.id.sp_country);
        btn_next_phone = (Button) rootView.findViewById(R.id.btn_next_phone);
        edit_buy_dash_phone = (EditText) rootView.findViewById(R.id.edit_buy_dash_phone);
        btn_next_email = (Button) rootView.findViewById(R.id.btn_next_email);
        edit_buy_dash_email = (EditText) rootView.findViewById(R.id.edit_buy_dash_email);
        linear_phone = (LinearLayout) rootView.findViewById(R.id.linear_phone);
        layout_hold = (LinearLayout) rootView.findViewById(R.id.layout_hold);
        tv_skip_email = (TextView) rootView.findViewById(R.id.tv_skip_email);
        btn_sign_in = (Button) rootView.findViewById(R.id.btn_sign_in);
    }

    private void setListeners() {
        btn_next_phone.setOnClickListener(this);
        imgViewToolbarBack.setOnClickListener(this);
        btn_next_email.setOnClickListener(this);
        tv_skip_email.setOnClickListener(this);
        btn_sign_in.setOnClickListener(this);
    }

    /**
     * handle the arguments according to user come from previos screen
     */
    private void handleArgs() {
        if (getArguments() != null) {
            if (getArguments().containsKey(WOCConstants.OFFER_ID)) {
                offerId = getArguments().getString(WOCConstants.OFFER_ID);
            }
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.imgViewToolbarBack:

                if (linear_email.getVisibility() == View.VISIBLE)
                    ((BuyDashBaseActivity) mContext).popbackFragment();
                else if (linear_phone.getVisibility() == View.GONE) {
                    layout_hold.setVisibility(View.GONE);
                    linear_phone.setVisibility(View.VISIBLE);
                    linear_email.setVisibility(View.GONE);

                } else if (linear_email.getVisibility() == View.GONE) {
                    linear_email.setVisibility(View.VISIBLE);
                    linear_phone.setVisibility(View.GONE);
                    layout_hold.setVisibility(View.GONE);
                }

                break;
            case R.id.btn_next_phone:
                country_code = countryData.countries.get(sp_country.getSelectedItemPosition()).code;
                phone_no = edit_buy_dash_phone.getText().toString().trim();
                String phone = country_code + edit_buy_dash_phone.getText().toString().trim();
                buyDashPref.setPhone(phone);
                hideKeyBoard();
                checkAuth();
                break;

            case R.id.btn_next_email:
                if (!edit_buy_dash_email.getText().toString().isEmpty() && isValidEmail(edit_buy_dash_email.getText().toString())) {
                    email = edit_buy_dash_email.getText().toString();
                    linear_phone.setVisibility(View.VISIBLE);
                    linear_email.setVisibility(View.GONE);
                    // hideViewExcept(binding.linearPhone);
                } else {
                    Toast.makeText(mContext, R.string.alert_enter_valid_email, Toast.LENGTH_LONG).show();
                }
                break;
            case R.id.tv_skip_email:
              /*  binding.tvSkipEmail.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        hideViewExcept(binding.linearPhone);
                    }
                });*/
                linear_phone.setVisibility(View.VISIBLE);
                linear_email.setVisibility(View.GONE);
                break;
            case R.id.btn_sign_in:
                goToUrl("https://wallofcoins.com/signin/" + country_code.replace("+", "") + "-" + phone_no + "/");
                break;
        }
    }

    //add country code list for phone
    private void addCountryCodeList() {
        String json;
        try {
            InputStream is = mContext.getAssets().open("countries.json");
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
     * Method for check authentication type
     */
    private void checkAuth() {
        String phone = buyDashPref.getPhone();
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
                            if (response.body().getAvailableAuthSources().get(0).equals("password")) {
                                showUserPasswordAuthenticationDialog();
                                return;
                            } else if ((response.body().getAvailableAuthSources().size() >= 2
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
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Toast.makeText(getContext(), R.string.alert_phone, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * User authentication custom dialog for authenticate user using password
     */
    private void showUserPasswordAuthenticationDialog() {

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(mContext);
        LayoutInflater inflater = getActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.authenticate_password_wallet_dialog, null);
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
                    Toast.makeText(getContext(), R.string.password_alert, Toast.LENGTH_SHORT).show();
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
        String phone = buyDashPref.getPhone();

        if (!TextUtils.isEmpty(phone)) {

            HashMap<String, String> getAuthTokenReq = new HashMap<String, String>();
            if (!TextUtils.isEmpty(password)) {
                getAuthTokenReq.put(WOCConstants.KEY_PASSWORD, password);
            } else {
                getAuthTokenReq.put(WOCConstants.KEY_DEVICECODE, getDeviceCode(mContext, buyDashPref));
            }

            if (!TextUtils.isEmpty(buyDashPref.getDeviceId())) {
                getAuthTokenReq.put(WOCConstants.KEY_DEVICEID, buyDashPref.getDeviceId());
            }

            getAuthTokenReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));

            linearProgress.setVisibility(View.VISIBLE);
            WallofCoins.createService(interceptor, getActivity()).getAuthToken(phone, getAuthTokenReq).enqueue(new Callback<GetAuthTokenResp>() {
                @Override
                public void onResponse(Call<GetAuthTokenResp> call, Response<GetAuthTokenResp> response) {
                    linearProgress.setVisibility(View.GONE);
                    int code = response.code();

                    if (code >= 400 && response.body() == null) {
                        try {
                            BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                            if (!TextUtils.isEmpty(password)) {
                                showAlertPasswordDialog();
                            } else {
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
                    //hideViewExcept(null);
                    if (!TextUtils.isEmpty(password) && TextUtils.isEmpty(buyDashPref.getDeviceId())) {
                        getDevice();
                    } else {
                        createHold();
                    }
                }

                @Override
                public void onFailure(Call<GetAuthTokenResp> call, Throwable t) {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                    linearProgress.setVisibility(View.GONE);
                }
            });

        } else {
            Toast.makeText(getContext(), R.string.alert_phone_password_required, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Method for create new hold
     */
    public void createHold() {
        String phone = buyDashPref.getPhone();

        final HashMap<String, String> createHoldPassReq = new HashMap<String, String>();

        if (TextUtils.isEmpty(buyDashPref.getAuthToken())) {
            createHoldPassReq.put(WOCConstants.KEY_PHONE, phone);
            createHoldPassReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
            createHoldPassReq.put(WOCConstants.KEY_EMAIL, email);
            createHoldPassReq.put(WOCConstants.KEY_deviceName, WOCConstants.KEY_DEVICE_NAME_VALUE);
            createHoldPassReq.put(WOCConstants.KEY_DEVICECODE, getDeviceCode(mContext, buyDashPref));
        }
        createHoldPassReq.put(WOCConstants.KEY_OFFER, offerId);

        linearProgress.setVisibility(View.VISIBLE);

        WallofCoins.createService(interceptor, getActivity()).createHold(createHoldPassReq).enqueue(new Callback<CreateHoldResp>() {
            @Override
            public void onResponse(Call<CreateHoldResp> call, Response<CreateHoldResp> response) {
                linearProgress.setVisibility(View.GONE);

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
                    navigateToVerifyOtp(createHoldResp.__PURCHASE_CODE);
                    //hideViewExcept(binding.layoutVerifyOtp);
                    //clearForm((ViewGroup) binding.getRoot());
                    //binding.etOtp.setText(createHoldResp.__PURCHASE_CODE);

                } else if (null != response.errorBody()) {
                    if (response.code() == 403 && TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                        layout_hold.setVisibility(View.VISIBLE);
                        linear_email.setVisibility(View.GONE);
                        linear_phone.setVisibility(View.GONE);
                        //hideViewExcept(binding.layoutHold);
                        //clearForm((ViewGroup) binding.getRoot());
                    } else if (response.code() == 403 && !TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                        getHolds();
                    } else if (response.code() == 400) {
                        if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                            navigateToOrderList(false);
                        } else {
                            layout_hold.setVisibility(View.VISIBLE);
                            linear_email.setVisibility(View.GONE);
                            linear_phone.setVisibility(View.GONE);
                            // hideViewExcept(binding.layoutHold);
                            //clearForm((ViewGroup) binding.getRoot());
                        }
                    } else {
                        try {
                            if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
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

    private void navigateToVerifyOtp(String otp) {
        Bundle bundle = new Bundle();
        bundle.putString(WOCConstants.VERIFICATION_OTP, otp);
        VerifycationOtpFragment otpFragment = new VerifycationOtpFragment();
        otpFragment.setArguments(bundle);

        ((BuyDashBaseActivity) mContext).replaceFragment(otpFragment, true, false,
                "VerifycationOtpFragment");
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
                            //getOrderList(false);
                            navigateToOrderList(false);
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

    private void navigateToOrderList(boolean isFromCreateHold) {
        OrderHistoryFragment historyFragment = new OrderHistoryFragment();
        Bundle bundle = new Bundle();
        bundle.putBoolean("isFromCreateHold", isFromCreateHold);
        historyFragment.setArguments(bundle);
        ((BuyDashBaseActivity) mContext).replaceFragment(historyFragment, true, true,
                "OrderHistoryFragment");
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

    /**
     * Get Devices for Register user with password
     */
    private void getDevice() {

        linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).getDevice().enqueue(new Callback<List<CreateDeviceResp>>() {
            @Override
            public void onResponse(Call<List<CreateDeviceResp>> call, Response<List<CreateDeviceResp>> response) {
                if (response.code() == 200 && response.body() != null) {
                    List<CreateDeviceResp> deviceList = response.body();
                    if (deviceList.size() > 0) {
                        buyDashPref.setDeviceId(deviceList.get(deviceList.size() - 1).getId() + "");
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
        createDeviceReq.put(WOCConstants.KEY_DEVICE_CODE, getDeviceCode(mContext, buyDashPref));
        createDeviceReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
        linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).createDevice(createDeviceReq).enqueue(new Callback<CreateDeviceResp>() {
            @Override
            public void onResponse(Call<CreateDeviceResp> call, Response<CreateDeviceResp> response) {
                if (null != response.body() && response.code() < 299) {
                    createDeviceResp = response.body();
                    buyDashPref.setDeviceId(createDeviceResp.getId() + "");
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
}
