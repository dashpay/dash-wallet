package de.schildbach.wallet.wallofcoins.buyingwizard.payment_center;

import android.content.Context;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v7.widget.AppCompatSpinner;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import de.schildbach.wallet.wallofcoins.BuyDashPref;
import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseActivity;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.offer_amount.BuyDashOfferAmountFragment;
import de.schildbach.wallet.wallofcoins.response.GetReceivingOptionsResp;
import de.schildbach.wallet_test.R;
import okhttp3.Interceptor;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created on 07-Mar-18.
 */

public class BuyDashPaymentCenterFragment extends BuyDashBaseFragment implements View.OnClickListener {

    private View rootView;
    private BuyDashPref buyDashPref;
    private LinearLayout linear_progress;
    private final String TAG = "PaymentCenterFragment";
    private AppCompatSpinner sp_banks;
    private String bankId;
    private Button button_buy_dash_bank_next;
    private ImageView imgViewToolbarBack;


    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.layout_buy_dash_payment_center, container, false);
            init();
            setListeners();
            getReceivingOptions();
            return rootView;
        } else
            return rootView;
    }

    private void init() {
        this.buyDashPref = new BuyDashPref(PreferenceManager.getDefaultSharedPreferences(mContext));
        linear_progress = (LinearLayout) rootView.findViewById(R.id.linear_progress);
        sp_banks = (AppCompatSpinner) rootView.findViewById(R.id.sp_banks);
        button_buy_dash_bank_next = (Button) rootView.findViewById(R.id.button_buy_dash_bank_next);
        imgViewToolbarBack = (ImageView) rootView.findViewById(R.id.imgViewToolbarBack);
    }

    private void setListeners() {
        button_buy_dash_bank_next.setOnClickListener(this);
        imgViewToolbarBack.setOnClickListener(this);
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
     * API call for get all receiving options by country code
     */
    private void getReceivingOptions() {
        String locale;
        locale = getResources().getConfiguration().locale.getCountry();
        linear_progress.setVisibility(View.VISIBLE);
        //WallofCoins.createService(interceptor, getActivity()).getReceivingOptions(locale.toLowerCase(), getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<List<GetReceivingOptionsResp>>() {
        WallofCoins.createService(interceptor, getActivity()).getReceivingOptions().enqueue(new Callback<List<GetReceivingOptionsResp>>() {

            @Override
            public void onResponse(Call<List<GetReceivingOptionsResp>> call, Response<List<GetReceivingOptionsResp>> response) {

                if (response.body() != null) {
                    Log.e(TAG, "onResponse: " + response.body().size());
                    linear_progress.setVisibility(View.GONE);
                    //receivingOptionsResps = response.body();
                    // hideViewExcept(binding.layoutBanks);

                    //set data in drop down list
                    setPaymentOptNames(response.body());
                }

            }

            @Override
            public void onFailure(Call<List<GetReceivingOptionsResp>> call, Throwable t) {
                linear_progress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                t.printStackTrace();
            }
        });
    }

    /**
     * Set Payment option name for Payment options
     *
     * @param receivingOptionsResps
     */
    private void setPaymentOptNames(final List<GetReceivingOptionsResp> receivingOptionsResps) {
        final ArrayList<String> names = new ArrayList<String>();
        GetReceivingOptionsResp optionsRespDefaultName = new GetReceivingOptionsResp();
        optionsRespDefaultName.name = getString(R.string.label_select_payment_center);
        receivingOptionsResps.add(0, optionsRespDefaultName);
        for (GetReceivingOptionsResp receivingOptionsResp : receivingOptionsResps) {
            names.add((receivingOptionsResp.name));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(mContext, android.R.layout.simple_spinner_dropdown_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp_banks.setAdapter(adapter);

        sp_banks.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) return;
                bankId = "" + receivingOptionsResps.get(position - 1).id;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {

            case R.id.button_buy_dash_bank_next:
                if (sp_banks.getSelectedItemPosition() == 0) {
                    Toast.makeText(mContext, R.string.alert_select_any_payment_center, Toast.LENGTH_LONG).show();
                } else {
                    navigateToOtherScreen();
                }
                break;

            case R.id.imgViewToolbarBack:
                ((BuyDashBaseActivity) mContext).popbackFragment();
                break;

        }
    }

    private void navigateToOtherScreen() {
        Bundle bundle = new Bundle();
        bundle.putString(WOCConstants.BANK_ID, bankId);
        BuyDashOfferAmountFragment offerAmountFragment = new BuyDashOfferAmountFragment();
        offerAmountFragment.setArguments(bundle);

        ((BuyDashBaseActivity) mContext).replaceFragment(offerAmountFragment, true, true, "BuyDashOfferAmountFragment");
    }
}
