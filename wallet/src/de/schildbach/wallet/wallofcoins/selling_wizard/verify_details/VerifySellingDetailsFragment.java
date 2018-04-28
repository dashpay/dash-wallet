package de.schildbach.wallet.wallofcoins.selling_wizard.verify_details;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;

import java.util.HashMap;

import de.schildbach.wallet.wallofcoins.buyingwizard.utils.NetworkUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseActivity;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.RetrofitErrorUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingAPIClient;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingApiConstants;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.AddressVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SendVerificationRespVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.SellingConstants;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.WOCLogUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.verification_otp.VerifycationCodeFragment;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created by  on 05-Apr-18.
 */

public class VerifySellingDetailsFragment extends SellingBaseFragment implements View.OnClickListener {
    private View rootView;
    private Button button_continue;
    private ProgressBar progressBar;
    private EditText edit_account, edit_price, edit_email, edit_phone;
    private AddressVo addressVo;
    private String mAddressId, mPhone;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_selling_verify_details, container, false);
            init();
            setListeners();
            setTopbar();
            handleArgs();
            return rootView;
        } else
            return rootView;
    }

    private void init() {
        button_continue = (Button) rootView.findViewById(R.id.button_continue);
        progressBar = (ProgressBar) rootView.findViewById(R.id.progressBar);
        edit_account = (EditText) rootView.findViewById(R.id.edit_account);
        edit_price = (EditText) rootView.findViewById(R.id.edit_price);
        edit_email = (EditText) rootView.findViewById(R.id.edit_email);
        edit_phone = (EditText) rootView.findViewById(R.id.edit_phone);
    }

    private void setListeners() {
        button_continue.setOnClickListener(this);
    }

    private void setTopbar() {

        ((SellingBaseActivity) mContext).setTopbarTitle(
                mContext.getString(R.string.title_verify_selling_details));
    }

    private void handleArgs() {

        if (getArguments() != null) {
            addressVo = (AddressVo)
                    getArguments().getSerializable(SellingConstants.ARGUMENT_ADDRESS_DETAILS_VO);
            mPhone = addressVo.getNumber();
            edit_account.setText(addressVo.getNumber());
            edit_price.setText(addressVo.getCurrentPrice());
            edit_email.setText(addressVo.getEmail());
            edit_phone.setText(addressVo.getPhone());
            addressVo.setUserEnabled(true);
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
                // createAddress();
                navigateToCodeScreen("52015");
                break;
        }
    }

    private void createAddress() {
        if (NetworkUtil.isOnline(mContext)) {
/**
 * {
 "phone": "9417972681",
 "email": "demo@geni.to",
 "phoneCode": "1",
 "bankBusiness": "2",
 "sellCrypto": "DASH",
 "userEnabled": true,
 "dynamicPrice": true,
 "primaryMarket": "5",
 "secondaryMarket": "4",
 "minPayment": "10",
 "maxPayment": "1000",
 "sellerFee": "111",
 "currentPrice": "11",
 "name": "a",
 "number": "1",
 "number2": "1"
 }

 number=123&dynamicPrice=true&
 number2=123&primaryMarket=5&
 bankBusiness=null&
 email=abc%40gmail.com&phone=%2B123977765&
 minPayment=10&phoneCode=1&sellCrypto=DASH&
 maxPayment=11&name=abc&sellerFee=12&secondaryMarket=4&currentPrice=10&userEnabled=true



 */
            HashMap<String, Object> hashMap = new HashMap<String, Object>();
            hashMap.put(SellingApiConstants.KEY_PHONE, "2397776543");
            hashMap.put(SellingApiConstants.KEY_EMAIL, addressVo.getEmail());
            hashMap.put(SellingApiConstants.KEY_PHONE_CODE, "1");
            hashMap.put(SellingApiConstants.KEY_BANK_BUSINESS, "" + addressVo.getBankBusiness());//bank id
            hashMap.put(SellingApiConstants.KEY_SELL_CRYPTO, "DASH");
            hashMap.put(SellingApiConstants.KEY_USER_ENABLED, "true");
            hashMap.put(SellingApiConstants.KEY_DYNAMIC_PRICE, "" + addressVo.getDynamicPrice());
            //hashMap.put(SellingApiConstants.KEY_USER_PAY_FIELDS, "" + (receivingOptionsResp.payFields.payFieldsB == null));

            if (addressVo.getDynamicPrice()) {

                hashMap.put(SellingApiConstants.KEY_PRIMARY_MARKETS, addressVo.getPrimaryMarket());
                hashMap.put(SellingApiConstants.KEY_SECONDARY_MARKETS, addressVo.getSecondaryMarket());

                hashMap.put(SellingApiConstants.KEY_MIN_PAYMETS, addressVo.getMinPayment());
                hashMap.put(SellingApiConstants.KEY_MAX_PAYMETS, addressVo.getMaxPayment());
                hashMap.put(SellingApiConstants.KEY_SELLER_FEE, addressVo.getSellerFee());
            }

            hashMap.put(SellingApiConstants.KEY_CURRENT_PRICE, addressVo.getCurrentPrice());

            hashMap.put(SellingApiConstants.KEY_NAME, addressVo.getName());//acc holder name
            hashMap.put(SellingApiConstants.KEY_NUMBER, addressVo.getNumber());//acc number
            hashMap.put(SellingApiConstants.KEY_NUMBER2, addressVo.getNumber2());//acc confirm number


            progressBar.setVisibility(View.VISIBLE);
            SellingAPIClient.createService(interceptor, mContext).createAddress(hashMap).
                    enqueue(new Callback<AddressVo>() {
                        @Override
                        public void onResponse(Call<AddressVo> call, Response<AddressVo> response) {
                            progressBar.setVisibility(View.GONE);

                            if (response.code() == 200) {
                                AddressVo addressVo = response.body();
                                mAddressId = addressVo.getId();
                                WOCLogUtil.showLogError("Address Id:", addressVo.getId());
                                sendVerificationCode(addressVo.getPhone(), addressVo.getId());
                            } else {
                                String error = RetrofitErrorUtil.parseError(response);
                                if (error != null && !error.isEmpty())
                                    showToast(error);
                            }

                        }

                        @Override
                        public void onFailure(Call<AddressVo> call, Throwable t) {
                            showToast(t.getMessage());
                            progressBar.setVisibility(View.GONE);
                        }
                    });

        } else
            showToast(mContext.getString(R.string.network_not_avaialable));
    }

    private void sendVerificationCode(String phone, String addId) {
        if (NetworkUtil.isOnline(mContext)) {
            HashMap<String, String> hashMap = new HashMap<String, String>();
            hashMap.put(SellingApiConstants.KEY_PUBLISHER_ID, SellingApiConstants.WALLOFCOINS_PUBLISHER_ID);
            hashMap.put(SellingApiConstants.KEY_PHONE, phone);
            hashMap.put(SellingApiConstants.AD_ID, addId);

            progressBar.setVisibility(View.VISIBLE);
            SellingAPIClient.createService(interceptor, mContext).sendVerificationCode(hashMap).
                    enqueue(new Callback<SendVerificationRespVo>() {
                        @Override
                        public void onResponse(Call<SendVerificationRespVo> call, Response<SendVerificationRespVo> response) {
                            progressBar.setVisibility(View.GONE);

                            if (response.code() == 200) {
                                navigateToCodeScreen(response.body().__CASH_CODE);

                            } else {
                                String error = RetrofitErrorUtil.parseError(response);
                                if (error != null && !error.isEmpty())
                                    showToast(error);
                            }

                        }

                        @Override
                        public void onFailure(Call<SendVerificationRespVo> call, Throwable t) {
                            showToast(t.getMessage());
                            progressBar.setVisibility(View.GONE);
                        }
                    });

        } else
            showToast(mContext.getString(R.string.network_not_avaialable));
    }

    private void navigateToCodeScreen(String code) {
        Bundle bundle = new Bundle();
        bundle.putSerializable(SellingConstants.ARGUMENT_VERIFICATION_CODE, code);
        bundle.putSerializable(SellingConstants.ARGUMENT_PHONE_NUMBER, mPhone);
        bundle.putSerializable(SellingConstants.ARGUMENT_ADDRESS_ID, mAddressId);
        VerifycationCodeFragment fragment = new VerifycationCodeFragment();
        fragment.setArguments(bundle);

        ((SellingBaseActivity) mContext).replaceFragment(fragment, true, true);
    }
}
