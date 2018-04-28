package de.schildbach.wallet.wallofcoins.selling_wizard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.google.common.base.Charsets;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingApiConstants;
import de.schildbach.wallet.wallofcoins.selling_wizard.storage.SharedPreferenceUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.SellingConstants;
import de.schildbach.wallet_test.R;
import okhttp3.Interceptor;
import okhttp3.Request;

/**
 * Created by  on 03-Apr-18.
 */

public class SellingBaseFragment extends Fragment {

    protected Context mContext;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

    }

    /**
     * Method for Open web url link in external browser app
     *
     * @param url
     */
    protected void goToUrl(String url) {
        Uri uriUrl = Uri.parse(url);
        Intent launchBrowser = new Intent(Intent.ACTION_VIEW, uriUrl);
        startActivity(launchBrowser);
    }

    /**
     * get country code form current latitude & longitude
     *
     * @return Country Code
     */
    protected String getCountryCode(double latitude, double longitude) {
        String countryCode = "";
        try {
            Geocoder geo = new Geocoder(mContext, Locale.getDefault());
            List<Address> addresses = geo.getFromLocation(latitude, longitude, 1);
            Address obj = addresses.get(0);
            countryCode = obj.getCountryCode();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (countryCode.equals("")) {
            return "us";
        } else {
            return countryCode;
        }
    }

    /**
     * Method for hide keyboard
     */
    protected void hideKeyBoard() {
        View view = getActivity().getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }


    @SuppressLint("HardwareIds")
    protected String getDeviceCode(Context context) {

        String deviceUID = SharedPreferenceUtil.getString(SellingConstants.PREF_DEVICE_CODE, "");
        if (TextUtils.isEmpty(deviceUID)) {
            String deviceID;
            deviceID = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            byte[] data = (deviceID + deviceID + deviceID).getBytes(Charsets.UTF_8);
            deviceUID = Base64.encodeToString(data, Base64.DEFAULT).substring(0, 39);
            SharedPreferenceUtil.putValue(SellingConstants.PREF_DEVICE_CODE, deviceUID);
        }

        return deviceUID;
    }

    protected void showToast(String msg) {
        Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * Validate Email id
     *
     * @param target Email
     * @return boolean for email valid or not
     */
    protected boolean isValidEmail(CharSequence target) {
        if (target == null) {
            return false;
        } else {
            return android.util.Patterns.EMAIL_ADDRESS.matcher(target).matches();
        }
    }

    /**
     * Show alert dialog  wrong username or password
     */
    protected void showAlertPasswordDialog() {
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

    /**
     * API Header parameter interceptor
     */
    protected Interceptor interceptor = new Interceptor() {
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            Request original = chain.request();
            // Request customization: add request headers
            Request.Builder requestBuilder = original.newBuilder();
            if (!TextUtils.isEmpty(SharedPreferenceUtil.getString(SellingConstants.PREF_TOKEN_ID, ""))) {
                requestBuilder.addHeader(SellingApiConstants.KEY_HEADER_AUTH_TOKEN,
                        SharedPreferenceUtil.getString(SellingConstants.PREF_TOKEN_ID, ""));
            }
            requestBuilder.addHeader(SellingApiConstants.KEY_HEADER_PUBLISHER_ID,
                    SellingApiConstants.WALLOFCOINS_PUBLISHER_ID);
            requestBuilder.addHeader(SellingApiConstants.KEY_HEADER_CONTENT_TYPE,
                    SellingApiConstants.KEY_HEADER_CONTENT_TYPE_VALUE);
            Request request = requestBuilder.build();
            return chain.proceed(request);
        }
    };
}


