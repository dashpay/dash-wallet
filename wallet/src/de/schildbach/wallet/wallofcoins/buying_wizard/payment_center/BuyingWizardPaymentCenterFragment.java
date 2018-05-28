package de.schildbach.wallet.wallofcoins.buying_wizard.payment_center;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.widget.AppCompatSpinner;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.buying_wizard.BuyingWizardBaseActivity;
import de.schildbach.wallet.wallofcoins.buying_wizard.BuyingWizardBaseFragment;

import de.schildbach.wallet.wallofcoins.buying_wizard.offer_amount.BuyingWizardOfferAmountFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.BuyingWizardFragmentUtils;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.NetworkUtil;
import de.schildbach.wallet.wallofcoins.response.GetReceivingOptionsResp;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;


/**
 * Created on 07-Mar-18.
 */

public class BuyingWizardPaymentCenterFragment extends BuyingWizardBaseFragment implements View.OnClickListener {

    private View rootView;
    private LinearLayout linear_progress;
    private final String TAG = "PaymentCenterFragment";
    private AppCompatSpinner sp_banks;
    private String bankId;
    private Button button_bank_next;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_buying_payment_center, container, false);
            init();
            setListeners();
            getReceivingOptions();
            return rootView;
        } else
            return rootView;
    }

    private void init() {
        linear_progress = (LinearLayout) rootView.findViewById(R.id.linear_progress);
        sp_banks = (AppCompatSpinner) rootView.findViewById(R.id.sp_banks);
        button_bank_next = (Button) rootView.findViewById(R.id.button_bank_next);
    }

    private void setListeners() {
        button_bank_next.setOnClickListener(this);
    }

    /**
     * API call for get all receiving options by country code
     */
    private void getReceivingOptions() {
        if (NetworkUtil.isOnline(mContext)) {
            String locale;
            locale = getResources().getConfiguration().locale.getCountry();
            linear_progress.setVisibility(View.VISIBLE);
            WallofCoins.createService(interceptor, getActivity()).getReceivingOptions()
                    .enqueue(new Callback<List<GetReceivingOptionsResp>>() {

                        @Override
                        public void onResponse(Call<List<GetReceivingOptionsResp>> call,
                                               Response<List<GetReceivingOptionsResp>> response) {

                            if (response.body() != null) {
                                Log.e(TAG, "onResponse: " + response.body().size());
                                linear_progress.setVisibility(View.GONE);
                                setPaymentOptNames(response.body());
                            }

                        }

                        @Override
                        public void onFailure(Call<List<GetReceivingOptionsResp>> call, Throwable t) {
                            linear_progress.setVisibility(View.GONE);
                            showToast(mContext.getString(R.string.try_again));
                            t.printStackTrace();
                        }
                    });
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));

    }

    /**
     * Set Payment option name for Payment options
     *
     * @param receivingOptionsResps
     */
    private void setPaymentOptNames(final List<GetReceivingOptionsResp> receivingOptionsResps) {
        final ArrayList<String> names = new ArrayList<String>();
        GetReceivingOptionsResp optionsRespDefaultName = new GetReceivingOptionsResp();
        Collections.sort(receivingOptionsResps, new ContactComparator());

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
                bankId = "" + receivingOptionsResps.get(position).id;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

    }

    private class ContactComparator implements Comparator<GetReceivingOptionsResp> {
        public int compare(GetReceivingOptionsResp optionsResp1, GetReceivingOptionsResp optionsResp2) {
            //In the following line you set the criterion,
            //which is the name of Contact in my example scenario
            return optionsResp1.name.compareTo(optionsResp2.name);
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {

            case R.id.button_bank_next:
                if (sp_banks.getSelectedItemPosition() == 0)
                    showToast(mContext.getString(R.string.alert_select_any_payment_center));
                else
                    navigateToOtherScreen();
                break;

        }
    }

    private void navigateToOtherScreen() {
        Bundle bundle = new Bundle();
        bundle.putString(WOCConstants.ARG_BANK_ID, bankId);
        BuyingWizardOfferAmountFragment offerAmountFragment = new BuyingWizardOfferAmountFragment();
        offerAmountFragment.setArguments(bundle);

        ((BuyingWizardBaseActivity) mContext).replaceFragment(offerAmountFragment, true, true);
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
