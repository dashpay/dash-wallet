package de.schildbach.wallet.wallofcoins.selling_wizard.price;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.widget.AppCompatCheckBox;
import android.support.v7.widget.AppCompatSpinner;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.Toast;

import java.util.ArrayList;

import de.schildbach.wallet.wallofcoins.buyingwizard.utils.NetworkUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseActivity;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.advanced_options.AdvanceOptionsFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.RetrofitErrorUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingAPIClient;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.AddressVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.MarketsVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.SellingConstants;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created by  on 04-Apr-18.
 */

public class PriceFragment extends SellingBaseFragment implements View.OnClickListener {


    private View rootView;
    private Button button_continue;
    private EditText edit_static_price, edit_min_payment, edit_max_payment, edit_seller_price;
    private final String TAG = "PriceFragment";
    private ProgressBar progressBar;
    private AddressVo addressVo;
    private AppCompatSpinner spinner_primary_market, spinner_secondary_market;
    private AppCompatCheckBox chekbox_dynamic_pricing;
    private LinearLayout layout_static_price;
    private RelativeLayout layout_dynamic_price;
    private ArrayList<MarketsVo> marketsVoArrayList;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_selling_price, container, false);
            init();
            setListeners();
            setTopbar();
            handleArgs();
            return rootView;
        } else
            return rootView;
    }

    private void init() {

        edit_static_price = (EditText) rootView.findViewById(R.id.edit_static_price);
        edit_min_payment = (EditText) rootView.findViewById(R.id.edit_min_payment);
        edit_max_payment = (EditText) rootView.findViewById(R.id.edit_max_payment);
        edit_seller_price = (EditText) rootView.findViewById(R.id.edit_seller_price);
        chekbox_dynamic_pricing = (AppCompatCheckBox) rootView.findViewById(R.id.chekbox_dynamic_pricing);

        spinner_primary_market = (AppCompatSpinner) rootView.findViewById(R.id.spinner_primary_market);
        spinner_secondary_market = (AppCompatSpinner) rootView.findViewById(R.id.spinner_secondary_market);

        button_continue = (Button) rootView.findViewById(R.id.button_continue);
        progressBar = (ProgressBar) rootView.findViewById(R.id.progressBar);

        layout_static_price = (LinearLayout) rootView.findViewById(R.id.layout_static_price);
        layout_dynamic_price = (RelativeLayout) rootView.findViewById(R.id.layout_dynamic_price);

        marketsVoArrayList = new ArrayList<>();

    }

    private void setListeners() {
        button_continue.setOnClickListener(this);
        chekbox_dynamic_pricing.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                if (isChecked) {
                    if (marketsVoArrayList.size() == 0)
                        getMarkes();
                    layout_static_price.setVisibility(View.GONE);
                    layout_dynamic_price.setVisibility(View.VISIBLE);
                } else {
                    layout_dynamic_price.setVisibility(View.GONE);
                    layout_static_price.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void setTopbar() {

        ((SellingBaseActivity) mContext).setTopbarTitle(
                mContext.getString(R.string.title_price));
    }

    private void handleArgs() {

        if (getArguments() != null) {
            addressVo = (AddressVo)
                    getArguments().getSerializable(SellingConstants.ARGUMENT_ADDRESS_DETAILS_VO);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        setTopbar();
    }

    @Override
    public void onClick(View view) {

        switch (view.getId()) {
            case R.id.button_continue:
                //if (chkBoxdynamicPricing.isChecked()) {
                    /*if (isValidDetails()) {
                        Bundle bundle = new Bundle();
                        bundle.putSerializable(SellingConstants.ADDRESS_DETAILS_VO, getSellingDetails());
                        VerifySellingDetailsFragment fragment = new VerifySellingDetailsFragment();
                        fragment.setArguments(bundle);

                        ((SellingBaseActivity) mContext).replaceFragment(fragment, true, true);
                    }*/
                   // if (isValidDetails()) {

                        if (isValid())
                        {
                            Bundle bundle = new Bundle();
                            bundle.putSerializable(SellingConstants.ARGUMENT_ADDRESS_DETAILS_VO, getSellingDetails());
                            AdvanceOptionsFragment fragment = new AdvanceOptionsFragment();
                            fragment.setArguments(bundle);

                            ((SellingBaseActivity) mContext).replaceFragment(fragment, true, true);
                        }

                    //}

               // }

                break;
        }
    }
    private boolean isValid()
    {
        if (edit_static_price.getText().toString().trim().isEmpty()) {
            showToast(getString(R.string.enter_price));
            edit_static_price.requestFocus();
            return false;
        }
        return true;
    }
    private boolean isValidDetails() {

        if (chekbox_dynamic_pricing.isChecked()) {
            if (spinner_primary_market.getSelectedItemPosition() == 0) {
                showToast(getString(R.string.primary_empty));
                return false;
            } else if (spinner_secondary_market.getSelectedItemPosition() == 0) {
                showToast(getString(R.string.secondary_empty));
                return false;
            } else if (spinner_primary_market.getSelectedItemPosition() == spinner_secondary_market.getSelectedItemPosition()) {
                showToast(getString(R.string.error_primary_secondry_same));
                return false;
            }
        } else {
            if (edit_static_price.getText().toString().trim().isEmpty()) {
                showToast(getString(R.string.all_field_required));
                edit_static_price.requestFocus();
                return false;
            }
        }

        return true;
    }

    private AddressVo getSellingDetails() {

        addressVo.setDynamicPrice(true);

        if (chekbox_dynamic_pricing.isChecked()) {
            int priMarketPos, secMarketPos;
            priMarketPos = spinner_primary_market.getSelectedItemPosition();
            secMarketPos = spinner_secondary_market.getSelectedItemPosition();
            addressVo.setPrimaryMarket(marketsVoArrayList.get(priMarketPos - 1).getId());
            addressVo.setSecondaryMarket(marketsVoArrayList.get(secMarketPos - 1).getId());

            addressVo.setSellerFee(edit_seller_price.getText().toString().trim());
            addressVo.setMinPayment(edit_min_payment.getText().toString().trim());
            addressVo.setMaxPayment(edit_max_payment.getText().toString().trim());

        } else {

            //null other fields
            addressVo.setPrimaryMarket(null);
            addressVo.setSecondaryMarket(null);
            addressVo.setSellerFee(null);
            addressVo.setMinPayment(null);
            addressVo.setMaxPayment(null);
        }
        addressVo.setCurrentPrice(edit_static_price.getText().toString().trim());
        return addressVo;
    }

    private void getMarkes() {
        if (NetworkUtil.isOnline(mContext)) {
            SellingAPIClient.createService(mContext).getMarkets("DASH", "USD").enqueue(new Callback<ArrayList<MarketsVo>>() {
                @Override
                public void onResponse(Call<ArrayList<MarketsVo>> call, Response<ArrayList<MarketsVo>> response) {

                    if (response.code() == 200) {
                        marketsVoArrayList = response.body();
                        handleMarkesReponse(marketsVoArrayList);
                    } else {
                        String error = RetrofitErrorUtil.parseError(response);
                        if (error != null && !error.isEmpty())
                            showToast(error);
                    }

                }

                @Override
                public void onFailure(Call<ArrayList<MarketsVo>> call, Throwable t) {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                }
            });
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));

    }

    private void handleMarkesReponse(ArrayList<MarketsVo> arrayList) {
        ArrayList<String> primaryList = new ArrayList<String>();
        ArrayList<String> secondaryList = new ArrayList<String>();
        primaryList.add(0, "Select Primary market");
        secondaryList.add(0, "Select Secondary market");
        for (MarketsVo marketsVo : arrayList) {
            primaryList.add(marketsVo.getLabel());
            secondaryList.add(marketsVo.getLabel());
        }

        ArrayAdapter<String> primaryAdapter = new ArrayAdapter<String>(mContext, android.R.layout.simple_spinner_dropdown_item, primaryList);
        primaryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner_primary_market.setAdapter(primaryAdapter);

        ArrayAdapter<String> secondaryAdapter = new ArrayAdapter<String>(mContext, android.R.layout.simple_spinner_dropdown_item, secondaryList);
        secondaryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner_secondary_market.setAdapter(secondaryAdapter);
    }
}
