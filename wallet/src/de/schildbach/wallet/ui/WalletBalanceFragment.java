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

import javax.annotation.Nullable;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Fiat;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.data.ExchangeRate;
import org.dash.wallet.common.ui.CurrencyTextView;

import org.dash.wallet.common.Configuration;

import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet.rates.ExchangeRatesViewModel;
import de.schildbach.wallet_test.R;

import android.app.Activity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import android.content.Intent;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.TextView;

import static org.dash.wallet.common.Constants.PREFIX_ALMOST_EQUAL_TO;

/**
 * @author Andreas Schildbach
 */
public final class WalletBalanceFragment extends Fragment {
    private WalletApplication application;
    private AbstractBindServiceActivity activity;
    private Configuration config;
    private Wallet wallet;
    private LoaderManager loaderManager;
    private ExchangeRatesViewModel exchangeRatesViewModel;

    private View viewBalance;
    private CurrencyTextView viewBalanceBtc;
    private View viewBalanceTooMuch;
    private CurrencyTextView viewBalanceLocal;
    private TextView viewProgress;

    private boolean showLocalBalance;
    private boolean installedFromGooglePlay;

    @Nullable
    private Coin balance = null;
    @Nullable
    private ExchangeRate exchangeRate = null;
    @Nullable
    private de.schildbach.wallet.data.BlockchainState blockchainState = null;

    private static final int ID_BALANCE_LOADER = 0;
    private static final int ID_RATE_LOADER = 1;
    private static final int ID_BLOCKCHAIN_STATE_LOADER = 2;

    private static final long BLOCKCHAIN_UPTODATE_THRESHOLD_MS = DateUtils.HOUR_IN_MILLIS;
    private static final Coin SOME_BALANCE_THRESHOLD = Coin.COIN.divide(20);
    private static final Coin TOO_MUCH_BALANCE_THRESHOLD = Coin.COIN.multiply(2);

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (AbstractBindServiceActivity) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        this.wallet = application.getWallet();
        this.loaderManager = getLoaderManager();

        showLocalBalance = getResources().getBoolean(R.bool.show_local_balance);
        installedFromGooglePlay = "com.android.vending"
                .equals(application.getPackageManager().getInstallerPackageName(application.getPackageName()));
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        setHasOptionsMenu(true);
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.wallet_balance_fragment, container, false);
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final boolean showExchangeRatesOption = getResources().getBoolean(R.bool.show_exchange_rates_option);

        viewBalance = view.findViewById(R.id.wallet_balance);
        if (showExchangeRatesOption) {
            viewBalance.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(final View v) {
                    startActivity(new Intent(getActivity(), ExchangeRatesActivity.class));
                }
            });
        } else {
            viewBalance.setEnabled(false);
        }

        viewBalanceBtc = view.findViewById(R.id.wallet_balance_dash);
        viewBalanceBtc.setPrefixScaleX(0.9f);

        viewBalanceTooMuch = view.findViewById(R.id.wallet_balance_too_much);

        viewBalanceLocal = (CurrencyTextView) view.findViewById(R.id.wallet_balance_local);
        viewBalanceLocal.setInsignificantRelativeSize(1);
        viewBalanceLocal.setStrikeThru(!Constants.IS_PROD_BUILD);

        viewProgress = (TextView) view.findViewById(R.id.wallet_balance_progress);
        exchangeRatesViewModel = ViewModelProviders.of(this).get(ExchangeRatesViewModel.class);

        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(getViewLifecycleOwner(),
                new Observer<BlockchainState>() {
                    @Override
                    public void onChanged(BlockchainState blockchainState) {
                        WalletBalanceFragment.this.blockchainState = blockchainState;
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


        updateView();
    }

    @Override
    public void onPause() {
        loaderManager.destroyLoader(ID_BLOCKCHAIN_STATE_LOADER);
        loaderManager.destroyLoader(ID_RATE_LOADER);
        loaderManager.destroyLoader(ID_BALANCE_LOADER);

        super.onPause();
    }

    private void updateView() {
        if (!isAdded())
            return;

        final boolean showProgress;

        if (blockchainState != null && blockchainState.getBestChainDate() != null) {
            final long blockchainLag = System.currentTimeMillis() - blockchainState.getBestChainDate().getTime();
            final boolean blockchainUptodate = blockchainLag < BLOCKCHAIN_UPTODATE_THRESHOLD_MS;
            final boolean noImpediments = blockchainState.getImpediments().isEmpty();

            showProgress = !(blockchainUptodate || !blockchainState.getReplaying());

            final String downloading = getString(noImpediments ? R.string.blockchain_state_progress_downloading
                    : R.string.blockchain_state_progress_stalled);

            if (blockchainLag < 2 * DateUtils.DAY_IN_MILLIS) {
                final long hours = blockchainLag / DateUtils.HOUR_IN_MILLIS;
                viewProgress.setText(getString(R.string.blockchain_state_progress_hours, downloading, hours));
            } else if (blockchainLag < 2 * DateUtils.WEEK_IN_MILLIS) {
                final long days = blockchainLag / DateUtils.DAY_IN_MILLIS;
                viewProgress.setText(getString(R.string.blockchain_state_progress_days, downloading, days));
            } else if (blockchainLag < 90 * DateUtils.DAY_IN_MILLIS) {
                final long weeks = blockchainLag / DateUtils.WEEK_IN_MILLIS;
                viewProgress.setText(getString(R.string.blockchain_state_progress_weeks, downloading, weeks));
            } else {
                final long months = blockchainLag / (30 * DateUtils.DAY_IN_MILLIS);
                viewProgress.setText(getString(R.string.blockchain_state_progress_months, downloading, months));
            }
        } else {
            showProgress = false;
        }

        if (!showProgress) {
            viewBalance.setVisibility(View.VISIBLE);

            if (!showLocalBalance)
                viewBalanceLocal.setVisibility(View.GONE);

            if (balance != null) {
                viewBalanceBtc.setVisibility(View.VISIBLE);
                viewBalanceBtc.setFormat(config.getFormat());
                viewBalanceBtc.setAmount(balance);

                final boolean tooMuch = balance.isGreaterThan(TOO_MUCH_BALANCE_THRESHOLD);

                viewBalanceTooMuch.setVisibility(tooMuch ? View.VISIBLE : View.GONE);

                if (showLocalBalance) {
                    if (exchangeRate != null) {
                        org.bitcoinj.utils.ExchangeRate rate = new org.bitcoinj.utils.ExchangeRate(Coin.COIN,
                                exchangeRate.getFiat());
                        final Fiat localValue = rate.coinToFiat(balance);
                        viewBalanceLocal.setVisibility(View.VISIBLE);
                        viewBalanceLocal.setFormat(Constants.LOCAL_FORMAT.code(0,
                                PREFIX_ALMOST_EQUAL_TO + exchangeRate.getCurrencyCode()));
                        viewBalanceLocal.setAmount(localValue);
                        viewBalanceLocal.setTextColor(getResources().getColor(R.color.fg_less_significant));
                    } else {
                        viewBalanceLocal.setVisibility(View.INVISIBLE);
                    }
                }
            } else {
                viewBalanceBtc.setVisibility(View.INVISIBLE);
            }

            viewProgress.setVisibility(View.GONE);
        } else {
            viewProgress.setVisibility(View.VISIBLE);
            viewBalance.setVisibility(View.INVISIBLE);
        }
    }

    private final LoaderManager.LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>() {
        @Override
        public Loader<Coin> onCreateLoader(final int id, final Bundle args) {
            return new WalletBalanceLoader(activity, wallet);
        }

        @Override
        public void onLoadFinished(final Loader<Coin> loader, final Coin balance) {
            WalletBalanceFragment.this.balance = balance;

            activity.invalidateOptionsMenu();
            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<Coin> loader) {
        }
    };
}
