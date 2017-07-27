package de.schildbach.wallet.ui;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.databinding.DataBindingUtil;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.bitcoinj.core.Coin;
import org.bitcoinj.uri.BitcoinURI;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import de.schildbach.wallet.AddressBookProvider;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.request.CreateAuthReq;
import de.schildbach.wallet.request.GetAuthTokenReq;
import de.schildbach.wallet.request.SendVerificationReq;
import de.schildbach.wallet.request.VerifyAdReq;
import de.schildbach.wallet.response.CreateAdReq;
import de.schildbach.wallet.response.CreateAdResp;
import de.schildbach.wallet.response.CreateAuthResp;
import de.schildbach.wallet.response.GetAuthTokenResp;
import de.schildbach.wallet.response.GetPricingOptionsResp;
import de.schildbach.wallet.response.GetReceivingOptionsResp;
import de.schildbach.wallet.response.SendVerificationResp;
import de.schildbach.wallet.response.VerifyAdResp;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.service.BlockchainStateLoader;
import de.schildbach.wallet.service.ServiceGenerator;
import de.schildbach.wallet.util.Qr;
import hashengineering.darkcoin.wallet.BuildConfig;
import hashengineering.darkcoin.wallet.R;
import hashengineering.darkcoin.wallet.databinding.SellDashFragmentBinding;
import okhttp3.Interceptor;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;


public final class SellDashFragment extends Fragment implements OnSharedPreferenceChangeListener {
    private static final String TAG = SellDashFragment.class.getSimpleName();
    private static final int ID_BALANCE_LOADER = 0;
    private static final int ID_RATE_LOADER = 1;
    private static final int ID_BLOCKCHAIN_STATE_LOADER = 2;
    private AbstractWalletActivity activity;
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private LoaderManager loaderManager;
    private Coin balance = null;
    private final LoaderManager.LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>() {
        @Override
        public Loader<Coin> onCreateLoader(final int id, final Bundle args) {
            return new WalletBalanceLoader(activity, wallet);
        }

        @Override
        public void onLoadFinished(final Loader<Coin> loader, final Coin balance) {
            SellDashFragment.this.balance = balance;

            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<Coin> loader) {
        }
    };
    @Nullable
    private BlockchainState blockchainState = null;

    //    binding.sellDashProgress binding.sellDashProgress;
//    View layoutCreateAuth;
//    EditText email, phone, pass;
//    Button createAuth;
    private final LoaderManager.LoaderCallbacks<BlockchainState> blockchainStateLoaderCallbacks = new LoaderManager.LoaderCallbacks<BlockchainState>() {
        @Override
        public Loader<BlockchainState> onCreateLoader(final int id, final Bundle args) {
            return new BlockchainStateLoader(activity);
        }

        @Override
        public void onLoadFinished(final Loader<BlockchainState> loader, final BlockchainState blockchainState) {
            SellDashFragment.this.blockchainState = blockchainState;

            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<BlockchainState> loader) {
        }
    };
    @Nullable
    private String defaultCurrency = null;
    private SellDashFragmentBinding binding;
    private List<GetReceivingOptionsResp> receivingOptionsResps;
    private GetReceivingOptionsResp receivingOptionsResp;
    private List<GetPricingOptionsResp> optionsRespList;
    private GetAuthTokenResp getAuthTokenResp;
    private SendVerificationResp sendVerificationResp;


    private Interceptor interceptor = new Interceptor() {
        @Override
        public okhttp3.Response intercept(Interceptor.Chain chain) throws IOException {
            Request original = chain.request();

            // Request customization: add request headers
            Request.Builder requestBuilder = original.newBuilder()
                    .addHeader("X-Coins-Api-Token", getAuthTokenResp.token);

            Request request = requestBuilder.build();
            return chain.proceed(request);
        }
    };
    private CreateAdResp createAdResp;
    private VerifyAdResp verifyAdResp;
    private Bitmap fundingAddressQrBitmap;
    ;

    @Override
    public void onAttach(final Context context) {
        super.onAttach(context);

        this.activity = (AbstractWalletActivity) context;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        this.wallet = application.getWallet();
        this.loaderManager = getLoaderManager();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(true);
        setHasOptionsMenu(true);


        defaultCurrency = config.getExchangeCurrencyCode();
        config.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {

        binding = DataBindingUtil.inflate(inflater, R.layout.sell_dash_fragment, container, false);

        // do something for a debug build
        if (BuildConfig.DEBUG) {
            binding.editSellDashPhone.setText("+919033949493");
            binding.editSellDashEmail.setText("viral@viralsonawala.com");
            binding.editSellDashPass.setText("1234qwer");
            binding.etMaxPayment.setText("1000");
            binding.etMinPayment.setText("10");
            binding.etSellerFee.setText("0");
        }

        binding.buttonSellDashCreateAuth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendCreateAuth();
            }
        });


        binding.buttonSellDashCreateAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendAdData();
            }
        });

        binding.buttonVerifyAd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                verifyAd();
            }
        });

        binding.cbIsDynamicPricing.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    getDynamicPricingOpt();
                    binding.etStaticPrice.setVisibility(View.GONE);
                    binding.llSelectPricingOpt.setVisibility(View.VISIBLE);
                } else {
                    binding.llSelectPricingOpt.setVisibility(View.GONE);
                    binding.etStaticPrice.setVisibility(View.VISIBLE);
                }
            }
        });
        return binding.getRoot();
    }

    private void verifyAd() {

        if (sendVerificationResp.__CASH_CODE.equals(binding.etOtp.getText().toString())) {

            final VerifyAdReq verifyAdReq = new VerifyAdReq();
            verifyAdReq.ad_id = "" + createAdResp.id;
            verifyAdReq.code = sendVerificationResp.__CASH_CODE;
            verifyAdReq.phone = getAuthTokenResp.phone;

            ServiceGenerator.createService(interceptor).verifyAd(verifyAdReq).enqueue(new Callback<VerifyAdResp>() {
                @Override
                public void onResponse(Call<VerifyAdResp> call, Response<VerifyAdResp> response) {
                    if (null != response && null != response.body()) {
                        verifyAdResp = response.body();

                        Uri mNewUri;
                        ContentValues mNewValues = new ContentValues();

                        mNewValues.put(AddressBookProvider.KEY_ADDRESS, verifyAdResp.fundingAddress);
                        mNewValues.put(AddressBookProvider.KEY_LABEL, receivingOptionsResp.name);

                        mNewUri = getActivity().getContentResolver().insert(
                                AddressBookProvider.contentUri(getContext().getPackageName()),
                                mNewValues
                        );

                        final int size = getResources().getDimensionPixelSize(R.dimen.bitmap_dialog_qr_size);
                        fundingAddressQrBitmap = Qr.bitmap("dash:" + verifyAdResp.fundingAddress, size);
                        binding.layoutVerifyOtp.setVisibility(View.GONE);
                        binding.ivFundingAddressQr.setVisibility(View.VISIBLE);
                        binding.ivFundingAddressQr.setImageBitmap(fundingAddressQrBitmap);
                    }
                }

                @Override
                public void onFailure(Call<VerifyAdResp> call, Throwable t) {
                    Log.e(TAG, "onFailure: ", t);
                }
            });
        } else {
            Toast.makeText(getContext(), "OTP not match, Try again!", Toast.LENGTH_LONG).show();
        }

    }

    private void getDynamicPricingOpt() {
        ServiceGenerator.createService().getPricingOptions().enqueue(new Callback<List<GetPricingOptionsResp>>() {
            @Override
            public void onResponse(Call<List<GetPricingOptionsResp>> call, Response<List<GetPricingOptionsResp>> response) {
                optionsRespList = response.body();
                List<String> primaryList = new ArrayList<String>();
                List<String> secondaryList = new ArrayList<String>();
                primaryList.add(0, "Select Primary market");
                secondaryList.add(0, "Select Secondary market");
                for (GetPricingOptionsResp getPricingOptionsResp : optionsRespList) {
                    primaryList.add(getPricingOptionsResp.label);
                    secondaryList.add(getPricingOptionsResp.label);
                }

                ArrayAdapter<String> primaryAdapter = new ArrayAdapter<String>(getActivity(), android.R.layout.simple_spinner_dropdown_item, primaryList);
                primaryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                binding.spPrimaryMarket.setAdapter(primaryAdapter);

                binding.spPrimaryMarket.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {

                    }
                });
                ArrayAdapter<String> secondaryAdapter = new ArrayAdapter<String>(getActivity(), android.R.layout.simple_spinner_dropdown_item, secondaryList);
                secondaryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                binding.spSecondaryMarket.setAdapter(secondaryAdapter);

                binding.spSecondaryMarket.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {

                    }
                });
            }

            @Override
            public void onFailure(Call<List<GetPricingOptionsResp>> call, Throwable t) {

            }
        });
    }

    private void sendAdData() {

        final CreateAdReq createAdReq = new CreateAdReq();
        createAdReq.phone = getAuthTokenResp.phone;
        createAdReq.email = getAuthTokenResp.email;
        createAdReq.phoneCode = "1";
        createAdReq.bankBusiness = "" + receivingOptionsResp.id;
        createAdReq.sellCrypto = "DASH";
        createAdReq.userEnabled = true;
        createAdReq.dynamicPrice = binding.cbIsDynamicPricing.isChecked();
        createAdReq.usePayFields = receivingOptionsResp.payFields.payFieldsB != null;

        JsonObject asJsonObject = new Gson().toJsonTree(createAdReq).getAsJsonObject();

        if (binding.cbIsDynamicPricing.isChecked()) {

            int selPrimary = binding.spPrimaryMarket.getSelectedItemPosition();
            int selSecondary = binding.spSecondaryMarket.getSelectedItemPosition();

            if (selPrimary == 0) {
                asJsonObject.addProperty("primaryMarket", "");
            } else {
                asJsonObject.addProperty("primaryMarket", optionsRespList.get(selPrimary - 1).id);
            }
            if (selSecondary == 0) {
                asJsonObject.addProperty("secondaryMarket", "");
            } else {
                asJsonObject.addProperty("secondaryMarket", optionsRespList.get(selSecondary - 1).id);
            }
            asJsonObject.addProperty("minPayment", binding.etMinPayment.getText().toString());
            asJsonObject.addProperty("maxPayment", binding.etMaxPayment.getText().toString());
            asJsonObject.addProperty("sellerFee", binding.etSellerFee.getText().toString());
        } else {
            asJsonObject.addProperty("currentPrice", binding.etStaticPrice.getText().toString());
        }

//        reqMap.put("usePayFields", receivingOptionsResp.payFields.payFieldsB != null);

        if (createAdReq.usePayFields) {
            int count = binding.llReceivingPricingOptions.getChildCount() - 1;
            Log.e(TAG, "sendAdData: " + count);
            for (int i = count; i >= 0; i--) {
                View view = binding.llReceivingPricingOptions.getChildAt(i);
                Object tag = view.getTag();
                if (tag != null && !tag.toString().contains("*")) {
                    EditText editText = (EditText) view;
                    EditText editTextC = (EditText) binding.llReceivingPricingOptions.findViewWithTag(tag + "*");
                    if (null != editTextC) {
                        if (!editText.getText().toString().equals(editTextC.getText().toString())) {
                            Toast.makeText(getContext(), "Confirm " + tag + " not match", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                    asJsonObject.addProperty("payfield_" + tag, editText.getText().toString());
                }
            }
        } else {
            asJsonObject.addProperty("name", ((EditText) binding.llReceivingPricingOptions.findViewById(R.id.et_name_acct)).getText().toString());
            asJsonObject.addProperty("number", ((EditText) binding.llReceivingPricingOptions.findViewById(R.id.et_acct)).getText().toString());
            asJsonObject.addProperty("number2", ((EditText) binding.llReceivingPricingOptions.findViewById(R.id.et_acct_confirm)).getText().toString());
        }

        ServiceGenerator.createService(interceptor).createAd(asJsonObject).enqueue(new Callback<CreateAdResp>() {
            @Override
            public void onResponse(Call<CreateAdResp> call, Response<CreateAdResp> response) {

                if (null != response && null != response.body()) {
                    createAdResp = response.body();

                    SendVerificationReq sendVerificationReq = new SendVerificationReq();
                    sendVerificationReq.ad_id = "" + createAdResp.id;
                    sendVerificationReq.phone = createAdReq.phone;

                    ServiceGenerator.createService(interceptor).sendVerification(sendVerificationReq).enqueue(new Callback<SendVerificationResp>() {
                        @Override
                        public void onResponse(Call<SendVerificationResp> call, Response<SendVerificationResp> response) {
                            if (null != response && null != response.body() && null != response.body().__CASH_CODE) {
                                binding.layoutCreateBankOpts.setVisibility(View.GONE);
                                binding.llReceivingPricingOptions.setVisibility(View.GONE);
                                binding.layoutVerifyOtp.setVisibility(View.VISIBLE);

                                sendVerificationResp = response.body();
                            }
                        }

                        @Override
                        public void onFailure(Call<SendVerificationResp> call, Throwable t) {
                            Log.e(TAG, "onFailure: ", t);
                        }
                    });
                }

            }

            @Override
            public void onFailure(Call<CreateAdResp> call, Throwable t) {

            }
        });
    }

    //send first request to get payment options
    private void sendCreateAuth() {
        if (TextUtils.isEmpty(binding.editSellDashEmail.getText()) || TextUtils.isEmpty(binding.editSellDashPhone.getText()) || TextUtils.isEmpty(binding.editSellDashPass.getText())) {
            Toast.makeText(getContext(), "All Fields are required!", Toast.LENGTH_LONG).show();
            return;
        }

        CreateAuthReq createAuthReq = new CreateAuthReq();
        createAuthReq.email = binding.editSellDashEmail.getText().toString();
        createAuthReq.password = binding.editSellDashPass.getText().toString();
        createAuthReq.phone = binding.editSellDashPhone.getText().toString();

        binding.sellDashProgress.setVisibility(View.VISIBLE);

        ServiceGenerator.createService().createAuth(createAuthReq).enqueue(new Callback<CreateAuthResp>() {
            @Override
            public void onResponse(Call<CreateAuthResp> call, Response<CreateAuthResp> response) {

                GetAuthTokenReq getAuthTokenReq = new GetAuthTokenReq();
                getAuthTokenReq.password = binding.editSellDashPass.getText().toString();

                ServiceGenerator.createService().getAuthToken(binding.editSellDashPhone.getText().toString(), getAuthTokenReq).enqueue(new Callback<GetAuthTokenResp>() {
                    @Override
                    public void onResponse(Call<GetAuthTokenResp> call, Response<GetAuthTokenResp> response) {
                        getAuthTokenResp = response.body();
                        String locale;
                        locale = getResources().getConfiguration().locale.getCountry();

                        ServiceGenerator.createService().getReceivingOptions(locale.toLowerCase()).enqueue(new Callback<List<GetReceivingOptionsResp>>() {
                            @Override
                            public void onResponse(Call<List<GetReceivingOptionsResp>> call, Response<List<GetReceivingOptionsResp>> response) {
                                Log.e(TAG, "onResponse: " + response.body().size());
                                binding.sellDashProgress.setVisibility(View.GONE);

                                receivingOptionsResps = response.body();
                                binding.layoutCreateAuth.setVisibility(View.GONE);
                                binding.layoutCreateBankOpts.setVisibility(View.VISIBLE);
                                //set data in drop down list
                                setPaymentOptNames(receivingOptionsResps);
                            }

                            @Override
                            public void onFailure(Call<List<GetReceivingOptionsResp>> call, Throwable t) {
                                binding.sellDashProgress.setVisibility(View.GONE);
                                t.printStackTrace();
                            }
                        });
                    }

                    @Override
                    public void onFailure(Call<GetAuthTokenResp> call, Throwable t) {
                        binding.sellDashProgress.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onFailure(Call<CreateAuthResp> call, Throwable t) {
                binding.sellDashProgress.setVisibility(View.GONE);
            }
        });
    }

    private void setPaymentOptNames(List<GetReceivingOptionsResp> receivingOptionsResps) {
        ArrayList<String> names = new ArrayList<String>();
        GetReceivingOptionsResp optionsRespDefaultName = new GetReceivingOptionsResp();
        optionsRespDefaultName.name = "Select Your Cash Account";
        receivingOptionsResps.add(0, optionsRespDefaultName);
        for (GetReceivingOptionsResp receivingOptionsResp : receivingOptionsResps) {
            names.add((receivingOptionsResp.name));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(getActivity(), android.R.layout.simple_spinner_dropdown_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spPaymentOptions.setAdapter(adapter);

        binding.spPaymentOptions.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) {
                    updateInputs(position);
                } else {
                    binding.llReceivingPricingOptions.removeAllViews();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

    }

    private void updateInputs(int position) {
        receivingOptionsResp = receivingOptionsResps.get(position);
        if (receivingOptionsResp.payFields.payFieldsB != null) {
            binding.llReceivingPricingOptions.removeAllViews();
            View defaultFieldView = LayoutInflater.from(getActivity()).inflate(R.layout.default_fields_receiving_opt, binding
                    .llReceivingPricingOptions, false);
            binding.llReceivingPricingOptions.addView(defaultFieldView);
        } else {
            binding.llReceivingPricingOptions.removeAllViews();
            // dynamic editText
            List<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> payFields = receivingOptionsResp.payFields.payFieldsX;
            String trigger = receivingOptionsResp.payFields.trigger;
            CheckBox cbTrigger = null;
            // setup trigger
            if (trigger != null) {
                cbTrigger = new CheckBox(getActivity());
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams
                        .WRAP_CONTENT);
                cbTrigger.setLayoutParams(params);
                cbTrigger.setText(trigger);
                binding.llReceivingPricingOptions.addView(cbTrigger);
            }
            // setup static field
            if (payFields != null && payFields.size() > 0) {
                // display sort comparator
                Comparator<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> payFieldsDisplayComparator = new Comparator<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean>() {
                    @Override
                    public int compare(GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean o1, GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean o2) {
                        return o1.displaySort < o2.displaySort ? -1 : (o1.displaySort == o2.displaySort ? 0 : 1);
                    }
                };
                Collections.sort(payFields, Collections.reverseOrder(payFieldsDisplayComparator));

                int sizeDynamicList = payFields.size();
                List<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> confirmFields = receivingOptionsResp.payFields.confirmFields;
                for (int i = 0; i < sizeDynamicList; i++) {
                    for (int j = 0; j < confirmFields.size(); j++) {
                        if (confirmFields.size() > 0 && payFields.get(i).idX == confirmFields.get(j).idX) {
                            payFields.add(i + 1, confirmFields.get(j));
                            confirmFields.remove(j);
                        }
                    }
                }
                List<String> ids = new ArrayList<>();
                for (GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean fieldsBean : payFields) {
                    EditText etField = new EditText(getActivity());
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams
                            .WRAP_CONTENT);
                    etField.setLayoutParams(params);
                    etField.setLines(1);
                    if (ids.contains(String.valueOf(fieldsBean.idX))) {
                        etField.setTag("" + fieldsBean.nameX + "*");
                    } else {
                        etField.setTag("" + fieldsBean.nameX);
                    }
                    ids.add(String.valueOf(fieldsBean.idX));
                    etField.setHint(fieldsBean.label);
                    etField.setLines(1);
                    binding.llReceivingPricingOptions.addView(etField);
                }
            }

            if (cbTrigger != null) {
                cbTrigger.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        int count = binding.llReceivingPricingOptions.getChildCount() - 1;
                        for (int i = count; i > 0; i--) {
                            binding.llReceivingPricingOptions.removeViewAt(i);
                        }
                        if (isChecked) {
                            // add dynamic fields
                            List<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> fieldsBeanList = new ArrayList<GetReceivingOptionsResp
                                    .PayFieldsBeanX.PayFieldsBean>();
                            fieldsBeanList.addAll(receivingOptionsResp.payFields.dynamicFields);
                            List<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> confirmFields = new ArrayList<GetReceivingOptionsResp
                                    .PayFieldsBeanX.PayFieldsBean>();
                            confirmFields.addAll(receivingOptionsResp.payFields.confirmFields);
                            if (fieldsBeanList.size() > 0) {
                                Comparator<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> payFieldsDisplayComparator = new Comparator<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean>() {
                                    @Override
                                    public int compare(GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean o1, GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean o2) {
                                        return o1.displaySort < o2.displaySort ? -1 : (o1.displaySort == o2.displaySort ? 0 : 1);
                                    }
                                };
                                Collections.sort(fieldsBeanList, Collections.reverseOrder(payFieldsDisplayComparator));

                                int sizeDynamicList = fieldsBeanList.size();
                                for (int i = 0; i < sizeDynamicList; i++) {
                                    for (int j = 0; j < confirmFields.size(); j++) {
                                        if (confirmFields.size() > 0 && fieldsBeanList.get(i).idX == confirmFields.get(j).idX) {
                                            fieldsBeanList.add(i + 1, confirmFields.get(j));
                                            confirmFields.remove(j);
                                        }
                                    }
                                }
                                List<String> ids = new ArrayList<>();
                                for (GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean fieldsBean : fieldsBeanList) {
                                    EditText etField = new EditText(getActivity());
                                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams
                                            .WRAP_CONTENT);
                                    etField.setLayoutParams(params);
                                    etField.setLines(1);
                                    if (ids.contains(String.valueOf(fieldsBean.idX))) {
                                        etField.setTag("" + fieldsBean.nameX + "*");
                                    } else {
                                        etField.setTag("" + fieldsBean.nameX);
                                    }
                                    ids.add(String.valueOf(fieldsBean.idX));
                                    etField.setHint(fieldsBean.label);
                                    etField.setLines(1);
                                    binding.llReceivingPricingOptions.addView(etField);
                                }
                            }
                        } else {
                            List<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> payFields = receivingOptionsResp.payFields.payFieldsX;
                            List<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> confirmFields = receivingOptionsResp.payFields.confirmFields;
                            // setup static field
                            if (payFields != null && payFields.size() > 0) {
                                // display sort comparator
                                Comparator<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean> payFieldsDisplayComparator = new Comparator<GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean>() {
                                    @Override
                                    public int compare(GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean o1, GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean o2) {
                                        return o1.displaySort < o2.displaySort ? -1 : (o1.displaySort == o2.displaySort ? 0 : 1);
                                    }
                                };
                                Collections.sort(payFields, payFieldsDisplayComparator);

                                for (int i = 0; i < payFields.size(); i++) {
                                    for (int j = 0; j < confirmFields.size(); j++) {
                                        if (payFields.get(i).idX == confirmFields.get(j).idX) {
                                            payFields.add(i + 1, confirmFields.get(j));
                                        }
                                    }
                                }
                                List<String> ids = new ArrayList<>();
                                for (GetReceivingOptionsResp.PayFieldsBeanX.PayFieldsBean fieldsBean : payFields) {
                                    EditText etField = new EditText(getActivity());
                                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams
                                            .WRAP_CONTENT);
                                    etField.setLayoutParams(params);
                                    etField.setLines(1);
                                    if (ids.contains(String.valueOf(fieldsBean.idX))) {
                                        etField.setTag("" + fieldsBean.nameX + "*");
                                    } else {
                                        etField.setTag("" + fieldsBean.nameX);
                                    }
                                    ids.add(String.valueOf(fieldsBean.idX));
                                    etField.setHint(fieldsBean.label);
                                    etField.setLines(1);
                                    binding.llReceivingPricingOptions.addView(etField);
                                }
                            }
                        }
                    }
                });
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);
        loaderManager.initLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);

        updateView();
    }

    @Override
    public void onPause() {
        loaderManager.destroyLoader(ID_BALANCE_LOADER);
        loaderManager.destroyLoader(ID_BLOCKCHAIN_STATE_LOADER);

        super.onPause();
    }

    @Override
    public void onDestroy() {
        config.unregisterOnSharedPreferenceChangeListener(this);

        loaderManager.destroyLoader(ID_RATE_LOADER);

        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (Configuration.PREFS_KEY_EXCHANGE_CURRENCY.equals(key) || Configuration.PREFS_KEY_BTC_PRECISION.equals(key)) {
            defaultCurrency = config.getExchangeCurrencyCode();

            updateView();
        }
    }

    private void updateView() {
        balance = application.getWallet().getBalance(BalanceType.ESTIMATED);
    }
}
