package de.schildbach.wallet.wallofcoins.buying_wizard.verification_otp;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.google.gson.Gson;
import java.util.HashMap;
import java.util.List;
import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.buying_wizard.BuyingWizardBaseActivity;
import de.schildbach.wallet.wallofcoins.buying_wizard.BuyingWizardBaseFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.location.BuyingWizardLocationFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.order_history.BuyingWizardOrderHistoryFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.BuyingWizardAddressPref;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.BuyingWizardFragmentUtils;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.NetworkUtil;
import de.schildbach.wallet.wallofcoins.response.CaptureHoldResp;
import de.schildbach.wallet.wallofcoins.response.ErrorResp;


import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created on 08-Mar-18.
 */

public class BuyingWizardVerifycationOtpFragment extends BuyingWizardBaseFragment implements
        View.OnClickListener, SharedPreferences.OnSharedPreferenceChangeListener {


    private View rootView;
    private Button button_verify_otp;
    private EditText et_otp;
    private LinearLayout linearProgress;
    private final String TAG = "VerifycationOtpFragment";
    private String otp = "", keyAddress = "";

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_buying_verification_otp, container, false);
            init();
            setListeners();
            readBundle(getArguments());
            return rootView;
        } else
            return rootView;
    }

    private void init() {

        BuyingWizardAddressPref addressPref =
                new BuyingWizardAddressPref(PreferenceManager.getDefaultSharedPreferences(mContext));
        keyAddress = addressPref.getBuyCoinAddress();
        linearProgress = (LinearLayout) rootView.findViewById(R.id.linear_progress);
        button_verify_otp = (Button) rootView.findViewById(R.id.button_verify_otp);
        et_otp = (EditText) rootView.findViewById(R.id.et_otp);
    }

    private void setListeners() {
        button_verify_otp.setOnClickListener(this);
    }

    //Read data from bundle
    private void readBundle(Bundle bundle) {
        if (bundle != null)
            otp = bundle.getString(WOCConstants.ARG_VERIFICATION_OTP);

        et_otp.setText(otp);
    }

    @Override
    public void onClick(View view) {

        switch (view.getId()) {
            case R.id.button_verify_otp:
                verifyOTP();
                break;
        }
    }


    /**
     * API call for call for Capture Hold @POST("api/v1/holds/{id}/capture/")
     */
    private void verifyOTP() {

        if (NetworkUtil.isOnline(mContext)) {
            hideKeyBoard();
            HashMap<String, String> captureHoldReq = new HashMap<String, String>();
            String otp = et_otp.getText().toString().trim();

            if (TextUtils.isEmpty(otp)) {
                showToast(mContext.getString(R.string.alert_purchase_code));
                return;
            }

            captureHoldReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
            captureHoldReq.put(WOCConstants.KEY_VERIFICATION_CODE, otp);
            linearProgress.setVisibility(View.VISIBLE);
            WallofCoins.createService(interceptor, getActivity()).captureHold((
                    (BuyingWizardBaseActivity) mContext).buyDashPref.getHoldId(), captureHoldReq)
                    .enqueue(new Callback<List<CaptureHoldResp>>() {
                        @Override
                        public void onResponse(Call<List<CaptureHoldResp>> call, final Response<List<CaptureHoldResp>> response) {
                            linearProgress.setVisibility(View.GONE);
                            ((BuyingWizardBaseActivity) mContext).buyDashPref.setHoldId("");
                            ((BuyingWizardBaseActivity) mContext).buyDashPref.setCreateHoldResp(null);
                            Log.e(TAG, "onResponse: " + ((BuyingWizardBaseActivity) mContext)
                                    .buyDashPref.getHoldId() + " here");
                            if (null != response && null != response.body() && !response.body().isEmpty()) {
                                if (response.body().get(0).account != null && !TextUtils.isEmpty(response.body().get(0).account)) {
                                    updateAddressBookValue(keyAddress, "WallofCoins.com - Order " +
                                            response.body().get(0).id);
                                    navigateToOrderList(true);
                                } else {
                                    navigateToOrderList(true);
                                }
                            } else if (null != response && null != response.errorBody()) {
                                linearProgress.setVisibility(View.GONE);

                                if (response.code() == 404) {
                                    AlertDialog.Builder builder;
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        builder = new AlertDialog.Builder(mContext, android.R.style.Theme_Material_Dialog_Alert);
                                    } else {
                                        builder = new AlertDialog.Builder(mContext);
                                    }
                                    builder.setTitle(getString(R.string.alert_title_purchase_code))
                                            .setMessage(getString(R.string.alert_description_purchase_code))
                                            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                                public void onClick(DialogInterface dialog, int which) {
                                                    navigateToLocation();
                                                }
                                            })
                                            .show();
                                } else {
                                    try {
                                        ErrorResp errorResp = new Gson().fromJson(response.errorBody().string(), ErrorResp.class);
                                        Toast.makeText(getContext(), errorResp.detail, Toast.LENGTH_LONG).show();
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        showToast(mContext.getString(R.string.try_again));
                                    }
                                }
                            } else {
                                showToast(mContext.getString(R.string.try_again));
                                linearProgress.setVisibility(View.GONE);
                            }
                        }

                        @Override
                        public void onFailure
                                (Call<List<CaptureHoldResp>> call, Throwable t) {
                            linearProgress.setVisibility(View.GONE);
                            showToast(mContext.getString(R.string.try_again));
                            Log.e(TAG, "onFailure: ", t);
                        }
                    });
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));

    }

    /**
     * create hold screen
     *
     * @param
     */
    private void navigateToLocation() {
        BuyingWizardLocationFragment locationFragment = new BuyingWizardLocationFragment();
        ((BuyingWizardBaseActivity) mContext).replaceFragment(locationFragment, true, true);
    }

    private void navigateToOrderList(boolean isFromCreateHold) {
        BuyingWizardOrderHistoryFragment historyFragment = new BuyingWizardOrderHistoryFragment();
        Bundle bundle = new Bundle();
        bundle.putBoolean("isFromCreateHold", isFromCreateHold);
        historyFragment.setArguments(bundle);
        ((BuyingWizardBaseActivity) mContext).replaceFragment(historyFragment, true, true);
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
