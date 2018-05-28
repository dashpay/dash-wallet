package de.schildbach.wallet.wallofcoins.buying_wizard.phone_list;

import android.content.Context;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;


import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.buying_wizard.BuyingWizardBaseActivity;
import de.schildbach.wallet.wallofcoins.buying_wizard.BuyingWizardBaseFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.adapters.BuyingWizardPhoneListAdapter;
import de.schildbach.wallet.wallofcoins.buying_wizard.email_phone.BuyingWizardEmailPhoneFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.models.BuyingWizardPhoneListVO;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.BuyingWizardPhoneListPref;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.NetworkUtil;
import de.schildbach.wallet.wallofcoins.response.GetAuthTokenResp;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created on 19-Mar-18.
 */

public class BuyingWizardPhoneListFragment extends BuyingWizardBaseFragment implements View.OnClickListener {

    private final String TAG = "OrderHistoryFragment";
    private View rootView;
    private RecyclerView recyclerViewPhoneList;
    private Button btnSignUp, btnExistingSignIn;
    private BuyingWizardPhoneListFragment fragment;
    private TextView txtViewNoData;


    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_buying_phone_list, container, false);
        init();
        setListeners();
        setPhoneList();
        return rootView;
    }

    private void init() {
        fragment = this;
        recyclerViewPhoneList = (RecyclerView) rootView.findViewById(R.id.recyclerViewPhoneList);
        btnSignUp = (Button) rootView.findViewById(R.id.btnSignUp);
        btnExistingSignIn = (Button) rootView.findViewById(R.id.btnExistingSignIn);
        txtViewNoData = (TextView) rootView.findViewById(R.id.txtViewNoData);
        recyclerViewPhoneList.setLayoutManager(new LinearLayoutManager(mContext));
    }

    private void setListeners() {
        btnExistingSignIn.setOnClickListener(this);
        btnSignUp.setOnClickListener(this);
    }

    private void setPhoneList() {
        BuyingWizardPhoneListPref credentialsPref =
                new BuyingWizardPhoneListPref(PreferenceManager.getDefaultSharedPreferences(mContext));

        ArrayList<BuyingWizardPhoneListVO> buyingWizardPhoneListVOS = credentialsPref.getStoredPhoneList();

        HashSet<BuyingWizardPhoneListVO> hashSet = new HashSet<>();
        hashSet.addAll(buyingWizardPhoneListVOS);
        buyingWizardPhoneListVOS.clear();
        buyingWizardPhoneListVOS.addAll(hashSet);

        if (buyingWizardPhoneListVOS != null & buyingWizardPhoneListVOS.size() > 0) {
            recyclerViewPhoneList.setAdapter(new BuyingWizardPhoneListAdapter(mContext,
                    credentialsPref.getStoredPhoneList(), fragment));
            txtViewNoData.setVisibility(View.GONE);
        } else
            txtViewNoData.setVisibility(View.VISIBLE);

    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.btnExistingSignIn:
                Bundle bundle = new Bundle();
                bundle.putString(WOCConstants.ARG_SCREEN_TYPE, "PhoneListFragment");
                BuyingWizardEmailPhoneFragment phoneFragment = new BuyingWizardEmailPhoneFragment();
                phoneFragment.setArguments(bundle);

                ((BuyingWizardBaseActivity) mContext).replaceFragment(phoneFragment, true, true);
                break;
            case R.id.btnSignUp:
                goToUrl(WOCConstants.KEY_SIGN_UP_URL);
                break;
        }
    }

    /**
     * Authorized user using phone
     *
     * @param deviceId
     */
    public void getAuthTokenCall(final String phone, String deviceId) {
        if (NetworkUtil.isOnline(mContext)) {

            HashMap<String, String> getAuthTokenReq = new HashMap<String, String>();
            getAuthTokenReq.put(WOCConstants.KEY_DEVICEID, deviceId);
            getAuthTokenReq.put(WOCConstants.KEY_PUBLISHER_ID, getString(R.string.WALLOFCOINS_PUBLISHER_ID));
            getAuthTokenReq.put(WOCConstants.KEY_DEVICECODE, getDeviceCode(mContext,
                    ((BuyingWizardBaseActivity) mContext).buyDashPref));

            WallofCoins.createService(interceptor, getActivity()).getAuthToken(phone, getAuthTokenReq)
                    .enqueue(new Callback<GetAuthTokenResp>() {
                @Override
                public void onResponse(Call<GetAuthTokenResp> call, Response<GetAuthTokenResp> response) {
                    int code = response.code();

                    if (code == 200) {
                        if (!TextUtils.isEmpty(response.body().token)) {
                            ((BuyingWizardBaseActivity) mContext).buyDashPref.setAuthToken(response.body().token);
                            ((BuyingWizardBaseActivity) mContext).buyDashPref.setPhone(phone);
                            ((BuyingWizardBaseActivity) mContext).buyDashPref.setDeviceId(response.body().deviceId);
                            ((BuyingWizardBaseActivity) mContext).popBackDirect();
                        }
                    }
                }

                @Override
                public void onFailure(Call<GetAuthTokenResp> call, Throwable t) {
                    showToast(mContext.getString(R.string.try_again));
                }
            });

        } else
            showToast(mContext.getString(R.string.network_not_avaialable));
    }
}
