/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Fiat;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.ui.CurrencyTextView;
import org.dash.wallet.common.util.GenericUtils;

import javax.annotation.Nullable;

import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet.rates.ExchangeRate;
import de.schildbach.wallet.rates.ExchangeRatesViewModel;
import de.schildbach.wallet_test.R;

public final class HeaderBalanceFragment extends Fragment {

    private WalletApplication application;
    private AbstractBindServiceActivity activity;
    private Configuration config;
    private Wallet wallet;
    private LoaderManager loaderManager;

    private Boolean hideBalance;
    private View showBalanceButton;
    private TextView hideShowBalanceHint;
    private TextView caption;
    private View view;
    private CurrencyTextView viewBalanceDash;
    private CurrencyTextView viewBalanceLocal;
    private TextView syncingIndicator;

    private boolean showLocalBalance;

    private ExchangeRatesViewModel exchangeRatesViewModel;

    @Nullable
    private Coin balance = null;
    @Nullable
    private ExchangeRate exchangeRate = null;

    private static final int ID_BALANCE_LOADER = 0;
    private static final int ID_BLOCKCHAIN_STATE_LOADER = 1;

    private boolean initComplete = false;

    private Handler autoLockHandler = new Handler();
    private BlockchainState blockchainState;

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (AbstractBindServiceActivity) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        this.wallet = application.getWallet();
        this.loaderManager = LoaderManager.getInstance(this);
        hideBalance = config.getHideBalance();

        showLocalBalance = getResources().getBoolean(R.bool.show_local_balance);
    }

    @Override
    public void onActivityCreated(@androidx.annotation.Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        exchangeRatesViewModel = ViewModelProviders.of(this).get(ExchangeRatesViewModel.class);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.header_balance_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        caption = view.findViewById(R.id.caption);
        hideShowBalanceHint = view.findViewById(R.id.hide_show_balance_hint);
        this.view = view;
        showBalanceButton = view.findViewById(R.id.show_balance_button);
        syncingIndicator = view.findViewById(R.id.balance_syncing_indicator);

        viewBalanceDash = view.findViewById(R.id.wallet_balance_dash);
        viewBalanceDash.setApplyMarkup(false);
        viewBalanceDash.setFormat(config.getFormat().noCode());
        viewBalanceDash.setAmount(Coin.ZERO);

        viewBalanceLocal = view.findViewById(R.id.wallet_balance_local);
        viewBalanceLocal.setInsignificantRelativeSize(1);
        viewBalanceLocal.setStrikeThru(!Constants.IS_PROD_BUILD);

        view.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hideBalance = !hideBalance;
                updateView();
            }
        });

        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(getViewLifecycleOwner(), new Observer<de.schildbach.wallet.data.BlockchainState>() {
            @Override
            public void onChanged(de.schildbach.wallet.data.BlockchainState blockchainState) {
                HeaderBalanceFragment.this.blockchainState = blockchainState;
                updateView();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();

        loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);
        exchangeRatesViewModel.getRate(config.getExchangeCurrencyCode()).observe(this,
                new Observer<ExchangeRate>() {
                    @Override
                    public void onChanged(ExchangeRate rate) {
                        if (rate != null) {
                            exchangeRate = rate;
                            updateView();
                        }
                    }
                });

        if (config.getHideBalance()) {
            hideBalance = true;
        }

        updateView();
    }

    @Override
    public void onPause() {
        loaderManager.destroyLoader(ID_BLOCKCHAIN_STATE_LOADER);
        loaderManager.destroyLoader(ID_BALANCE_LOADER);

        autoLockHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void updateView() {
        View balances = view.findViewById(R.id.balances_layout);

        if (hideBalance) {
            caption.setText(R.string.home_balance_hidden);
            hideShowBalanceHint.setText(R.string.home_balance_show_hint);
            balances.setVisibility(View.INVISIBLE);
            showBalanceButton.setVisibility(View.VISIBLE);
            return;
        }
        balances.setVisibility(View.VISIBLE);
        caption.setText(R.string.home_available_balance);
        hideShowBalanceHint.setText(R.string.home_balance_hide_hint);
        showBalanceButton.setVisibility(View.GONE);

        if (!isAdded()) {
            return;
        }

        if (blockchainState != null && !blockchainState.isSynced()) {
            syncingIndicator.setVisibility(View.VISIBLE);
            startSyncingIndicatorAnimation();
        } else {
            syncingIndicator.setVisibility(View.GONE);
            if (syncingIndicator.getAnimation() != null) {
                syncingIndicator.getAnimation().cancel();
            }
        }

        if (!showLocalBalance) {
            viewBalanceLocal.setVisibility(View.GONE);
        }

        if (balance != null) {
            viewBalanceDash.setAmount(balance);

            if (showLocalBalance) {
                if (exchangeRate != null) {
                    org.bitcoinj.utils.ExchangeRate rate = new org.bitcoinj.utils.ExchangeRate(Coin.COIN,
                            exchangeRate.getFiat());
                    final Fiat localValue = rate.coinToFiat(balance);
                    viewBalanceLocal.setVisibility(View.VISIBLE);
                    String currencySymbol = GenericUtils.currencySymbol(exchangeRate.getCurrencyCode());
                    viewBalanceLocal.setFormat(Constants.LOCAL_FORMAT.code(0, currencySymbol));
                    viewBalanceLocal.setAmount(localValue);
                } else {
                    viewBalanceLocal.setVisibility(View.INVISIBLE);
                }
            }
        } else {
            viewBalanceDash.setAmount(Coin.ZERO);
        }

        activity.invalidateOptionsMenu();
    }

    private void startSyncingIndicatorAnimation() {
        Animation currentAnimation = syncingIndicator.getAnimation();
        if (currentAnimation == null || currentAnimation.hasEnded()) {
            AlphaAnimation alphaAnimation = new AlphaAnimation(0.2f, 0.8f);
            alphaAnimation.setDuration(833);
            alphaAnimation.setRepeatCount(Animation.INFINITE);
            alphaAnimation.setRepeatMode(Animation.REVERSE);
            syncingIndicator.startAnimation(alphaAnimation);
        }
    }

    private void showExchangeRatesActivity() {
        Intent intent = new Intent(getActivity(), ExchangeRatesActivity.class);
        getActivity().startActivity(intent);
    }

    private final LoaderManager.LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>() {
        @Override
        public Loader<Coin> onCreateLoader(final int id, final Bundle args) {
            return new WalletBalanceLoader(activity, wallet);
        }

        @Override
        public void onLoadFinished(@NonNull final Loader<Coin> loader, final Coin balance) {
            HeaderBalanceFragment.this.balance = balance;

            updateView();
        }

        @Override
        public void onLoaderReset(@NonNull final Loader<Coin> loader) {
        }
    };
}
