/*
 * Copyright 2011-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.bitcoinj.core.Coin;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;

import java.util.List;

import javax.annotation.Nullable;

import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.request.CreateAuthReq;
import de.schildbach.wallet.request.GetAuthTokenReq;
import de.schildbach.wallet.response.CreateAuthResp;
import de.schildbach.wallet.response.GetAuthTokenResp;
import de.schildbach.wallet.response.GetReceivingOptionsResp;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.service.BlockchainStateLoader;
import de.schildbach.wallet.service.ServiceGenerator;
import hashengineering.darkcoin.wallet.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * @author Andreas Schildbach
 */
public final class SellDashFragment extends Fragment implements OnSharedPreferenceChangeListener {
    private static final String TAG = SellDashFragment.class.getSimpleName();
    private AbstractWalletActivity activity;
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private LoaderManager loaderManager;


    private Coin balance = null;
    @Nullable
    private BlockchainState blockchainState = null;
    @Nullable
    private String defaultCurrency = null;

    private static final int ID_BALANCE_LOADER = 0;
    private static final int ID_RATE_LOADER = 1;
    private static final int ID_BLOCKCHAIN_STATE_LOADER = 2;

    ProgressBar progressBar;
    View layoutCreateAuth;
    EditText email, phone, pass;
    Button createAuth;

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

        final View view = inflater.inflate(R.layout.sell_dash_fragment, container, false);

        progressBar = (ProgressBar) view.findViewById(R.id.sell_dash_progress);


        layoutCreateAuth = view.findViewById(R.id.layout_create_auth);
        email = (EditText) view.findViewById(R.id.edit_sell_dash_email);
        phone = (EditText) view.findViewById(R.id.edit_sell_dash_phone);
        pass = (EditText) view.findViewById(R.id.edit_sell_dash_pass);
        createAuth = (Button) view.findViewById(R.id.button_sell_dash_create_auth);

        createAuth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (TextUtils.isEmpty(email.getText()) || TextUtils.isEmpty(phone.getText()) || TextUtils.isEmpty(pass.getText())) {
                    Toast.makeText(getContext(), "All Fields are required!", Toast.LENGTH_LONG).show();
                    return;
                }
                CreateAuthReq createAuthReq = new CreateAuthReq();
                createAuthReq.email = email.getText().toString();
                createAuthReq.password = pass.getText().toString();
                createAuthReq.phone = phone.getText().toString();

                progressBar.setVisibility(View.VISIBLE);
                ServiceGenerator.createService().createAuth(createAuthReq).enqueue(new Callback<CreateAuthResp>() {
                    @Override
                    public void onResponse(Call<CreateAuthResp> call, Response<CreateAuthResp> response) {

                        GetAuthTokenReq getAuthTokenReq = new GetAuthTokenReq();
                        getAuthTokenReq.password = pass.getText().toString();

                        ServiceGenerator.createService().getAuthToken(phone.getText().toString(), getAuthTokenReq).enqueue(new Callback<GetAuthTokenResp>() {
                            @Override
                            public void onResponse(Call<GetAuthTokenResp> call, Response<GetAuthTokenResp> response) {

                                String locale;
                                locale = getResources().getConfiguration().locale.getCountry();

                                ServiceGenerator.createService().getReceivingOptions(locale.toLowerCase()).enqueue(new Callback<List<GetReceivingOptionsResp>>() {
                                    @Override
                                    public void onResponse(Call<List<GetReceivingOptionsResp>> call, Response<List<GetReceivingOptionsResp>> response) {
                                        Log.e(TAG, "onResponse: " + response.body().size());
                                    }

                                    @Override
                                    public void onFailure(Call<List<GetReceivingOptionsResp>> call, Throwable t) {
                                        progressBar.setVisibility(View.GONE);
                                    }
                                });
                            }

                            @Override
                            public void onFailure(Call<GetAuthTokenResp> call, Throwable t) {
                                progressBar.setVisibility(View.GONE);
                            }
                        });
                    }

                    @Override
                    public void onFailure(Call<CreateAuthResp> call, Throwable t) {
                        progressBar.setVisibility(View.GONE);
                    }
                });
            }
        });


        return view;
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
}
