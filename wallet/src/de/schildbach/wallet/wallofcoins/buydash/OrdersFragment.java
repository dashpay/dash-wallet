package de.schildbach.wallet.wallofcoins.buydash;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.databinding.DataBindingUtil;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.uri.BitcoinURI;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.Nullable;

import de.schildbach.wallet.BuyDashPref;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.ExchangeRatesProvider;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.service.BlockchainStateLoader;
import de.schildbach.wallet.ui.AbstractWalletActivity;
import de.schildbach.wallet.ui.AddressAndLabel;
import de.schildbach.wallet.ui.CurrencyAmountView;
import de.schildbach.wallet.ui.CurrencyCalculatorLink;
import de.schildbach.wallet.ui.ExchangeRateLoader;
import de.schildbach.wallet.ui.WalletBalanceLoader;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.response.BuyDashErrorResp;
import de.schildbach.wallet.wallofcoins.response.CaptureHoldResp;
import de.schildbach.wallet.wallofcoins.response.ConfirmDepositResp;
import de.schildbach.wallet.wallofcoins.response.OrderListResp;
import hashengineering.darkcoin.wallet.R;
import hashengineering.darkcoin.wallet.databinding.BuyDashFragmentBinding;
import hashengineering.darkcoin.wallet.databinding.ItemBankDetailBinding;
import hashengineering.darkcoin.wallet.databinding.ItemOrderListBinding;
import okhttp3.Interceptor;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;


public final class OrdersFragment extends Fragment implements OnSharedPreferenceChangeListener {
    private static final String TAG = OrdersFragment.class.getSimpleName();
    private static final int ID_BALANCE_LOADER = 0;
    private static final int ID_RATE_LOADER = 1;
    private static final int ID_BLOCKCHAIN_STATE_LOADER = 2;
    private static final int ID_ADDRESS_LOADER = 4;
    private static final int PERMISSIONS_REQUEST_LOCATION = 8989;
    private AbstractWalletActivity activity;
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private LoaderManager loaderManager;
    private Coin balance = null;
    private CurrencyCalculatorLink amountCalculatorLink;
    private BuyDashPref buyDashPref;
    private AddressAndLabel currentAddressQrAddress = null;


    private final LoaderManager.LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            return new ExchangeRateLoader(activity, config);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> loader, final Cursor data) {
            if (data != null && data.getCount() > 0) {
                data.moveToFirst();
                final ExchangeRatesProvider.ExchangeRate exchangeRate = ExchangeRatesProvider.getExchangeRate(data);

                amountCalculatorLink.setExchangeRate(exchangeRate.rate);
                updateView();
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> loader) {
        }
    };

    private final LoaderManager.LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>() {
        @Override
        public Loader<Coin> onCreateLoader(final int id, final Bundle args) {
            return new WalletBalanceLoader(activity, wallet);
        }

        @Override
        public void onLoadFinished(final Loader<Coin> loader, final Coin balance) {
            OrdersFragment.this.balance = balance;
            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<Coin> loader) {
        }
    };

    @Nullable
    private BlockchainState blockchainState = null;


    private final LoaderManager.LoaderCallbacks<BlockchainState> blockchainStateLoaderCallbacks = new LoaderManager.LoaderCallbacks<BlockchainState>() {
        @Override
        public Loader<BlockchainState> onCreateLoader(final int id, final Bundle args) {
            return new BlockchainStateLoader(activity);
        }

        @Override
        public void onLoadFinished(final Loader<BlockchainState> loader, final BlockchainState blockchainState) {
            OrdersFragment.this.blockchainState = blockchainState;

            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<BlockchainState> loader) {
        }
    };

    private final LoaderManager.LoaderCallbacks<Address> addressLoaderCallbacks = new LoaderManager.LoaderCallbacks<Address>() {
        @Override
        public Loader<Address> onCreateLoader(final int id, final Bundle args) {
            return new CurrentAddressLoader(activity, application.getWallet(), config);
        }

        @Override
        public void onLoadFinished(Loader<Address> loader, Address currentAddress) {
            if (!currentAddress.equals(currentAddressQrAddress)) {

                currentAddressQrAddress = new AddressAndLabel(currentAddress, config.getOwnName());

                addressStr = BitcoinURI.convertToBitcoinURI(currentAddressQrAddress.address, null, currentAddressQrAddress.label, null);
            }
        }

        @Override
        public void onLoaderReset(Loader<Address> loader) {

        }
    };
    private boolean isBuyMoreVisible;

    public static class CurrentAddressLoader extends AsyncTaskLoader<Address> {
        private LocalBroadcastManager broadcastManager;
        private final Wallet wallet;
        private Configuration config;

        private static final Logger log = LoggerFactory.getLogger(WalletBalanceLoader.class);

        public CurrentAddressLoader(final Context context, final Wallet wallet, final Configuration config) {
            super(context);

            this.broadcastManager = LocalBroadcastManager.getInstance(context.getApplicationContext());
            this.wallet = wallet;
            this.config = config;
        }

        @Override
        protected void onStartLoading() {
            super.onStartLoading();

            wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletChangeListener);
            wallet.addCoinsSentEventListener(Threading.SAME_THREAD, walletChangeListener);
            wallet.addChangeEventListener(Threading.SAME_THREAD, walletChangeListener);
            broadcastManager.registerReceiver(walletChangeReceiver, new IntentFilter(WalletApplication.ACTION_WALLET_REFERENCE_CHANGED));
            config.registerOnSharedPreferenceChangeListener(preferenceChangeListener);

            safeForceLoad();
        }

        @Override
        protected void onStopLoading() {
            config.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
            broadcastManager.unregisterReceiver(walletChangeReceiver);
            wallet.removeChangeEventListener(walletChangeListener);
            wallet.removeCoinsSentEventListener(walletChangeListener);
            wallet.removeCoinsReceivedEventListener(walletChangeListener);
            walletChangeListener.removeCallbacks();

            super.onStopLoading();
        }

        @Override
        protected void onReset() {
            config.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
            broadcastManager.unregisterReceiver(walletChangeReceiver);
            wallet.removeChangeEventListener(walletChangeListener);
            wallet.removeCoinsSentEventListener(walletChangeListener);
            wallet.removeCoinsReceivedEventListener(walletChangeListener);
            walletChangeListener.removeCallbacks();

            super.onReset();
        }

        @Override
        public Address loadInBackground() {
            org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

            return wallet.currentReceiveAddress();
        }

        private final ThrottlingWalletChangeListener walletChangeListener = new ThrottlingWalletChangeListener() {
            @Override
            public void onThrottledWalletChanged() {
                safeForceLoad();
            }
        };

        private final BroadcastReceiver walletChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                safeForceLoad();
            }
        };

        private final OnSharedPreferenceChangeListener preferenceChangeListener = new OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
                if (Configuration.PREFS_KEY_OWN_NAME.equals(key))
                    safeForceLoad();
            }
        };

        private void safeForceLoad() {
            try {
                forceLoad();
            } catch (final RejectedExecutionException x) {
                log.info("rejected execution: " + OrdersFragment.CurrentAddressLoader.this.toString());
            }
        }
    }

    private BuyDashFragmentBinding binding;

    private Interceptor interceptor = new Interceptor() {
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            Request original = chain.request();

            // Request customization: add request headers
            Request.Builder requestBuilder = original.newBuilder()
                    .addHeader("X-Coins-Api-Token", buyDashPref.getAuthToken())
                    .addHeader("publisherId", addressStr);

            Request request = requestBuilder.build();
            return chain.proceed(request);
        }
    };

    private String addressStr = "";

    @Override
    public void onAttach(final Context context) {
        super.onAttach(context);

        this.activity = (AbstractWalletActivity) context;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();

        this.wallet = application.getWallet();
        this.loaderManager = getLoaderManager();

    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.buyDashPref = new BuyDashPref(PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext()));

        setRetainInstance(true);
        setHasOptionsMenu(true);

        getDeviceId(getContext());
        config.registerOnSharedPreferenceChangeListener(this);
        buyDashPref.registerOnSharedPreferenceChangeListener(this);
    }

    String getDeviceId(Context context) {
        String deviceID = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        byte[] data = (deviceID + deviceID + deviceID).getBytes(Charsets.UTF_8);
        return Base64.encodeToString(data, Base64.DEFAULT).substring(0, 39);
    }





    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {

        binding = DataBindingUtil.inflate(inflater, R.layout.buy_dash_fragment, container, false);


        binding.buttonBuyDashGetLocationNo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                binding.layoutLocation.setVisibility(View.GONE);
                binding.layoutZip.setVisibility(View.VISIBLE);
            }
        });




        if (config.getFormat().code().equals("DASH")) {
            binding.btnBuyMore.setText("Buy More Dash!");
        } else {
            binding.btnBuyMore.setText("Buy More Bitcoin!");
        }
        binding.requestCoinsAmountBtc.setCurrencySymbol(config.getFormat().code());
        binding.requestCoinsAmountBtc.setInputFormat(config.getMaxPrecisionFormat());
        binding.requestCoinsAmountBtc.setHintFormat(config.getFormat());

        binding.requestCoinsAmountLocal.setInputFormat(Constants.LOCAL_FORMAT);
        binding.requestCoinsAmountLocal.setHintFormat(Constants.LOCAL_FORMAT);
        amountCalculatorLink = new CurrencyCalculatorLink(binding.requestCoinsAmountBtc, binding.requestCoinsAmountLocal);

        binding.rvOffers.setLayoutManager(new LinearLayoutManager(activity));


        binding.buttonVerifyOtp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HashMap<String, String> captureHoldReq = new HashMap<String, String>();
                String otp = binding.etOtp.getText().toString().trim();
                if (TextUtils.isEmpty(otp)) {
                    Toast.makeText(getContext(), "Please Enter Purchase Code!", Toast.LENGTH_LONG).show();
                    return;
                }
                captureHoldReq.put("publisherId", addressStr);
                captureHoldReq.put("verificationCode", otp);
                Log.d(TAG, "onClick: authToken==>>" + buyDashPref.getAuthToken());
                binding.linearProgress.setVisibility(View.VISIBLE);
                WallofCoins.createService(interceptor, getActivity()).captureHold(buyDashPref.getHoldId(), captureHoldReq)
                        .enqueue(new Callback<List<CaptureHoldResp>>() {
                            @Override
                            public void onResponse(Call<List<CaptureHoldResp>> call, final Response<List<CaptureHoldResp>> response) {
                                binding.linearProgress.setVisibility(View.GONE);
                                if (null != response && null != response.body() && !response.body().isEmpty()) {
                                    if (response.body().get(0).account != null && !TextUtils.isEmpty(response.body().get(0).account)) {
                                        if (isJSONValid(response.body().get(0).account)) {
                                            ItemBankDetailBinding itemBankBinding = DataBindingUtil.inflate(LayoutInflater.from(activity), R.layout.item_bank_detail, null, false);
                                            Glide.with(activity).load(response.body().get(0).bankLogo).into(itemBankBinding.imageBank);
                                            if (response.body().get(0).bankName != null && !TextUtils.isEmpty(response.body().get(0).bankName)) {
                                                itemBankBinding.textBankName.setText(response.body().get(0).bankName);
                                            } else {
                                                itemBankBinding.textBankName.setVisibility(View.GONE);
                                            }
                                            if (response.body().get(0).nearestBranch.phone != null && !TextUtils.isEmpty(response.body().get(0).nearestBranch.phone)) {
                                                itemBankBinding.textPhone.setText("Phone: " + response.body().get(0).nearestBranch.phone);
                                            } else {
                                                itemBankBinding.textPhone.setVisibility(View.GONE);
                                            }

                                            if (response.body().get(0).nameOnAccount != null && !TextUtils.isEmpty(response.body().get(0).nameOnAccount)) {
                                                itemBankBinding.textNameAccount.setText("Name on Account: " + response.body().get(0).nameOnAccount);
                                            } else {
                                                itemBankBinding.textNameAccount.setVisibility(View.GONE);
                                            }

                                            if (response.body().get(0).payment != null && !TextUtils.isEmpty(response.body().get(0).payment)) {
                                                itemBankBinding.textCashToDeposite.setText("Cash to Deposit: " + response.body().get(0).payment);
                                            } else {
                                                itemBankBinding.textCashToDeposite.setVisibility(View.GONE);
                                            }

                                            if (response.body().get(0).paymentDue != null && !TextUtils.isEmpty(response.body().get(0).paymentDue)) {
                                                itemBankBinding.textDepositeDue.setText("Deposit Due: " + response.body().get(0).paymentDue.substring(0, 16).replace("T", " "));
                                            } else {
                                                itemBankBinding.textDepositeDue.setVisibility(View.GONE);
                                            }
                                            Type listType = new TypeToken<ArrayList<AccountJson>>() {
                                            }.getType();
                                            ArrayList<AccountJson> accountList = new Gson().fromJson(response.body().get(0).account, listType);
                                            for (int i = 0; i < accountList.size(); i++) {
                                                TextView textView = new TextView(getActivity());
                                                textView.setTextSize(16);
                                                LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                                                layoutParams.topMargin = 8;
                                                textView.setLayoutParams(layoutParams);
                                                textView.setText(accountList.get(i).getLabel() + ": " + accountList.get(i).getValue());
                                                itemBankBinding.linearAccountDetail.addView(textView);
                                            }
                                            binding.layoutCompletionDetail.removeAllViews();
                                            binding.layoutCompletionDetail.addView(itemBankBinding.getRoot());

                                            if (response.body().get(0).status.equals("WD")) {
                                                itemBankBinding.btnCancelOrder.setVisibility(View.VISIBLE);
                                                itemBankBinding.btnDepositFinished.setVisibility(View.VISIBLE);
                                            } else {
                                                itemBankBinding.btnCancelOrder.setVisibility(View.GONE);
                                                itemBankBinding.btnDepositFinished.setVisibility(View.GONE);
                                            }

                                            itemBankBinding.btnDepositFinished.setOnClickListener(new View.OnClickListener() {
                                                @Override
                                                public void onClick(View v) {
                                                    confirmDeposit(response.body().get(0));
                                                }
                                            });

                                            itemBankBinding.btnCancelOrder.setOnClickListener(new View.OnClickListener() {
                                                @Override
                                                public void onClick(View v) {
                                                    // call cancel order
                                                    cancelOrder("" + response.body().get(0).id);
                                                }
                                            });
                                        } else {
                                            binding.setConfiremedData(response.body().get(0));
                                        }
                                    } else {
                                        binding.setConfiremedData(response.body().get(0));
                                    }
                                    binding.layoutVerifyOtp.setVisibility(View.GONE);
                                    binding.layoutCompletionDetail.setVisibility(View.VISIBLE);

                                    switch (response.body().get(0).status) {
                                        case "WD":
                                            binding.orderStatus.setText("Status: Waiting Deposit");
                                            break;
                                        case "WDV":
                                            binding.orderStatus.setText("Status: Waiting Deposit Verification");
                                            break;
                                        case "RERR":
                                            binding.orderStatus.setText("Status: Issue w/ Receipt, Contacted Buyer");
                                            break;
                                        case "DERR":
                                            binding.orderStatus.setText("Status: Issue with Deposit, Needs Follow-up");
                                            break;
                                        case "RSD":
                                            binding.orderStatus.setText("Status: Reserved for Seller Deposit");
                                            break;
                                        case "RMIT":
                                            binding.orderStatus.setText("Status: Remit Address Missing");
                                            break;
                                        case "UCRV":
                                            binding.orderStatus.setText("Status: Under Compliance Review");
                                            break;
                                        case "PAYP":
                                            binding.orderStatus.setText("Status: Done - Pending Delivery");
                                            break;
                                        case "SENT":
                                            binding.orderStatus.setText("Status: Done - Units Delivered");
                                            break;
                                        case "CANC":
                                            binding.orderStatus.setText("Status: Buyer Canceled");
                                            break;
                                        case "ACAN":
                                            binding.orderStatus.setText("Status: Staff Canceled");
                                            break;
                                        case "EXP":
                                            binding.orderStatus.setText("Status: Deposit Time Expired");
                                            break;
                                        case "ML":
                                            binding.orderStatus.setText("Status: Meat Locker");
                                            break;
                                        case "MLR":
                                            binding.orderStatus.setText("Status: Meat Locker Returned");
                                            break;
                                    }
                                    binding.textNearByCenter.setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            String yourAddress = response.body().get(0).nearestBranch.name
                                                    + ", " + response.body().get(0).nearestBranch.address
                                                    + ", " + response.body().get(0).nearestBranch.city
                                                    + ", " + response.body().get(0).nearestBranch.state;
                                            String uri = "http://maps.google.co.in/maps?q=" + yourAddress;
                                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                                            activity.startActivity(intent);
                                        }
                                    });
                                    binding.btnDepositFinished.setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            confirmDeposit(response.body().get(0));
                                        }
                                    });

                                    binding.btnCancelOrder.setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            // call cancel order
                                            cancelOrder("" + response.body().get(0).id);
                                        }
                                    });

                                } else if (null != response && null != response.errorBody()) {
                                    binding.linearProgress.setVisibility(View.GONE);
                                    try {
                                        BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                                        Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                    }
                                } else {
                                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                    binding.linearProgress.setVisibility(View.GONE);
                                }
                            }

                            @Override
                            public void onFailure
                                    (Call<List<CaptureHoldResp>> call, Throwable t) {
                                binding.linearProgress.setVisibility(View.GONE);
                                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                Log.e(TAG, "onFailure: ", t);
                            }
                        });
            }
        });

        return binding.getRoot();
    }



    public boolean isJSONValid(String test) {
        try {
            new JSONObject(test);
        } catch (JSONException ex) {
            // edited, to include @Arthur's comment
            // e.g. in case JSONArray is valid as well...
            try {
                new JSONArray(test);
            } catch (JSONException ex1) {
                return false;
            }
        }
        return true;
    }

    private void cancelOrder(String orderId) {
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, activity).cancelOrder(orderId, addressStr).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                binding.linearProgress.setVisibility(View.GONE);
                if (response.code() == 204) {
                    Toast.makeText(getContext(), "Your Order cancelled successfully", Toast.LENGTH_SHORT).show();
                    activity.finish();
                } else {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "onFailure: ", t);
                binding.linearProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmDeposit(CaptureHoldResp response) {
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).confirmDeposit("" + response.id, "", addressStr).enqueue(new Callback<ConfirmDepositResp>() {
            @Override
            public void onResponse(Call<ConfirmDepositResp> call, Response<ConfirmDepositResp> response) {
                binding.linearProgress.setVisibility(View.GONE);

                if (null != response && null != response.body()) {
                    binding.layoutCompletionDetail.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Your order is confirmed successfully", Toast.LENGTH_SHORT).show();
                    activity.finish();
                } else if (null != response && null != response.errorBody()) {
                    try {
                        BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                        Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }

                } else {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<ConfirmDepositResp> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Log.e(TAG, "onFailure: ", t);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        amountCalculatorLink.setExchangeDirection(config.getLastExchangeDirection());
        amountCalculatorLink.requestFocus();
    }

    @Override
    public void onResume() {
        super.onResume();

        amountCalculatorLink.setListener(new CurrencyAmountView.Listener() {
            @Override
            public void changed() {
                updateView();
            }

            @Override
            public void focusChanged(final boolean hasFocus) {
            }
        });
        loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);

        loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);
        loaderManager.initLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);
        loaderManager.initLoader(ID_ADDRESS_LOADER, null, addressLoaderCallbacks);

        updateView();
    }

    @Override
    public void onPause() {

        loaderManager.destroyLoader(ID_BALANCE_LOADER);
        loaderManager.destroyLoader(ID_BLOCKCHAIN_STATE_LOADER);
        loaderManager.destroyLoader(ID_ADDRESS_LOADER);

        amountCalculatorLink.setListener(null);

        super.onPause();
    }

    @Override
    public void onDestroy() {
        config.unregisterOnSharedPreferenceChangeListener(this);

        config.setLastExchangeDirection(amountCalculatorLink.getExchangeDirection());
        loaderManager.destroyLoader(ID_RATE_LOADER);

        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (Configuration.PREFS_KEY_EXCHANGE_CURRENCY.equals(key) || Configuration.PREFS_KEY_BTC_PRECISION.equals(key)) {
            updateView();
        }
    }

    private void updateView() {
        balance = application.getWallet().getBalance(BalanceType.ESTIMATED);
    }

    private String bankId = "";


    public void getOrderList(final boolean isFromCreateHold) {
        Log.d(TAG, "getOrderList: " + buyDashPref.getAuthToken());
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, activity).getOrders(addressStr).enqueue(new Callback<List<OrderListResp>>() {
            @Override
            public void onResponse(Call<List<OrderListResp>> call, Response<List<OrderListResp>> response) {
                binding.linearProgress.setVisibility(View.GONE);
                if (response.code() == 200 && response.body() != null) {
                    Log.d(TAG, "onResponse: boolean==>" + isFromCreateHold);
                    if (isFromCreateHold) {
                        List<OrderListResp> orderList = response.body();
                        if (orderList != null && orderList.size() > 0) {
                            for (OrderListResp orderListResp : orderList) {
                                if (orderListResp.status.equals("WD")) {
                                    Log.d(TAG, "onResponse: status==>" + orderListResp.status);
                                    manageOrderList(response);
                                    break;
                                } else {
                                    Log.d(TAG, "onResponse: status2==>" + orderListResp.status);
                                    binding.layoutCreateHold.setVisibility(View.GONE);
                                    binding.linearPhone.setVisibility(View.GONE);
                                    binding.linearEmail.setVisibility(View.GONE);
                                    binding.linearPassword.setVisibility(View.GONE);
                                    binding.layoutCompletionDetail.setVisibility(View.GONE);
                                    binding.rvOrderList.setVisibility(View.GONE);
                                    binding.layoutVerifyOtp.setVisibility(View.VISIBLE);
                                    binding.rvOffers.setVisibility(View.GONE);
                                }
                            }
                        } else {
                            binding.layoutCreateHold.setVisibility(View.GONE);
                            binding.linearPhone.setVisibility(View.GONE);
                            binding.linearEmail.setVisibility(View.GONE);
                            binding.linearPassword.setVisibility(View.GONE);
                            binding.layoutCompletionDetail.setVisibility(View.GONE);
                            binding.rvOrderList.setVisibility(View.GONE);
                            binding.layoutVerifyOtp.setVisibility(View.VISIBLE);
                            binding.rvOffers.setVisibility(View.GONE);
                        }
                    } else {
                        manageOrderList(response);
                    }
                } else if (response.code() == 403) {
                    binding.layoutLocation.setVisibility(View.VISIBLE);
                    binding.linearPhone.setVisibility(View.GONE);
                    binding.linearEmail.setVisibility(View.GONE);
                    binding.linearPassword.setVisibility(View.GONE);
                    binding.layoutCompletionDetail.setVisibility(View.GONE);
                    binding.rvOrderList.setVisibility(View.GONE);
                    binding.layoutVerifyOtp.setVisibility(View.GONE);
                    binding.rvOffers.setVisibility(View.GONE);
                }
            }

            @Override
            public void onFailure(Call<List<OrderListResp>> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Log.e(TAG, "onFailure: ", t);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void manageOrderList(Response<List<OrderListResp>> response) {
        List<OrderListResp> orderList = response.body();
        List<OrderListResp> orderListTemp = new ArrayList<OrderListResp>();
        orderListTemp.addAll(orderList);

        if (orderList != null && orderList.size() > 0) {
            binding.layoutCreateHold.setVisibility(View.GONE);
            binding.linearPhone.setVisibility(View.GONE);
            binding.linearEmail.setVisibility(View.GONE);
            binding.linearPassword.setVisibility(View.GONE);
            binding.layoutCompletionDetail.setVisibility(View.GONE);
            binding.rvOrderList.setVisibility(View.VISIBLE);
            binding.layoutVerifyOtp.setVisibility(View.GONE);
            binding.rvOffers.setVisibility(View.GONE);
            for (OrderListResp orderListResp : orderList) {
                if (orderListResp.status.equals("WD")) {
                    binding.btnBuyMore.setVisibility(View.GONE);
                    isBuyMoreVisible = false;
                    break;
                } else {
                    binding.btnBuyMore.setVisibility(View.VISIBLE);
                    isBuyMoreVisible = true;
                }
            }
            binding.btnBuyMore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    binding.rvOrderList.setVisibility(View.GONE);
                    binding.btnBuyMore.setVisibility(View.GONE);
                    binding.requestCoinsAmountBtcEdittext.setText("");
                    binding.requestCoinsAmountLocalEdittext.setText("");
                    binding.buyDashZip.setText("");
                    binding.layoutLocation.setVisibility(View.VISIBLE);
                }
            });
            LinearLayoutManager linearLayoutManager = new LinearLayoutManager(activity);
            binding.rvOrderList.setLayoutManager(linearLayoutManager);
            binding.rvOrderList.setAdapter(new OrderListAdapter(activity, orderList));
        } else {
            binding.layoutLocation.setVisibility(View.VISIBLE);
        }
    }

    class OrderListAdapter extends RecyclerView.Adapter<OrderListAdapter.VHolder> {

        private final Activity activity;
        private final List<OrderListResp> orderList;

        public OrderListAdapter(Activity activity, List<OrderListResp> orderList) {
            this.activity = activity;
            this.orderList = orderList;
        }

        @Override
        public OrderListAdapter.VHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater layoutInflater = LayoutInflater.from(activity);
            ItemOrderListBinding itemBinding = DataBindingUtil.inflate(layoutInflater, R.layout.item_order_list, parent, false);
            return new VHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(OrderListAdapter.VHolder holder, int position) {
            final OrderListResp orderListResp = orderList.get(position);
            if (orderListResp.account != null && !TextUtils.isEmpty(orderListResp.account)) {
                if (isJSONValid(orderListResp.account)) {
                    ItemBankDetailBinding itemBankBinding = DataBindingUtil.inflate(LayoutInflater.from(activity), R.layout.item_bank_detail, null, false);
                    Glide.with(activity).load(orderListResp.bankLogo).into(itemBankBinding.imageBank);
                    if (orderListResp.bankName != null && !TextUtils.isEmpty(orderListResp.bankName)) {
                        itemBankBinding.textBankName.setText(orderListResp.bankName);
                    } else {
                        itemBankBinding.textBankName.setVisibility(View.GONE);
                    }
                    if (orderListResp.nearestBranch.phone != null && !TextUtils.isEmpty(orderListResp.nearestBranch.phone)) {
                        itemBankBinding.textPhone.setText("Phone: " + orderListResp.nearestBranch.phone);
                    } else {
                        itemBankBinding.textPhone.setVisibility(View.GONE);
                    }

                    if (orderListResp.nameOnAccount != null && !TextUtils.isEmpty(orderListResp.nameOnAccount)) {
                        itemBankBinding.textNameAccount.setText("Name on Account: " + orderListResp.nameOnAccount);
                    } else {
                        itemBankBinding.textNameAccount.setVisibility(View.GONE);
                    }

                    if (orderListResp.payment != null && !TextUtils.isEmpty(orderListResp.payment)) {
                        itemBankBinding.textCashToDeposite.setText("Cash to Deposit: " + orderListResp.payment);
                    } else {
                        itemBankBinding.textCashToDeposite.setVisibility(View.GONE);
                    }

                    if (orderListResp.paymentDue != null && !TextUtils.isEmpty(orderListResp.paymentDue)) {
                        itemBankBinding.textDepositeDue.setText("Deposit Due: " + orderListResp.paymentDue.substring(0, 16).replace("T", " "));
                    } else {
                        itemBankBinding.textDepositeDue.setVisibility(View.GONE);
                    }
                    Type listType = new TypeToken<ArrayList<AccountJson>>() {
                    }.getType();
                    ArrayList<AccountJson> accountList = new Gson().fromJson(orderListResp.account, listType);
                    for (int i = 0; i < accountList.size(); i++) {
                        TextView textView = new TextView(getActivity());
                        textView.setTextSize(16);
                        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                        layoutParams.topMargin = 8;
                        textView.setLayoutParams(layoutParams);
                        textView.setText(accountList.get(i).getLabel() + ": " + accountList.get(i).getValue());
                        itemBankBinding.linearAccountDetail.addView(textView);
                    }

                    holder.itemBinding.layoutCompletionDetail.removeAllViews();
                    holder.itemBinding.layoutCompletionDetail.addView(itemBankBinding.getRoot());

                    if (orderListResp.status.equals("WD")) {
                        itemBankBinding.btnCancelOrder.setVisibility(View.VISIBLE);
                        itemBankBinding.btnDepositFinished.setVisibility(View.VISIBLE);
                    } else {
                        itemBankBinding.btnCancelOrder.setVisibility(View.GONE);
                        itemBankBinding.btnDepositFinished.setVisibility(View.GONE);
                    }

                    itemBankBinding.btnDepositFinished.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            CaptureHoldResp response = new CaptureHoldResp();
                            response.id = orderListResp.id;
                            response.total = orderListResp.total;
                            response.payment = orderListResp.payment;
                            response.paymentDue = orderListResp.paymentDue;
                            response.bankName = orderListResp.bankName;
                            response.nameOnAccount = orderListResp.nameOnAccount;
                            response.account = orderListResp.account;
                            response.status = orderListResp.status;
                            CaptureHoldResp.NearestBranchBean nearestBranchBean = new CaptureHoldResp.NearestBranchBean();
                            nearestBranchBean.name = orderListResp.nearestBranch.name;
                            nearestBranchBean.city = orderListResp.nearestBranch.city;
                            nearestBranchBean.state = orderListResp.nearestBranch.state;
                            nearestBranchBean.phone = orderListResp.nearestBranch.phone;
                            nearestBranchBean.address = orderListResp.nearestBranch.address;
                            response.nearestBranch = nearestBranchBean;
                            response.bankUrl = orderListResp.account;
                            response.bankLogo = orderListResp.account;
                            response.bankIcon = orderListResp.account;
                            response.bankIconHq = orderListResp.account;
                            response.privateId = orderListResp.account;

                            confirmDeposit(response);
                        }
                    });

                    itemBankBinding.btnCancelOrder.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // call cancel order
                            cancelOrder("" + orderListResp.id);
                        }
                    });
                } else {
                    holder.itemBinding.setItem(orderListResp);
                }
            } else {
                holder.itemBinding.setItem(orderListResp);
            }

            switch (orderListResp.status) {
                case "WD":
                    holder.itemBinding.orderStatus.setText("Status: Waiting Deposit");
                    break;
                case "WDV":
                    holder.itemBinding.orderStatus.setText("Status: Waiting Deposit Verification");
                    break;
                case "RERR":
                    holder.itemBinding.orderStatus.setText("Status: Issue w/ Receipt, Contacted Buyer");
                    break;
                case "DERR":
                    holder.itemBinding.orderStatus.setText("Status: Issue with Deposit, Needs Follow-up");
                    break;
                case "RSD":
                    holder.itemBinding.orderStatus.setText("Status: Reserved for Seller Deposit");
                    break;
                case "RMIT":
                    holder.itemBinding.orderStatus.setText("Status: Remit Address Missing");
                    break;
                case "UCRV":
                    holder.itemBinding.orderStatus.setText("Status: Under Compliance Review");
                    break;
                case "PAYP":
                    holder.itemBinding.orderStatus.setText("Status: Done - Pending Delivery");
                    break;
                case "SENT":
                    holder.itemBinding.orderStatus.setText("Status: Done - Units Delivered");
                    break;
                case "CANC":
                    holder.itemBinding.orderStatus.setText("Status: Buyer Canceled");
                    break;
                case "ACAN":
                    holder.itemBinding.orderStatus.setText("Status: Staff Canceled");
                    break;
                case "EXP":
                    holder.itemBinding.orderStatus.setText("Status: Deposit Time Expired");
                    break;
                case "ML":
                    holder.itemBinding.orderStatus.setText("Status: Meat Locker");
                    break;
                case "MLR":
                    holder.itemBinding.orderStatus.setText("Status: Meat Locker Returned");
                    break;
            }
            if (orderListResp.status.equals("WD")) {
                holder.itemBinding.btnCancelOrder.setVisibility(View.VISIBLE);
                holder.itemBinding.btnDepositFinished.setVisibility(View.VISIBLE);
            } else {
                holder.itemBinding.btnCancelOrder.setVisibility(View.GONE);
                holder.itemBinding.btnDepositFinished.setVisibility(View.GONE);
            }

            holder.itemBinding.btnDepositFinished.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CaptureHoldResp response = new CaptureHoldResp();
                    response.id = orderListResp.id;
                    response.total = orderListResp.total;
                    response.payment = orderListResp.payment;
                    response.paymentDue = orderListResp.paymentDue;
                    response.bankName = orderListResp.bankName;
                    response.nameOnAccount = orderListResp.nameOnAccount;
                    response.account = orderListResp.account;
                    response.status = orderListResp.status;
                    CaptureHoldResp.NearestBranchBean nearestBranchBean = new CaptureHoldResp.NearestBranchBean();
                    nearestBranchBean.name = orderListResp.nearestBranch.name;
                    nearestBranchBean.city = orderListResp.nearestBranch.city;
                    nearestBranchBean.state = orderListResp.nearestBranch.state;
                    nearestBranchBean.phone = orderListResp.nearestBranch.phone;
                    nearestBranchBean.address = orderListResp.nearestBranch.address;
                    response.nearestBranch = nearestBranchBean;
                    response.bankUrl = orderListResp.account;
                    response.bankLogo = orderListResp.account;
                    response.bankIcon = orderListResp.account;
                    response.bankIconHq = orderListResp.account;
                    response.privateId = orderListResp.account;

                    confirmDeposit(response);
                }
            });

            holder.itemBinding.btnCancelOrder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // call cancel order
                    cancelOrder("" + orderListResp.id);
                }
            });

        }

        @Override
        public int getItemCount() {
            return orderList.size();
        }

        public class VHolder extends RecyclerView.ViewHolder {
            private ItemOrderListBinding itemBinding;

            public VHolder(ItemOrderListBinding itemBinding) {
                super(itemBinding.getRoot());
                this.itemBinding = itemBinding;
            }
        }
    }

    class AccountJson {

        /**
         * displaySort : 0
         * name : fullName
         * value : Ian Marshall
         * label : Full Name
         */

        private String displaySort;
        private String name;
        private String value;
        private String label;

        public String getDisplaySort() {
            return displaySort;
        }

        public void setDisplaySort(String displaySort) {
            this.displaySort = displaySort;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

}
