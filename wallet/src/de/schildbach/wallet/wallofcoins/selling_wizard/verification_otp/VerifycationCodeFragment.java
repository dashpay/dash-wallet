package de.schildbach.wallet.wallofcoins.selling_wizard.verification_otp;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;

import java.util.HashMap;

import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseActivity;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.utils.NetworkUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingAPIClient;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingApiConstants;
import de.schildbach.wallet.wallofcoins.selling_wizard.instruction.InstructionFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.VerifyAdResp;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.FragmentUtils;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.SellingConstants;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created on 08-Mar-18.
 */

public class VerifycationCodeFragment extends BuyDashBaseFragment implements View.OnClickListener {


    private View rootView;
    private Button btnVerifyAdd, btnResendOtp;
    private EditText edtViewCode;
    private final String TAG = "VerifycationOtpFragment";
    private String verificationCode = "", phone = "", addressId = "";
    private ProgressBar progressBar;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.layout_selling_verification_code, container, false);
            init();
            setListeners();
            handleArgs();
            return rootView;
        } else
            return rootView;
    }

    private void init() {
        btnVerifyAdd = (Button) rootView.findViewById(R.id.btnVerifyAdd);
        btnResendOtp = (Button) rootView.findViewById(R.id.btnResendOtp);
        edtViewCode = (EditText) rootView.findViewById(R.id.edtViewCode);
        progressBar = (ProgressBar) rootView.findViewById(R.id.progressBar);
    }

    private void setListeners() {
        btnResendOtp.setOnClickListener(this);
        btnVerifyAdd.setOnClickListener(this);
    }

    private void handleArgs() {
        if (getArguments() != null) {
            verificationCode = getArguments().getString(SellingConstants.VERIFICATION_CODE);
            phone = getArguments().getString(SellingConstants.PHONE_NUMBER);
            addressId = getArguments().getString(SellingConstants.ADDRESS_ID);
            edtViewCode.setText(verificationCode);
        }
    }

    @Override
    public void onClick(View view) {

        switch (view.getId()) {
            case R.id.btnResendOtp:
                // verifyOTP();
                break;
            case R.id.btnVerifyAdd:
                verifyAd();
                break;
        }
    }


    private void verifyAd() {

        if (NetworkUtil.isOnline(mContext)) {

            HashMap<String, String> verifyAdReq = new HashMap<String, String>();

            verifyAdReq.put(SellingApiConstants.AD_ID, addressId);
            verifyAdReq.put(SellingApiConstants.KEY_CODE, verificationCode);
            verifyAdReq.put(SellingApiConstants.KEY_PHONE, phone);

            SellingAPIClient.createService(interceptor, mContext).verifyAd(verifyAdReq).enqueue(new Callback<VerifyAdResp>() {
                @Override
                public void onResponse(Call<VerifyAdResp> call, Response<VerifyAdResp> response) {
                    if (null != response && null != response.body()) {

                        if (null != response.body().fundingAddress) {
                            navigateToLocation();

                        } else {
                            showToast(getString(R.string.try_again));
                        }
                    }
                }

                @Override
                public void onFailure(Call<VerifyAdResp> call, Throwable t) {
                    showToast(t.getMessage());
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
        InstructionFragment instructionFragment = new InstructionFragment();
        ((BuyDashBaseActivity) mContext).replaceFragment(instructionFragment, true, true);
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
