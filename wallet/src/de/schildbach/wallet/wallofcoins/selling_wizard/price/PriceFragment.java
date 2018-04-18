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
import de.schildbach.wallet.wallofcoins.selling_wizard.api.RetrofitErrorUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingAPIClient;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.AddressVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.MarketsVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.SellingConstants;
import de.schildbach.wallet.wallofcoins.selling_wizard.verify_details.VerifySellingDetailsFragment;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created by  on 04-Apr-18.
 */

public class PriceFragment extends SellingBaseFragment implements View.OnClickListener {


    private View rootView;
    private Button btnContinue;
    private EditText edtViewStaticPrice, edtViewMinPayment, edtViewMaxPayment, edtViewSellerPrice;
    private final String TAG = "PriceFragment";
    private ProgressBar progressBar;
    private AddressVo addressVo;
    private AppCompatSpinner sp_primary_market, sp_secondary_market;
    private AppCompatCheckBox chkBoxdynamicPricing;
    private LinearLayout layoutStaticPrice;
    private RelativeLayout layoutDynamicPrice;
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
            rootView = inflater.inflate(R.layout.layout_selling_price, container, false);
            init();
            setListeners();
            setTopbar();
            handleArgs();
            return rootView;
        } else
            return rootView;
    }

    private void init() {

        edtViewStaticPrice = (EditText) rootView.findViewById(R.id.edtViewStaticPrice);
        edtViewMinPayment = (EditText) rootView.findViewById(R.id.edtViewMinPayment);
        edtViewMaxPayment = (EditText) rootView.findViewById(R.id.edtViewMaxPayment);
        edtViewSellerPrice = (EditText) rootView.findViewById(R.id.edtViewSellerPrice);
        chkBoxdynamicPricing = (AppCompatCheckBox) rootView.findViewById(R.id.chkBoxdynamicPricing);

        sp_primary_market = (AppCompatSpinner) rootView.findViewById(R.id.sp_primary_market);
        sp_secondary_market = (AppCompatSpinner) rootView.findViewById(R.id.sp_secondary_market);

        btnContinue = (Button) rootView.findViewById(R.id.btnContinue);
        progressBar = (ProgressBar) rootView.findViewById(R.id.progressBar);

        layoutStaticPrice = (LinearLayout) rootView.findViewById(R.id.layoutStaticPrice);
        layoutDynamicPrice = (RelativeLayout) rootView.findViewById(R.id.layoutDynamicPrice);

        marketsVoArrayList = new ArrayList<>();

    }

    private void setListeners() {
        btnContinue.setOnClickListener(this);
        chkBoxdynamicPricing.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                if (isChecked) {
                    if (marketsVoArrayList.size() == 0)
                        getMarkes();
                    layoutStaticPrice.setVisibility(View.GONE);
                    layoutDynamicPrice.setVisibility(View.VISIBLE);
                } else {
                    layoutDynamicPrice.setVisibility(View.GONE);
                    layoutStaticPrice.setVisibility(View.VISIBLE);
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
                    getArguments().getSerializable(SellingConstants.ADDRESS_DETAILS_VO);
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
            case R.id.btnContinue:
                if (chkBoxdynamicPricing.isChecked()) {
                    if (isValidDetails()) {
                        Bundle bundle = new Bundle();
                        bundle.putSerializable(SellingConstants.ADDRESS_DETAILS_VO, getSellingDetails());
                        VerifySellingDetailsFragment fragment = new VerifySellingDetailsFragment();
                        fragment.setArguments(bundle);

                        ((SellingBaseActivity) mContext).replaceFragment(fragment, true, true);
                    }
                }
                break;
        }
    }

    private boolean isValidDetails() {

        if (chkBoxdynamicPricing.isChecked()) {
            if (sp_primary_market.getSelectedItemPosition() == 0) {
                showToast(getString(R.string.primary_empty));
                return false;
            } else if (sp_secondary_market.getSelectedItemPosition() == 0) {
                showToast(getString(R.string.secondary_empty));
                return false;
            } else if (sp_primary_market.getSelectedItemPosition() == sp_secondary_market.getSelectedItemPosition()) {
                showToast(getString(R.string.error_primary_secondry_same));
                return false;
            }
        } else {
            if (edtViewStaticPrice.getText().toString().trim().isEmpty()) {
                showToast(getString(R.string.all_field_required));
                edtViewStaticPrice.requestFocus();
                return false;
            }
        }

        return true;
    }

    private AddressVo getSellingDetails() {

        addressVo.setDynamicPrice(true);

        if (chkBoxdynamicPricing.isChecked()) {
            int priMarketPos, secMarketPos;
            priMarketPos = sp_primary_market.getSelectedItemPosition();
            secMarketPos = sp_secondary_market.getSelectedItemPosition();
            addressVo.setPrimaryMarket(marketsVoArrayList.get(priMarketPos - 1).getId());
            addressVo.setSecondaryMarket(marketsVoArrayList.get(secMarketPos - 1).getId());

            addressVo.setSellerFee(edtViewSellerPrice.getText().toString().trim());
            addressVo.setMinPayment(edtViewMinPayment.getText().toString().trim());
            addressVo.setMaxPayment(edtViewMaxPayment.getText().toString().trim());

        } else {

            //null other fields
            addressVo.setPrimaryMarket(null);
            addressVo.setSecondaryMarket(null);
            addressVo.setSellerFee(null);
            addressVo.setMinPayment(null);
            addressVo.setMaxPayment(null);
        }
        addressVo.setCurrentPrice(edtViewStaticPrice.getText().toString().trim());
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
        sp_primary_market.setAdapter(primaryAdapter);

        ArrayAdapter<String> secondaryAdapter = new ArrayAdapter<String>(mContext, android.R.layout.simple_spinner_dropdown_item, secondaryList);
        secondaryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp_secondary_market.setAdapter(secondaryAdapter);
    }
}
