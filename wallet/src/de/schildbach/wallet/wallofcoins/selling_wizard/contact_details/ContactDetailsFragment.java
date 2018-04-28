package de.schildbach.wallet.wallofcoins.selling_wizard.contact_details;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.Spinner;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import de.schildbach.wallet.wallofcoins.buyingwizard.utils.NetworkUtil;
import de.schildbach.wallet.wallofcoins.response.CountryData;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseActivity;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.adapters.CountryAdapter;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.RetrofitErrorUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingAPIClient;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingApiConstants;
import de.schildbach.wallet.wallofcoins.selling_wizard.cash_deposit.CashDepositFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.common.PhoneUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.AddressVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SignUpResponseVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.storage.SharedPreferenceUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.SellingConstants;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created by  on 03-Apr-18.
 */

public class ContactDetailsFragment extends SellingBaseFragment implements View.OnClickListener {


    private View rootView;
    private Button button_continue;
    private EditText edit_mobile, edit_email, edit_confirm_email, edit_password;
    private final String TAG = "ContactDetailsFragment";
    private CountryData countryData;
    private Spinner spinner_country;
    private ProgressBar progressBar;
    private String mCountryCode = "";
    private RelativeLayout layout_password;
    private boolean isLoggedIn;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_selling_contact_details, container, false);
            init();
            setListeners();
            setTopbar();
            addCountryCodeList();
            handleArgs();

            return rootView;
        } else
            return rootView;
    }


    private void init() {

        edit_mobile = (EditText) rootView.findViewById(R.id.edit_mobile);
        edit_email = (EditText) rootView.findViewById(R.id.edit_email);
        edit_confirm_email = (EditText) rootView.findViewById(R.id.edit_confirm_email);
        edit_password = (EditText) rootView.findViewById(R.id.edit_password);
        spinner_country = (Spinner) rootView.findViewById(R.id.spinner_country);
        button_continue = (Button) rootView.findViewById(R.id.button_continue);
        progressBar = (ProgressBar) rootView.findViewById(R.id.progressBar);
        layout_password = (RelativeLayout) rootView.findViewById(R.id.layout_password);
    }

    private void setListeners() {
        button_continue.setOnClickListener(this);
        spinner_country.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                mCountryCode = countryData.countries.get(i).code;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });
    }

    private void setTopbar() {

        ((SellingBaseActivity) mContext).setTopbarTitle(
                mContext.getString(R.string.title_contact_details));
    }

    private void addCountryCodeList() {
        String json;
        try {
            InputStream is = getActivity().getAssets().open("countries.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            json = new String(buffer, "UTF-8");
        } catch (IOException ex) {
            ex.printStackTrace();
            return;
        }

        countryData = new Gson().fromJson(json, CountryData.class);

        List<String> stringList = new ArrayList<>();

        for (CountryData.CountriesBean bean : countryData.countries) {
            stringList.add(bean.name + " (" + bean.code + ")");
        }
        CountryAdapter customAdapter = new CountryAdapter(getActivity(), R.layout.spinner_row_country, countryData.countries);
        customAdapter.setDropDownViewResource(R.layout.spinner_row_country);
        spinner_country.setAdapter(customAdapter);
    }

    private void handleArgs() {
        if (!TextUtils.isEmpty(SharedPreferenceUtil.getString(SellingConstants.PREF_TOKEN_ID, ""))) {
            layout_password.setVisibility(View.GONE);
            spinner_country.setVisibility(View.GONE);
            isLoggedIn = true;
            edit_mobile.setEnabled(false);
            spinner_country.setEnabled(false);
            edit_mobile.setText(SharedPreferenceUtil.getString(SellingConstants.PREF_LOGGED_IN_PHONE, ""));
            String email = SharedPreferenceUtil.getString(SellingConstants.PREF_LOGGED_IN_EMAIL, "");
            edit_email.setText(email);
            Log.e("Email------", email);

        }
    }

    private boolean checkDetails() {
        String email;
        email = edit_email.getText().toString().trim();

        if (email.isEmpty()) {
            showToast(getString(R.string.enter_email));
            return false;
        } else if (!isValidEmail(email)) {
            showToast(getString(R.string.enter_valid_email));
            return false;
        }
        return true;
    }

    private boolean isValidDetails() {
        String email, confirmEmail;
        email = edit_email.getText().toString().trim();
        confirmEmail = edit_confirm_email.getText().toString().trim();

        if (edit_mobile.getText().toString().trim().isEmpty()) {
            showToast(getString(R.string.enter_mo_no));
            return false;
        } else if (mCountryCode.isEmpty()) {
            showToast(getString(R.string.enter_country_code));
            return false;
        } else if (email.isEmpty()) {
            showToast(getString(R.string.enter_email));
            return false;
        } else if (!isValidEmail(email)) {
            showToast(getString(R.string.enter_valid_email));
            return false;
        } else if (confirmEmail.isEmpty()) {
            showToast(getString(R.string.enter_confirm_email));
            return false;
        } else if (!isValidEmail(confirmEmail)) {
            showToast(getString(R.string.enter_valid_confirm_email));
            return false;
        } else if (!email.equalsIgnoreCase(confirmEmail)) {
            showToast(getString(R.string.email_not_matched));
            return false;
        } else if (edit_password.getText().toString().trim().isEmpty()) {
            showToast(getString(R.string.enter_pass));
            return false;
        }
        return true;
    }

    private void registerUser() {
        if (NetworkUtil.isOnline(mContext)) {
            progressBar.setVisibility(View.VISIBLE);
            HashMap<String, String> signUpHashMap = new HashMap<String, String>();
            signUpHashMap.put(SellingApiConstants.KEY_PHONE, mCountryCode + edit_mobile.getText().toString().trim());
            signUpHashMap.put(SellingApiConstants.KEY_EMAIL, edit_email.getText().toString().trim());
            signUpHashMap.put(SellingApiConstants.KEY_PASSWORD, edit_password.getText().toString().trim());

            SellingAPIClient.createService(interceptor, mContext)
                    .signUp(signUpHashMap)
                    .enqueue(new Callback<SignUpResponseVo>() {
                        @Override
                        public void onResponse(Call<SignUpResponseVo> call, Response<SignUpResponseVo> response) {
                            progressBar.setVisibility(View.GONE);
                            if (response.body() != null && response.code() == 200 ||
                                    response.body() != null && response.code() == 201) {
                                SignUpResponseVo signUpVo = response.body();
                                PhoneUtil.addPhone(signUpVo.getPhone(), "");
                                ((SellingBaseActivity) mContext).popBackDirect();
                            } else {
                                String error = RetrofitErrorUtil.parseError(response);
                                if (error != null && !error.isEmpty())
                                    showToast(error);
                            }
                        }

                        @Override
                        public void onFailure(Call<SignUpResponseVo> call, Throwable t) {
                            progressBar.setVisibility(View.GONE);
                            showToast(t.getMessage());
                        }
                    });
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));

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

                if (isLoggedIn) {
                    if (checkDetails()) {

                        Bundle bundle = new Bundle();
                        bundle.putSerializable(SellingConstants.ARGUMENT_ADDRESS_DETAILS_VO, getSellingDetails());
                        CashDepositFragment fragment = new CashDepositFragment();
                        fragment.setArguments(bundle);

                        ((SellingBaseActivity) mContext).replaceFragment(fragment, true, true);
                    }
                } else {
                    if (isValidDetails())
                        registerUser();
                }

                break;
        }
    }

    //if user is logged in
    private AddressVo getSellingDetails() {
        AddressVo detailsVo = new AddressVo();
        detailsVo.setEmail(edit_email.getText().toString().trim());
        detailsVo.setPhone(edit_mobile.getText().toString().trim());
        detailsVo.setPhoneCode(mCountryCode);

        return detailsVo;
    }
}