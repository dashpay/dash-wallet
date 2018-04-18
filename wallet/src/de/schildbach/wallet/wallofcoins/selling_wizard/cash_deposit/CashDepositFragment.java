package de.schildbach.wallet.wallofcoins.selling_wizard.cash_deposit;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.widget.AppCompatSpinner;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import de.schildbach.wallet.wallofcoins.buyingwizard.utils.NetworkUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseActivity;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingAPIClient;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.AddressVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.GetReceivingOptionsResp;
import de.schildbach.wallet.wallofcoins.selling_wizard.price.PriceFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.SellingConstants;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created by  on 04-Apr-18.
 */

public class CashDepositFragment extends SellingBaseFragment implements View.OnClickListener {


    private View rootView;
    private Button btnContinue;
    private EditText edtViewHolderName, edtViewAcc, edtViewConfirmAcc;
    private final String TAG = "CashDepositFragment";
    private AppCompatSpinner sp_banks;
    private ProgressBar progressBar;
    private String bankId = "";
    private List<GetReceivingOptionsResp> bankList;
    private RelativeLayout layoutAccDetails;
    private AddressVo addressVo;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.layout_selling_cash_deposit, container, false);
            init();
            setListeners();
            setTopbar();
            getReceivingOptions();
            handleArgs();
            return rootView;
        } else
            return rootView;
    }

    private void init() {

        edtViewHolderName = (EditText) rootView.findViewById(R.id.edtViewHolderName);
        edtViewAcc = (EditText) rootView.findViewById(R.id.edtViewAcc);
        edtViewConfirmAcc = (EditText) rootView.findViewById(R.id.edtViewConfirmAcc);
        sp_banks = (AppCompatSpinner) rootView.findViewById(R.id.sp_banks);
        btnContinue = (Button) rootView.findViewById(R.id.btnContinue);
        progressBar = (ProgressBar) rootView.findViewById(R.id.progressBar);
        layoutAccDetails = (RelativeLayout) rootView.findViewById(R.id.layoutAccDetails);

        bankList = new ArrayList<>();
    }

    private void setListeners() {
        btnContinue.setOnClickListener(this);
        sp_banks.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int position, long l) {
                if (position == 0) {
                    layoutAccDetails.setVisibility(View.GONE);
                    bankId = "";
                    return;
                }
                layoutAccDetails.setVisibility(View.VISIBLE);
                bankId = "" + bankList.get(position).id;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

    }

    private void setTopbar() {

        ((SellingBaseActivity) mContext).setTopbarTitle(
                mContext.getString(R.string.title_cash_depo_details));
    }

    private boolean isValidDetails() {
        String acc, confirmAcc;
        acc = edtViewAcc.getText().toString().trim();
        confirmAcc = edtViewConfirmAcc.getText().toString().trim();

        if (bankId.isEmpty()) {
            showToast(getString(R.string.please_select_bank));
            return false;
        } else if (edtViewHolderName.getText().toString().trim().isEmpty()) {
            showToast(getString(R.string.please_enter_holder));
            edtViewHolderName.requestFocus();
            return false;
        } else if (acc.isEmpty()) {
            showToast(getString(R.string.please_enter_acc_no));
            edtViewAcc.requestFocus();
            return false;
        } else if (confirmAcc.isEmpty()) {
            showToast(getString(R.string.please_enter_conf_acc_no));
            edtViewConfirmAcc.requestFocus();
            return false;
        } else if (!acc.equalsIgnoreCase(confirmAcc)) {
            showToast(getString(R.string.acc_not_matched));
            return false;
        }
        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
        setTopbar();
    }

    /**
     * API call for get all bank list by country code
     */
    private void getReceivingOptions() {
        if (NetworkUtil.isOnline(mContext)) {
            String locale;
            locale = getResources().getConfiguration().locale.getCountry();
            progressBar.setVisibility(View.VISIBLE);
            SellingAPIClient.createService(interceptor, mContext).getReceivingOptions("").enqueue(new Callback<List<GetReceivingOptionsResp>>() {

                @Override
                public void onResponse(Call<List<GetReceivingOptionsResp>> call, Response<List<GetReceivingOptionsResp>> response) {
                    progressBar.setVisibility(View.GONE);
                    if (response.body() != null) {
                        Log.e(TAG, "onResponse: " + response.body().size());
                        progressBar.setVisibility(View.GONE);
                        //set data in drop down list
                        setPaymentOptNames(response.body());
                    }

                }

                @Override
                public void onFailure(Call<List<GetReceivingOptionsResp>> call, Throwable t) {
                    progressBar.setVisibility(View.GONE);
                    showToast(t.getMessage());
                }
            });
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));

    }

    private void handleArgs() {

        if (getArguments() != null) {
            addressVo = (AddressVo)
                    getArguments().getSerializable(SellingConstants.ADDRESS_DETAILS_VO);
        }
    }

    /**
     * Set Payment option name for Payment options
     *
     * @param receivingOptionsResps
     */
    private void setPaymentOptNames(final List<GetReceivingOptionsResp> receivingOptionsResps) {
        bankList = receivingOptionsResps;

        final ArrayList<String> names = new ArrayList<String>();
        GetReceivingOptionsResp optionsRespDefaultName = new GetReceivingOptionsResp();
        Collections.sort(bankList, new ContactComparator());

        optionsRespDefaultName.name = getString(R.string.label_select_payment_center);
        bankList.add(0, optionsRespDefaultName);

        for (GetReceivingOptionsResp receivingOptionsResp : bankList) {
            names.add((receivingOptionsResp.name));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(mContext, android.R.layout.simple_spinner_dropdown_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp_banks.setAdapter(adapter);

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
            case R.id.btnContinue:
                if (isValidDetails()) {
                    Bundle bundle = new Bundle();
                    bundle.putSerializable(SellingConstants.ADDRESS_DETAILS_VO, getSellingDetails());
                    PriceFragment fragment = new PriceFragment();
                    fragment.setArguments(bundle);
                    ((SellingBaseActivity) mContext).replaceFragment(fragment,
                            true, true);
                }
                break;
        }
    }

    private AddressVo getSellingDetails() {
        addressVo.setName(edtViewHolderName.getText().toString().trim());
        addressVo.setNumber(edtViewAcc.getText().toString().trim());
        addressVo.setNumber2(edtViewConfirmAcc.getText().toString().trim());
        addressVo.setBankBusiness(bankId);
        return addressVo;
    }
}