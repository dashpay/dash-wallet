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
import android.widget.TextView;

import java.util.HashMap;

import de.schildbach.wallet.wallofcoins.buying_wizard.utils.NetworkUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingWizardBaseActivity;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingWizardBaseFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingAPIClient;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingApiConstants;
import de.schildbach.wallet.wallofcoins.selling_wizard.instruction.SellingWizardInstructionFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardVerifyAdResp;
import de.schildbach.wallet.wallofcoins.selling_wizard.storage.SharedPreferenceUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.SellingConstants;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.SellingWizardFragmentUtils;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created on 08-Mar-18.
 */

public class SellingWizardVerifCodeFragment extends SellingWizardBaseFragment implements View.OnClickListener {


    private View rootView;
    private Button button_verify_add, button_resend_otp;
    private EditText edit_code;
    private final String TAG = "VerifycationOtpFragment";
    private String mVerificationCode = "", mPhone = "", mAddressId = "";
    private ProgressBar progressBar;
    private TextView text_msg;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_selling_verification_code, container, false);
            init();
            setListeners();
            readBundle(getArguments());
            return rootView;
        } else
            return rootView;
    }

    private void init() {
        button_verify_add = (Button) rootView.findViewById(R.id.button_verify_add);
        button_resend_otp = (Button) rootView.findViewById(R.id.button_resend_otp);
        edit_code = (EditText) rootView.findViewById(R.id.edit_code);
        progressBar = (ProgressBar) rootView.findViewById(R.id.progressBar);
        text_msg = (TextView) rootView.findViewById(R.id.text_msg);

        text_msg.setText(getString(R.string.verification_code_msg,
                SharedPreferenceUtil.getString(SellingConstants.PREF_LOGGED_IN_PHONE, "")));
    }

    private void setListeners() {
        button_resend_otp.setOnClickListener(this);
        button_verify_add.setOnClickListener(this);
    }

    //Read data from bundle
    private void readBundle(Bundle bundle) {
        if (bundle != null) {
            mVerificationCode = bundle.getString(SellingConstants.ARG_VERIFICATION_CODE);
            mPhone = bundle.getString(SellingConstants.ARG_PHONE_NUMBER);
            mAddressId = bundle.getString(SellingConstants.ARG_ADDRESS_ID);
            edit_code.setText(mVerificationCode);
        }
    }

    @Override
    public void onClick(View view) {

        switch (view.getId()) {
            case R.id.button_resend_otp:
                showToast("Under Implementation");
                // verifyOTP();
                break;
            case R.id.button_verify_add:
                navigateToLocation();
                //verifyAd();
                break;
        }
    }


    private void verifyAd() {

        if (NetworkUtil.isOnline(mContext)) {

            HashMap<String, String> verifyAdReq = new HashMap<String, String>();

            verifyAdReq.put(SellingApiConstants.AD_ID, mAddressId);
            verifyAdReq.put(SellingApiConstants.KEY_CODE, mVerificationCode);
            verifyAdReq.put(SellingApiConstants.KEY_PHONE, mPhone);

            SellingAPIClient.createService(interceptor, mContext).verifyAd(verifyAdReq)
                    .enqueue(new Callback<SellingWizardVerifyAdResp>() {
                @Override
                public void onResponse(Call<SellingWizardVerifyAdResp> call, Response<SellingWizardVerifyAdResp> response) {
                    if (null != response && null != response.body()) {

                        if (null != response.body().fundingAddress) {
                            navigateToLocation();

                        } else {
                            showToast(getString(R.string.try_again));
                        }
                    }
                }

                @Override
                public void onFailure(Call<SellingWizardVerifyAdResp> call, Throwable t) {
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
        SellingWizardInstructionFragment fragment = new SellingWizardInstructionFragment();
        ((SellingWizardBaseActivity) mContext).replaceFragment(fragment, true, true);
    }


    //this method remove animation when user want to clear back stack
    @Override
    public Animation onCreateAnimation(int transit, boolean enter, int nextAnim) {
        if (SellingWizardFragmentUtils.sDisableFragmentAnimations) {
            Animation a = new Animation() {
            };
            a.setDuration(0);
            return a;
        }
        return super.onCreateAnimation(transit, enter, nextAnim);
    }
}
