package de.schildbach.wallet.wallofcoins.buyingwizard.buy_dash_location;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.base.Charsets;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import de.schildbach.wallet.wallofcoins.BuyDashPref;
import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseActivity;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.offer_amount.BuyDashOfferAmountFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.order_history.OrderHistoryFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.verification_otp.VerifycationOtpFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.zip.BuyDashZipFragment;
import de.schildbach.wallet.wallofcoins.response.BuyDashErrorResp;
import de.schildbach.wallet.wallofcoins.response.CheckAuthResp;
import de.schildbach.wallet.wallofcoins.response.CreateDeviceResp;
import de.schildbach.wallet.wallofcoins.response.GetAuthTokenResp;
import de.schildbach.wallet_test.R;
import okhttp3.Interceptor;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created on 6/3/18.
 */

public class BuyDashLocationFragment extends BuyDashBaseFragment implements View.OnClickListener,
        SharedPreferences.OnSharedPreferenceChangeListener {


    private View rootView;
    private Button button_buy_dash_get_location, button_buy_dash_get_location_no, btn_sign_out_woc, btn_order_history_woc;
    private TextView txtViewLocationMessage, text_message_sign_out;
    private ImageView imgViewToolbarBack;
    private String zipCode, password;
    private double latitude, longitude;
    private final String TAG = "BuyDashOfferAmtFragment";
    private BuyDashPref buyDashPref;
    private LinearLayout layout_sign_out, linear_progress;
    private CreateDeviceResp createDeviceResp;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {

        if (rootView == null) {
            rootView = inflater.inflate(R.layout.layout_buy_dash_location, container, false);
            init();
            setListeners();

            /*if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                if (!TextUtils.isEmpty(buyDashPref.getHoldId())) {
                    CreateHoldResp createHoldResp = buyDashPref.getCreateHoldResp();
                    //binding.etOtp.setText(createHoldResp.__PURCHASE_CODE);
                    // hideViewExcept(binding.layoutVerifyOtp);
                    navigateToVerifyOtp(createHoldResp.__PURCHASE_CODE);
                } else {
                    //hideViewExcept(binding.rvOrderList);
                    //getOrderList(false);
                    navigateToOrderList(false);
                }
            }*/

            return rootView;
        } else
            return rootView;
    }

    private void init() {
        this.buyDashPref = new BuyDashPref(PreferenceManager.getDefaultSharedPreferences(mContext));

        button_buy_dash_get_location_no = (Button) rootView.findViewById(R.id.button_buy_dash_get_location_no);
        button_buy_dash_get_location = (Button) rootView.findViewById(R.id.button_buy_dash_get_location);
        txtViewLocationMessage = (TextView) rootView.findViewById(R.id.txtViewLocationMessage);
        imgViewToolbarBack = (ImageView) rootView.findViewById(R.id.imgViewToolbarBack);
        btn_sign_out_woc = (Button) rootView.findViewById(R.id.btn_sign_out_woc);
        text_message_sign_out = (TextView) rootView.findViewById(R.id.text_message_sign_out);
        layout_sign_out = (LinearLayout) rootView.findViewById(R.id.layout_sign_out);
        linear_progress = (LinearLayout) rootView.findViewById(R.id.linear_progress);
        btn_order_history_woc = (Button) rootView.findViewById(R.id.btn_order_history_woc);
    }

    private void setListeners() {
        button_buy_dash_get_location.setOnClickListener(this);
        button_buy_dash_get_location_no.setOnClickListener(this);
        imgViewToolbarBack.setOnClickListener(this);
        btn_sign_out_woc.setOnClickListener(this);
        buyDashPref.registerOnSharedPreferenceChangeListener(this);
        btn_order_history_woc.setOnClickListener(this);
    }

    private void navigateToVerifyOtp(String otp) {
        Bundle bundle = new Bundle();
        bundle.putString(WOCConstants.VERIFICATION_OTP, otp);
        VerifycationOtpFragment otpFragment = new VerifycationOtpFragment();
        otpFragment.setArguments(bundle);

        ((BuyDashBaseActivity) mContext).replaceFragment(otpFragment, true, true,
                "VerifycationOtpFragment");
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.button_buy_dash_get_location:
                if (checkPermissions())
                    getZip();
                else
                    requestLocationPermission();
                break;

            case R.id.button_buy_dash_get_location_no:
                ((BuyDashBaseActivity) mContext).replaceFragment(new BuyDashZipFragment(), true, true,
                        "BuyDashZipFragment");
                break;

            case R.id.imgViewToolbarBack:
                ((BuyDashBaseActivity) mContext).finishBaseActivity();
                break;
            case R.id.btn_sign_out_woc:
                deleteAuthCall(false);
                break;
            case R.id.btn_order_history_woc:
                navigateToOrderList(false);
                break;


        }
    }

    private void navigateToOrderList(boolean isFromCreateHold) {
        OrderHistoryFragment historyFragment = new OrderHistoryFragment();
        Bundle bundle = new Bundle();
        bundle.putBoolean("isFromCreateHold", isFromCreateHold);
        historyFragment.setArguments(bundle);
        ((BuyDashBaseActivity) mContext).replaceFragment(historyFragment, true, true,
                "OrderHistoryFragment");
    }

    @Override
    public void onResume() {
        super.onResume();
     /*   if (!TextUtils.isEmpty(buyDashPref.getAuthToken())) {
            layout_sign_out.setVisibility(View.VISIBLE);
            text_message_sign_out.setText("Your wallet is signed into Wall of Coins using " +
                    "your mobile number " + buyDashPref.getPhone());
        } else {
            layout_sign_out.setVisibility(View.GONE);
        }*/
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
                getAuthTokenReq.put(WOCConstants.KEY_DEVICECODE, getDeviceCode(mContext));
            }

            if (!TextUtils.isEmpty(buyDashPref.getDeviceId())) {
                getAuthTokenReq.put(WOCConstants.KEY_DEVICEID, buyDashPref.getDeviceId());
            }

            getAuthTokenReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));

            linear_progress.setVisibility(View.VISIBLE);
            WallofCoins.createService(interceptor, getActivity()).getAuthToken(phone, getAuthTokenReq).enqueue(new Callback<GetAuthTokenResp>() {
                @Override
                public void onResponse(Call<GetAuthTokenResp> call, Response<GetAuthTokenResp> response) {
                    linear_progress.setVisibility(View.GONE);
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
                    // hideViewExcept(null);
                    if (!TextUtils.isEmpty(password) && TextUtils.isEmpty(buyDashPref.getDeviceId())) {
                        getDevice();
                    } else {
                        //createHold();
                    }
                }

                @Override
                public void onFailure(Call<GetAuthTokenResp> call, Throwable t) {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                    linear_progress.setVisibility(View.GONE);
                }
            });

        } else {
            Toast.makeText(getContext(), R.string.alert_phone_password_required, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Get Devices for Register user with password
     */
    private void getDevice() {

        linear_progress.setVisibility(View.VISIBLE);
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
                linear_progress.setVisibility(View.GONE);
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
        linear_progress.setVisibility(View.VISIBLE);
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

    /**
     * Show alert dialog  wrong username or password
     *//*
    private void showAlertPasswordDialog() {
        AlertDialog.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder = new AlertDialog.Builder(mContext, android.R.style.Theme_Material_Dialog_Alert);
        } else {
            builder = new AlertDialog.Builder(mContext);
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
*/
    @SuppressLint("HardwareIds")
    private String getDeviceCode(Context context) {

        String deviceUID = buyDashPref.getDeviceCode();
        if (TextUtils.isEmpty(deviceUID)) {
            String deviceID;
            deviceID = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            byte[] data = (deviceID + deviceID + deviceID).getBytes(Charsets.UTF_8);
            deviceUID = Base64.encodeToString(data, Base64.DEFAULT).substring(0, 39);
            buyDashPref.setDeviceCode(deviceUID);
        }

        return deviceUID;
    }

    /**
     * Method for check authentication type
     */
    private void checkAuth() {
        String phone = buyDashPref.getPhone();
        if (!TextUtils.isEmpty(phone)) {
            linear_progress.setVisibility(View.VISIBLE);

            WallofCoins.createService(interceptor, mContext).checkAuth(phone, getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<CheckAuthResp>() {
                @Override
                public void onResponse(Call<CheckAuthResp> call, Response<CheckAuthResp> response) {
                    Log.d(TAG, "onResponse: response code==>>" + response.code());
                    linear_progress.setVisibility(View.GONE);
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
                                //createHold();
                            }
                        }
                    } else if (response.code() == 404) {
                        hideKeyBoard();
                        //createHold();
                    }
                }

                @Override
                public void onFailure(Call<CheckAuthResp> call, Throwable t) {
                    linear_progress.setVisibility(View.GONE);
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Toast.makeText(getContext(), R.string.alert_phone, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Method for singout user
     *
     * @param isPendingHold
     */
    public void deleteAuthCall(final boolean isPendingHold) {
        final String phone = buyDashPref.getPhone();
        if (!TextUtils.isEmpty(phone)) {
            linear_progress.setVisibility(View.VISIBLE);
            password = "";
            WallofCoins.createService(interceptor, mContext)
                    .deleteAuth(phone, getString(R.string.WALLOFCOINS_PUBLISHER_ID))
                    .enqueue(new Callback<CheckAuthResp>() {
                        @Override
                        public void onResponse(Call<CheckAuthResp> call, Response<CheckAuthResp> response) {
                            Log.d(TAG, "onResponse: response code==>>" + response.code());
                            linear_progress.setVisibility(View.GONE);
                            if (response.code() < 299) {
                                buyDashPref.setAuthToken("");
                                password = "";
                                buyDashPref.clearAllPrefrance();
                                if (isPendingHold) {
                                    // binding.editBuyDashPhone.setText(null);
                                    checkAuth();
                                } else {
                                    Toast.makeText(mContext, R.string.alert_sign_out, Toast.LENGTH_LONG).show();
                                    //hideViewExcept(binding.layoutLocation);

                                }
                            } else {
                                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<CheckAuthResp> call, Throwable t) {
                            linear_progress.setVisibility(View.GONE);
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                        }
                    });
        } else {
            Toast.makeText(getContext(), R.string.alert_phone, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Get ZipCode for device current location
     */
    private void getZip() {

        Location myLocation = getLastKnownLocation();
        if (myLocation != null) {

            Geocoder geocoder;
            List<Address> addresses;
            geocoder = new Geocoder(mContext, Locale.getDefault());
            if (geocoder != null) {
                try {
                    addresses = geocoder.getFromLocation(myLocation.getLatitude(), myLocation.getLongitude(), 1); // Here 1 represent max location result to returned, by documents it recommended 1 to 5
                    latitude = myLocation.getLatitude();
                    longitude = myLocation.getLongitude();
                    zipCode = addresses.get(0).getPostalCode();
                    navigateToOtherScreen();

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } else {
            LocationManager mlocManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
            boolean enabled = mlocManager.isProviderEnabled(LocationManager.GPS_PROVIDER);

            if (!enabled) {
                showDialogGPS();
            }
        }
    }

    private void navigateToOtherScreen() {
        Bundle bundle = new Bundle();
        bundle.putDouble(WOCConstants.LATITUDE, latitude);
        bundle.putDouble(WOCConstants.LONGITUDE, longitude);
        bundle.putString(WOCConstants.ZIP, zipCode);
        BuyDashOfferAmountFragment offerAmountFragment = new BuyDashOfferAmountFragment();
        offerAmountFragment.setArguments(bundle);

        ((BuyDashBaseActivity) mContext).replaceFragment(offerAmountFragment, true, true, "BuyDashOfferAmountFragment");
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {

    }
}
