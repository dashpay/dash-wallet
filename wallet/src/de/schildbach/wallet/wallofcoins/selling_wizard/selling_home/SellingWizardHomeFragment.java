package de.schildbach.wallet.wallofcoins.selling_wizard.selling_home;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import de.schildbach.wallet.wallofcoins.buying_wizard.utils.NetworkUtil;
import de.schildbach.wallet.wallofcoins.response.CheckAuthResp;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingWizardBaseActivity;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingWizardBaseFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.add_listing.SellingWizardAddressListingFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.RetrofitErrorUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingAPIClient;
import de.schildbach.wallet.wallofcoins.selling_wizard.contact_details.SellingWizardContactDetailsFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.phone_list.SellingWizardPhoneListFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.storage.SharedPreferenceUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.SellingConstants;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.WOCLogUtil;
import de.schildbach.wallet_test.R;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created on 03-Apr-18.
 */

public class SellingWizardHomeFragment extends SellingWizardBaseFragment implements View.OnClickListener {

    private View rootView;
    private Button button_sign_here, button_list, button_sign_out_woc, button_sell_coin;
    private final String TAG = "SellingHomeFragment";
    private LinearLayout layout_sign_out, layout_sign_in;
    private TextView text_message_sign_out;
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
            rootView = inflater.inflate(R.layout.fragment_selling_home, container, false);
            init();
            setListeners();
            setTopbar();
            return rootView;
        } else
            return rootView;
    }

    private void init() {
        button_sign_here = (Button) rootView.findViewById(R.id.button_sign_here);
        button_list = (Button) rootView.findViewById(R.id.button_list);
        button_sign_out_woc = (Button) rootView.findViewById(R.id.button_sign_out_woc);
        progressBar = (ProgressBar) rootView.findViewById(R.id.progressBar);
        text_message_sign_out = (TextView) rootView.findViewById(R.id.text_message_sign_out);
        layout_sign_out = (LinearLayout) rootView.findViewById(R.id.layout_sign_out);
        layout_sign_in = (LinearLayout) rootView.findViewById(R.id.layout_sign_in);
        button_sell_coin = (Button) rootView.findViewById(R.id.button_sell_coin);
    }

    private void setListeners() {
        button_sign_here.setOnClickListener(this);
        button_list.setOnClickListener(this);
        button_sign_out_woc.setOnClickListener(this);
        button_sell_coin.setOnClickListener(this);
    }

    private void setTopbar() {
        ((SellingWizardBaseActivity) mContext).setTopbarTitle(
                mContext.getString(R.string.title_selling));
    }

    @Override
    public void onResume() {
        super.onResume();
        setTopbar();
        if (!TextUtils.isEmpty(SharedPreferenceUtil.getString(SellingConstants.PREF_TOKEN_ID, ""))) {
            layout_sign_out.setVisibility(View.VISIBLE);
            layout_sign_in.setVisibility(View.GONE);
            WOCLogUtil.showLogError("-----", SharedPreferenceUtil.getString(SellingConstants.PREF_LOGGED_IN_PHONE, ""));
            text_message_sign_out.setText(mContext.getString(R.string.wallet_is_signed_msg,
                    SharedPreferenceUtil.getString(SellingConstants.PREF_LOGGED_IN_PHONE, "")));
        } else {
            layout_sign_out.setVisibility(View.GONE);
            layout_sign_in.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onClick(View view) {

        switch (view.getId()) {
            case R.id.button_sign_here:
                ((SellingWizardBaseActivity) mContext).replaceFragment(new SellingWizardPhoneListFragment(),
                        true, true);
                break;
            case R.id.button_list:
                ((SellingWizardBaseActivity) mContext).replaceFragment(new SellingWizardAddressListingFragment(),
                        true, true);
                break;
            case R.id.button_sign_out_woc:
                deleteAuthCall();
                break;
            case R.id.button_sell_coin:
                //if (!TextUtils.isEmpty(SharedPreferenceUtil.getString(SellingConstants.TOKEN_ID, "")))
                ((SellingWizardBaseActivity) mContext).replaceFragment(new SellingWizardContactDetailsFragment(),
                        true, true);
               /* else
                    showToast("Please Sign in first");
                    break;*/

        }
    }

    /**
     * Method for singout user
     */
    public void deleteAuthCall() {
        if (NetworkUtil.isOnline(mContext)) {
            final String phone = SharedPreferenceUtil.getString(SellingConstants.PREF_LOGGED_IN_PHONE, "");
            progressBar.setVisibility(View.VISIBLE);
            SellingAPIClient.createService(interceptor, mContext)
                    .deleteAuth(phone, getString(R.string.WALLOFCOINS_PUBLISHER_ID))
                    .enqueue(new Callback<CheckAuthResp>() {
                        @Override
                        public void onResponse(Call<CheckAuthResp> call, Response<CheckAuthResp> response) {
                            Log.d(TAG, "onResponse: response code==>>" + response.code());
                            progressBar.setVisibility(View.GONE);
                            if (response.code() < 299) {
                                SharedPreferenceUtil.putValue(SellingConstants.PREF_LOGGED_IN_PHONE, "");
                                SharedPreferenceUtil.putValue(SellingConstants.PREF_TOKEN_ID, "");
                                SharedPreferenceUtil.putValue(SellingConstants.PREF_DEVICE_ID, "");
                                SharedPreferenceUtil.putValue(SellingConstants.PREF_DEVICE_CODE, "");

                                showToast(mContext.getString(R.string.alert_sign_out));
                                layout_sign_in.setVisibility(View.VISIBLE);
                                layout_sign_out.setVisibility(View.GONE);
                            } else {
                                String error = RetrofitErrorUtil.parseError(response);
                                if (error != null && !error.isEmpty())
                                    showToast(error);
                            }
                        }

                        @Override
                        public void onFailure(Call<CheckAuthResp> call, Throwable t) {
                            progressBar.setVisibility(View.GONE);
                            showToast(t.getMessage());
                        }
                    });

        } else
            showToast(mContext.getString(R.string.network_not_avaialable));

    }
}