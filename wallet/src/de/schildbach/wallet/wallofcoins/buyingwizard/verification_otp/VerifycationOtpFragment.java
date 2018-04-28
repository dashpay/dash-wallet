package de.schildbach.wallet.wallofcoins.buyingwizard.verification_otp;

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

import org.bitcoinj.wallet.Wallet;

import java.util.HashMap;
import java.util.List;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseActivity;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.buy_dash_location.BuyDashLocationFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.order_history.OrderHistoryFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.utils.BuyDashAddressPref;
import de.schildbach.wallet.wallofcoins.buyingwizard.utils.FragmentUtils;
import de.schildbach.wallet.wallofcoins.response.BuyDashErrorResp;
import de.schildbach.wallet.wallofcoins.response.CaptureHoldResp;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created on 08-Mar-18.
 */

public class VerifycationOtpFragment extends BuyDashBaseFragment implements View.OnClickListener, SharedPreferences.OnSharedPreferenceChangeListener {


    private View rootView;
    private Button button_verify_otp;
    private EditText et_otp;
    private LinearLayout linearProgress;
    private final String TAG = "VerifycationOtpFragment";
    private String otp = "", keyAddress = "";
    private Wallet wallet;
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
            rootView = inflater.inflate(R.layout.fragment_buy_dash_verification_otp, container, false);
            init();
            setListeners();
            handleArgs();
            return rootView;
        } else
            return rootView;
    }

    private void init() {
        this.application = (WalletApplication) getActivity().getApplication();
        this.wallet = application.getWallet();
        //this.loaderManager = getLoaderManager();

        BuyDashAddressPref dashAddressPref= new BuyDashAddressPref(PreferenceManager.getDefaultSharedPreferences(mContext));
        //keyAddress = wallet.freshAddress(RECEIVE_FUNDS).toBase58();
        keyAddress=dashAddressPref.getBuyDashAddress();
         Log.e("------------------",keyAddress);
        linearProgress = (LinearLayout) rootView.findViewById(R.id.linear_progress);
        button_verify_otp = (Button) rootView.findViewById(R.id.button_verify_otp);
        et_otp = (EditText) rootView.findViewById(R.id.et_otp);
    }

    private void setListeners() {
        button_verify_otp.setOnClickListener(this);
    }

    private void handleArgs() {
        if (getArguments() != null)
            otp = getArguments().getString(WOCConstants.VERIFICATION_OTP);

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

        hideKeyBoard();
        HashMap<String, String> captureHoldReq = new HashMap<String, String>();
        String otp = et_otp.getText().toString().trim();

        if (TextUtils.isEmpty(otp)) {
            Toast.makeText(getContext(), R.string.alert_purchase_code, Toast.LENGTH_LONG).show();
            return;
        }

        captureHoldReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
        captureHoldReq.put(WOCConstants.KEY_VERIFICATION_CODE, otp);
        linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).captureHold((
                (BuyDashBaseActivity) mContext).buyDashPref.getHoldId(), captureHoldReq)
                .enqueue(new Callback<List<CaptureHoldResp>>() {
                    @Override
                    public void onResponse(Call<List<CaptureHoldResp>> call, final Response<List<CaptureHoldResp>> response) {
                        linearProgress.setVisibility(View.GONE);
                        ((BuyDashBaseActivity) mContext).buyDashPref.setHoldId("");
                        ((BuyDashBaseActivity) mContext).buyDashPref.setCreateHoldResp(null);
                        Log.e(TAG, "onResponse: " + ((BuyDashBaseActivity) mContext).buyDashPref.getHoldId() + " here");
                        if (null != response && null != response.body() && !response.body().isEmpty()) {
                            if (response.body().get(0).account != null && !TextUtils.isEmpty(response.body().get(0).account)) {
                                updateAddressBookValue(keyAddress, "WallofCoins.com - Order " + response.body().get(0).id);
                                navigateToOrderList(true);
                                // getOrderList(true);
                            } else {
                                //getOrderList(true);
                                navigateToOrderList(true);
                            }

                            // hideViewExcept(binding.scrollCompletionDetail);

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
                                                // hideViewExcept(binding.layoutCreateHold);
                                                navigateToLocation();

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
                            linearProgress.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onFailure
                            (Call<List<CaptureHoldResp>> call, Throwable t) {
                        linearProgress.setVisibility(View.GONE);
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                        Log.e(TAG, "onFailure: ", t);
                    }
                });
    }

    /**
     * create hold screen
     *
     * @param
     */
    private void navigateToLocation() {
        BuyDashLocationFragment locationFragment = new BuyDashLocationFragment();
        ((BuyDashBaseActivity) mContext).replaceFragment(locationFragment, true, true);
    }

    private void navigateToOrderList(boolean isFromCreateHold) {
        OrderHistoryFragment historyFragment = new OrderHistoryFragment();
        Bundle bundle = new Bundle();
        bundle.putBoolean("isFromCreateHold", isFromCreateHold);
        historyFragment.setArguments(bundle);
        ((BuyDashBaseActivity) mContext).replaceFragment(historyFragment, true, true);
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
